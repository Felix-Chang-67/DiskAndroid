package io.github.felixchang67.diskandroid.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.log.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @property critical 关键步骤失败意味着后续阶段（BOT 传输）无法进行；非关键步骤只是诊断信息。
 */
data class ProbeStep(val title: String, val ok: Boolean, val detail: String, val millis: Long, val critical: Boolean)

class ProbeReport(
    val deviceName: String,
    val vidPid: String,
    val displayName: String,
    val startedAtMillis: Long,
    val durationMs: Long,
    val steps: List<ProbeStep>,
    val rawDescriptors: ByteArray?,
    val parsed: ParsedUsbDescriptors?,
    val kernelSpeed: UsbSpeed?,
    val inferredSpeed: UsbSpeed,
    val capabilities: Int?,
    val driversBefore: Map<Int, String>,
    val driversAfter: Map<Int, String>,
    val selection: MassStorageSelection,
    val maxLun: Int?,
) {
    val success: Boolean get() = steps.isNotEmpty() && steps.none { it.critical && !it.ok }

    val effectiveSpeed: UsbSpeed get() = kernelSpeed?.takeIf { it != UsbSpeed.UNKNOWN } ?: inferredSpeed

    val maxPowerMilliAmps: Int?
        get() = parsed?.configurations?.firstOrNull()?.maxPowerMilliAmps(effectiveSpeed.isSuperSpeed)

    /** 链路速度相关的提示（例如 USB3 设备跑在 USB2 速率上）。 */
    val speedHint: String?
        get() {
            val bcd = parsed?.device?.bcdUsb ?: return null
            return if (effectiveSpeed == UsbSpeed.HIGH && bcd == 0x0210) {
                "设备以 USB 2.0 速率连接（bcdUSB 2.10，通常是 USB3 设备降速运行）。请确认扩展坞和线材支持 USB 3.x 数据传输；USB 2.0 对 DVD 足够，对 BD 读取偏慢。"
            } else {
                null
            }
        }

    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        out += "连接测试 ${LogFormat.isoTime(startedAtMillis)}（耗时 $durationMs ms）：${if (success) "成功" else "失败"}"
        out += "  设备：$displayName  VID:PID $vidPid  路径 $deviceName"
        out += "  链路速度：${effectiveSpeed.label}" +
            "（内核报告：${kernelSpeed?.label ?: "无"}；描述符推断：${inferredSpeed.label}）"
        speedHint?.let { out += "  ⚠ $it" }
        maxPowerMilliAmps?.let { out += "  声明最大电流：$it mA" }
        capabilities?.let { out += "  usbfs 能力：${UsbfsNative.describeCapabilities(it)}" }
        if (driversBefore.isNotEmpty()) {
            out += "  内核驱动（占用前）：" + driversBefore.entries.joinToString("；") { "接口 ${it.key} = ${it.value}" }
        }
        if (driversAfter.isNotEmpty()) {
            out += "  内核驱动（占用后）：" + driversAfter.entries.joinToString("；") { "接口 ${it.key} = ${it.value}" }
        }
        out += when (val sel = selection) {
            is MassStorageSelection.Selected -> "  选中接口：${sel.summary()}"
            is MassStorageSelection.Unsupported -> "  接口不支持：${sel.reason}"
            MassStorageSelection.NotMassStorage -> "  不是大容量存储设备"
        }
        maxLun?.let { out += "  Max LUN：$it" }
        out += "  步骤："
        for (s in steps) {
            val mark = if (s.ok) "✓" else if (s.critical) "✗" else "!"
            out += "    $mark ${s.title}（${s.millis} ms）：${s.detail}"
        }
        parsed?.let { p ->
            out += "  描述符："
            p.describe().forEach { out += "    $it" }
        }
        rawDescriptors?.let { r ->
            out += "  原始描述符（${r.size} 字节）："
            Hex.dump(r).lineSequence().forEach { out += "    $it" }
        }
        return out
    }
}

