package io.github.felixchang67.diskandroid.core.rip

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 用 FileChannel 按位置读写（Android 上来自 SAF 的 ParcelFileDescriptor："rw" 模式打开，
 * 写用 FileOutputStream 的 channel、读用 FileInputStream 的 channel，两者共享同一个文件描述符）。
 */
class ChannelRipOutput(
    private val writeChannel: FileChannel,
    private val readChannel: FileChannel?,
    private val onClose: () -> Unit = {},
) : RipOutput {
    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        val bb = ByteBuffer.wrap(data, offset, length)
        var pos = position
        while (bb.hasRemaining()) {
            val n = writeChannel.write(bb, pos)
            if (n < 0) throw IOException("写入返回 $n")
            pos += n
        }
    }

    override fun read(position: Long, data: ByteArray, offset: Int, length: Int): Int {
        val ch = readChannel ?: throw IOException("输出不支持读回")
        val bb = ByteBuffer.wrap(data, offset, length)
        var pos = position
        while (bb.hasRemaining()) {
            val n = ch.read(bb, pos)
            if (n < 0) break
            pos += n
        }
        return (pos - position).toInt()
    }

    override fun flush() {
        writeChannel.force(false)
    }

    override fun close() {
        try {
            runCatching { writeChannel.force(false) }
            writeChannel.close()
            readChannel?.close()
        } finally {
            onClose()
        }
    }
}
