package io.github.felixchang67.diskandroid.core.meta

import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.mmc.Toc
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String = requireNotNull(MetaTest::class.java.getResource("/fixtures/$name")) { name }.readText()

/** 按 URL 前缀返回固定响应，并记下所有请求。 */
private class FakeHttp(private val routes: Map<String, () -> HttpResponse>) : HttpClient {
    val requests = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse = route("GET $url", headers)

    override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse =
        route("POST $url ${form.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}", headers)

    private fun route(key: String, h: Map<String, String>): HttpResponse {
        requests += key
        headers += h
        val match = routes.entries.firstOrNull { key.startsWith(it.key) } ?: return HttpResponse(404, """{"error":"Not Found"}""".toByteArray())
        return match.value()
    }
}

private fun ok(body: String) = { HttpResponse(200, body.toByteArray()) }

class MetaTest {
    private val log = DiskLog().apply { addSink(RingBufferSink(2000)) }

    /** N1.3 / N1.5 真机：那张 10 轨 CD 的 READ TOC 格式 0 原始响应。 */
    private val deeperToc = Toc.parse(
        Hex.decode(
            "005A010A00100100000000000010020000003CF20010030000007B73001004000000B719001005000001088C00100600000154980010070000019102" +
                "001008000001F1FC00100900000240C400100A00000278620010AA000002D45D",
        ),
    )!!

    private fun discInfo(toc: Toc) = DiscInfo(0x0008, null, null, toc, null, null, emptyList(), null, null, null, null, emptyMap(), emptyMap(), 0)

    private val deeper: DiscToc get() = DiscToc.from(discInfo(deeperToc))!!

    @Test
    fun discIdsMatchLibdiscidTestVector() {
        // libdiscid test/test_put.c
        val offsets = listOf(150, 9700, 25887, 39297, 53795, 63735, 77517, 94877, 107270, 123552, 135522, 148422, 161197, 174790, 192022, 205545, 218010, 228700, 239590, 255470, 266932, 288750)
        val toc = DiscToc(1, 22, 303602, offsets)
        assertEquals("xUp1F2NkfP8s8jaeFn_Av3jNEI4-", toc.musicBrainzId)
        assertEquals("370fce16", toc.freedbId)
    }

    @Test
    fun realDiscTocGivesTheIdMusicBrainzKnows() {
        val toc = deeper
        assertEquals(10, toc.trackCount)
        assertEquals(185_587, toc.leadOut)
        assertEquals("beyzshJLWsTuHOEnc1BtgGwQoW0-", toc.musicBrainzId)
        assertEquals("6309a80a", toc.freedbId)
        assertEquals("1+10+185587+150+15752+31753+47023+67874+87342+102808+127634+147802+162040", toc.musicBrainzToc())
        // MusicBrainz 按 TOC 算出的第 1 轨时长是 208026 ms
        assertEquals(208_026L, toc.trackLengthsMs().first())
    }

    @Test
    fun enhancedCdUsesAudioSessionOnly() {
        // 2 首音轨 + 第 2 区段的数据轨（加强版 CD）：lead-out = 数据轨起点 − 11400 + 150
        val raw = Hex.decode("001A0103" + "00100100" + "00000000" + "00100200" + "000034BC" + "00140300" + "00009510" + "0014AA00" + "00010000")
        val toc = assertNotNull(DiscToc.from(discInfo(Toc.parse(raw)!!)))
        assertEquals(2, toc.lastTrack)
        assertEquals(0x9510 - 11_400 + 150, toc.leadOut)
    }

