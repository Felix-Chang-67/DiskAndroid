package io.github.felixchang67.diskandroid.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 原生层给的状态数组（顺序见 dp_android.c 的 nativeGetState）→ Kotlin 的状态。 */
class DiscPlayerStateTest {
    @Test
    fun parsesTheNativeStateLayout() {
        val ints = intArrayOf(
            4, 2, 3, 5, 12, // 域（正片）、标题、章节、标题数、章节数
            0, 0, 0, 1, 0, // 按钮数、当前按钮、静止、暂停、结束
            2, 1, 3, 2, 1, // 音轨数、当前音轨、字幕数、当前字幕、字幕显示
            2, 1, 720, 480, 1, // 角度数、当前角度、视频宽、视频高、16:9
            1920, 1080, 720, 480, 0xA0, // 显示宽、显示高、帧宽、帧高、音频流
            0, 0, 0, 0, 0, 0, 0, 0, // DVD：蓝光字段都是 0
        )
        val longs = longArrayOf(61_000, 5_400_000, 123_456_789, 4, 9000, 12, 2_880_000)
        val s = DiscPlayer.parseState(ints, longs, arrayOf("ja AC-3 6ch", "ja LPCM 2ch", "ja", "zh", "en", "mpeg2video", "pcm_dvd", ""))
        assertEquals(DiscPlayer.Domain.TITLE, s.domain)
        assertEquals(2, s.title)
        assertEquals(3, s.chapter)
        assertEquals(12, s.chapters)
        assertTrue(s.paused)
        assertFalse(s.inMenu)
        assertEquals(listOf("ja AC-3 6ch", "ja LPCM 2ch"), s.audio)
        assertEquals(1, s.audioCurrent)
        assertEquals(0xA0, s.audioStream)
        assertEquals(listOf("ja", "zh", "en"), s.subtitles)
        assertEquals(2, s.subtitleCurrent)
        assertTrue(s.subtitleShown)
        assertEquals(2, s.angles)
        assertTrue(s.wide)
        assertEquals(1920, s.displayWidth)
        assertEquals(480, s.frameHeight)
        assertEquals(61_000, s.positionMs)
        assertEquals(5_400_000, s.durationMs)
        assertEquals(4, s.readErrors)
        assertEquals(12, s.framesDropped)
        assertEquals(DiscPlayer.Kind.DVD, s.kind)
        assertEquals("mpeg2video", s.videoCodec)
        assertEquals("pcm_dvd", s.audioCodec)
    }

    @Test
    fun parsesBlurayFields() {
        val ints = IntArray(33).also {
            it[0] = 2 // 菜单显示中 → 当作菜单域
            it[5] = 1
            it[10] = 1
            it[11] = 0
            it[25] = 1 // 蓝光
            it[26] = 1 // 光盘菜单导航
            it[27] = 1
            it[28] = 1 // 有弹出菜单
            it[29] = 800
            it[31] = 1920
            it[32] = 1080
        }
        val s = DiscPlayer.parseState(ints, LongArray(7), arrayOf("jpn LPCM 2ch", "h264", "pcm_bluray", ""))
        assertTrue(s.isBluray)
        assertTrue(s.inMenu)
        assertTrue(s.popup)
        assertEquals(800, s.playlist)
        assertEquals(1920, s.planeWidth)
        assertEquals(listOf("jpn LPCM 2ch"), s.audio)
        assertEquals("h264", s.videoCodec)
        assertEquals("pcm_bluray", s.audioCodec)
    }

    @Test
    fun menuStateAndMissingStrings() {
        val ints = IntArray(33).also {
            it[0] = 2 // 主菜单
            it[5] = 4 // 4 个按钮
            it[6] = 1
            it[7] = 1 // 静止
            it[11] = -1
            it[13] = -1
            it[24] = -1
        }
        val s = DiscPlayer.parseState(ints, LongArray(7), null)
        assertEquals(DiscPlayer.Domain.VMG_MENU, s.domain)
        assertTrue(s.inMenu)
        assertTrue(s.still)
        assertEquals(1, s.angles) // 0 → 至少 1
        assertTrue(s.audio.isEmpty())
        assertEquals(-1, s.audioStream)
    }
}
