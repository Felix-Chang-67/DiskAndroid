package io.github.felixchang67.diskandroid.core.meta

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs

/** 一首曲目的在线信息。[number] 是盘上的轨道号。 */
data class TrackMetadata(
    val number: Int,
    val title: String,
    /** 与专辑艺人不同时才有（合辑、客串）。 */
    val artist: String?,
    /** 网易云的中文译名（tns）或别名。 */
    val translatedTitle: String?,
    val neteaseSongId: Long?,
)

/** 一张盘的在线信息（按 MusicBrainz 光盘 ID 缓存在本机）。 */
data class DiscMetadata(
    val discId: String,
    val album: String?,
    val artist: String?,
    val date: String?,
    val label: String?,
    val barcode: String?,
    /** 版本说明，例如 MusicBrainz 的"通常盤 / 初回限定盤"。 */
    val edition: String?,
    val country: String?,
    val coverUrl: String?,
    val mbReleaseId: String?,
    val neteaseAlbumId: Long?,
    /** 数据来源（显示用），例如 ["MusicBrainz", "网易云音乐"]。 */
    val sources: List<String>,
    val tracks: List<TrackMetadata>,
    val savedAt: Long,
    /** 用户手动选的（重新识别时不自动覆盖）。 */
    val chosenByUser: Boolean = false,
) {
    fun track(number: Int): TrackMetadata? = tracks.firstOrNull { it.number == number }

    fun summary(): String = listOfNotNull(album, artist, date?.take(4), edition?.takeIf { it.isNotBlank() }).joinToString(" · ")

    fun toJson(): String = buildJsonObject {
        put("version", 1)
        put("discId", discId)
        put("album", album)
        put("artist", artist)
        put("date", date)
        put("label", label)
        put("barcode", barcode)
        put("edition", edition)
        put("country", country)
        put("coverUrl", coverUrl)
        put("mbReleaseId", mbReleaseId)
        put("neteaseAlbumId", neteaseAlbumId)
        putJsonArray("sources") { sources.forEach { add(it) } }
        put("savedAt", savedAt)
        put("chosenByUser", chosenByUser)
        putJsonArray("tracks") {
            tracks.forEach { t ->
                addJsonObject {
                    put("number", t.number)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("translatedTitle", t.translatedTitle)
                    put("neteaseSongId", t.neteaseSongId)
                }
            }
        }
    }.toString()

    companion object {
        fun fromJson(text: String): DiscMetadata? = runCatching {
            val o = parseJsonObject(text)
            DiscMetadata(
                discId = o.str("discId") ?: return null,
                album = o.str("album"),
                artist = o.str("artist"),
                date = o.str("date"),
                label = o.str("label"),
                barcode = o.str("barcode"),
                edition = o.str("edition"),
                country = o.str("country"),
                coverUrl = o.str("coverUrl"),
                mbReleaseId = o.str("mbReleaseId"),
                neteaseAlbumId = o.long("neteaseAlbumId"),
                sources = o.arr("sources").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                tracks = o.arr("tracks").mapNotNull { e ->
                    val t = e.asObj() ?: return@mapNotNull null
                    TrackMetadata(
                        number = t.int("number") ?: return@mapNotNull null,
                        title = t.str("title") ?: "",
                        artist = t.str("artist"),
                        translatedTitle = t.str("translatedTitle"),
                        neteaseSongId = t.long("neteaseSongId"),
                    )
                },
                savedAt = o.long("savedAt") ?: 0,
                chosenByUser = o.bool("chosenByUser") ?: false,
            )
        }.getOrNull()
    }
}

/** 候选结果与 CD 的时长比对。 */
data class MatchScore(val matched: Int, val total: Int, val candidateTracks: Int) {
    val sameCount: Boolean get() = candidateTracks == total

    /** 0–1：时长吻合的比例；曲目数不同打六折。 */
    val value: Double get() = if (total == 0) 0.0 else matched.toDouble() / total * (if (sameCount) 1.0 else 0.6)

    fun label(): String = "时长吻合 $matched/$total" + if (!sameCount) "（候选有 $candidateTracks 首）" else ""
}

object TrackMatcher {
    /**
     * 按顺序比对每首时长：相差不超过 3 秒或 1.5%（取大者）算吻合。
     * 流媒体版本与 CD 常差零点几秒（N1.5 那张 CD 与网易云相差 0.05–0.07 秒），偶尔因为曲间空白差一两秒。
     */
    fun score(cdMs: List<Long>, candidateMs: List<Long?>): MatchScore {
        var matched = 0
        for (i in 0 until minOf(cdMs.size, candidateMs.size)) {
            val c = candidateMs[i] ?: continue
            val tolerance = maxOf(3_000L, cdMs[i] * 15 / 1000)
            if (abs(cdMs[i] - c) <= tolerance) matched++
        }
        return MatchScore(matched, cdMs.size, candidateMs.size)
    }
}

/** 一个候选（自动识别的多个版本，或手动搜索的结果）。 */
data class MetadataCandidate(
    val metadata: DiscMetadata,
    val score: MatchScore,
    /** 来源说明，例如"MusicBrainz（光盘 ID 精确匹配）"。 */
    val source: String,
    /** 光盘 ID 精确匹配（MusicBrainz 上有人提交过这张盘的 TOC）。 */
    val exact: Boolean,
)

