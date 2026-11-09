package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.meta.DiscToc
import io.github.felixchang67.diskandroid.core.meta.HttpClient
import io.github.felixchang67.diskandroid.core.meta.HttpResponse
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import io.github.felixchang67.diskandroid.meta.MetadataManager
import io.github.felixchang67.diskandroid.ui.CdPlayerSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 没有 CD-TEXT 的盘：MetadataManager 用（假的）MusicBrainz 识别、网易云补全，界面显示在线曲名与中文译名，并能取到带翻译的歌词。
 * 不联网：HTTP 由 [FakeServices] 按 URL 返回固定响应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetadataUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeServices(private val discId: String) : HttpClient {
        val requests = mutableListOf<String>()

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += "GET $url"
            val body = when {
                url.startsWith("https://musicbrainz.org/ws/2/discid/$discId") -> MB
                url.startsWith("https://music.163.com/api/v1/album/777") -> NE_ALBUM
                url.startsWith("https://music.163.com/api/song/lyric?id=1001") -> LYRIC
                else -> return HttpResponse(404, ByteArray(0))
            }
            return HttpResponse(200, body.replace("DISCID", discId).toByteArray())
        }

        override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
            requests += "POST $url ${form["s"]}"
            return if (url == "https://music.163.com/api/cloudsearch/pc") HttpResponse(200, NE_SEARCH.toByteArray()) else HttpResponse(404, ByteArray(0))
        }

        companion object {
            const val MB = """{"id":"DISCID","releases":[{"id":"rel-1","title":"テスト盤","date":"2001-02-03","country":"JP","barcode":"4988000000000",
                "disambiguation":"初回限定盤","artist-credit":[{"name":"山田太郎","joinphrase":""}],"cover-art-archive":{"front":false},
                "media":[{"position":1,"format":"CD","discs":[{"id":"DISCID"}],"tracks":[
                {"position":1,"title":"一曲目","length":20000},{"position":2,"title":"二曲目","length":20000},{"position":3,"title":"三曲目","length":20000}]}]}]}"""
            const val NE_SEARCH = """{"code":200,"result":{"albums":[{"id":777,"name":"テスト盤","size":3,"artist":{"name":"山田太郎"},"artists":[{"name":"山田太郎"}]}]}}"""
            const val NE_ALBUM = """{"code":200,"album":{"id":777,"name":"テスト盤","size":3,"artists":[{"name":"山田太郎"}]},"songs":[
                {"id":1001,"name":"一曲目","dt":20050,"no":1,"cd":"1","tns":["第一首"],"ar":[{"name":"山田太郎"}]},
                {"id":1002,"name":"二曲目","dt":19980,"no":2,"cd":"1","tns":["第二首"],"ar":[{"name":"山田太郎"}]},
                {"id":1003,"name":"三曲目","dt":20010,"no":3,"cd":"1","alia":["别名三"],"ar":[{"name":"山田太郎"}]}]}"""
            const val LYRIC = """{"code":200,"lrc":{"lyric":"[00:01.00]はじめの行\n[00:05.50]つぎの行\n"},"tlyric":{"lyric":"[00:01.00]第一行\n[00:05.50]下一行\n"}}"""
        }
    }

    @Test
    fun discWithoutCdTextShowsOnlineTitlesTranslationsAndLyrics() {
        val app = ApplicationProvider.getApplicationContext<DiskApp>()
        val container = app.container
        val mmc = FakeMmc().apply {
            insertDisc(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 20), spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
        }
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val session = DriveSession(FakeMmcTransport(mmc), container.log, "测试光驱", scope, executor, pollIntervalMs = 50, becomingReadyIntervalMs = 20)
        val dir: File = Files.createTempDirectory("meta").toFile()
        try {
            session.start()
            val t0 = System.currentTimeMillis()
            while (session.disc.value == null && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
            val disc = requireNotNull(session.disc.value)
            assertTrue(disc.cdText == null, "这张模拟盘没有 CD-TEXT")
            val discId = DiscToc.from(disc)!!.musicBrainzId
            val http = FakeServices(discId)
            val meta = MetadataManager(app, container.log, container.mainScope, http, dir)

            compose.setContent {
                CdPlayerSection(session, disc, container.player, meta) { track -> container.player.play(session, disc, track) }
            }
            compose.onNodeWithText("（未识别：曲名显示为 Track N）").assertExists()
            meta.onDiscReady(session, disc)
            fun idle() = shadowOf(Looper.getMainLooper()).idle()
            compose.waitUntil(10_000) {
                idle()
                meta.discs.value[discId]?.metadata != null
            }
            compose.waitForIdle()
            compose.onNodeWithText("テスト盤").assertExists()
            compose.onNodeWithText("一曲目").assertExists()
            compose.onNodeWithText("第一首").assertExists()
            compose.onNodeWithText("别名三").assertExists()
            compose.onNodeWithText("2001-02-03 · 初回限定盤").assertExists()
            compose.onNodeWithText("曲目信息：MusicBrainz + 网易云音乐").assertExists()
            val m = meta.discs.value[discId]!!.metadata!!
            assertEquals(1001L, m.track(1)?.neteaseSongId)
            assertTrue(http.requests.any { it.startsWith("POST https://music.163.com/api/cloudsearch/pc 山田太郎 テスト盤") }, http.requests.toString())

            meta.requestLyrics(discId, 1, 20_000)
            compose.waitUntil(10_000) {
                idle()
                meta.lyrics.value[meta.lyricsKey(discId, 1)] is MetadataManager.LyricsState.Ready
            }
            val lyrics = (meta.lyrics.value[meta.lyricsKey(discId, 1)] as MetadataManager.LyricsState.Ready).lyrics
            assertEquals(listOf("はじめの行", "つぎの行"), lyrics.lines.map { it.text })
            assertEquals("第一行", lyrics.lines[0].translation)

            // 再放一次同一张盘：直接用本机缓存，不再联网
            val before = http.requests.size
            val again = MetadataManager(app, container.log, container.mainScope, http, dir)
            again.onDiscReady(session, disc)
            assertEquals("テスト盤", again.discs.value[discId]?.metadata?.album)
            assertTrue(again.discs.value[discId]!!.fromCache)
            assertEquals(before, http.requests.size)
        } finally {
            session.close()
            scope.cancel()
            executor.close()
            dir.deleteRecursively()
        }
    }
}
