package io.github.felixchang67.diskandroid.core.drive

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.scsi.ScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.TransportErrorKind
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DriveSessionTest {
    private val ring = RingBufferSink(5000)
    private val log = DiskLog().apply { addSink(ring) }

    private fun TestScope.session(transport: ScsiTransport): DriveSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DriveSession(transport, log, "测试光驱", backgroundScope, dispatcher, wallClock = { testScheduler.currentTime })
    }

    private fun DriveSession.eventKinds() = events.value.map { it.kind }

    @Test
    fun startsAfterPowerOnWithEmptyTray() = runTest {
        val fake = FakeMmc().apply { powerOn() }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        assertEquals(DriveState.Online, s.state.value)
        assertEquals(MediaStatus.NoMedium(TrayState.CLOSED), s.media.value)
        val info = assertNotNull(s.info.value)
        assertEquals("HL-DT-ST", info.inquiry?.vendor)
        assertEquals(17, info.features?.profiles?.size)
        assertEquals(listOf(2), info.rpc?.regions)
        assertTrue(info.capabilities?.readDvdRom == true)
        assertTrue(info.errors.isEmpty(), info.errors.toString())
        // 上电的 UNIT ATTENTION 在初始化时是预期内的，不能当成供电问题
        assertTrue(DriveEvent.Kind.UNEXPECTED_RESET !in s.eventKinds())
        // 阶段 1 只发读类命令
        val opcodes = fake.received.map { it[0].toInt() and 0xFF }.toSet()
        assertTrue(opcodes.all { it in setOf(0x00, 0x03, 0x12, 0x46, 0x4A, 0x5A, 0xA4, 0xBD) }, opcodes.toString())
    }

    @Test
    fun insertingDiscGoesThroughBecomingReadyToReady() = runTest {
        val fake = FakeMmc()
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        fake.insertDisc(0x0010, spinUpPolls = 3)
        advanceTimeBy(1_501)
        runCurrent()
        assertIs<MediaStatus.BecomingReady>(s.media.value)
        advanceTimeBy(3 * 501)
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0010), s.media.value)
        assertTrue(DriveEvent.Kind.MEDIA_INSERTED in s.eventKinds())
        assertTrue(s.events.value.any { it.message.contains("DVD-ROM") })
    }

    @Test
    fun discSwappedBetweenPollsIsNoticed() = runTest {
        val fake = FakeMmc().apply { insertDisc(0x0008, spinUpPolls = 0); pendingUnitAttention.clear(); pendingMediaEvents.clear() }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0008), s.media.value)
        // 两次轮询之间：开托盘、换成 BD、关托盘，光驱只报告 UNIT ATTENTION 28h
        fake.discProfile = 0x0040
        fake.pendingUnitAttention.addLast(0x28 to 0x00)
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0040), s.media.value)
    }

    @Test
    fun ejectOpensTrayAndEjectButtonIsReported() = runTest {
        val fake = FakeMmc().apply { insertDisc(0x0040, spinUpPolls = 0); pendingUnitAttention.clear(); pendingMediaEvents.clear() }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        val msg = s.eject()
        assertEquals("已发送弹出命令", msg)
        assertEquals(MediaStatus.NoMedium(TrayState.OPEN), s.media.value)
        assertTrue(DriveEvent.Kind.MEDIA_REMOVED in s.eventKinds())
        assertTrue(fake.received.any { (it[0].toInt() and 0xFF) == 0x1B && it[4].toInt() == 0x02 })

        // 推回托盘：盘片重新就绪
        fake.closeTray(spinUpPolls = 1)
        advanceTimeBy(1_501 + 2 * 501)
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0040), s.media.value)
        // 用户按光驱上的弹出键：GESN 报告弹出请求
        fake.pressEjectButton()
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(DriveEvent.Kind.EJECT_REQUEST in s.eventKinds())
    }

    @Test
    fun unexpectedResetMidSessionIsFlaggedAsPossiblePowerProblem() = runTest {
        val fake = FakeMmc()
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        fake.powerOn()
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(DriveEvent.Kind.UNEXPECTED_RESET in s.eventKinds())
        assertTrue(ring.snapshot().any { it.message.contains("供电") })
    }

    @Test
    fun unplugEndsSessionAsDisconnected() = runTest {
        val transport = FakeMmcTransport(FakeMmc())
        val s = session(transport)
        s.start()
        runCurrent()
        transport.disconnected = true
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(DriveState.Disconnected, s.state.value)
        assertEquals(MediaStatus.Unknown, s.media.value)
    }

    @Test
    fun driveWithoutGesnFallsBackToTurPolling() = runTest {
        val fake = FakeMmc().apply { supportsGesn = false }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        assertEquals(DriveState.Online, s.state.value)
        assertTrue(s.info.value!!.errors.containsKey("GESN"))
        fake.insertDisc(0x0009, spinUpPolls = 0)
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0009), s.media.value)
    }

    @Test
    fun repeatedTransportErrorsMarkDriveAsFailed() = runTest {
        val fake = FakeMmc()
        val inner = FakeMmcTransport(fake)
        var broken = false
        val flaky = object : ScsiTransport by inner {
            override fun execute(cmd: ScsiCommand): ScsiResult =
                if (broken) ScsiResult.TransportError(0, TransportErrorKind.TIMEOUT, "模拟超时") else inner.execute(cmd)
        }
        val s = session(flaky)
        s.start()
        runCurrent()
        broken = true
        advanceTimeBy(3 * 1_501)
        runCurrent()
        assertIs<DriveState.Failed>(s.state.value)
    }

    @Test
    fun cdExtraDiscInfoIsReadWhenReadyAndClearedOnEject() = runTest {
        val fake = FakeMmc()
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        fake.insertDisc(FakeDisc.cdExtra(), spinUpPolls = 1)
        advanceTimeBy(1_501 + 2 * 501)
        runCurrent()
        val disc = assertNotNull(s.disc.value)
        assertTrue(disc.errors.isEmpty(), disc.errors.toString())
        assertEquals(3, disc.toc?.tracks?.size)
        assertEquals(2, disc.audioTracks.size)
        assertEquals(1, disc.dataTracks.size)
        assertTrue(disc.hasAudio)
        assertEquals(3, disc.tracks.size)
        assertEquals(2, disc.discInformation?.sessions)
        // 第 2 轨是第 1 区段最后一首：结尾取第 1 区段的 lead-out（完整 TOC 的 A2h），而不是第 3 轨起点
        assertEquals(13_500L, disc.sectorsOf(disc.toc!!.tracks[1]))
        assertTrue(s.events.value.any { it.message.contains("3 轨") }, s.events.value.toString())
        // 阶段 1 只发读类命令
        val opcodes = fake.received.map { it[0].toInt() and 0xFF }.toSet()
        assertTrue(opcodes.all { it in io.github.felixchang67.diskandroid.core.scsi.ReadOnlyPolicy.allowed.keys }, opcodes.toString())

        s.eject()
        assertNull(s.disc.value)
    }

    @Test
    fun dvdDiscInfoIncludesPhysicalCopyrightAndSpeed() = runTest {
        val fake = FakeMmc().apply { insertDisc(FakeDisc.dvdVideo(regionMask = 0xFD, css = true), spinUpPolls = 0); pendingUnitAttention.clear(); pendingMediaEvents.clear() }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        val disc = assertNotNull(s.disc.value)
        assertTrue(disc.errors.isEmpty(), disc.errors.toString())
        assertEquals("DVD-ROM", disc.dvdPhysical?.bookTypeName())
        assertEquals(true, disc.dvdCopyright?.isCss)
        assertEquals(listOf(2), disc.dvdCopyright?.regions)
        assertEquals(11_080L, disc.performance?.maxKBps)
        assertEquals(2_295_104L * 2048, disc.capacity?.bytes)
        assertTrue(disc.summary().contains("CSS 加密"), disc.summary())
    }

    @Test
    fun oneTrayActionProducesOneEvent() = runTest {
        val fake = FakeMmc().apply { insertDisc(0x0008, spinUpPolls = 0); pendingUnitAttention.clear(); pendingMediaEvents.clear() }
        val s = session(FakeMmcTransport(fake))
        s.start()
        runCurrent()
        val before = s.events.value.size
        fake.openTray() // 用户按了光驱上的弹出键
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("盘片已取出（托盘已打开）"), s.events.value.drop(before).map { it.message })

        val before2 = s.events.value.size
        fake.removeDiscFromOpenTray()
        fake.closeTray()
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf("托盘已关闭"), s.events.value.drop(before2).map { it.message })
    }
}
