package io.github.felixchang67.diskandroid.core.fs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 盘上文字的解码，以及小端 / 大端整数读取。 */
object DiscText {
    private val SJIS: Charset = Charset.forName("windows-31j")
    private val GBK: Charset = Charset.forName("GBK")

    /**
     * ISO 9660 的标签、文件名（标准只允许 ASCII 的 A–Z、0–9、_）。实际的盘：
     * Linux / mkisofs 做的常是 UTF-8，日本的 Windows 软件做的常是 Shift-JIS（CP932），中文软件做的是 GBK。
     * 依次尝试 ASCII → UTF-8 → Shift-JIS（日本盘优先）→ GBK → Latin-1；字段被截断在多字节字符中间时，去掉残缺的尾巴再试。
     */
    fun decode(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val slice = b.copyOfRange(off, off + len)
        if (slice.all { it >= 0 }) return String(slice, Charsets.US_ASCII)
        strict(slice, Charsets.UTF_8)?.let { return it }
        for (cut in 1..3) {
            if (slice.size <= cut) break
            if (isUtf8Prefix(slice, slice.size - cut)) strict(slice.copyOf(slice.size - cut), Charsets.UTF_8)?.let { return it }
        }
        strict(slice, SJIS)?.let { return it }
        if (slice.size > 1 && isSjisLead(slice[slice.size - 1].toInt() and 0xFF)) strict(slice.copyOf(slice.size - 1), SJIS)?.let { return it }
        strict(slice, GBK)?.let { return it }
        return String(slice, Charsets.ISO_8859_1)
    }

    /** 固定长度的文字字段：去掉尾部的空格和 NUL 再解码。 */
    fun field(b: ByteArray, off: Int, len: Int): String {
        var end = off + len
        while (end > off && (b[end - 1].toInt() == 0x20 || b[end - 1].toInt() == 0)) end--
        return decode(b, off, end - off).trim()
    }

    /** Joliet：UCS-2 大端，去掉尾部空格 / NUL。 */
    fun ucs2(b: ByteArray, off: Int, len: Int): String {
        val s = String(b, off, len and 1.inv(), Charsets.UTF_16BE)
        return s.trimEnd(' ', '\u0000')
    }

    /** UDF 的 OSTA CS0 d-字符：第一个字节是压缩 ID（8 = 每字节一个字符，16 = UTF-16 大端；254 / 255 同 8 / 16）。 */
    fun cs0(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0) return ""
        return when (b[off].toInt() and 0xFF) {
            8, 254 -> String(b, off + 1, len - 1, Charsets.ISO_8859_1)
            16, 255 -> String(b, off + 1, (len - 1) and 1.inv(), Charsets.UTF_16BE)
            else -> decode(b, off + 1, len - 1)
        }.trimEnd('\u0000')
    }

    /** UDF dstring（固定长度字段，最后一个字节是实际使用的长度，含压缩 ID）。 */
    fun dstring(b: ByteArray, off: Int, fieldLen: Int): String {
        val used = b[off + fieldLen - 1].toInt() and 0xFF
        if (used == 0 || used > fieldLen - 1) return ""
        return cs0(b, off, used).trimEnd(' ')
    }

    /** 给文件名用：去掉文件系统不允许的字符，限制长度。 */
    fun safeFileName(name: String, fallback: String = "DISC", maxLength: Int = 80): String {
        val cleaned = name.map { c ->
            when {
                c in "\\/:*?\"<>|" || c.code < 0x20 || c == '�' -> '_'
                else -> c
            }
        }.joinToString("").trim().trim('.')
        return cleaned.take(maxLength).ifBlank { fallback }
    }

    private fun strict(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** 从 [cut] 开始的尾巴是不是一个不完整的 UTF-8 字符的开头。 */
    private fun isUtf8Prefix(b: ByteArray, cut: Int): Boolean {
        val lead = b[cut].toInt() and 0xFF
        val need = when {
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> return false
        }
        val have = b.size - cut
        if (have >= need) return false
        return (cut + 1 until b.size).all { (b[it].toInt() and 0xC0) == 0x80 }
    }

    private fun isSjisLead(v: Int) = v in 0x81..0x9F || v in 0xE0..0xFC

    // ---------------- 整数 ----------------

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    fun le16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
    fun be16(b: ByteArray, off: Int): Int = (u8(b, off) shl 8) or u8(b, off + 1)
    fun le32(b: ByteArray, off: Int): Long = (le16(b, off).toLong()) or (le16(b, off + 2).toLong() shl 16)
    fun be32(b: ByteArray, off: Int): Long = (be16(b, off).toLong() shl 16) or be16(b, off + 2).toLong()
    fun le64(b: ByteArray, off: Int): Long = le32(b, off) or (le32(b, off + 4) shl 32)

    // ---------------- 时间 ----------------

    /** ISO 9660 目录记录里的 7 字节时间（年 − 1900、月、日、时、分、秒、相对 GMT 的 15 分钟数）。 */
    fun isoRecordTime(b: ByteArray, off: Int): Long? {
        val year = u8(b, off) + 1900
        val month = u8(b, off + 1)
        val day = u8(b, off + 2)
        if (month !in 1..12 || day !in 1..31) return null
        val offset = b[off + 6].toInt().coerceIn(-48, 52) * 15
        return runCatching {
            LocalDateTime.of(year, month, day, u8(b, off + 3), u8(b, off + 4), u8(b, off + 5))
                .toInstant(ZoneOffset.ofTotalSeconds(offset * 60)).toEpochMilli()
        }.getOrNull()
    }

    /** ISO 9660 卷描述符里的 17 字节时间（"YYYYMMDDHHMMSScc" + 15 分钟时区）。 */
    fun isoVolumeTime(b: ByteArray, off: Int): Long? {
        val s = String(b, off, 16, Charsets.US_ASCII)
        if (!s.all { it.isDigit() } || s.startsWith("0000")) return null
        val offset = b[off + 16].toInt().coerceIn(-48, 52) * 15
        return runCatching {
            LocalDateTime.of(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt(),
            ).toInstant(ZoneOffset.ofTotalSeconds(offset * 60)).toEpochMilli()
        }.getOrNull()
    }

    /** UDF 的 12 字节时间戳（类型 + 时区分钟数、年、月、日、时、分、秒、厘秒…）。 */
    fun udfTime(b: ByteArray, off: Int): Long? {
        val typeTz = le16(b, off)
        var tz = typeTz and 0x0FFF
        if (tz and 0x0800 != 0) tz -= 0x1000
        if (tz == -2047 || tz !in -1440..1440) tz = 0
        val year = (le16(b, off + 2).toShort()).toInt()
        val month = u8(b, off + 4)
        val day = u8(b, off + 5)
        if (year !in 1900..9999 || month !in 1..12 || day !in 1..31) return null
        return runCatching {
            LocalDateTime.of(year, month, day, u8(b, off + 6), u8(b, off + 7), u8(b, off + 8))
                .toInstant(ZoneOffset.ofTotalSeconds(tz * 60)).toEpochMilli() + u8(b, off + 9) * 10L
        }.getOrNull()
    }
}
