package io.github.felixchang67.diskandroid.core.drive

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.scsi.BotTransport
import io.github.felixchang67.diskandroid.core.scsi.GuardedScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.LoggingScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.ReadOnlyPolicy
import io.github.felixchang67.diskandroid.core.testing.FakeBotDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与 App 里完全相同的传输栈：会话 → 只读守卫 → 日志 → BOT → 模拟 USB 设备。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveStackIntegrationTest {
    @Test
    fun fullStackOverBotIncludingResetRecovery() = runTest {
        val ring = RingBufferSink(10_000)
        val log = DiskLog().apply { addSink(ring) }
        val device = FakeBotDevice().apply { mmc.powerOn() }
        val bot = BotTransport(device, lun = 0, log = log, description = "fake BOT", resetSettleMs = 0)
        val transport = GuardedScsiTransport(LoggingScsiTransport(bot, log), ReadOnlyPolicy, log)
        val session = DriveSession(transport, log, "集成测试", backgroundScope, StandardTestDispatcher(testScheduler), wallClock = { testScheduler.currentTime })

        session.start()
        runCurrent()
        assertEquals(DriveState.Online, session.state.value)
        assertEquals(MediaStatus.NoMedium(TrayState.CLOSED), session.media.value)
        assertEquals("BD-RE  BU40N", session.info.value?.inquiry?.product)

        // 插入 DVD，经过 BOT 的 CHECK CONDITION + 自动 REQUEST SENSE 走到就绪
        device.mmc.insertDisc(0x0010, spinUpPolls = 2)
        advanceTimeBy(1_501 + 3 * 501)
        runCurrent()
        assertEquals(MediaStatus.Ready(0x0010), session.media.value)

        // 下一次轮询遇到 Phase Error：BOT 复位恢复，之后光驱报告的 29h 属于预期，不能报供电问题
        device.faults.addLast(FakeBotDevice.Fault.PHASE_ERROR)
        advanceTimeBy(1_501 * 3)
        runCurrent()
        assertEquals(1, bot.stats.resets)
        assertEquals(DriveState.Online, session.state.value)
        assertTrue(session.events.value.none { it.kind == DriveEvent.Kind.UNEXPECTED_RESET }, session.events.value.toString())
        assertEquals(MediaStatus.Ready(0x0010), session.media.value)

        // 没有复位恢复时出现的 29h 才是疑似供电问题
        device.mmc.powerOn()
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(session.events.value.any { it.kind == DriveEvent.Kind.UNEXPECTED_RESET })

        // 日志里每条命令都有 CDB 和结果
        val scsiLogs = ring.snapshot().filter { it.tag == LoggingScsiTransport.TAG && it.message.startsWith("#") }
        assertTrue(scsiLogs.isNotEmpty())
        assertTrue(scsiLogs.all { r -> r.fields.any { it.first == "CDB" } })
        // 只读：没有任何被拒绝的命令，也没有任何写类操作码
        assertTrue(ring.snapshot().none { it.tag == "GUARD" })
        val opcodes = device.mmc.received.map { it[0].toInt() and 0xFF }.toSet()
        assertTrue(opcodes.all { it in ReadOnlyPolicy.allowed.keys }, opcodes.toString())
    }
}
