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
    fun seekWhilePlayingSplicesWithoutSilence() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 3))
        r.engine.play(trackNumber = 2)
        runCurrent()
        advanceTimeBy(500)
        val flushes = r.output.flushes
        r.engine.seekTo(2_000)
        runCurrent()
        // 按下就显示新位置
        assertEquals(2, r.engine.state.value.track?.number)
        assertTrue(r.engine.state.value.positionMs in 2_000..2_100, "位置 ${r.engine.state.value.positionMs}")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(Status.ENDED, r.engine.state.value.status)
        // 没有清空输出（旧的声音一直播到接缝），接缝之后就是跳转点（第 2 轨 2 秒 = LBA 225 + 150）以后的数据
        assertEquals(flushes, r.output.flushes)
        val out = r.output.all.toByteArray()
        val before = r.mmc.expectedAudio(225, 150)
        val splice = firstDifference(out, before)
        assertTrue(splice > 0, "接缝之前应是连续的旧音频")
        assertSpliced(out, splice, r.mmc.expectedAudio(375, 75))
    }

    /** 两段 PCM 第一个不同的帧（相同则返回较短者的帧数）。 */
    private fun firstDifference(a: ByteArray, b: ByteArray): Int {
        val frames = minOf(a.size, b.size) / 4
        for (f in 0 until frames) {
            for (k in 0 until 4) if (a[f * 4 + k] != b[f * 4 + k]) return f
        }
        return frames
    }

    /**
     * 接缝在 [firstDiff]（与旧音频第一个不同的帧）之前不超过 10 ms：平滑段开头新音频的比例很小，四舍五入后可能好几帧都和旧音频一样。
     * 要求：接缝之后 10 ms 是平滑段，再往后逐字节等于 [next]（新位置的数据从头开始，中间没有插入任何静音）。
     */
    private fun assertSpliced(out: ByteArray, firstDiff: Int, next: ByteArray) {
        val smooth = CdPlayerEngine.SWITCH_SPLICE_MS * 44_100 / 1000
        val splice = (firstDiff - smooth..firstDiff).firstOrNull { sp ->
            sp >= 0 && (sp + smooth) * 4 < out.size && run {
                val tail = out.copyOfRange((sp + smooth) * 4, out.size)
                val n = minOf(tail.size, next.size - smooth * 4)
                n > 0 && tail.copyOf(n).contentEquals(next.copyOfRange(smooth * 4, smooth * 4 + n)) &&
                    (tail.size <= next.size - smooth * 4) // 接缝之后不能多出别的数据
            }
        }
        assertTrue(splice != null, "接缝之后应该紧接着新位置的数据（firstDiff=$firstDiff）")
        // 接缝处不是一段静音
        val seam = out.copyOfRange(splice * 4, (splice + smooth) * 4)
        assertTrue(seam.any { it.toInt() != 0 }, "接缝不应是静音")
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

    @Test
    fun naturalTrackChangeIsSampleContinuousEvenWithPreEmphasis() = runTest {
        // 两首都带预加重：去加重滤波器在曲目交界处不清零，输出等于对整段连续处理的结果（不会"咔"一声）
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 2, preEmphasisTracks = setOf(1, 2)))
        r.engine.play()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(Status.ENDED, r.engine.state.value.status)
        val expected = r.mmc.expectedAudio(0, 300)
        Deemphasis().process(expected)
        assertContentEquals(expected, r.output.all.toByteArray())
    }

    @Test
    fun nextIsGaplessAndUsesPrefetchedHeadOfNextTrack() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 20))
        r.engine.play()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        fun readLbas() = r.mmc.received.filter { (it[0].toInt() and 0xFF) == 0xBE }
            .map { (((it[2].toInt() and 0xFF) shl 24) or ((it[3].toInt() and 0xFF) shl 16) or ((it[4].toInt() and 0xFF) shl 8) or (it[5].toInt() and 0xFF)).toLong() }
        // 缓冲满了以后，趁空闲把第 2 轨开头（LBA 1500 起 192 扇区）预读好了
        assertTrue(1500L in readLbas(), "应该预读过第 2 轨开头")
        val readsBefore = readLbas().size
        val flushes = r.output.flushes
        r.engine.next()
        runCurrent()
        assertEquals(2, r.engine.state.value.track?.number)
        advanceTimeBy(3_000)
        runCurrent()
        // 切歌后新的读取从预读数据之后接着读，不再从第 2 轨开头重读
        val after = readLbas().drop(readsBefore)
        assertTrue(after.isNotEmpty() && after.first() == 1500L + 192, "切歌后的第一次读取应从 LBA 1692 开始：$after")
        // 没有清空输出、没有插入静音：旧的第 1 轨连续播到接缝，接缝之后就是第 2 轨开头
        assertEquals(flushes, r.output.flushes)
        val out = r.output.all.toByteArray()
        val splice = firstDifference(out, r.mmc.expectedAudio(0, 1500))
        assertTrue(splice in 1 until 1500 * 588, "接缝位置 $splice")
        assertSpliced(out, splice, r.mmc.expectedAudio(1500, 1500))
        // 第 1 轨播放进度 6 秒左右按下"下一首"：接缝应该在 7 秒以内（输出缓冲 0.5 秒 + 调度）
        assertTrue(splice < 7 * 44_100, "接缝太晚：${splice / 441} ms")
    }

    @Test
    fun nextWhilePausedStartsCleanlyFromPrefetch() = runTest {
        val r = rig(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 20))
        r.engine.play()
        runCurrent()
        advanceTimeBy(6_000)
        r.engine.pause()
        runCurrent()
        r.engine.next()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(2, r.engine.state.value.track?.number)
        r.engine.resume()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        // 暂停中切歌是普通的"停下再开始"：输出清空后从第 2 轨开头播
        val out = r.output.all.toByteArray()
        assertContentEquals(r.mmc.expectedAudio(1500, 150).copyOfRange(0, minOf(out.size, 150 * SECTOR_BYTES)), out.copyOfRange(0, minOf(out.size, 150 * SECTOR_BYTES)))
    }
}

