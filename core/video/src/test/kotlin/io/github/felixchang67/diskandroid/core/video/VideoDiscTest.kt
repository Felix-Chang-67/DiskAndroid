package io.github.felixchang67.diskandroid.core.video

import io.github.felixchang67.diskandroid.core.fs.ByteArraySectorSource
import io.github.felixchang67.diskandroid.core.fs.CachedSectorSource
import io.github.felixchang67.diskandroid.core.fs.ContentDetector
import io.github.felixchang67.diskandroid.core.fs.ContentKind
import io.github.felixchang67.diskandroid.core.fs.DiscFileSystem
import io.github.felixchang67.diskandroid.core.fs.DiscFs
import kotlinx.coroutines.test.runTest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 镜像由 src/test/fixtures/make_fixtures.sh 生成（dvdauthor 做的 DVD-Video，按规范拼的 BDMV），
 * 期望值来自同目录的 dvd_lsdvd.txt（lsdvd / libdvdread）和 bd_list_titles.txt（libbluray）。
 */
class VideoDiscTest {
    private suspend fun open(name: String): DiscFileSystem {
        val img = GZIPInputStream(javaClass.getResourceAsStream("/fixtures/$name")!!).use { it.readBytes() }
        val src = CachedSectorSource(ByteArraySectorSource(img))
        return DiscFs.open(src, DiscFs.probe(src))!!
    }

    @Test
    fun dvdTitlesMatchLsdvd() = runTest {
        val fs = open("dvd_video.iso.gz")
        val content = ContentDetector.detect(fs)
        assertEquals(listOf(ContentKind.DVD_VIDEO), content.kinds)
        val info = assertNotNull(VideoAnalyzer.analyze(fs, content))
        val disc = assertNotNull(info.dvd)
        assertEquals(3, disc.titles.size)
        assertEquals(3, disc.titleSets)
        assertTrue(disc.warnings.isEmpty(), disc.warnings.toString())

        // Title: 01, Length: 00:00:08.000 Chapters: 03, Cells: 03, Audio streams: 02
        val t1 = disc.titles[0]
        assertEquals(1, t1.titleSet)
        assertEquals(8_000, t1.durationMs)
        assertEquals(25.0, t1.fps)
        assertEquals(listOf(1_920L, 2_880L, 3_200L), t1.chapters.map { it.durationMs })
        assertEquals(listOf(1, 2, 3), t1.chapters.map { it.startCell })
        assertEquals(3, t1.cells.size)
        assertEquals("MPEG-2 PAL 720×576 16:9", t1.video.describe())
        assertEquals(2, t1.audio.size)
        assertEquals("ja", t1.audio[0].language)
        assertEquals("AC-3", t1.audio[0].codec)
        assertEquals("单声道 48 kHz · 流 80h", t1.audio[0].detail)
        assertEquals("en", t1.audio[1].language)
        assertEquals("MPEG-1", t1.audio[1].codec)
        // lsdvd 写的是 0xc1（它直接用"格式基数 + 音轨序号"）；按 PGC 音轨控制字算是 C0h，
        // ffprobe 看 VOB 里这条 MP2 的流号确实是 0x1C0（第一条 MPEG 音频流）
        assertEquals("立体声 48 kHz · 流 C0h", t1.audio[1].detail)
        assertTrue(t1.sequential)

        // Title: 02, Length: 00:00:03.000，LPCM 16bit，Stream id: 0xa0，4:3
        val t2 = disc.titles[1]
        assertEquals(3_000, t2.durationMs)
        assertEquals("MPEG-2 PAL 720×576 4:3", t2.video.describe())
        assertEquals("LPCM", t2.audio.single().codec)
        assertEquals("单声道 48 kHz 16 bit · 流 A0h", t2.audio.single().detail)

        // Title: 03, NTSC 29.97，8.000，章节 1.500 / 6.500，4 个单元
        val t3 = disc.titles[2]
        assertEquals(29.97, t3.fps)
        assertEquals(8_000, t3.durationMs)
        assertEquals(listOf(1_500L, 6_500L), t3.chapters.map { it.durationMs })
        assertEquals(4, t3.cells.size)
        assertEquals("MPEG-2 NTSC 720×480 16:9", t3.video.describe())
        assertEquals("立体声 48 kHz · 流 80h", t3.audio.single().detail)

        // Longest track: 01（与第 3 个一样长，取标题号小的）
        assertEquals(1, disc.main?.number)
        assertEquals("标题 1", info.main?.id)
        assertEquals("标题 1", info.titles.first().id)
        // 单元扇区 + 标题集起点 + 标题 VOB 偏移 = 盘上的 LBA；单元覆盖整个 VOB
        assertTrue(t1.titleSetSector > 0 && t1.titleVobOffset > 0)
        assertEquals(1_370_112L / 2048, t1.sectors)
        assertTrue(info.describeLines().first().contains("主片 标题 1"))
    }