/** 连接结果：诊断报告 + （成功且要求保持时）已占用接口的连接。 */
class ConnectResult(val report: ProbeReport, val opened: OpenedUsbDrive?)

/**
 * 连接光驱：打开设备 → 读原始描述符 → 查内核驱动 / 链路速度 / usbfs 能力 →
 * 选择 BOT 接口 → 强制占用（断开内核 usb-storage 驱动）→ Get Max LUN →
 * [keepOpen] 时保持占用、交给光驱会话；否则释放（只做诊断）。
 *
 * 这一步只做 USB 层面的操作，不发送 SCSI 命令。
 */
class UsbDriveConnector(private val usbManager: UsbManager, private val log: DiskLog) {
    companion object {
        private const val TAG = LogTags.PROBE
        private const val GET_MAX_LUN_TIMEOUT_MS = 2000

        /** Bulk-Only Mass Storage 类请求：Get Max LUN（bmRequestType A1h，bRequest FEh）。 */
        private const val BOT_GET_MAX_LUN_REQUEST_TYPE = 0xA1
        private const val BOT_GET_MAX_LUN_REQUEST = 0xFE
    }

    suspend fun connect(device: UsbDevice, keepOpen: Boolean = true): ConnectResult =
        withContext(Dispatchers.IO) { probe(device, keepOpen) }

    private fun now() = SystemClock.elapsedRealtime()