    @Test
    fun musicBrainzDiscLookupParsesRealResponse() {
        val http = FakeHttp(mapOf("GET https://musicbrainz.org/ws/2/discid/beyzshJLWsTuHOEnc1BtgGwQoW0-" to ok(fixture("mb_discid_deeper.json"))))
        val mb = MusicBrainzClient(http, "DiskAndroid/test ( https://example.invalid )", sleeper = {})
        val r = mb.lookupDisc(deeper)
        assertTrue(r.exact)
        val rel = r.releases.single()
        assertEquals("DEEPER", rel.title)
        assertEquals("ヒトリエ", rel.artist)
        assertEquals("4547403043075", rel.barcode)
        assertEquals("通常盤", rel.disambiguation)
        assertTrue(rel.hasFrontCover)
        assertEquals("シャッタードール", rel.media.single().tracks[1].title)
        assertTrue("beyzshJLWsTuHOEnc1BtgGwQoW0-" in rel.media.single().discIds)
        assertTrue(http.requests.single().contains("toc=1+10+185587+150+"))
        assertEquals("DiskAndroid/test ( https://example.invalid )", http.headers.single()["User-Agent"])
    }

    @Test
    fun netEaseParsersReadRealResponses() {
        val http = FakeHttp(
            mapOf(
                "POST https://music.163.com/api/cloudsearch/pc" to ok(fixture("ne_search_albums_deeper.json")),
                "GET https://music.163.com/api/v1/album/3464017" to ok(fixture("ne_album_deeper.json")),
                "GET https://music.163.com/api/song/lyric?id=41672429" to ok(fixture("ne_lyric_synthetic.json")),
            ),
        )
        val ne = NetEaseClient(http)
        val album = ne.searchAlbums("ヒトリエ DEEPER").first()
        assertEquals(3464017L, album.id)
        assertEquals("ヒトリエ", album.artist)
        assertEquals(10, album.size)
        val detail = assertNotNull(ne.album(album.id))
        assertEquals(10, detail.songs.size)
        assertEquals("摄影人偶", detail.songs[1].translated)
        assertEquals(213_414L, detail.songs[1].durationMs)
        assertEquals("1", detail.songs[1].disc)
        val lyric = assertNotNull(ne.lyric(41672429))
        val lyrics = assertNotNull(LrcParser.build(lyric.lrc, lyric.translated, "网易云音乐"))
        assertTrue(lyrics.synced)
        // JSON 格式的作词行与 [offset] 行被跳过；同一行两个时间标签展开成两行
        assertEquals(listOf(0L, 12_500L, 15_000L, 20_300L, 80_300L), lyrics.lines.map { it.timeMs })
        assertEquals("第一行歌词", lyrics.lines[1].translation)
        assertEquals("重复", lyrics.lines[3].translation)
        assertNull(lyrics.lines[0].translation)
        assertEquals(1, lyrics.indexAt(13_000))
        assertEquals(-1, Lyrics(lyrics.lines.drop(1), "", null).indexAt(1_000))
        // 浏览器式请求头 + Referer
        assertEquals("https://music.163.com/", http.headers.first()["Referer"])
        assertTrue(http.requests.first().contains("type=10"))
    }

    @Test
    fun identifyUsesMusicBrainzThenEnrichesFromNetEase() {
        val http = FakeHttp(
            mapOf(
                "GET https://musicbrainz.org/ws/2/discid/" to ok(fixture("mb_discid_deeper.json")),
                "POST https://music.163.com/api/cloudsearch/pc" to ok(fixture("ne_search_albums_deeper.json")),
                "GET https://music.163.com/api/v1/album/3464017" to ok(fixture("ne_album_deeper.json")),
            ),
        )
        val id = DiscIdentifier(MusicBrainzClient(http, "test", sleeper = {}), NetEaseClient(http), log, clock = { 42 })
        var mcnRead = false
        val outcome = id.identify(deeper) { mcnRead = true; null }
        val meta = assertNotNull(outcome.chosen)
        assertFalse(mcnRead, "光盘 ID 精确匹配时不需要读条码")
        assertEquals("DEEPER", meta.album)
        assertEquals("ヒトリエ", meta.artist)
        assertEquals("2016-02-24", meta.date)
        assertEquals(listOf("MusicBrainz", "网易云音乐"), meta.sources)
        assertEquals("シャッタードール", meta.track(2)?.title)
        assertEquals("摄影人偶", meta.track(2)?.translatedTitle)
        assertEquals(41672429L, meta.track(2)?.neteaseSongId)
        assertEquals(3464017L, meta.neteaseAlbumId)
        // MusicBrainz 有封面就用封面库的
        assertEquals("https://coverartarchive.org/release/16843e39-5453-4f97-8321-ac7da3ed4ad8/front-500", meta.coverUrl)
        assertTrue(outcome.candidates.first().exact)
        assertTrue(outcome.notes.any { it.contains("精确匹配") })
        assertTrue(outcome.notes.any { it.contains("时长吻合 10/10") }, outcome.notes.toString())
    }

