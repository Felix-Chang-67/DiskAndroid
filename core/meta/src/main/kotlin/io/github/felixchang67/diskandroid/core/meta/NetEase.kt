package io.github.felixchang67.diskandroid.core.meta

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class NeSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val durationMs: Long?,
    /** 中文译名（tns）。 */
    val translated: String?,
    val alias: List<String>,
    val trackNo: Int?,
    /** 第几张盘（多碟专辑），网易云里是字符串 "1"、"2"。 */
    val disc: String?,
)

class NeAlbum(
    val id: Long,
    val name: String,
    val artist: String?,
    val size: Int?,
    val picUrl: String?,
    val publishTime: Long?,
    val company: String?,
)

class NeAlbumDetail(val album: NeAlbum, val songs: List<NeSong>)

class NeLyric(val lrc: String?, val translated: String?, val lyricist: String?, val translator: String?)

/**
 * 网易云音乐的网页端接口（非官方、无需登录）。2026-11 实测：
 * - 搜索：POST /api/cloudsearch/pc（旧的 /api/search/get/web 已改为返回加密结果）；
 * - 专辑：GET /api/v1/album/{id}（每首有 id、时长 dt、中文译名 tns）；
 * - 歌词：GET /api/song/lyric?id=…&lv=1&kv=1&tv=-1（lrc 原文 + tlyric 中文翻译，时间轴一致）。
 * 只发送搜索词和歌曲 / 专辑 ID。
 */
class NetEaseClient(private val http: HttpClient) {
    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to "https://music.163.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    fun searchAlbums(keywords: String, limit: Int = 10): List<NeAlbum> {
        val o = post("$BASE/api/cloudsearch/pc", mapOf("s" to keywords, "type" to "10", "limit" to "$limit", "offset" to "0"))
        return o.obj("result")?.arr("albums").orEmpty().mapNotNull { parseAlbum(it) }
    }

    fun searchSongs(keywords: String, limit: Int = 10): List<NeSong> {
        val o = post("$BASE/api/cloudsearch/pc", mapOf("s" to keywords, "type" to "1", "limit" to "$limit", "offset" to "0"))
        return o.obj("result")?.arr("songs").orEmpty().mapNotNull { parseSong(it) }
    }

    fun album(id: Long): NeAlbumDetail? {
        val o = get("$BASE/api/v1/album/$id")
        val album = parseAlbum(o["album"] ?: return null) ?: return null
        return NeAlbumDetail(album, o.arr("songs").mapNotNull { parseSong(it) })
    }

    fun lyric(songId: Long): NeLyric? {
        val o = get("$BASE/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
        val lrc = o.obj("lrc")?.str("lyric")?.ifBlank { null }
        val tl = o.obj("tlyric")?.str("lyric")?.ifBlank { null }
        if (lrc == null && tl == null) return null
        return NeLyric(lrc, tl, o.obj("lyricUser")?.str("nickname"), o.obj("transUser")?.str("nickname"))
    }

    private fun get(url: String): JsonObject = check(call { http.get(url, headers) })

    private fun post(url: String, form: Map<String, String>): JsonObject = check(call { http.post(url, form, headers) })

    private fun call(block: () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: Exception) {
        throw MetaException("连接网易云音乐失败：${e.message}", e)
    }

    private fun check(r: HttpResponse): JsonObject {
        if (r.code != 200) throw MetaException("网易云音乐返回 HTTP ${r.code}")
        val o = parseJsonObject(r.text)
        val code = o.int("code")
        if (code != null && code != 200) throw MetaException("网易云音乐拒绝了请求（code $code：${o.str("message") ?: o.str("msg") ?: ""}）")
        return o
    }

    companion object {
        const val BASE = "https://music.163.com"
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** 封面统一用 https（Android 默认禁止明文 HTTP），并请求 600×600 的缩略图。 */
        fun coverUrl(picUrl: String?): String? = picUrl?.takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://")?.let { "$it?param=600y600" }

        internal fun parseAlbum(e: JsonElement): NeAlbum? {
            val o = e.asObj() ?: return null
            val artists = o.arr("artists").mapNotNull { it.asObj()?.str("name") }
            return NeAlbum(
                id = o.long("id") ?: return null,
                name = o.str("name") ?: "",
                artist = artists.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: o.obj("artist")?.str("name"),
                size = o.int("size"),
                picUrl = o.str("picUrl"),
                publishTime = o.long("publishTime")?.takeIf { it > 0 },
                company = o.str("company")?.ifBlank { null },
            )
        }

        internal fun parseSong(e: JsonElement): NeSong? {
            val o = e.asObj() ?: return null
            fun strings(key: String) = o.arr(key).mapNotNull { (it as? JsonPrimitive)?.content?.ifBlank { null } }
            return NeSong(
                id = o.long("id") ?: return null,
                name = o.str("name") ?: "",
                artists = (o.arr("ar").ifEmpty { o.arr("artists") }).mapNotNull { it.asObj()?.str("name") },
                durationMs = o.long("dt") ?: o.long("duration"),
                translated = strings("tns").firstOrNull() ?: strings("transNames").firstOrNull(),
                alias = strings("alia").ifEmpty { strings("alias") },
                trackNo = o.int("no")?.takeIf { it > 0 },
                disc = o.str("cd")?.ifBlank { null },
            )
        }
    }
}

/** LRCLIB（lrclib.net，开放的歌词库）：网易云没有歌词时的备选。按艺人 + 曲名 + 专辑 + 时长精确查。 */
class LrcLibClient(private val http: HttpClient, private val userAgent: String) {
    class Result(val synced: String?, val plain: String?)

    fun get(artist: String, title: String, album: String?, durationSec: Long): Result? {
        val q = buildString {
            append("artist_name=").append(UrlConnectionHttpClient.enc(artist))
            append("&track_name=").append(UrlConnectionHttpClient.enc(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(UrlConnectionHttpClient.enc(album))
            append("&duration=").append(durationSec)
        }
        val r = try {
            http.get("https://lrclib.net/api/get?$q", mapOf("User-Agent" to userAgent))
        } catch (e: Exception) {
            throw MetaException("连接 LRCLIB 失败：${e.message}", e)
        }
        if (r.code == 404) return null
        if (r.code != 200) throw MetaException("LRCLIB 返回 HTTP ${r.code}")
        val o = parseJsonObject(r.text)
        if (o.bool("instrumental") == true) return Result(null, null)
        return Result(o.str("syncedLyrics")?.ifBlank { null }, o.str("plainLyrics")?.ifBlank { null })
    }
}
