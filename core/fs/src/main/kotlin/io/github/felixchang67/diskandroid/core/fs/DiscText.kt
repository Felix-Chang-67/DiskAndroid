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
    private val EUC_JP: Charset = Charset.forName("EUC-JP")

    /**
     * ISO 9660 的标签、文件名（标准只允许 ASCII 的 A–Z、0–9、_）。实际的盘：
     * Linux / mkisofs 做的常是 UTF-8，日本的 Windows 软件做的是 Shift-JIS（CP932），中文 Windows 上做的是 GBK。
     * 先试 UTF-8；不是的话把 Shift-JIS / GBK / EUC-JP 都解一遍，按"像不像正常文字"打分取最高的（见 [legacy]）；
     * 都不像就按 Latin-1。字段被截断在多字节字符中间时，丢掉残缺的尾巴。
     */
    fun decode(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val slice = b.copyOfRange(off, off + len)
        if (slice.all { it >= 0 }) return String(slice, Charsets.US_ASCII)
        utf8(slice)?.let { return it }
        legacy(slice, minScore = LEGACY_MIN_SCORE)?.let { return it }
        return String(slice, Charsets.ISO_8859_1)
    }

    /**
     * 没有其他证据（80h–9Fh 字节、假名）时，至少要像两个常用汉字才算双字节编码：
     * 西欧文字要连着 4 个带重音的字母才会凑出这个分数，实际不会出现（"AÇÃO" 只凑出一个字，得 4 分）。
     */
    private const val LEGACY_MIN_SCORE = 8

    /**
     * 严格的 UTF-8（允许结尾被截断在一个字符中间）。截断的情况要求前面已经有多字节字符，
     * 否则 "Café" 这种 Latin-1 文字（结尾的 E9 恰好像 UTF-8 的开头）会被误判、丢掉最后一个字母。
     */
    private fun utf8(slice: ByteArray): String? {
        strict(slice, Charsets.UTF_8)?.let { return it }
        for (cut in 1..3) {
            if (slice.size <= cut) break
            if (isUtf8Prefix(slice, slice.size - cut)) {
                strict(slice.copyOf(slice.size - cut), Charsets.UTF_8)?.takeIf { t -> t.any { it.code >= 0x80 } }?.let { return it }
            }
        }
        return null
    }

    // ---------------- 传统双字节编码的猜测 ----------------

    private class Guess(val text: String, val score: Int, val kana: Int, val halfKanaRun: Int)

    /**
     * 在 Shift-JIS、GBK、EUC-JP 里挑最像的一种。三种编码的字节范围互相重叠，光看"能不能解开"分不出来
     * （N2.1 真机：中文 Windows 上的 DVDFab 把「ヒトリエ」按 GBK 写进卷标，字节 A5 D2 A5 C8 A5 EA A5 A8，
     * 当成 Shift-JIS 是一串半角片假名加一个未定义的码位），所以按解出来的字符打分：
     * 假名 +6；常用汉字（各自编码的第一水准 / 一级字）+4，次常用 +2，GBK 扩展区 +1；全角字母数字 +1；
     * 半角片假名 −2；希腊 / 西里尔字母、制表符 −1；解不开或落在自定义区就淘汰。同分时 Shift-JIS > GBK > EUC-JP
     * （假名在 GBK 和 EUC-JP 里的编码相同，只有假名时两者结果一样；汉字分不清时按中文 Windows 软件更常见取 GBK）。
     * 采用的条件：字节里有 80h–9Fh（Latin-1 的控制符，正常的西文不会有）、或者有假名、或者得分 ≥ [minScore]、
     * 或者连着 3 个以上半角片假名（老的日文 DOS 文件名）。都不满足返回 null（多半是西欧文字，按 Latin-1）。
     */
    private fun legacy(b: ByteArray, minScore: Int): String? {
        val hasC1 = b.any { (it.toInt() and 0xFF) in 0x80..0x9F }
        var best: Guess? = null
        for (g in listOfNotNull(guessSjis(b), guessGbk(b), guessEucJp(b))) {
            if (best == null || g.score > best.score) best = g
        }
        val g = best ?: return null
        return if (hasC1 || g.kana > 0 || g.score >= minScore || g.halfKanaRun >= 3) g.text else null
    }

    private class Scorer {
        val sb = StringBuilder()
        var score = 0
        var kana = 0
        var halfRun = 0
        var maxHalfRun = 0

        /** @param common 这个编码里汉字的常用程度：2 = 第一水准 / 一级，1 = 第二水准 / 二级，0 = 扩展区 */
        fun add(c: Char, common: Int): Boolean {
            if (c == '\uFFFD' || c in '\uE000'..'\uF8FF' || c.code < 0x20) return false
            sb.append(c)
            when {
                c in '\uFF61'..'\uFF9F' -> {
                    score -= 2
                    halfRun++
                    maxHalfRun = maxOf(maxHalfRun, halfRun)
                    return true
                }
                c in '\u3041'..'\u30FF' -> {
                    score += 6
                    kana++
                }
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\u3005'..'\u3007' -> score += when (common) {
                    2 -> 4
                    1 -> 2
                    else -> 1
                }
                c in '\uFF01'..'\uFF5E' -> score += 1
                c in '\u0391'..'\u03C9' || c in '\u0401'..'\u0451' || c in '\u2500'..'\u257F' -> score -= 1
            }
            halfRun = 0
            return true
        }

        fun ascii(v: Int): Boolean {
            if (v < 0x20) return false
            sb.append(v.toChar())
            halfRun = 0
            return true
        }

        fun guess() = Guess(sb.toString(), score, kana, maxHalfRun)
    }

    private fun one(cs: Charset, vararg bytes: Int): Char? {
        val s = String(ByteArray(bytes.size) { bytes[it].toByte() }, cs)
        return if (s.length == 1) s[0] else null
    }

    private fun guessSjis(b: ByteArray): Guess? {
        val sc = Scorer()
        var i = 0
        while (i < b.size) {
            val v = u8(b, i)
            when {
                v < 0x80 -> if (!sc.ascii(v)) return null
                v in 0xA1..0xDF -> if (!sc.add((0xFF61 + (v - 0xA1)).toChar(), 0)) return null
                v in 0x81..0x9F || v in 0xE0..0xFC -> {
                    if (i + 1 >= b.size) break // 结尾被截断的半个字符
                    val t = u8(b, i + 1)
                    if (t !in 0x40..0xFC || t == 0x7F) return null
                    val c = one(SJIS, v, t) ?: return null
                    val code = (v shl 8) or t
                    if (!sc.add(c, if (code in 0x889F..0x9872) 2 else 1)) return null
                    i++
                }
                else -> return null
            }
            i++
        }
        return sc.guess()
    }

    private fun guessGbk(b: ByteArray): Guess? {
        val sc = Scorer()
        var i = 0
        while (i < b.size) {
            val v = u8(b, i)
            when {
                v < 0x80 -> if (!sc.ascii(v)) return null
                v in 0x81..0xFE -> {
                    if (i + 1 >= b.size) break
                    val t = u8(b, i + 1)
                    if (t !in 0x40..0xFE || t == 0x7F) return null
                    val c = one(GBK, v, t) ?: return null
                    val common = when {
                        t < 0xA1 -> 0
                        v in 0xB0..0xD7 -> 2
                        v in 0xD8..0xF7 -> 1
                        else -> 0
                    }
                    if (!sc.add(c, common)) return null
                    i++
                }
                else -> return null
            }
            i++
        }
        return sc.guess()
    }

    private fun guessEucJp(b: ByteArray): Guess? {
        val sc = Scorer()
        var i = 0
        while (i < b.size) {
            val v = u8(b, i)
            when {
                v < 0x80 -> if (!sc.ascii(v)) return null
                v == 0x8E -> {
                    if (i + 1 >= b.size) break
                    val t = u8(b, i + 1)
                    if (t !in 0xA1..0xDF) return null
                    if (!sc.add((0xFF61 + (t - 0xA1)).toChar(), 0)) return null
                    i++
                }
                v == 0x8F -> {
                    if (i + 2 >= b.size) break
                    val c = one(EUC_JP, v, u8(b, i + 1), u8(b, i + 2)) ?: return null
                    if (!sc.add(c, 0)) return null
                    i += 2
                }
                v in 0xA1..0xFE -> {
                    if (i + 1 >= b.size) break
                    val t = u8(b, i + 1)
                    if (t !in 0xA1..0xFE) return null
                    val c = one(EUC_JP, v, t) ?: return null
                    if (!sc.add(c, if (v in 0xB0..0xCF) 2 else 1)) return null
                    i++
                }
                else -> return null
            }
            i++
        }
        return sc.guess()
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

    /**
     * UDF 的 OSTA CS0 d-字符：第一个字节是压缩 ID（8 = 每字节一个字符，16 = UTF-16 大端；254 / 255 同 8 / 16）。
     * 压缩 ID 8 按规范每个字节是一个 Unicode 码位（即 Latin-1），但有的软件直接把本地编码的字节放进去
     * （N2.1 真机：DVDFab 写的卷标是 GBK 字节，按 Latin-1 就成了「¥Ò¥È¥ê¥¨」），所以先看是不是 UTF-8 或者
     * 明显的双字节编码，不是才按 Latin-1。
     */
    fun cs0(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0) return ""
        return when (b[off].toInt() and 0xFF) {
            8, 254 -> eightBit(b.copyOfRange(off + 1, off + len))
            16, 255 -> String(b, off + 1, (len - 1) and 1.inv(), Charsets.UTF_16BE)
            else -> decode(b, off + 1, len - 1)
        }.trimEnd('\u0000')
    }

    private fun eightBit(raw: ByteArray): String {
        // 尾部的 NUL 不参与判断
        var end = raw.size
        while (end > 0 && raw[end - 1].toInt() == 0) end--
        val slice = raw.copyOf(end)
        if (slice.all { it >= 0 }) return String(slice, Charsets.ISO_8859_1)
        utf8(slice)?.let { return it }
        legacy(slice, minScore = LEGACY_MIN_SCORE)?.let { return it }
        return String(slice, Charsets.ISO_8859_1)
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
