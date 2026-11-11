package io.github.felixchang67.diskandroid.video

import io.github.felixchang67.diskandroid.core.player.DiscPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 播放界面顶栏 / 技术信息的文字（DVD 和蓝光）。 */
class PlayerTextTest {
    private val bd = DiscPlayer.State(kind = DiscPlayer.Kind.BLURAY, domain = DiscPlayer.Domain.TITLE, bdMode = DiscPlayer.BD_MODE_MENU)

    @Test
    fun blurayTitleWithPlaylistAndChapter() {
        val s = bd.copy(title = 1, titles = 3, playlist = 800, chapter = 2, chapters = 24)
        assertEquals("标题 1/3 · 00800.mpls · 第 2/24 章", where(s))
    }

    @Test
    fun blurayTopMenuAndPopup() {
        assertEquals("顶层菜单 · 00801.mpls", where(bd.copy(domain = DiscPlayer.Domain.VMG_MENU, buttons = 1, title = 0, playlist = 801, chapters = 1, chapter = 1)))
        assertEquals("光盘菜单 · 标题 2/3 · 00800.mpls", where(bd.copy(domain = DiscPlayer.Domain.VMG_MENU, buttons = 1, title = 2, titles = 3, playlist = 800)))
    }

    @Test
    fun blurayDirectPlaybackOfABdjDisc() {
        val s = bd.copy(bdMode = DiscPlayer.BD_MODE_PLAYLIST, bdj = true, playlist = 1, chapter = 1, chapters = 3, paused = true)
        assertEquals("00001.mpls · 第 1/3 章 · 已暂停 · BD-J 菜单不支持，直接播放主片", where(s))
    }

    @Test
    fun dvdTextIsUnchanged() {
        val s = DiscPlayer.State(domain = DiscPlayer.Domain.TITLE, title = 2, titles = 5, chapter = 3, chapters = 12, wide = true)
        assertEquals("标题 2/5 · 第 3/12 章 · 16:9", where(s))
        assertEquals("主菜单（4 个按钮）", where(DiscPlayer.State(domain = DiscPlayer.Domain.VMG_MENU, buttons = 4)))
    }

    @Test
    fun audioNamesForBothFormats() {
        assertEquals("日语 AC-3 2ch", audioName("ja AC-3 2ch"))
        assertEquals("日语 LPCM 2ch", audioName("jpn LPCM 2ch"))
        assertEquals("DTS-HD MA 6ch", audioName("--- DTS-HD MA 6ch"))
    }

    @Test
    fun statsUseTheDecoderNames() {
        val s = bd.copy(videoWidth = 1920, videoHeight = 1080, videoCodec = "h264", audioCodec = "truehd", bytesRead = 50L * 1_048_576, framesDropped = 3)
        assertEquals("H.264 1920×1080 TrueHD · 读盘 50 MB · 丢帧 3", stats(s))
        // 旧的 DVD 状态（没有解码器名）还是按流号显示
        val dvd = DiscPlayer.State(videoWidth = 720, videoHeight = 480, audioStream = 0x80)
        assertTrue(stats(dvd).startsWith("720×480 AC-3"))
        assertEquals("LPCM", codecLabel("pcm_bluray"))
    }
}
