package io.github.felixchang67.diskandroid.core.meta

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class MbTrack(val position: Int, val title: String, val artist: String?, val lengthMs: Long?)

class MbMedium(val position: Int, val format: String?, val discIds: List<String>, val tracks: List<MbTrack>)

class MbRelease(
    val id: String,
    val title: String,
    val artist: String?,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val disambiguation: String?,
    val label: String?,
    val hasFrontCover: Boolean,
    val media: List<MbMedium>,
)

/**
 * MusicBrainz Web Service v2（JSON）。遵守它的使用规则：
 * - User-Agent 写明应用名、版本和联系方式（这里用项目地址，不发送任何个人信息）；
 * - 每秒最多一个请求。
 * 查询内容只有光盘 TOC（光盘 ID）、条码或搜索词。
 */
class MusicBrainzClient(
    private val http: HttpClient,
    private val userAgent: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRequestAt = 0L

    class DiscLookup(val releases: List<MbRelease>, val exact: Boolean)

    /** 按光盘 ID 查；查不到时 MusicBrainz 会按 toc 参数做模糊匹配（exact = false）。CD stub 不要。 */
    fun lookupDisc(toc: DiscToc): DiscLookup {
        val url = "$BASE/discid/${toc.musicBrainzId}?toc=${toc.musicBrainzToc()}&cdstubs=no&inc=artist-credits+labels+recordings&fmt=json"
        val o = getJson(url) ?: return DiscLookup(emptyList(), false)
        return DiscLookup(o.arr("releases").mapNotNull { parseRelease(it) }, exact = o.containsKey("id"))
    }

    /** 按条码（JAN / UPC）搜，返回发行 ID（最多 [limit] 个）。 */
    fun searchBarcode(barcode: String, limit: Int = 5): List<String> {
        val o = getJson("$BASE/release?query=barcode:$barcode&limit=$limit&fmt=json") ?: return emptyList()
        return o.arr("releases").mapNotNull { it.asObj()?.str("id") }
    }

    /** 按文字搜发行（手动搜索用）。 */
    fun searchReleases(keywords: String, limit: Int = 5): List<String> {
        val q = UrlConnectionHttpClient.enc(keywords)
        val o = getJson("$BASE/release?query=$q&limit=$limit&fmt=json") ?: return emptyList()
        return o.arr("releases").mapNotNull { it.asObj()?.str("id") }
    }

    fun release(id: String): MbRelease? = getJson("$BASE/release/$id?inc=artist-credits+labels+recordings&fmt=json")?.let { parseRelease(it) }

    private fun getJson(url: String): JsonObject? {
        throttle()
        val r = try {
            http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        } catch (e: Exception) {
            throw MetaException("连接 MusicBrainz 失败：${e.message}", e)
        }
        if (r.code == 404) return null
        if (r.code == 503) throw MetaException("MusicBrainz 暂时繁忙（HTTP 503），稍后再试")
        if (r.code != 200) throw MetaException("MusicBrainz 返回 HTTP ${r.code}")
        return parseJsonObject(r.text)
    }

    @Synchronized
    private fun throttle() {
        val wait = lastRequestAt + MIN_INTERVAL_MS - clock()
        if (wait > 0) sleeper(wait)
        lastRequestAt = clock()
    }

    companion object {
        const val BASE = "https://musicbrainz.org/ws/2"
        const val MIN_INTERVAL_MS = 1_100L

        fun coverUrl(releaseId: String) = "https://coverartarchive.org/release/$releaseId/front-500"

        fun artistCredit(credits: List<JsonElement>): String? {
            if (credits.isEmpty()) return null
            return credits.joinToString("") { e -> e.asObj()?.let { (it.str("name") ?: "") + (it.str("joinphrase") ?: "") } ?: "" }
                .ifBlank { null }
        }

        internal fun parseRelease(e: JsonElement): MbRelease? {
            val o = e.asObj() ?: return null
            return MbRelease(
                id = o.str("id") ?: return null,
                title = o.str("title") ?: "",
                artist = artistCredit(o.arr("artist-credit")),
                date = o.str("date")?.ifBlank { null },
                country = o.str("country"),
                barcode = o.str("barcode")?.ifBlank { null },
                disambiguation = o.str("disambiguation")?.ifBlank { null },
                label = o.arr("label-info").firstNotNullOfOrNull { it.asObj()?.obj("label")?.str("name") },
                hasFrontCover = o.obj("cover-art-archive")?.bool("front") == true,
                media = o.arr("media").mapNotNull { m -> m.asObj()?.let { parseMedium(it) } },
            )
        }

        private fun parseMedium(m: JsonObject): MbMedium = MbMedium(
            position = m.int("position") ?: 1,
            format = m.str("format"),
            discIds = m.arr("discs").mapNotNull { it.asObj()?.str("id") },
            tracks = m.arr("tracks").mapNotNull { e ->
                val t = e.asObj() ?: return@mapNotNull null
                val recording = t.obj("recording")
                MbTrack(
                    position = t.int("position") ?: 0,
                    title = t.str("title") ?: recording?.str("title") ?: "",
                    artist = artistCredit(t.arr("artist-credit")) ?: recording?.let { artistCredit(it.arr("artist-credit")) },
                    lengthMs = t.long("length") ?: recording?.long("length"),
                )
            },
        )
    }
}
