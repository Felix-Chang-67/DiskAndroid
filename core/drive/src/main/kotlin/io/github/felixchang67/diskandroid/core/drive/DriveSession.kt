package io.github.felixchang67.diskandroid.core.drive

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.mmc.CapabilitiesPage
import io.github.felixchang67.diskandroid.core.mmc.EventStatus
import io.github.felixchang67.diskandroid.core.mmc.FeatureList
import io.github.felixchang67.diskandroid.core.mmc.InquiryData
import io.github.felixchang67.diskandroid.core.mmc.MechanismStatus
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.mmc.RpcState
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.ScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.SenseData
import io.github.felixchang67.diskandroid.core.scsi.SenseKey
import io.github.felixchang67.diskandroid.core.scsi.TransportErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一台光驱的会话：所有命令都在 [dispatcher]（单线程）上执行，保证 BOT 同一时刻只有一条命令在途。
 *
 * 启动：INQUIRY → TEST UNIT READY（处理上电 / 换盘的 UNIT ATTENTION）→ 读取光驱信息 → 进入轮询。
 * 轮询：GESN（媒体事件、托盘状态）+ TEST UNIT READY，状态变化写日志并记事件。
 * 会话中途出现非本方触发的 UNIT ATTENTION 29h（上电/复位）→ 记为疑似供电问题。
 */
