package io.github.felixchang67.diskandroid.drive

import io.github.felixchang67.diskandroid.core.drive.DriveEvent
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.drive.DriveState
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.scsi.BotTransport
import io.github.felixchang67.diskandroid.core.scsi.GuardedScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.LoggingScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.ReadOnlyPolicy
import io.github.felixchang67.diskandroid.core.usb.MassStorageSelection
import io.github.felixchang67.diskandroid.core.usb.OpenedUsbDrive
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceMonitor
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot
import io.github.felixchang67.diskandroid.core.usb.UsbDriveConnector
import io.github.felixchang67.diskandroid.core.usb.UsbEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** 一台已连接的光驱：USB 连接 + BOT 传输 + 会话（独占一个线程）。 */
class ActiveDrive(
    val deviceName: String,
    val vidPid: String,
    val displayName: String,
    val report: ProbeReport,
    val bot: BotTransport,
    val session: DriveSession,
    private val opened: OpenedUsbDrive,
    private val dispatcher: ExecutorCoroutineDispatcher,
    private val watchers: List<Job>,
) {
    fun shutdown(disconnected: Boolean) {
        watchers.forEach { it.cancel() }
        if (disconnected) session.markDisconnected()
        session.close()
        opened.close()
        dispatcher.close()
    }
}

/**
 * 管理光驱会话的生命周期：有权限的 USB 大容量存储设备出现时自动连接；拔出时立即关闭并释放。
 * 所有状态修改都在主线程（[scope] 使用 Dispatchers.Main），USB 操作在 IO 线程和各会话自己的线程上。
 *
 * 发往光驱的命令都经过：只读守卫 → 日志 → BOT → USB。
 */
class DriveManager(
    private val usbMonitor: UsbDeviceMonitor,
    private val connector: UsbDriveConnector,
    private val log: DiskLog,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DRIVE"
        private const val MAX_HISTORY = 3
    }

    private val _drives = MutableStateFlow<Map<String, ActiveDrive>>(emptyMap())
    val drives: StateFlow<Map<String, ActiveDrive>> = _drives.asStateFlow()

    private val _reports = MutableStateFlow<Map<String, ProbeReport>>(emptyMap())
    val reports: StateFlow<Map<String, ProbeReport>> = _reports.asStateFlow()

    private val _connecting = MutableStateFlow<Set<String>>(emptySet())
    val connecting: StateFlow<Set<String>> = _connecting.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 最近结束的会话摘要（拔出后仍能在诊断信息里看到）。 */
    private val _history = MutableStateFlow<List<List<String>>>(emptyList())
    val history: StateFlow<List<List<String>>> = _history.asStateFlow()

    fun start() {
        scope.launch { usbMonitor.events.collect { onUsbEvent(it) } }
        scope.launch { usbMonitor.devices.collect { list -> list.forEach { autoConnect(it) } } }
    }

    private fun onUsbEvent(event: UsbEvent) {
        when (event) {
            is UsbEvent.Detached -> shutdown(event.deviceName, disconnected = true, reason = "USB 拔出")
            is UsbEvent.Attached -> autoConnect(event.snapshot)
            is UsbEvent.PermissionResult -> if (event.granted) {
                usbMonitor.devices.value.firstOrNull { it.deviceName == event.deviceName }?.let { autoConnect(it) }
            }
        }
    }

    private fun autoConnect(snapshot: UsbDeviceSnapshot) {
        if (!snapshot.hasPermission || snapshot.massStorage !is MassStorageSelection.Selected) return
        if (snapshot.deviceName in _reports.value && snapshot.deviceName !in _drives.value) return // 已经试过且失败：等用户点"重新连接"
        connect(snapshot.deviceName)
    }

    fun connect(deviceName: String) {
        if (deviceName in _drives.value || deviceName in _connecting.value) return
        val device = usbMonitor.findDevice(deviceName)
        if (device == null) {
            _messages.tryEmit("设备已经断开")
            return
        }
        _connecting.update { it + deviceName }
        scope.launch {
            try {
                val result = connector.connect(device, keepOpen = true)
                _reports.update { it + (deviceName to result.report) }
                val opened = result.opened
                if (opened == null) {
                    _messages.tryEmit("连接光驱失败，详情见设备卡片和日志")
                    return@launch
                }
                if (usbMonitor.findDevice(deviceName) == null) {
                    opened.close()
                    return@launch
                }
                val report = result.report
                val bot = BotTransport(
                    opened.io, lun = 0, log = log,
                    description = "BOT over USB ${report.vidPid}（LUN 0，${report.effectiveSpeed.label}）",
                )
                val transport = GuardedScsiTransport(LoggingScsiTransport(bot, log), ReadOnlyPolicy, log)
                val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "drive-${report.vidPid}").apply { isDaemon = true } }
                val dispatcher = executor.asCoroutineDispatcher()
                val session = DriveSession(transport, log, "${report.displayName}（${report.vidPid}）", scope, dispatcher)
                val watchers = listOf(
                    scope.launch { session.eventFlow.collect { onDriveEvent(it) } },
                    scope.launch {
                        session.state.collect { state ->
                            if (state is DriveState.Disconnected) shutdown(deviceName, disconnected = false, reason = "光驱无应答 / 断开")
                        }
                    },
                )
                _drives.update { it + (deviceName to ActiveDrive(deviceName, report.vidPid, report.displayName, report, bot, session, opened, dispatcher, watchers)) }
                session.start()
            } catch (e: Exception) {
                log.e(TAG, "连接光驱时出现异常", throwable = e)
                _messages.tryEmit("连接光驱出现异常：${e.message}")
            } finally {
                _connecting.update { it - deviceName }
            }
        }
    }

    fun reconnect(deviceName: String) {
        shutdown(deviceName, disconnected = false, reason = "用户请求重新连接")
        _reports.update { it - deviceName }
        connect(deviceName)
    }

    fun eject(deviceName: String) {
        val drive = _drives.value[deviceName] ?: return
        scope.launch { _messages.tryEmit(drive.session.eject()) }
    }

    fun refresh(deviceName: String) {
        val drive = _drives.value[deviceName] ?: return
        scope.launch {
            drive.session.refreshInfo()
            _messages.tryEmit("已重新读取光驱信息")
        }
    }

    private fun onDriveEvent(event: DriveEvent) {
        val text = when (event.kind) {
            DriveEvent.Kind.UNEXPECTED_RESET -> "${event.message}。${DriveSession.POWER_HINT}"
            DriveEvent.Kind.MEDIA_INSERTED, DriveEvent.Kind.MEDIA_REMOVED, DriveEvent.Kind.EJECT_REQUEST, DriveEvent.Kind.ERROR -> event.message
            else -> return
        }
        _messages.tryEmit(text)
    }

    private fun shutdown(deviceName: String, disconnected: Boolean, reason: String) {
        val drive = _drives.value[deviceName] ?: return
        _drives.update { it - deviceName }
        val summary = mutableListOf("会话结束 ${LogFormat.isoTime(System.currentTimeMillis())}（$reason）：${drive.displayName} ${drive.vidPid}")
        summary += "  BOT 统计：${drive.bot.stats}"
        drive.session.events.value.takeLast(15).forEach { summary += "  $it" }
        _history.update { (it + listOf(summary)).takeLast(MAX_HISTORY) }
        drive.shutdown(disconnected)
        log.i(TAG, "光驱会话结束", "原因" to reason, "光驱" to drive.displayName)
        if (disconnected) _messages.tryEmit("光驱已断开（${drive.vidPid}）")
    }
}
