package io.github.felixchang67.diskandroid.core.usb

/**
 * 极小的 usbfs ioctl 封装（native/diskcore/src/usbfs_jni.c），只做 Android 公开 API 做不到的事：
 * 查询链路速度、usbfs 能力、内核驱动绑定情况，BOT 错误恢复要用的 CLEAR_HALT / RESET，
 * 以及能返回 errno 的批量传输（USBDEVFS_BULK；原生库不可用时退回 UsbDeviceConnection.bulkTransfer）。
 *
 * 所有返回 Int 的函数：>= 0 表示成功，< 0 表示 -errno。
 */
object UsbfsNative {
    private val loadError: Throwable? = try {
        System.loadLibrary("diskcore")
        null
    } catch (t: Throwable) {
        t
    }

    val isAvailable: Boolean get() = loadError == null
    val loadErrorText: String get() = loadError?.toString() ?: ""

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeGetSpeed(fd: Int): Int
    @JvmStatic external fun nativeGetCapabilities(fd: Int): Int
    @JvmStatic external fun nativeGetDriver(fd: Int, iface: Int): String
    @JvmStatic external fun nativeClearHalt(fd: Int, endpoint: Int): Int
    @JvmStatic external fun nativeResetDevice(fd: Int): Int
    @JvmStatic external fun nativeStrerror(errno: Int): String
    @JvmStatic external fun nativeBulk(fd: Int, endpoint: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** 原生库里有没有 nativeBulk（旧版本的 .so 没有）。 */
    val hasBulk: Boolean by lazy {
        isAvailable && runCatching { nativeVersion() }.getOrDefault("").substringAfter('/').toIntOrNull()?.let { it >= 2 } == true
    }

    /** -errno → 可读文本。 */
    fun errorText(result: Int): String {
        val errno = -result
        val text = if (isAvailable) runCatching { nativeStrerror(errno) }.getOrDefault("") else ""
        return "errno $errno" + if (text.isNotEmpty()) "（$text）" else ""
    }

    /** 当前绑定在该接口上的内核驱动名。ENODATA(61) 表示没有驱动绑定。 */
    fun driverName(fd: Int, iface: Int): String {
        if (!isAvailable) return "（原生库不可用）"
        val raw = runCatching { nativeGetDriver(fd, iface) }.getOrElse { return "（查询失败：$it）" }
        if (!raw.startsWith("errno:")) return raw
        val errno = raw.removePrefix("errno:").toIntOrNull() ?: return raw
        return if (errno == 61) "（无内核驱动）" else "（查询失败：${errorText(-errno)}）"
    }

    /** 解码 USBDEVFS_GET_CAPABILITIES 位图（linux/usbdevice_fs.h）。 */
    fun describeCapabilities(caps: Int): String {
        val names = listOf(
            0x01 to "ZERO_PACKET",
            0x02 to "BULK_CONTINUATION",
            0x04 to "NO_PACKET_SIZE_LIM",
            0x08 to "BULK_SCATTER_GATHER",
            0x10 to "REAP_AFTER_DISCONNECT",
            0x20 to "MMAP",
            0x40 to "DROP_PRIVILEGES",
            0x80 to "CONNINFO_EX",
            0x100 to "SUSPEND",
        )
        val set = names.filter { (bit, _) -> caps and bit != 0 }.map { it.second }
        return "0x" + Integer.toHexString(caps).uppercase() + if (set.isEmpty()) "" else "（${set.joinToString(" | ")}）"
    }
}
