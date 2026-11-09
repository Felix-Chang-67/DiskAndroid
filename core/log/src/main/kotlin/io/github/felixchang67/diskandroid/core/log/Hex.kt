package io.github.felixchang67.diskandroid.core.log

/** 十六进制编码与 hex dump 工具（不依赖 Locale）。 */
object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    /** 例如 `55 53 42 43`。 */
    fun encode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        separator: String = " ",
    ): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "范围越界: offset=$offset length=$length size=${bytes.size}"
        }
        val sb = StringBuilder(length * (2 + separator.length))
        for (i in 0 until length) {
            if (i > 0) sb.append(separator)
            appendByte(sb, bytes[offset + i].toInt())
        }
        return sb.toString()
    }

    /** 无分隔符，例如 `55534243`。 */
    fun compact(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        encode(bytes, offset, length, "")

    /**
     * 经典 hex dump：
     * `0000: 55 53 42 43 01 00 00 00 24 00 00 00 80 00 06 12  |USBC....$.......|`
     */
    fun dump(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, bytesPerLine: Int = 16): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        require(bytesPerLine > 0)
        val sb = StringBuilder()
        var i = 0
        while (i < length) {
            val n = minOf(bytesPerLine, length - i)
            if (i > 0) sb.append('\n')
            sb.append(hex(i, 4)).append(": ")
            for (j in 0 until bytesPerLine) {
                if (j < n) {
                    appendByte(sb, bytes[offset + i + j].toInt())
                    sb.append(' ')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |")
            for (j in 0 until n) {
                val c = bytes[offset + i + j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('|')
            i += n
        }
        return sb.toString()
    }

    /** 把整数格式化为固定位数的大写十六进制，例如 `hex(0x411, 4)` → `0411`。 */
    fun hex(value: Int, digits: Int): String {
        val sb = StringBuilder(digits)
        for (shift in (digits - 1) * 4 downTo 0 step 4) {
            sb.append(DIGITS[(value ushr shift) and 0xF])
        }
        return sb.toString()
    }

    fun byte(value: Int): String = hex(value and 0xFF, 2)

    fun word(value: Int): String = hex(value and 0xFFFF, 4)

    /** 解析 hex 字符串（忽略空白），主要给测试用。 */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex 长度必须为偶数" }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[2 * i], 16) shl 4) or Character.digit(clean[2 * i + 1], 16)).toByte()
        }
    }

    private fun appendByte(sb: StringBuilder, b: Int) {
        sb.append(DIGITS[(b ushr 4) and 0xF]).append(DIGITS[b and 0xF])
    }
}
