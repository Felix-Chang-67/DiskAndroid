package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.testing.FakeBotDevice
import io.github.felixchang67.diskandroid.core.testing.FakeBotDevice.Fault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotTransportTest {
    private val ring = RingBufferSink(1000)
    private val log = DiskLog().apply { addSink(ring) }
    private val device = FakeBotDevice()
    private val sleeps = mutableListOf<Long>()
    private val bot = BotTransport(device, lun = 0, log = log, description = "test", sleeper = { sleeps += it })

    private fun inquiry() = ScsiCommand.dataIn(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36, "INQUIRY", 5_000)
    private fun tur() = ScsiCommand.noData(ByteArray(6), "TEST UNIT READY", 5_000)

    @Test
    fun cbwLayoutFollowsBotSpec() {
        val cbw = BotTransport.buildCbw(tag = 0x01020304, dataLength = 36, dataIn = true, lun = 0, cdb = byteArrayOf(0x12, 0, 0, 0, 36, 0))
        assertEquals("55 53 42 43 04 03 02 01 24 00 00 00 80 00 06 12 00 00 00 24 00 00 00 00 00 00 00 00 00 00 00", Hex.encode(cbw))
        val out = BotTransport.buildCbw(tag = 1, dataLength = 0, dataIn = false, lun = 1, cdb = ByteArray(10))
        assertEquals(0x00, out[12].toInt())
        assertEquals(0x01, out[13].toInt())
        assertEquals(10, out[14].toInt())
    }

    @Test
    fun inquiryGoodPath() {
        val cmd = inquiry()
        val r = bot.execute(cmd)
        assertIs<ScsiResult.Good>(r)
        assertEquals(36, r.transferred)
        assertEquals(0x05, cmd.buffer!![0].toInt())
        assertEquals("HL-DT-ST", String(cmd.buffer, 8, 8, Charsets.US_ASCII))
        // 每条命令的 tag 递增
        bot.execute(inquiry())
        assertEquals(listOf(1, 2), device.cbws.map { BotTransport.le32(it, 4) })
    }

    @Test
    fun checkConditionTriggersAutomaticRequestSense() {
        val r = bot.execute(tur())
        assertIs<ScsiResult.CheckCondition>(r)
        val sense = assertNotNull(r.sense)
        assertEquals("2/3A/01", sense.code())
        assertTrue(sense.describe().contains("TRAY CLOSED"))
        // 第二个 CBW 是自动 REQUEST SENSE
        assertEquals(0x03, device.cbws[1][15].toInt())
        assertEquals(18, BotTransport.le32(device.cbws[1], 8))
        assertEquals(1, bot.stats.checkConditions)
    }

    @Test
    fun skippedDataPhaseIsTreatedAsCsw() {
        device.faults.addLast(Fault.SKIP_DATA_PHASE)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.Good>(r)
        assertEquals(0, r.transferred)
        assertEquals(1, bot.stats.skippedDataPhase)
        assertEquals(0, device.resetCalls)
    }

    @Test
    fun dataInStallClearsHaltThenReadsCsw() {
        device.faults.addLast(Fault.STALL_DATA_IN)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.CheckCondition>(r)
        assertEquals(listOf(0x81), device.clearHaltCalls)
        assertEquals(0, device.resetCalls)
        assertEquals(1, bot.stats.stalls)
        // 之后的命令照常工作
        assertIs<ScsiResult.Good>(bot.execute(inquiry()))
    }

    @Test
    fun zeroLengthCswIsRetried() {
        device.faults.addLast(Fault.ZERO_LENGTH_CSW)
        assertIs<ScsiResult.Good>(bot.execute(inquiry()))
        assertEquals(1, bot.stats.zeroLengthCsw)
    }

    @Test
    fun cswStallIsClearedAndRetriedOnce() {
        device.faults.addLast(Fault.STALL_CSW)
        assertIs<ScsiResult.Good>(bot.execute(inquiry()))
        assertEquals(listOf(0x81), device.clearHaltCalls)
        assertEquals(0, device.resetCalls)
    }

    @Test
    fun phaseErrorTriggersResetRecovery() {
        device.faults.addLast(Fault.PHASE_ERROR)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.TransportError>(r)
        assertEquals(TransportErrorKind.PHASE_ERROR, r.kind)
        assertEquals(1, device.resetCalls)
        assertEquals(listOf(0x81, 0x02), device.clearHaltCalls)
        assertEquals(listOf(6_000L), sleeps) // 与 Linux 一样，类复位后等 6 秒
        assertIs<ScsiResult.Good>(bot.execute(inquiry()))
    }

    @Test
    fun wrongTagTriggersResetRecovery() {
        device.faults.addLast(Fault.WRONG_TAG)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.TransportError>(r)
        assertEquals(TransportErrorKind.PROTOCOL_ERROR, r.kind)
        assertEquals(1, device.resetCalls)
    }

    @Test
    fun dataTimeoutIsClassifiedByElapsedTimeAndResets() {
        device.faults.addLast(Fault.TIMEOUT_DATA_IN)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.TransportError>(r)
        assertEquals(TransportErrorKind.TIMEOUT, r.kind)
        assertEquals(1, device.resetCalls)
        assertEquals(1, bot.stats.timeouts)
    }

    @Test
    fun unplugDuringCommandIsReportedAsDeviceGoneWithoutReset() {
        device.faults.addLast(Fault.DISCONNECT_AFTER_CBW)
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.TransportError>(r)
        assertEquals(TransportErrorKind.DEVICE_GONE, r.kind)
        assertEquals(0, device.resetCalls)
        assertTrue(device.clearHaltCalls.isEmpty())
    }

    @Test
    fun closedTransportRefusesWork() {
        bot.close()
        val r = bot.execute(inquiry())
        assertIs<ScsiResult.TransportError>(r)
        assertEquals(TransportErrorKind.CLOSED, r.kind)
        assertTrue(device.cbws.isEmpty())
    }
}
