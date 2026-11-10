package io.github.felixchang67.diskandroid.core.cdda

import io.github.felixchang67.diskandroid.core.cdda.PlayerState.Status
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.testing.CdTextBuilder
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CdPlayerEngineTest {
    private val ring = RingBufferSink(20_000)
    private val log = DiskLog().apply { addSink(ring) }

    private class Rig(val mmc: FakeMmc, val engine: CdPlayerEngine, val output: FakeAudioOutput, val album: CdAlbum)

    /** 默认关掉起播淡入，输出才能与盘上数据逐字节比对（淡入另有专门的测试）。 */
    private fun TestScope.rig(disc: FakeDisc, options: CdPlayerEngine.Options = CdPlayerEngine.Options(shortFadeMs = 0), configure: FakeMmc.() -> Unit = {}): Rig {
        val mmc = FakeMmc().apply {
            insertDisc(disc, spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
            configure()
        }
        val transport = FakeMmcTransport(mmc)
        val tracks = disc.tracks.filter { it.audio }.map { CdTrack(it.number, it.startLba, it.sectors, it.preEmphasis, "Track ${it.number}", null) }
        val album = CdAlbum(tracks, null, null)
        val output = FakeAudioOutput({ testScheduler.currentTime })
        val reader = CddaReader({ transport.execute(it) }, log, sleep = {})
        val engine = CdPlayerEngine(album, reader, output, log, backgroundScope, StandardTestDispatcher(testScheduler), options)
        return Rig(mmc, engine, output, album)
    }

    @Test
    fun playsWholeAlbumGaplesslyAndEnds() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 3))
        r.engine.play()
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(Status.PLAYING, r.engine.state.value.status)
        assertEquals(1, r.engine.state.value.track?.number)
        advanceTimeBy(3_000)
        assertEquals(2, r.engine.state.value.track?.number)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(Status.ENDED, r.engine.state.value.status)
        // 两轨共 450 扇区，逐字节等于盘上的数据（小端，无去加重）
        assertContentEquals(r.mmc.expectedAudio(0, 450), r.output.all.toByteArray())
        // 播放时降到约 4 倍速，播完恢复光驱最大速度
        assertEquals(listOf(706, 0xFFFF), r.mmc.speedRequests)
        assertTrue(r.output.drained)
    }

    @Test
    fun seekRestartsFromRequestedPosition() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 3))
        r.engine.play(trackNumber = 2)
        runCurrent()
        advanceTimeBy(500)
        r.engine.seekTo(2_000)
        runCurrent()
        advanceTimeBy(200)
        val s = r.engine.state.value
        assertEquals(2, s.track?.number)
        assertTrue(s.positionMs in 2_000..2_400, "位置 ${s.positionMs}")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Status.ENDED, r.engine.state.value.status)
        // flush 之后输出里只剩跳转点（第 2 轨 2 秒 = LBA 225 + 150）以后的数据
        assertContentEquals(r.mmc.expectedAudio(375, 75), r.output.all.toByteArray())
    }

    @Test
    fun nextAndPreviousFollowCdPlayerConventions() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 10))
        r.engine.play()
        runCurrent()
        advanceTimeBy(1_000)
        r.engine.next()
        runCurrent()
        advanceTimeBy(500)
        assertEquals(2, r.engine.state.value.track?.number)
        r.engine.previous() // 本轨才播了不到 3 秒：回到上一轨
        runCurrent()
        advanceTimeBy(4_000)
        assertEquals(1, r.engine.state.value.track?.number)
        r.engine.previous() // 已经播了 4 秒：回到本轨开头
        runCurrent()
        advanceTimeBy(300)
        assertEquals(1, r.engine.state.value.track?.number)
        assertTrue(r.engine.state.value.positionMs < 1_000)
    }

    @Test
    fun pauseHoldsPosition() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 10))
        r.engine.play()
        runCurrent()
        advanceTimeBy(2_000)
        r.engine.pause()
        runCurrent()
        val at = r.engine.state.value.positionMs
        advanceTimeBy(3_000)
        assertEquals(Status.PAUSED, r.engine.state.value.status)
        assertEquals(at, r.engine.state.value.positionMs)
        r.engine.togglePause()
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(Status.PLAYING, r.engine.state.value.status)
        assertTrue(r.engine.state.value.positionMs > at)
    }

    @Test
    fun badSectorsAreMutedAndPlaybackContinues() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 3, badSectors = setOf(100L, 101L, 102L)))
        r.engine.play()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        val s = r.engine.state.value
        assertEquals(Status.ENDED, s.status)
        assertEquals(3, s.badSectors)
        val expected = r.mmc.expectedAudio(0, 225)
        expected.fill(0, 100 * SECTOR_BYTES, 103 * SECTOR_BYTES)
        // 静音段前后各 5 ms 淡出 / 淡入（只在坏扇区所在的那一块 96–119 里做）
        val chunk = expected.copyOfRange(96 * SECTOR_BYTES, 120 * SECTOR_BYTES)
        PcmGain.fadeAroundSilence(chunk, 96, listOf(100L, 101L, 102L))
        chunk.copyInto(expected, 96 * SECTOR_BYTES)
        assertContentEquals(expected, r.output.all.toByteArray())
        assertTrue(ring.snapshot().any { it.message.contains("已静音") })
    }

    @Test
    fun removingDiscStopsPlaybackWithReason() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 60))
        r.engine.play()
        runCurrent()
        advanceTimeBy(2_000)
        r.mmc.openTray()
        advanceTimeBy(6_000)
        runCurrent()
        val s = r.engine.state.value
        assertEquals(Status.ERROR, s.status)
        assertTrue(s.message!!.contains("盘片已取出"), s.message)
    }

    @Test
    fun bigEndianDriveIsDetectedAndCorrected() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 2)) { bigEndianAudio = true }
        r.engine.play()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(Status.ENDED, r.engine.state.value.status)
        assertEquals("大端（自动检测）", r.engine.state.value.byteOrder)
        assertContentEquals(r.mmc.expectedAudio(0, 150), r.output.all.toByteArray())
    }

    @Test
    fun preEmphasisedTrackIsFilteredOthersUntouched() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 2, preEmphasisTracks = setOf(2)))
        r.engine.play()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        val out = r.output.all.toByteArray()
        val ref = r.mmc.expectedAudio(0, 300)
        assertContentEquals(ref.copyOfRange(0, 150 * SECTOR_BYTES), out.copyOfRange(0, 150 * SECTOR_BYTES))
        assertTrue(!ref.copyOfRange(150 * SECTOR_BYTES, 300 * SECTOR_BYTES).contentEquals(out.copyOfRange(150 * SECTOR_BYTES, 300 * SECTOR_BYTES)))
    }

    @Test
    fun releaseRestoresDriveSpeedAndReleasesOutput() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 10))
        r.engine.play()
        runCurrent()
        advanceTimeBy(1_000)
        r.engine.release()
        runCurrent()
        assertTrue(r.output.released)
        assertEquals(listOf(706, 0xFFFF), r.mmc.speedRequests)
        // 只读：只发过 READ CD 与 SET CD SPEED
        val opcodes = r.mmc.received.map { it[0].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0xBE, 0xBB), opcodes)
    }

    @Test
    fun albumFromDiscInfoUsesCdText() = runTest {
        // 走一遍真实的盘片信息读取流程（DriveSession + DiscReader），确认 CD-TEXT 标题进入播放列表
        val mmc = FakeMmc().apply { powerOn() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = io.github.felixchang67.diskandroid.core.drive.DriveSession(
            FakeMmcTransport(mmc), log, "测试", backgroundScope, dispatcher, wallClock = { testScheduler.currentTime },
        )
        session.start()
        runCurrent()
        mmc.insertDisc(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 5, cdText = CdTextBuilder.japaneseAlbum()), spinUpPolls = 1)
        advanceTimeBy(4_000)
        runCurrent()
        val disc = session.disc.value!!
        val album = CdAlbum.from(disc)!!
        assertEquals("テスト・アルバム", album.title)
        assertEquals("山田太郎", album.performer)
        assertEquals(listOf("春の歌", "夏の歌～ライブ", "秋の歌①"), album.tracks.map { it.title })
        assertEquals("山田太郎＆花子", album.tracks[2].performer)
        assertTrue(disc.summary().contains("テスト・アルバム"), disc.summary())
        // 会话的 read() 也能直接读音频
        val buf = ByteArray(2 * SECTOR_BYTES)
        val result = session.read(MmcCommands.readCdda(0, 2, buf, 0, dap = false))
        assertTrue(result is io.github.felixchang67.diskandroid.core.scsi.ScsiResult.Good, result.toString())
        assertContentEquals(mmc.expectedAudio(0, 2), buf)
    }

    @Test
    fun autoplayFadesInOverOneSecond() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 3))
        r.engine.play(trackNumber = 1, fadeInMs = CdPlayerEngine.AUTOPLAY_FADE_MS)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        val out = r.output.all.toByteArray()
        val ref = r.mmc.expectedAudio(0, 225)
        fun sample(b: ByteArray, frame: Int) = (b[frame * 4 + 1].toInt() shl 8) or (b[frame * 4].toInt() and 0xFF)
        // 第一帧增益为 0，半秒处约一半，一秒以后与原始数据完全相同
        assertEquals(0, sample(out, 0))
        val half = 22_050
        val expectedHalf = sample(ref, half) * 0.5
        assertTrue(kotlin.math.abs(sample(out, half) - expectedHalf) <= 2, "${sample(out, half)} vs $expectedHalf")
        assertContentEquals(ref.copyOfRange(44_100 * 4, ref.size), out.copyOfRange(44_100 * 4, out.size))
    }
}

