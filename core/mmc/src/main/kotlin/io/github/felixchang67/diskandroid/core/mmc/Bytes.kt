package io.github.felixchang67.diskandroid.core.mmc

/** 只读的大端字节视图，越界读返回 0（响应被截断时不崩溃）。 */
internal class Bytes(private val data: ByteArray, val size: Int = data.size) {
    fun u8(i: Int): Int = if (i in 0 until size) data[i].toInt() and 0xFF else 0
    fun be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
    fun be24(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
    fun be32(i: Int): Long = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
    fun bit(i: Int, bit: Int): Boolean = (u8(i) shr bit) and 1 == 1

    /** ASCII 字段（去掉尾部空格与 NUL；不可打印字符换成 '?'）。 */
    fun ascii(from: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in from until minOf(from + length, size)) {
            val c = u8(i)
            sb.append(if (c in 0x20..0x7E) c.toChar() else if (c == 0) ' ' else '?')
        }
        return sb.toString().trimEnd()
    }

    fun slice(from: Int, length: Int): ByteArray {
        val end = minOf(from + length, size)
        return if (from >= end) ByteArray(0) else data.copyOfRange(from, end)
    }
}
