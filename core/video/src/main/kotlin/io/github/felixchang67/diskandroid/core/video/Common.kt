package io.github.felixchang67.diskandroid.core.video

import java.io.IOException
import java.util.Locale

/** IFO / MPLS 之类的结构文件有问题（太短、偏移越界、标识不对）。 */
class VideoParseException(message: String) : IOException(message)

/** 带边界检查的大端读取（DVD 的 IFO、BD 的 MPLS / CLPI 都是大端）。 */
internal class Be(val b: ByteArray, private val what: String) {
    val size: Int get() = b.size

    private fun need(off: Int, n: Int) {
        if (off < 0 || off + n > b.size) throw VideoParseException("$what：偏移 $off+$n 超出文件（${b.size} 字节）")
    }

    fun u8(off: Int): Int {
        need(off, 1)
        return b[off].toInt() and 0xFF
    }

    fun u16(off: Int): Int {
        need(off, 2)
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    fun u32(off: Int): Long {
        need(off, 4)
        return (u16(off).toLong() shl 16) or u16(off + 2).toLong()
    }

    fun ascii(off: Int, n: Int): String {
        need(off, n)
        return String(b, off, n, Charsets.ISO_8859_1)
    }

    fun has(off: Int, n: Int) = off >= 0 && off + n <= b.size
}

/** 一条音轨 / 字幕 / 视频流的说明（给界面和日志用）。 */
data class StreamInfo(
    /** 语言代码（DVD 两个字母，BD 三个字母），没有为 null。 */
    val language: String?,
    /** "AC-3"、"LPCM"、"H.264/AVC" 之类。 */
    val codec: String,
    /** "5.1 声道 48 kHz"、"1080i 29.97" 之类。 */
    val detail: String = "",
    /** 附加说明（"导演评论"、"大号字幕"…）。 */
    val note: String? = null,
) {
    val languageName: String get() = Languages.name(language)

    override fun toString(): String = listOf(languageName, codec, detail, note.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 语言代码 → 中文名称（日语、英语…），查不到就原样显示。 */
object Languages {
    private val special = mapOf(
        "jpn" to "ja", "eng" to "en", "chi" to "zh", "zho" to "zh", "kor" to "ko", "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de", "spa" to "es", "ita" to "it", "rus" to "ru", "por" to "pt", "tha" to "th",
        "dut" to "nl", "nld" to "nl", "cze" to "cs", "ces" to "cs", "gre" to "el", "ell" to "el",
    )

    fun name(code: String?): String {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } } ?: return "未知语言"
        if (c == "und" || c == "zxx" || c == "mis") return "未指定语言"
        val two = special[c] ?: c
        val n = Locale.forLanguageTag(two).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
        return if (n.isBlank() || n.equals(two, ignoreCase = true)) c else n
    }

    /** 统一成两个字母的代码（"jpn" → "ja"、"chi" / "zho" → "zh"；没收录的原样小写）；不是语言代码为 null。 */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } } ?: return null
        return special[c] ?: c
    }

    /** 两个代码是不是同一种语言（DVD 的两个字母和蓝光的三个字母可以比）。 */
    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }
}

/** 毫秒 → "1:35:00" / "4:07"。 */
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
