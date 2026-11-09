package io.github.felixchang67.diskandroid.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.felixchang67.diskandroid.core.scsi.BulkIo
import java.io.Closeable

/**
 * 用 Android 公开 API 实现的批量传输（纯 Kotlin 路径）：数据收发走 UsbDeviceConnection.bulkTransfer。
 * 清 HALT 走 JNI 的 USBDEVFS_CLEAR_HALT（内核会同时复位主机侧 data toggle），原生库不可用时退回控制传输。
 */
class AndroidUsbBulkIo(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : BulkIo {
    override val inEndpoint: Int = epIn.address
    override val outEndpoint: Int = epOut.address
    override val interfaceNumber: Int = intf.id

    private val fd = connection.fileDescriptor

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(epIn, buffer, offset, length, timeoutMs)

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        connection.bulkTransfer(epOut, buffer, offset, length, timeoutMs)

    override fun clearHalt(endpoint: Int): Int {
        if (UsbfsNative.isAvailable) return UsbfsNative.nativeClearHalt(fd, endpoint)
        // 退路：标准请求 CLEAR_FEATURE(ENDPOINT_HALT)（bmRequestType 02h，bRequest 01h，wValue 0）
        val r = connection.controlTransfer(0x02, 0x01, 0, endpoint, null, 0, 2_000)
        return if (r >= 0) 0 else -5
    }

    override fun massStorageReset(): Int =
        connection.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 5_000)

    override fun isAttached(): Boolean =
        runCatching { usbManager.deviceList.containsKey(device.deviceName) }.getOrDefault(false)

    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/** 已打开并占用了 BOT 接口的光驱连接。关闭时释放接口并关闭连接。 */
class OpenedUsbDrive(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val intf: UsbInterface,
    val io: AndroidUsbBulkIo,
    val maxLun: Int,
) : Closeable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
    }
}
