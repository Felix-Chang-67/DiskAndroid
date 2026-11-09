package io.github.felixchang67.diskandroid.core.meta

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 一行歌词。[timeMs] < 0 表示没有时间轴（纯文本歌词）。 */
data class LyricLine(val timeMs: Long, val text: String, val translation: String?)

/** 一首歌的歌词（同步或纯文本），附带中文翻译（网易云 tlyric）。 */
class Lyrics(
    val lines: List<LyricLine>,
    val source: String,
    /** 作词 / 翻译贡献者等说明。 */
    val credits: String?,
) {
    val synced: Boolean get() = lines.any { it.timeMs >= 0 }
    val hasTranslation: Boolean get() = lines.any { it.translation != null }

    /** 当前播放位置对应的行（最后一个时间 ≤ 位置的行），没有则 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun toJson(): String = buildJsonObject {
        put("version", 1)
        put("source", source)
        put("credits", credits)
        putJsonArray("lines") {
            lines.forEach { l ->
                addJsonObject {
                    put("t", l.timeMs)
                    put("x", l.text)
                    put("tr", l.translation)
                }
            }
        }
    }.toString()

    companion object {
        fun fromJson(text: String): Lyrics? = runCatching {
            val o = parseJsonObject(text)
            Lyrics(
                lines = o.arr("lines").mapNotNull { e ->
                    val l = e.asObj() ?: return@mapNotNull null
                    LyricLine(l.long("t") ?: -1, l.str("x") ?: "", l.str("tr"))
                },
                source = o.str("source") ?: "",
                credits = o.str("credits"),
            )
        }.getOrNull()
    }
}

/**
 * LRC 解析：`[mm:ss.xx]歌词`，一行可以有多个时间标签；`[offset:±毫秒]` 整体平移；
 * `[ar:] [ti:] [by:]` 等标签跳过；网易云歌词开头的 JSON 行（作词作曲信息的新格式）跳过。
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<Pair<Long, String>> {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("{")) continue
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val m = timeTag.matchAt(rest, 0) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracText = m.groupValues[3]
                val frac = when (fracText.length) {
                    0 -> 0L
                    1 -> fracText.toLong() * 100
                    2 -> fracText.toLong() * 10
                    else -> fracText.take(3).toLong()
                }
                times += (min * 60 + sec) * 1000 + frac
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            val text = rest.trim()
            times.forEach { out += (it - offset).coerceAtLeast(0) to text }
        }
        return out.sortedBy { it.first }
    }

    /**
     * 合并原文与翻译：同一时间点（±100 ms）的翻译放在原文下面。
     * 网易云开头的"作词 : … / 作曲 : …"行保留在歌词里（与网易云显示一致），空行去掉。
     */
    fun build(lrc: String?, translated: String?, source: String, credits: String? = null): Lyrics? {
        val main = lrc?.let { parse(it) }.orEmpty().filter { it.second.isNotBlank() }
        if (main.isEmpty()) {
            // 纯文本歌词（没有时间轴）
            val plain = lrc?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("{") }.orEmpty()
            if (plain.isEmpty()) return null
            return Lyrics(plain.map { LyricLine(-1, it, null) }, source, credits)
        }
        val trans = translated?.let { parse(it) }.orEmpty().filter { it.second.isNotBlank() }
        val lines = main.map { (t, text) ->
            val tr = trans.firstOrNull { kotlin.math.abs(it.first - t) <= 100 }?.second?.takeIf { it != text }
            LyricLine(t, text, tr)
        }
        return Lyrics(lines, source, credits)
    }
}
