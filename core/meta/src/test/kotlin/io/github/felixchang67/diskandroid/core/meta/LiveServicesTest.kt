package io.github.felixchang67.diskandroid.core.meta

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 真实联网测试（默认跳过）：DISKANDROID_LIVE=1 ./gradlew :core:meta:test --tests '*LiveServicesTest*'
 * 用 N1.5 真机那张 CD 的 TOC 走一遍 MusicBrainz → 网易云 → 歌词。
 */
class LiveServicesTest {
    @Test
    fun identifyRealDiscAndFetchLyrics() {
        assumeTrue(System.getenv("DISKANDROID_LIVE") == "1")
        val ring = RingBufferSink(500)
        val log = DiskLog().apply { addSink(ring) }
        val http = UrlConnectionHttpClient()
        val mb = MusicBrainzClient(http, "DiskAndroid/dev ( https://github.com/Felix-Chang-67/DiskAndroid )")
        val ne = NetEaseClient(http)
        val toc = DiscToc(1, 10, 185_587, listOf(150, 15752, 31753, 47023, 67874, 87342, 102808, 127634, 147802, 162040))
        val outcome = DiscIdentifier(mb, ne, log).identify(toc) { null }
        outcome.notes.forEach { println("note: $it") }
        val meta = assertNotNull(outcome.chosen)
        meta.tracks.forEach { println("${it.number}. ${it.title}  ${it.translatedTitle ?: ""}  ne=${it.neteaseSongId}") }
        assertEquals("DEEPER", meta.album)
        assertTrue(meta.tracks.count { it.neteaseSongId != null } == 10)
        val t2 = meta.track(2)!!
        val lyrics = assertNotNull(LyricsFinder(ne, LrcLibClient(http, "DiskAndroid/dev"), log).find(t2.title, meta.artist, meta.album, 213_346, t2.neteaseSongId))
        println("lyrics: ${lyrics.lines.size} 行，同步 ${lyrics.synced}，翻译 ${lyrics.hasTranslation}，${lyrics.credits}")
        lyrics.lines.take(4).forEach { println("  [${it.timeMs}] ${it.text} / ${it.translation}") }
        assertTrue(lyrics.synced && lyrics.hasTranslation)
        // 没有歌曲 ID 时按曲名搜
        val byName = LyricsFinder(ne, null, log).find("フユノ", "ヒトリエ", "DEEPER", 331_013, null)
        println("按曲名找到：${byName?.lines?.size} 行")
        assertNotNull(byName)
        ring.snapshot().forEach { println(LogFormat.text(it)) }
    }
}