    private fun probe(device: UsbDevice, keepOpen: Boolean): ConnectResult {
        val startedWall = System.currentTimeMillis()
        val t0 = now()
        val vidPid = "${Hex.word(device.vendorId)}:${Hex.word(device.productId)}"
        val displayName = UsbDeviceSnapshot.from(device, usbManager).displayName
        val steps = mutableListOf<ProbeStep>()
        var raw: ByteArray? = null
        var parsed: ParsedUsbDescriptors? = null
        var kernelSpeed: UsbSpeed? = null
        var capabilities: Int? = null
        val driversBefore = linkedMapOf<Int, String>()
        val driversAfter = linkedMapOf<Int, String>()
        var selection: MassStorageSelection = MassStorageSelection.NotMassStorage
        var maxLun: Int? = null
        var opened: OpenedUsbDrive? = null

        val nativeInfo = if (UsbfsNative.isAvailable) {
            runCatching { UsbfsNative.nativeVersion() }.getOrDefault("?")
        } else {
            "不可用：${UsbfsNative.loadErrorText}"
        }
        log.i(TAG, "开始连接测试：$displayName", "VID:PID" to vidPid, "路径" to device.deviceName, "原生库" to nativeInfo)

        fun record(title: String, ok: Boolean, detail: String, startedAt: Long, critical: Boolean = true) {
            val ms = now() - startedAt
            steps += ProbeStep(title, ok, detail, ms, critical)
            val level = when {
                ok -> LogLevel.INFO
                critical -> LogLevel.ERROR
                else -> LogLevel.WARN
            }
            log.log(level, TAG, "${if (ok) "✓" else "✗"} $title", listOf("详情" to detail, "耗时ms" to ms))
        }

        fun buildReport() = ProbeReport(
            deviceName = device.deviceName,
            vidPid = vidPid,
            displayName = displayName,
            startedAtMillis = startedWall,
            durationMs = now() - t0,
            steps = steps.toList(),
            rawDescriptors = raw,
            parsed = parsed,
            kernelSpeed = kernelSpeed,
            inferredSpeed = parsed?.inferSpeed() ?: UsbSpeed.UNKNOWN,
            capabilities = capabilities,
            driversBefore = driversBefore,
            driversAfter = driversAfter,
            selection = selection,
            maxLun = maxLun,
        ).also { report ->
            log.log(
                if (report.success) LogLevel.INFO else LogLevel.WARN, TAG,
                "连接测试结束：${if (report.success) "成功" else "失败"}",
                listOf(
                    "速度" to report.effectiveSpeed.label,
                    "最大电流mA" to (report.maxPowerMilliAmps ?: "?"),
                    "MaxLUN" to (report.maxLun ?: "?"),
                    "耗时ms" to report.durationMs,
                ),
            )
            report.speedHint?.let { log.w(TAG, it) }
        }

        var t = now()
        val connection = try {
            usbManager.openDevice(device)
        } catch (e: Exception) {
            log.e(TAG, "openDevice 抛出异常", throwable = e)
            null
        }
        if (connection == null) {
            record("打开设备", false, "openDevice 失败：可能没有 USB 权限，或设备已经断开", t)
            return ConnectResult(buildReport(), null)
        }
        record("打开设备", true, "文件描述符 fd=${connection.fileDescriptor}", t)

        try {
            val fd = connection.fileDescriptor

            // 1. 原始描述符
            t = now()
            val bytes = connection.rawDescriptors
            if (bytes != null && bytes.isNotEmpty()) {
                val p = UsbDescriptorParser.parse(bytes)
                raw = bytes
                parsed = p
                log.data(LogLevel.INFO, TAG, "原始描述符", bytes, "长度" to bytes.size)
                p.describe().forEach { log.i(TAG, it) }
                val warn = if (p.warnings.isEmpty()) "" else "，${p.warnings.size} 条解析警告"
                record("读取原始描述符", true, "${bytes.size} 字节$warn", t)
            } else {
                record("读取原始描述符", false, "getRawDescriptors 返回空", t)
            }

            // 2. 内核侧状态（usbfs ioctl，仅诊断）
            val interfaceIds = UsbDeviceSnapshot.androidInterfaces(device).map { it.id }.distinct()
            if (UsbfsNative.isAvailable) {
                t = now()
                interfaceIds.forEach { driversBefore[it] = UsbfsNative.driverName(fd, it) }
                record(
                    "查询内核驱动（占用前）", true,
                    driversBefore.entries.joinToString("；") { "接口 ${it.key}：${it.value}" }.ifEmpty { "没有接口" },
                    t, critical = false,
                )

                t = now()
                val speedValue = UsbfsNative.nativeGetSpeed(fd)
                if (speedValue >= 0) {
                    val speed = UsbSpeed.fromKernel(speedValue)
                    kernelSpeed = speed
                    record("查询链路速度（内核）", true, speed.label, t, critical = false)
                } else {
                    record("查询链路速度（内核）", false, UsbfsNative.errorText(speedValue), t, critical = false)
                }

                t = now()
                val caps = UsbfsNative.nativeGetCapabilities(fd)
                if (caps >= 0) {
                    capabilities = caps
                    record("查询 usbfs 能力", true, UsbfsNative.describeCapabilities(caps), t, critical = false)
                } else {
                    record("查询 usbfs 能力", false, UsbfsNative.errorText(caps), t, critical = false)
                }
            } else {
                record("加载原生库 diskcore", false, UsbfsNative.loadErrorText, now(), critical = false)
            }

            // 3. 选择 BOT 接口
            t = now()
            val chosen = MassStorageInterfaceSelector.select(UsbDeviceSnapshot.interfaceModels(device))
            selection = chosen
            when (chosen) {
                is MassStorageSelection.Selected ->
                    record("选择大容量存储接口", true, chosen.summary() + notesSuffix(chosen.notes), t)
                is MassStorageSelection.Unsupported ->
                    record("选择大容量存储接口", false, chosen.reason + notesSuffix(chosen.notes), t)
                MassStorageSelection.NotMassStorage ->
                    record("选择大容量存储接口", false, "不是 USB 大容量存储设备（没有 class 08h 接口）", t)
            }

            val selected = chosen as? MassStorageSelection.Selected
            if (selected != null) {
                val intf = UsbDeviceSnapshot.findInterface(device, selected.iface.id, selected.iface.alternateSetting)
                if (intf == null) {
                    record("查找接口对象", false, "找不到接口 ${selected.iface.id}/${selected.iface.alternateSetting}", now())
                } else {
                    // 4. 强制占用：Android 会先断开内核驱动（usb-storage / uas）
                    t = now()
                    val claimed = connection.claimInterface(intf, true)
                    record(
                        "占用接口（force=true，会断开内核驱动）", claimed,
                        if (claimed) "接口 ${intf.id} 已占用" else "claimInterface 返回 false（可能被其他应用占用）", t,
                    )
                    if (claimed) {
                        var handedOver = false
                        try {
                            var interfaceReady = true
                            if (selected.needsSetInterface) {
                                t = now()
                                val ok = connection.setInterface(intf)
                                interfaceReady = ok
                                record(
                                    "切换到 BOT 备用设置 ${intf.alternateSetting}", ok,
                                    if (ok) "SET_INTERFACE 成功" else "SET_INTERFACE 失败", t,
                                )
                            }
                            if (UsbfsNative.isAvailable) {
                                interfaceIds.forEach { driversAfter[it] = UsbfsNative.driverName(fd, it) }
                                log.i(TAG, "占用后的内核驱动", "驱动" to driversAfter.entries.joinToString("；") { "接口 ${it.key}：${it.value}" })
                            }

                            // 5. Get Max LUN（不支持多 LUN 的设备可能 STALL，规范允许，按 LUN 0 处理）
                            t = now()
                            val buf = ByteArray(1)
                            val n = connection.controlTransfer(
                                BOT_GET_MAX_LUN_REQUEST_TYPE, BOT_GET_MAX_LUN_REQUEST, 0, intf.id, buf, 1, GET_MAX_LUN_TIMEOUT_MS,
                            )
                            val value = buf[0].toInt() and 0xFF
                            when {
                                n == 1 && value <= 15 -> {
                                    maxLun = value
                                    record("Get Max LUN（类请求 FEh）", true, "最大 LUN = $value（共 ${value + 1} 个逻辑单元）", t, critical = false)
                                }
                                n == 1 -> {
                                    maxLun = 0
                                    record("Get Max LUN（类请求 FEh）", false, "返回值 $value 超出 0..15，按 0 处理", t, critical = false)
                                }
                                else -> {
                                    maxLun = 0
                                    record(
                                        "Get Max LUN（类请求 FEh）", true,
                                        "设备没有应答（返回 $n，通常是 STALL），按 BOT 规范视为只有 LUN 0", t, critical = false,
                                    )
                                }
                            }

                            // 6. 保持占用，交给光驱会话（BOT 传输）
                            if (keepOpen && interfaceReady) {
                                val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                                val epIn = endpoints.firstOrNull { it.address == selected.bulkIn.address }
                                val epOut = endpoints.firstOrNull { it.address == selected.bulkOut.address }
                                if (epIn != null && epOut != null) {
                                    val io = AndroidUsbBulkIo(usbManager, device, connection, intf, epIn, epOut)
                                    opened = OpenedUsbDrive(device, connection, intf, io, maxLun)
                                    handedOver = true
                                    log.i(TAG, "接口保持占用，交给光驱会话", "bulk IN" to "0x${Hex.byte(epIn.address)}", "bulk OUT" to "0x${Hex.byte(epOut.address)}")
                                } else {
                                    record("查找端点对象", false, "找不到 bulk 端点对象", now())
                                }
                            }
                        } finally {
                            if (!handedOver) {
                                val released = connection.releaseInterface(intf)
                                log.i(TAG, "释放接口", "结果" to released)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.e(TAG, "连接测试异常", throwable = e)
            steps += ProbeStep("异常", false, e.toString(), 0, critical = true)
        } finally {
            if (opened == null) runCatching { connection.close() }
        }
        return ConnectResult(buildReport(), opened)
    }

    private fun notesSuffix(notes: List<String>) = if (notes.isEmpty()) "" else "；" + notes.joinToString("；")
}
