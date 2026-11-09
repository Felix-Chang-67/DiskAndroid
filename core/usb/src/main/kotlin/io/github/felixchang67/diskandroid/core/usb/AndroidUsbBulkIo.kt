package io.github.felixchang67.diskandroid.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import io.github.felixchang67.diskandroid.core.scsi.BulkIo
import io.github.felixchang67.diskandroid.core.scsi.ChunkedTransfer
import java.io.Closeable

/**
 * Android 上的批量传输：
 * - 数据收发优先走 JNI 的 USBDEVFS_BULK（能拿到 errno），原生库不可用时退回 UsbDeviceConnection.bulkTransfer；
 * - 每次最多 16 KB，大块数据拆成多次传输（见 [ChunkedTransfer]：N1.5 真机上 56 KB 一次的传输偶发内核分配失败）；
 * - 清 HALT 走 JNI 的 USBDEVFS_CLEAR_HALT（内核会同时复位主机侧 data toggle），原生库不可用时退回控制传输。
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
    private val native = UsbfsNative.hasBulk
    private val chunkIn = ChunkedTransfer(chunkSize(epIn))
    private val chunkOut = ChunkedTransfer(chunkSize(epOut))

    /** 传输方式（写进连接报告）。 */
    val method: String = if (native) "usbfs ioctl（返回 errno），每次 ≤ ${chunkSize(epIn) / 1024} KB" else "bulkTransfer（公开 API），每次 ≤ ${chunkSize(epIn) / 1024} KB"

    override val lastPartial: Int get() = chunkIn.lastPartial

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        chunkIn.run(length) { off, len -> transfer(epIn, buffer, offset + off, len, timeoutMs) }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        chunkOut.run(length) { off, len -> transfer(epOut, buffer, offset + off, len, timeoutMs) }

    private fun transfer(ep: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        if (native) {
            UsbfsNative.nativeBulk(fd, ep.address, buffer, offset, length, timeoutMs)
        } else {
            connection.bulkTransfer(ep, buffer, offset, length, timeoutMs)
        }

    override fun extraStats(): String = "传输方式：$method；ENOMEM 重试 ${chunkIn.enomemRetries + chunkOut.enomemRetries} 次"

    /** 16 KB，并保证是端点最大包长的整数倍（拆开的每一块都必须以整包结束）。 */
    private fun chunkSize(ep: UsbEndpoint): Int {
        val packet = ep.maxPacketSize.coerceAtLeast(64)
        return maxOf(packet, ChunkedTransfer.DEFAULT_MAX_CHUNK / packet * packet)
    }

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
