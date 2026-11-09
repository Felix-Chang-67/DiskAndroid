package io.github.felixchang67.diskandroid.core.meta

import io.github.felixchang67.diskandroid.core.log.DiskLog
import java.time.Instant
import java.time.ZoneId

/**
 * 在线识别一张没有 CD-TEXT 的音乐 CD（参考 MusicTag 的做法：多个来源、按时长核对、有歧义时让用户挑）：
 * 1. MusicBrainz：按光盘 ID 精确查；没有就按 TOC 模糊匹配；
 * 2. 还没有：读盘上的条码（MCN / JAN），按条码查 MusicBrainz；
 * 3. 认出专辑后，按"艺人 + 专辑名"在网易云音乐里找时长吻合的同一张专辑，补上中文译名、歌曲 ID（取歌词用）和封面；
 * 4. 全都没有：交给用户手动搜索（[searchManual]：网易云 + MusicBrainz，按时长打分排序）。
 * 所有方法都是阻塞的网络调用，放在 IO 线程上执行。
 */
class DiscIdentifier(
    private val mb: MusicBrainzClient,
    private val ne: NetEaseClient,
    private val log: DiskLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Outcome(
        /** 自动采用的结果（足够可信时），否则 null。 */
        val chosen: DiscMetadata?,
        /** 全部候选，最可信的在前。 */
        val candidates: List<MetadataCandidate>,
        /** 过程说明（显示给用户、写日志）。 */
        val notes: List<String>,
    )

    fun identify(toc: DiscToc, readMcn: () -> String?): Outcome {
        val notes = mutableListOf<String>()
        val cd = toc.trackLengthsMs()
        val candidates = mutableListOf<MetadataCandidate>()
        log.i(TAG, "开始在线识别", "TOC" to toc.toString())

        try {
            val lookup = mb.lookupDisc(toc)
            lookup.releases.forEach { r -> fromMusicBrainz(r, toc, cd, lookup.exact)?.let { candidates += it } }
            notes += when {
                lookup.releases.isEmpty() -> "MusicBrainz：没有这张盘的记录"
                lookup.exact -> "MusicBrainz：光盘 ID 精确匹配 ${lookup.releases.size} 个发行版本"
                else -> "MusicBrainz：按 TOC 模糊匹配到 ${lookup.releases.size} 个发行版本"
            }
        } catch (e: MetaException) {
            notes += "MusicBrainz 查询失败：${e.message}"
        }

        if (candidates.none { it.exact || it.score.value >= AUTO_SCORE }) {
            val mcn = runCatching { readMcn() }.getOrNull()
            if (mcn == null) {
                notes += "盘上没有读到条码（MCN）"
            } else {
                notes += "盘上的条码：$mcn"
                try {
                    val ids = mb.searchBarcode(mcn)
                    ids.take(3).forEach { id -> mb.release(id)?.let { r -> fromMusicBrainz(r, toc, cd, exact = false, viaBarcode = true)?.let { candidates += it } } }
                    notes += "MusicBrainz：按条码找到 ${ids.size} 个发行版本"
                } catch (e: MetaException) {
                    notes += "按条码查询失败：${e.message}"
                }
            }
        }

        val sorted = sortCandidates(candidates)
        val best = sorted.firstOrNull()
        var chosen = best?.takeIf { it.exact || it.score.value >= AUTO_SCORE }?.metadata
        if (chosen != null) {
            chosen = enrichWithNetEase(chosen, cd, notes)
        } else if (best != null) {
            notes += "结果不够确定（${best.score.label()}），请在候选里选择"
        } else {
            notes += "没有识别出来，可以手动搜索（输入专辑名或艺人）"
        }
        notes.forEach { log.i(TAG, it) }
        return Outcome(chosen, sorted, notes)
    }

    /** 手动搜索：网易云专辑 + MusicBrainz 发行，按与 CD 的时长吻合程度排序。 */
    fun searchManual(keywords: String, toc: DiscToc): Outcome {
        val notes = mutableListOf<String>()
        val cd = toc.trackLengthsMs()
        val candidates = mutableListOf<MetadataCandidate>()
        try {
            val albums = ne.searchAlbums(keywords, 10)
            notes += "网易云音乐：找到 ${albums.size} 张专辑"
            val ordered = albums.sortedByDescending { it.size == cd.size }.take(MAX_NETEASE_DETAILS)
            for (a in ordered) {
                val detail = runCatching { ne.album(a.id) }.getOrNull() ?: continue
                bestNetEaseDisc(detail, cd)?.let { (songs, score) -> candidates += fromNetEase(detail, songs, score, toc) }
            }
        } catch (e: MetaException) {
            notes += "网易云音乐搜索失败：${e.message}"
        }
        try {
            val ids = mb.searchReleases(keywords, 5)
            notes += "MusicBrainz：找到 ${ids.size} 个发行版本"
            ids.take(3).forEach { id -> mb.release(id)?.let { r -> fromMusicBrainz(r, toc, cd, exact = false)?.let { candidates += it } } }
        } catch (e: MetaException) {
            notes += "MusicBrainz 搜索失败：${e.message}"
        }
        notes.forEach { log.i(TAG, "手动搜索「$keywords」：$it") }
        return Outcome(null, sortCandidates(candidates), notes)
    }

    /** 用户选定一个候选：MusicBrainz 的结果再用网易云补全（中文译名、歌曲 ID、封面）。 */
    fun finalizeChoice(candidate: MetadataCandidate, toc: DiscToc): DiscMetadata {
        val meta = candidate.metadata.copy(chosenByUser = true, savedAt = clock())
        if (meta.neteaseAlbumId != null) return meta
        return enrichWithNetEase(meta, toc.trackLengthsMs(), mutableListOf())
    }

    // ---------------- 内部 ----------------

    private fun sortCandidates(list: List<MetadataCandidate>): List<MetadataCandidate> =
        list.distinctBy { (it.metadata.mbReleaseId ?: "") + "|" + (it.metadata.neteaseAlbumId ?: "") }
            .sortedWith(
                compareByDescending<MetadataCandidate> { it.exact }
                    .thenByDescending { it.score.value }
                    .thenByDescending { it.metadata.country == "JP" }
                    .thenBy { it.metadata.date ?: "9999" },
            )

    private fun fromMusicBrainz(r: MbRelease, toc: DiscToc, cd: List<Long>, exact: Boolean, viaBarcode: Boolean = false): MetadataCandidate? {
        // 多碟发行：优先取含本盘光盘 ID 的那张；否则取时长最吻合的那张
        val medium = r.media.firstOrNull { toc.musicBrainzId in it.discIds }
            ?: r.media.filter { it.tracks.isNotEmpty() }.maxByOrNull { TrackMatcher.score(cd, it.tracks.map { t -> t.lengthMs }).value }
            ?: return null
        val score = TrackMatcher.score(cd, medium.tracks.map { it.lengthMs })
        val exactHere = exact && toc.musicBrainzId in medium.discIds
        val tracks = medium.tracks.take(toc.trackCount).mapIndexed { i, t ->
            TrackMetadata(toc.firstTrack + i, t.title, t.artist?.takeIf { it != r.artist }, null, null)
        }
        val meta = DiscMetadata(
            discId = toc.musicBrainzId,
            album = r.title + if (r.media.size > 1) "（Disc ${medium.position}）" else "",
            artist = r.artist,
            date = r.date,
            label = r.label,
            barcode = r.barcode,
            edition = r.disambiguation,
            country = r.country,
            coverUrl = if (r.hasFrontCover) MusicBrainzClient.coverUrl(r.id) else null,
            mbReleaseId = r.id,
            neteaseAlbumId = null,
            sources = listOf("MusicBrainz"),
            tracks = tracks,
            savedAt = clock(),
        )
        val how = when {
            exactHere -> "光盘 ID 精确匹配"
            viaBarcode -> "条码匹配"
            exact -> "同一光盘 ID"
            else -> "TOC 相近"
        }
        return MetadataCandidate(meta, score, "MusicBrainz（$how）", exactHere)
    }

    private fun bestNetEaseDisc(detail: NeAlbumDetail, cd: List<Long>): Pair<List<NeSong>, MatchScore>? =
        detail.songs.groupBy { it.disc ?: "1" }.values
            .map { songs -> songs to TrackMatcher.score(cd, songs.map { it.durationMs }) }
            .maxByOrNull { it.second.value }

    private fun fromNetEase(detail: NeAlbumDetail, songs: List<NeSong>, score: MatchScore, toc: DiscToc): MetadataCandidate {
        val a = detail.album
        val tracks = songs.take(toc.trackCount).mapIndexed { i, s ->
            TrackMetadata(
                number = toc.firstTrack + i,
                title = s.name,
                artist = s.artists.joinToString(" / ").takeIf { it.isNotBlank() && it != a.artist },
                translatedTitle = s.translated ?: s.alias.firstOrNull(),
                neteaseSongId = s.id,
            )
        }
        val discs = detail.songs.mapNotNull { it.disc }.distinct()
        val meta = DiscMetadata(
            discId = toc.musicBrainzId,
            album = a.name + if (discs.size > 1) "（Disc ${songs.firstOrNull()?.disc ?: "1"}）" else "",
            artist = a.artist,
            date = a.publishTime?.let { Instant.ofEpochMilli(it).atZone(TOKYO).toLocalDate().toString() },
            label = a.company,
            barcode = null,
            edition = null,
            country = null,
            coverUrl = NetEaseClient.coverUrl(a.picUrl),
            mbReleaseId = null,
            neteaseAlbumId = a.id,
            sources = listOf("网易云音乐"),
            tracks = tracks,
            savedAt = clock(),
        )
        return MetadataCandidate(meta, score, "网易云音乐", exact = false)
    }

    /** 在网易云里找同一张专辑（按艺人 + 专辑名搜，时长吻合 ≥ 80% 才采用），补中文译名、歌曲 ID、封面。 */
    private fun enrichWithNetEase(meta: DiscMetadata, cd: List<Long>, notes: MutableList<String>): DiscMetadata {
        val albumName = meta.album?.substringBefore("（Disc")?.trim()
        if (albumName.isNullOrBlank()) return meta
        val keywords = listOfNotNull(meta.artist, albumName).joinToString(" ")
        val albums = try {
            ne.searchAlbums(keywords, 10)
        } catch (e: MetaException) {
            notes += "网易云音乐：${e.message}"
            return meta
        }
        var best: Triple<NeAlbumDetail, List<NeSong>, MatchScore>? = null
        for (a in albums.sortedByDescending { it.size == cd.size }.take(MAX_NETEASE_DETAILS)) {
            val detail = runCatching { ne.album(a.id) }.getOrNull() ?: continue
            val (songs, score) = bestNetEaseDisc(detail, cd) ?: continue
            if (best == null || score.value > best.third.value) best = Triple(detail, songs, score)
            if (score.value >= 0.99) break
        }
        val found = best?.takeIf { it.third.value >= NETEASE_SCORE }
        if (found == null) {
            notes += "网易云音乐：没有找到时长吻合的同一张专辑（歌词会按曲名逐首搜索）"
            return meta
        }
        val (detail, songs, score) = found
        notes += "网易云音乐：匹配到专辑「${detail.album.name}」（${score.label()}），补充中文译名、歌词来源和封面"
        val tracks = meta.tracks.mapIndexed { i, t ->
            val s = songs.getOrNull(i) ?: return@mapIndexed t
            t.copy(translatedTitle = t.translatedTitle ?: s.translated ?: s.alias.firstOrNull(), neteaseSongId = s.id)
        }
        return meta.copy(
            tracks = tracks,
            neteaseAlbumId = detail.album.id,
            coverUrl = meta.coverUrl ?: NetEaseClient.coverUrl(detail.album.picUrl),
            label = meta.label ?: detail.album.company,
            sources = (meta.sources + "网易云音乐").distinct(),
        )
    }

    companion object {
        const val TAG = "META"
        /** 没有精确光盘 ID 时，时长吻合度达到这个值才自动采用。 */
        const val AUTO_SCORE = 0.9
        const val NETEASE_SCORE = 0.8
        private const val MAX_NETEASE_DETAILS = 4
        private val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}

/**
 * 歌词：先用网易云的歌曲 ID 取（有中文翻译）；没有 ID 时按"曲名 艺人"在网易云搜歌、按时长挑；再没有就查 LRCLIB。
 */
class LyricsFinder(
    private val ne: NetEaseClient,
    private val lrcLib: LrcLibClient?,
    private val log: DiskLog,
) {
    fun find(title: String, artist: String?, album: String?, durationMs: Long, neteaseSongId: Long?): Lyrics? {
        val songId = neteaseSongId ?: searchSong(title, artist, durationMs)
        if (songId != null) {
            val l = runCatching { ne.lyric(songId) }.onFailure { log.w(DiscIdentifier.TAG, "取歌词失败：${it.message}") }.getOrNull()
            val parsed = l?.let {
                val credits = listOfNotNull(it.lyricist?.let { u -> "歌词贡献：$u" }, it.translator?.let { u -> "翻译：$u" }).joinToString("，").ifBlank { null }
                LrcParser.build(it.lrc, it.translated, "网易云音乐", credits)
            }
            if (parsed != null) return parsed
        }
        val client = lrcLib
        if (client != null && artist != null) {
            val r = runCatching { client.get(artist, title, album, (durationMs + 500) / 1000) }.getOrNull()
            if (r != null) LrcParser.build(r.synced ?: r.plain, null, "LRCLIB")?.let { return it }
        }
        return null
    }

    private fun searchSong(title: String, artist: String?, durationMs: Long): Long? {
        val songs = runCatching { ne.searchSongs(listOfNotNull(title, artist).joinToString(" "), 10) }.getOrNull() ?: return null
        return songs.filter { s -> s.durationMs?.let { kotlin.math.abs(it - durationMs) <= 3_000 } == true }
            .firstOrNull { s -> s.name.equals(title, ignoreCase = true) || artist == null || s.artists.any { it.equals(artist, ignoreCase = true) } }
            ?.id
    }
}