class DriveSession(
    private val transport: ScsiTransport,
    private val log: DiskLog,
    val label: String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 1_500,
    private val becomingReadyIntervalMs: Long = 500,
) {
    companion object {
        private const val TAG = "DRIVE"
        private const val MAX_UNIT_ATTENTION_RETRIES = 5
        private const val MAX_TRANSPORT_ERRORS = 3
        private const val MAX_EVENTS = 40
        const val POWER_HINT = "如果你没有拔插光驱，这通常是供电不足导致光驱复位：请检查带供电的扩展坞 / Boost 线，以及光驱的两个 USB 头是否都插好"
    }

    private class DeviceGoneException(message: String) : Exception(message)
    private class UnresponsiveException(message: String) : Exception(message)

    private val _state = MutableStateFlow<DriveState>(DriveState.Initializing)
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private val _info = MutableStateFlow<DriveInfo?>(null)
    val info: StateFlow<DriveInfo?> = _info.asStateFlow()

    private val _media = MutableStateFlow<MediaStatus>(MediaStatus.Unknown)
    val media: StateFlow<MediaStatus> = _media.asStateFlow()

    private val _events = MutableStateFlow<List<DriveEvent>>(emptyList())
    val events: StateFlow<List<DriveEvent>> = _events.asStateFlow()

    /** 新事件的流（给界面弹提示用；不重放历史）。 */
    private val _eventFlow = MutableSharedFlow<DriveEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<DriveEvent> = _eventFlow.asSharedFlow()

    private var job: Job? = null
    private var gesnSupported = true
    private var lastGesnTray: Boolean? = null
    private var seenResetCount = 0L
    private var transportErrors = 0
    /** 收到 UNIT ATTENTION 28h/29h 后置位：下一次 TUR 成功时必须重新读取 profile（可能在两次轮询之间换了盘）。 */
    private var mediaMayHaveChanged = false

    val description: String get() = transport.description

    fun start() {
        if (job != null) return
        job = scope.launch(dispatcher) {
            try {
                initialize()
                pollLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceGoneException) {
                onGone(e.message ?: "设备断开")
            } catch (e: UnresponsiveException) {
                fail(e.message ?: "光驱无响应")
            } catch (e: Exception) {
                log.e(TAG, "光驱会话异常退出", throwable = e)
                fail("内部错误：${e.message}")
            }
        }
    }

    /** USB 断开广播到达时由管理器调用（比等命令失败更快）。 */
    fun markDisconnected() {
        job?.cancel()
        onGone("收到 USB 断开通知")
    }

    fun close() {
        job?.cancel()
        runCatching { transport.close() }
        if (_state.value !is DriveState.Disconnected) _state.value = DriveState.Closed
        log.i(TAG, "光驱会话已关闭", "光驱" to label)
    }

    /** 重新读取光驱信息（INQUIRY / GET CONFIGURATION / 2Ah / RPC / 机构 / GESN）。 */
    suspend fun refreshInfo() = runCommand { readInfo() }

    /** 弹出托盘（START STOP UNIT，LoEj=1 Start=0）。返回给用户看的结果。 */
    suspend fun eject(): String = runCommand {
        val r = exec(MmcCommands.startStopUnit(loadEject = true, start = false))
        val message = when (r) {
            is ScsiResult.Good -> "已发送弹出命令"
            is ScsiResult.CheckCondition -> "弹出失败：${r.sense?.describe() ?: "无 sense"}"
            else -> "弹出失败：$r"
        }
        addEvent(DriveEvent.Kind.TRAY, message)
        pollOnce()
        message
    } ?: "光驱当前不可用"

    private suspend fun <T> runCommand(block: suspend () -> T): T? {
        if (_state.value !is DriveState.Online) return null
        return withContext(dispatcher) {
            try {
                block()
            } catch (e: DeviceGoneException) {
                onGone(e.message ?: "设备断开")
                null
            } catch (e: UnresponsiveException) {
                fail(e.message ?: "光驱无响应")
                null
            }
        }
    }

    // ---------------- 初始化 ----------------

    private suspend fun initialize() {
        _state.value = DriveState.Initializing
        log.i(TAG, "初始化光驱会话", "光驱" to label, "传输" to transport.description)

        val (inqResult, inqData) = execIn(MmcCommands.inquiry())
        val inquiry = inqData?.let { InquiryData.parse(it) }
        if (inquiry == null) {
            fail("INQUIRY 失败：$inqResult")
            return
        }
        log.i(TAG, "INQUIRY：${inquiry.vendor} ${inquiry.product} ${inquiry.revision}", "外设类型" to inquiry.deviceTypeName(), "可移动" to inquiry.removable)
        if (!inquiry.isMmcDevice) {
            log.w(TAG, "外设类型不是 05h（CD/DVD），可能不是光驱", "类型" to inquiry.deviceTypeName())
        }

        val tur = testUnitReady(quiet = false)
        readInfo(inquiry, inqData)
        seenResetCount = transport.resetCount
        _state.value = DriveState.Online
        applyTur(tur, initial = true)
        addEvent(DriveEvent.Kind.INFO, "光驱已就绪：${inquiry.displayName}，${_media.value.label()}")
    }

    private fun readInfo(knownInquiry: InquiryData? = null, knownInquiryRaw: ByteArray? = null) {
        val raw = linkedMapOf<String, ByteArray>()
        val errors = linkedMapOf<String, String>()

        var inquiry = knownInquiry
        if (knownInquiryRaw != null) raw["INQUIRY"] = knownInquiryRaw
        if (inquiry == null) {
            val (r, d) = execIn(MmcCommands.inquiry())
            if (d != null) {
                raw["INQUIRY"] = d
                inquiry = InquiryData.parse(d)
            } else {
                errors["INQUIRY"] = r.toString()
            }
        }

        // GET CONFIGURATION：先读 8 字节头拿到总长度，再读全部（个别桥接芯片对大分配长度处理不好，分两次更稳）
        var features: FeatureList? = null
        val (h, hd) = execIn(MmcCommands.getConfiguration(0, 0, 8))
        if (hd != null && hd.size >= 8) {
            val total = (FeatureList.parse(hd)?.dataLength ?: 4L) + 4
            val alloc = minOf(total, 65_532L).toInt().let { it + (it and 1) }
            val (r2, d2) = execIn(MmcCommands.getConfiguration(0, 0, maxOf(alloc, 8)))
            if (d2 != null) {
                raw["GET CONFIGURATION"] = d2
                features = FeatureList.parse(d2)
            } else {
                errors["GET CONFIGURATION"] = r2.toString()
            }
        } else {
            errors["GET CONFIGURATION"] = h.toString()
        }

        var capabilities: CapabilitiesPage? = null
        val (mr, md) = execIn(MmcCommands.modeSense10(0x2A))
        if (md != null) {
            raw["MODE SENSE 2Ah"] = md
            capabilities = CapabilitiesPage.parse(md)
            if (capabilities == null) errors["MODE SENSE 2Ah"] = "响应里没有 2Ah 页"
        } else {
            errors["MODE SENSE 2Ah"] = mr.toString()
        }

        var rpc: RpcState? = null
        val (rr, rd) = execIn(MmcCommands.reportRpcState())
        if (rd != null) {
            raw["REPORT KEY RPC"] = rd
            rpc = RpcState.parse(rd)
            rpc?.let { log.i(TAG, "DVD 地区码：${it.summary()}") }
        } else {
            errors["REPORT KEY RPC"] = rr.toString()
        }

        var mechanism: MechanismStatus? = null
        val (ms, mdData) = execIn(MmcCommands.mechanismStatus())
        if (mdData != null) {
            raw["MECHANISM STATUS"] = mdData
            mechanism = MechanismStatus.parse(mdData)
        } else {
            errors["MECHANISM STATUS"] = ms.toString()
        }

        var gesn: EventStatus? = null
        val (gr, gd) = execIn(MmcCommands.getEventStatus())
        if (gd != null) {
            raw["GESN"] = gd
            gesn = EventStatus.parse(gd)
        }
        gesnSupported = gesn != null && gesn.supportsMediaClass
        if (!gesnSupported) {
            errors["GESN"] = if (gd == null) gr.toString() else "不支持媒体事件类"
            log.w(TAG, "光驱不支持 GESN 媒体事件，改用 TEST UNIT READY 轮询")
        } else {
            lastGesnTray = gesn?.trayOpen
        }

        val info = DriveInfo(inquiry, features, capabilities, rpc, mechanism, gesn, raw, errors)
        _info.value = info
        info.describeLines().forEach { log.i(TAG, it) }
    }

    // ---------------- 轮询 ----------------

    private suspend fun pollLoop() {
        while (true) {
            delay(if (_media.value is MediaStatus.BecomingReady) becomingReadyIntervalMs else pollIntervalMs)
            pollOnce()
        }
    }

    private fun pollOnce() {
        if (gesnSupported) {
            val (_, d) = execIn(MmcCommands.getEventStatus(quiet = true))
            d?.let { EventStatus.parse(it) }?.let { handleEvent(it) }
        }
        applyTur(testUnitReady(quiet = true), initial = false)
    }

    private fun handleEvent(ev: EventStatus) {
        val code = ev.mediaEventCode ?: return
        ev.trayOpen?.let { open ->
            if (lastGesnTray != null && lastGesnTray != open) {
                addEvent(DriveEvent.Kind.TRAY, if (open) "托盘已打开" else "托盘已关闭")
            }
            lastGesnTray = open
        }
        if (code == 0) return
        val text = "媒体事件：${MmcNames.mediaEvent(code)}"
        log.i(TAG, text, "盘片在位" to ev.mediaPresent, "托盘打开" to ev.trayOpen)
        when (code) {
            1 -> addEvent(DriveEvent.Kind.EJECT_REQUEST, "检测到弹出请求（按了光驱上的弹出键）")
            else -> addEvent(DriveEvent.Kind.INFO, text)
        }
    }

    private fun testUnitReady(quiet: Boolean): ScsiResult = exec(MmcCommands.testUnitReady(quiet))

    /**
     * 处理 UNIT ATTENTION（任何命令都可能收到，取决于光驱实现）：
     * 29h 上电/复位——本方复位恢复之后或初始化阶段属于预期，否则记为疑似供电问题；28h 盘片可能已更换。
     */
    private fun noteUnitAttention(sense: SenseData) {
        if (sense.asc == 0x28 || sense.asc == 0x29) mediaMayHaveChanged = true
        when (sense.asc) {
            0x29 -> {
                val expected = transport.resetCount > seenResetCount || _state.value is DriveState.Initializing
                seenResetCount = transport.resetCount
                if (expected) {
                    log.i(TAG, "光驱报告上电/复位（预期内）", "sense" to sense.code())
                } else {
                    log.w(TAG, "光驱在会话中途报告上电/复位（${sense.code()}）。$POWER_HINT")
                    addEvent(DriveEvent.Kind.UNEXPECTED_RESET, "光驱自行复位（${sense.code()}）——疑似供电不足")
                }
            }
            0x28 -> log.i(TAG, "光驱报告：盘片可能已更换", "sense" to sense.code())
            else -> log.i(TAG, "UNIT ATTENTION：${sense.describe()}")
        }
    }

    private fun applyTur(result: ScsiResult, initial: Boolean) {
        val previous = _media.value
        val next: MediaStatus = when (result) {
            is ScsiResult.Good -> if (previous is MediaStatus.Ready && !initial && !mediaMayHaveChanged) {
                previous
            } else {
                val changed = mediaMayHaveChanged && previous is MediaStatus.Ready
                mediaMayHaveChanged = false
                val ready = MediaStatus.Ready(currentProfile())
                if (changed && ready == previous) {
                    log.i(TAG, "盘片已更换（类型相同）：${ready.label()}")
                    addEvent(DriveEvent.Kind.MEDIA_INSERTED, "盘片已更换：${MmcNames.profileName(ready.profile)}")
                }
                ready
            }
            is ScsiResult.CheckCondition -> {
                val s = result.sense
                when {
                    s == null -> MediaStatus.NotReady("无 sense 数据")
                    s.matches(SenseKey.NOT_READY, 0x3A, 0x01) -> MediaStatus.NoMedium(TrayState.CLOSED)
                    s.matches(SenseKey.NOT_READY, 0x3A, 0x02) -> MediaStatus.NoMedium(TrayState.OPEN)
                    s.matches(SenseKey.NOT_READY, 0x3A) -> MediaStatus.NoMedium(
                        when (lastGesnTray) {
                            true -> TrayState.OPEN
                            false -> TrayState.CLOSED
                            null -> TrayState.UNKNOWN
                        },
                    )
                    s.matches(SenseKey.NOT_READY, 0x04, 0x01) ->
                        if (previous is MediaStatus.BecomingReady) previous else MediaStatus.BecomingReady(wallClock())
                    else -> MediaStatus.NotReady(s.describe())
                }
            }
            else -> return // 传输错误已在 exec 里处理
        }
        if (next == previous) return
        _media.value = next
        val level = if (next is MediaStatus.NotReady) LogLevel.WARN else LogLevel.INFO
        log.log(level, TAG, "盘片状态：${previous.label()} → ${next.label()}")
        when {
            next is MediaStatus.Ready -> addEvent(DriveEvent.Kind.MEDIA_INSERTED, "盘片就绪：${MmcNames.profileName(next.profile)}" + becomingReadyDuration(previous))
            next is MediaStatus.NoMedium && previous is MediaStatus.Ready -> addEvent(DriveEvent.Kind.MEDIA_REMOVED, "盘片已取出（${next.tray.label}）")
            next is MediaStatus.NoMedium && previous is MediaStatus.NoMedium && next.tray != previous.tray -> addEvent(DriveEvent.Kind.TRAY, next.tray.label)
            next is MediaStatus.BecomingReady -> addEvent(DriveEvent.Kind.INFO, "检测到盘片，正在读取…")
            next is MediaStatus.NotReady -> addEvent(DriveEvent.Kind.ERROR, next.label())
        }
    }

    private fun becomingReadyDuration(previous: MediaStatus): String =
        if (previous is MediaStatus.BecomingReady) "（用时 ${(wallClock() - previous.sinceMs) / 100 / 10.0} 秒）" else ""

    /** 只读特性头里的当前 profile。 */
    private fun currentProfile(): Int {
        val (_, d) = execIn(MmcCommands.getConfiguration(2, 0, 8))
        return d?.let { FeatureList.parse(it)?.currentProfile } ?: 0
    }

    // ---------------- 命令执行 ----------------

    private fun exec(cmd: ScsiCommand): ScsiResult {
        var r = transport.execute(cmd)
        var attempts = 0
        while (r is ScsiResult.CheckCondition && r.sense?.senseKey == SenseKey.UNIT_ATTENTION && attempts < MAX_UNIT_ATTENTION_RETRIES) {
            noteUnitAttention(r.sense!!)
            attempts++
            r = transport.execute(cmd)
        }
        if (r is ScsiResult.TransportError) {
            if (r.kind == TransportErrorKind.DEVICE_GONE || r.kind == TransportErrorKind.CLOSED) {
                throw DeviceGoneException("${cmd.displayName}：${r.kind.label}")
            }
            transportErrors++
            log.w(TAG, "传输错误（连续第 $transportErrors 次）", "命令" to cmd.displayName, "详情" to r.detail)
            if (transportErrors >= MAX_TRANSPORT_ERRORS) {
                throw UnresponsiveException("连续 $transportErrors 次传输错误，最后一次：${r.kind.label}（${cmd.displayName}）")
            }
        } else {
            transportErrors = 0
        }
        return r
    }

    private fun execIn(cmd: ScsiCommand): Pair<ScsiResult, ByteArray?> {
        val r = exec(cmd)
        return r to if (r is ScsiResult.Good) cmd.buffer!!.copyOf(r.transferred) else null
    }

    private fun addEvent(kind: DriveEvent.Kind, message: String) {
        val event = DriveEvent(wallClock(), kind, message)
        _events.update { (it + event).takeLast(MAX_EVENTS) }
        _eventFlow.tryEmit(event)
    }

    private fun onGone(reason: String) {
        if (_state.value is DriveState.Disconnected || _state.value is DriveState.Closed) return
        _state.value = DriveState.Disconnected
        _media.value = MediaStatus.Unknown
        addEvent(DriveEvent.Kind.ERROR, "光驱已断开：$reason")
        log.w(TAG, "光驱已断开", "光驱" to label, "原因" to reason)
    }

    private fun fail(reason: String) {
        _state.value = DriveState.Failed(reason)
        addEvent(DriveEvent.Kind.ERROR, reason)
        log.e(TAG, "光驱会话失败：$reason", "光驱" to label)
    }
}