    @Test
    fun unknownDiscFallsBackToBarcodeThenManualSearch() {
        val http = FakeHttp(
            mapOf(
                // MusicBrainz：光盘 ID 与条码都查不到（404 / 空列表）
                "GET https://musicbrainz.org/ws/2/release?query=barcode:" to ok("""{"releases":[]}"""),
                "GET https://musicbrainz.org/ws/2/release?query=" to ok("""{"releases":[]}"""),
                "POST https://music.163.com/api/cloudsearch/pc" to ok(fixture("ne_search_albums_deeper.json")),
                "GET https://music.163.com/api/v1/album/3464017" to ok(fixture("ne_album_deeper.json")),
            ),
        )
        val id = DiscIdentifier(MusicBrainzClient(http, "test", sleeper = {}), NetEaseClient(http), log, clock = { 42 })
        val auto = id.identify(deeper) { "4547403043075" }
        assertNull(auto.chosen)
        assertTrue(auto.notes.any { it.contains("条码：4547403043075") }, auto.notes.toString())
        assertTrue(http.requests.any { it.contains("query=barcode:4547403043075") })

        val manual = id.searchManual("ヒトリエ DEEPER", deeper)
        val best = manual.candidates.first()
        assertEquals("网易云音乐", best.source)
        assertEquals(10, best.score.matched)
        assertEquals("後天症のバックビート", best.metadata.track(9)?.title)
        assertEquals("后天性的基调强节奏", best.metadata.track(9)?.translatedTitle)
        assertEquals("2016-02-24", best.metadata.date)
        assertTrue(best.metadata.coverUrl!!.startsWith("https://p2.music.126.net/"))
        val chosen = id.finalizeChoice(best, deeper)
        assertTrue(chosen.chosenByUser)
    }

    @Test
    fun matcherToleratesStreamingDifferences() {
        val cd = listOf(208_026L, 213_346L, 203_600L)
        assertEquals(3, TrackMatcher.score(cd, listOf(208_097L, 213_414L, 205_473L)).matched)
        val s = TrackMatcher.score(cd, listOf(208_097L, 250_000L))
        assertEquals(1, s.matched)
        assertFalse(s.sameCount)
        assertEquals(1.0 / 3 * 0.6, s.value, 1e-9)
    }

    @Test
    fun storeRoundTripsMetadataAndLyrics() {
        val dir: File = Files.createTempDirectory("meta").toFile()
        try {
            val store = MetadataStore(dir, clock = { 1_000 })
            val meta = DiscMetadata(
                "beyzshJLWsTuHOEnc1BtgGwQoW0-", "DEEPER", "ヒトリエ", "2016-02-24", "Sony", "4547403043075", "通常盤", "JP", null, "mbid", 3464017,
                listOf("MusicBrainz"), listOf(TrackMetadata(1, "GO BACK TO VENUSFORT", null, null, 41672428)), 1_000, chosenByUser = true,
            )
            store.save(meta)
            assertEquals(meta, store.load(meta.discId))
            val lyrics = Lyrics(listOf(LyricLine(1_000, "一行目", "第一行"), LyricLine(2_000, "二行目", null)), "网易云音乐", "翻译：某人")
            store.saveLyrics("ne-1", lyrics)
            val back = assertNotNull((store.loadLyrics("ne-1") as? MetadataStore.CachedLyrics.Found)?.lyrics)
            assertEquals(lyrics.lines, back.lines)
            store.saveLyrics("ne-2", null)
            assertEquals(MetadataStore.CachedLyrics.None, store.loadLyrics("ne-2"))
            assertTrue(store.sizeBytes() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