    @Test
    fun bdPlaylistsMatchLibbluray() = runTest {
        val fs = open("bdmv.iso.gz")
        val content = ContentDetector.detect(fs)
        assertEquals(listOf(ContentKind.BDMV), content.kinds)
        val info = assertNotNull(VideoAnalyzer.analyze(fs, content))
        val disc = assertNotNull(info.bd)
        assertEquals(5, disc.playlists.size)
        // 00002（同一段重复 5 次）和 00003（与 00000 完全相同）被过滤
        assertEquals(2, disc.filtered)
        assertEquals(listOf("00000.mpls", "00001.mpls", "00099.mpls"), info.titles.map { it.id })

        // index: 1 duration: 01:35:00 chapters: 12 angles: 1 clips: 2 ... V:1 A:2 PG:1，AUD: jpn jpn，PG: jpn
        val main = assertNotNull(info.main)
        assertEquals("00000.mpls", main.id)
        assertEquals(95 * 60 * 1000L, main.durationMs)
        assertEquals(12, main.chapters)
        assertEquals("H.264/AVC 1080i 29.97", main.video)
        assertEquals(listOf("日语 LPCM 立体声 48 kHz", "日语 AC-3 多声道 48 kHz"), main.audio.map { it.toString() })
        assertEquals(listOf("jpn"), main.subtitles.map { it.language })
        assertEquals(listOf("00001", "00002"), main.bd!!.clips)
        assertEquals(2L * 192 * 32, main.sizeBytes)
        val starts = main.bd!!.chapterStartsMs()
        assertEquals((0 until 10).map { it * 5 * 60_000L } + listOf(50 * 60_000L, 70 * 60_000L), starts)

        val bonus = info.titles[1]
        assertEquals(10 * 60 * 1000L, bonus.durationMs)
        assertEquals(2, bonus.chapters)
        assertEquals(30_000L, info.titles[2].durationMs)
        assertEquals("MPEG-2 1080i 29.97", info.titles[2].video)
    }

    @Test
    fun mainTitleRulesFromLibbluray() {
        fun pl(name: String, minutes: Int, chapters: Int, video: Int = 0x1B, format: Int = 6, audio: Int = 0x81): BdPlaylist {
            val streams = listOf(
                BdStream(BdStream.Kind.VIDEO, 0x1011, video, format, 1, null),
                BdStream(BdStream.Kind.AUDIO, 0x1100, audio, 6, 1, "jpn"),
            )
            val item = BdPlayItem("00001", 0, minutes * 60L * 45000, 1, 1, streams)
            return BdPlaylist(name, listOf(item), (0 until chapters).map { BdMark(1, 0, it * 45000L) }, 0)
        }
        // 都超过 30 分钟：章节多的胜过只有 1 个章节的（即使短一点）
        assertEquals("b", BdTitles.main(listOf(pl("a", 100, 1), pl("b", 95, 20)))?.name)
        // 1080p 胜过 480i
        assertEquals("b", BdTitles.main(listOf(pl("a", 100, 10, format = 1), pl("b", 95, 10, format = 6)))?.name)
        // 无损音轨（TrueHD）胜过 AC-3
        assertEquals("b", BdTitles.main(listOf(pl("a", 100, 10), pl("b", 95, 10, audio = 0x83)))?.name)
        // 不到 30 分钟只比时长
        assertEquals("a", BdTitles.main(listOf(pl("a", 20, 1), pl("b", 10, 20)))?.name)
    }

    @Test
    fun languageNames() {
        assertEquals("日语", Languages.name("ja"))
        assertEquals("日语", Languages.name("jpn"))
        assertEquals("英语", Languages.name("eng"))
        assertEquals("中文", Languages.name("zho"))
        assertEquals("未知语言", Languages.name(null))
        assertEquals("1:35:00", formatDuration(95 * 60_000L))
        assertEquals("0:30", formatDuration(30_000L))
    }
}
