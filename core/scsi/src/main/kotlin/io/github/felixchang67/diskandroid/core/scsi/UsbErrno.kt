package io.github.felixchang67.diskandroid.core.scsi

/**
 * USB 传输常见的 Linux errno（include/uapi/asm-generic/errno*.h；含义见内核文档 driver-api/usb/error-codes）。
 * JNI 的 usbfs 传输返回 -errno；Android 公开 API 的 bulkTransfer 只返回 -1（拿不到原因）。
 */
object UsbErrno {
    const val UNKNOWN = 1 // bulkTransfer 的 -1；也是 EPERM，但 USB 传输不会返回 EPERM
    const val ENOENT = 2
    const val EIO = 5
    const val ENOMEM = 12
    const val ENODEV = 19
    const val EINVAL = 22
    const val EPIPE = 32
    const val ETIME = 62
    const val ECOMM = 70
    const val EPROTO = 71
    const val EOVERFLOW = 75
    const val EILSEQ = 84
    const val ECONNRESET = 104
    const val ESHUTDOWN = 108
    const val ETIMEDOUT = 110
    const val EREMOTEIO = 121

    /** -errno（或 -1）→ 便于看懂的名字。 */
    fun describe(result: Int): String = when (-result) {
        UNKNOWN -> "-1（Android API 不提供原因）"
        ENOENT -> "ENOENT（传输被取消）"
        EIO -> "EIO（I/O 错误）"
        ENOMEM -> "ENOMEM（内核分配缓冲区失败）"
        ENODEV -> "ENODEV（设备已移除）"
        EINVAL -> "EINVAL（参数无效）"
        EPIPE -> "EPIPE（端点 STALL）"
        ETIME, ETIMEDOUT -> "ETIMEDOUT（超时）"
        ECOMM -> "ECOMM（数据接收溢出）"
        EPROTO -> "EPROTO（协议错误 / 无响应）"
        EOVERFLOW -> "EOVERFLOW（设备发来的数据超过请求长度，babble）"
        EILSEQ -> "EILSEQ（CRC 错误）"
        ECONNRESET -> "ECONNRESET（传输被中止）"
        ESHUTDOWN -> "ESHUTDOWN（设备已断开）"
        EREMOTEIO -> "EREMOTEIO（短包）"
        else -> "errno ${-result}"
    }
}

/**
 * 把一次批量传输拆成不超过 [maxChunk] 字节的多次 usbfs 传输（[maxChunk] 应是端点最大包长的整数倍）。
 *
 * 为什么要拆：usbfs 每次传输都要在内核里 kmalloc 一块与传输等长的连续内存。N1.5 真机上 56 KB 的 READ CD
 * 偶尔在 1–2 ms 内就失败，而且成串出现，很像内存碎片时分配 64 KB 连续页失败（ENOMEM），传输根本没上总线；
 * 公开 API 只返回 -1，被当成 STALL 处理，结果读 CSW 时读到的是数据包，最后复位、卡 6 秒。
 * 16 KB（Android 9 以前公开 API 的上限）一次分配要容易得多；遇到 ENOMEM 稍等后重试同一块，设备那边什么都没发生。
 *
 * @param op 传输一块：(块在本次传输中的偏移, 块长度) → 实际字节数，失败返回负数。
 */
class ChunkedTransfer(
    private val maxChunk: Int = DEFAULT_MAX_CHUNK,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** 最近一次 [run] 在出错前已经完整传完的字节数（BOT 数据阶段 STALL 时用来确定已收到的数据量）。 */
    var lastPartial = 0
        private set

    /** 累计的 ENOMEM 重试次数（写进诊断信息）。 */
    var enomemRetries = 0L
        private set

    var lastError = 0
        private set

    fun run(length: Int, op: (chunkOffset: Int, chunkLength: Int) -> Int): Int {
        lastPartial = 0
        if (length == 0) return op(0, 0)
        var done = 0
        while (done < length) {
            val len = minOf(maxChunk, length - done)
            var n = op(done, len)
            var retries = 0
            while (n == -UsbErrno.ENOMEM && retries < MAX_ENOMEM_RETRIES) {
                retries++
                enomemRetries++
                sleeper(ENOMEM_BACKOFF_MS * retries)
                n = op(done, len)
            }
            if (n < 0) {
                lastPartial = done
                lastError = n
                return n
            }
            done += n
            if (n < len) break // 短包：数据阶段结束
        }
        return done
    }

    companion object {
        const val DEFAULT_MAX_CHUNK = 16 * 1024
        const val MAX_ENOMEM_RETRIES = 5
        const val ENOMEM_BACKOFF_MS = 10L
    }
}
