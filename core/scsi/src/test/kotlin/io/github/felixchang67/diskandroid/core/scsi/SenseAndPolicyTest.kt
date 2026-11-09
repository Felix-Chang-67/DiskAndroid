package io.github.felixchang67.diskandroid.core.scsi

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenseDataTest {
    @Test
    fun parsesFixedFormatWithInformationAndProgress() {
        // 70h + VALID，NOT READY 04/01，INFORMATION=0x00012345，SKSV 进度 0x8000（50%）
        val raw = Hex.decode("F0 00 02 00 01 23 45 0A 00 00 00 00 04 01 00 80 80 00")
        val s = assertNotNull(SenseData.parse(raw))
        assertEquals(SenseKey.NOT_READY, s.senseKey)
        assertEquals("2/04/01", s.code())
        assertEquals(0x12345L, s.information)
        assertEquals(0.5, s.progress)
        assertTrue(s.describe().contains("BECOMING READY"))
        assertTrue(s.describe().contains("正在就绪"))
    }

    @Test
    fun parsesDescriptorFormat() {
        // 72h，MEDIUM ERROR 11/05，信息描述符（VALID，LBA 0x1000）
        val raw = Hex.decode("72 03 11 05 00 00 00 0C 00 0A 80 00 00 00 00 00 00 00 10 00")
        val s = assertNotNull(SenseData.parse(raw))
        assertEquals("3/11/05", s.code())
        assertEquals(0x1000L, s.information)
        assertTrue(s.describe().contains("L-EC UNCORRECTABLE ERROR"))
    }

    @Test
    fun copyProtectionCodesAreExplainedInChinese() {
        val s = assertNotNull(SenseData.parse(Hex.decode("70 00 05 00 00 00 00 0A 00 00 00 00 6F 04 00 00 00 00")))
        assertTrue(s.describe().contains("REGION CODE IS MISMATCHED"))
        assertTrue(s.describe().contains("地区码"))
    }

    @Test
    fun truncatedAndUnknownSenseDoNotCrash() {
        assertNull(SenseData.parse(ByteArray(0)))
        val short = assertNotNull(SenseData.parse(Hex.decode("70 00 06")))
        assertEquals(SenseKey.UNIT_ATTENTION, short.senseKey)
        assertEquals(0, short.asc)
        val weird = assertNotNull(SenseData.parse(Hex.decode("7F 00 00")))
        assertFalse(weird.isRecognizedFormat)
    }
}

class ReadOnlyPolicyTest {
    private fun cmd(vararg cdb: Int, dir: DataDirection = DataDirection.NONE): ScsiCommand {
        val bytes = ByteArray(maxOf(cdb.size, 6)) { if (it < cdb.size) cdb[it].toByte() else 0 }
        return when (dir) {
            DataDirection.NONE -> ScsiCommand.noData(bytes, "t")
            DataDirection.IN -> ScsiCommand.dataIn(bytes, 8, "t")
            DataDirection.OUT -> ScsiCommand(bytes, DataDirection.OUT, ByteArray(8), 0, 8, name = "t")
        }
    }

    @Test
    fun readCommandsAreAllowed() {
        for (op in listOf(0x00, 0x03, 0x12, 0x25, 0x28, 0x43, 0x46, 0x4A, 0x51, 0x52, 0x5A, 0xA8, 0xAD, 0xBD, 0xBE)) {
            assertNull(ReadOnlyPolicy.check(cmd(op, dir = DataDirection.IN)), "0x${Hex.byte(op)} 应放行")
        }
        assertNull(ReadOnlyPolicy.check(cmd(0x1B, 0, 0, 0, 0x02, 0))) // 弹出
    }

    @Test
    fun writeAndModifyingCommandsAreRefused() {
        // WRITE(10)/(12)、WRITE AND VERIFY、BLANK、CLOSE TRACK/SESSION、FORMAT UNIT、RESERVE TRACK、REPAIR TRACK、
        // SEND CUE SHEET、SEND OPC、SEND DISC STRUCTURE、SYNCHRONIZE CACHE、WRITE BUFFER、MODE SELECT(10)、
        // SEND EVENT、SEND KEY，以及厂商自定义命令
        val forbidden = listOf(0x2A, 0xAA, 0x2E, 0xA1, 0x5B, 0x04, 0x53, 0x58, 0x5D, 0x54, 0xBF, 0x35, 0x3B, 0x55, 0xA2, 0xA3, 0xC0, 0xFF)
        for (op in forbidden) {
            assertTrue(ReadOnlyPolicy.check(cmd(op)) != null, "0x${Hex.byte(op)} 必须被拒绝")
        }
    }

    @Test
    fun anyDataOutIsRefusedEvenForAllowedOpcodes() {
        assertTrue(ReadOnlyPolicy.check(cmd(0x5A, dir = DataDirection.OUT)) != null)
    }

    @Test
    fun startStopPowerConditionIsRefused() {
        assertTrue(ReadOnlyPolicy.check(cmd(0x1B, 0, 0, 0, 0x30, 0)) != null)
    }

    @Test
    fun reportKeyOnlyAllowsRpcState() {
        val rpc = ByteArray(12).also { it[0] = 0xA4.toByte(); it[10] = 0x08 }
        assertNull(ReadOnlyPolicy.check(ScsiCommand.dataIn(rpc, 8, "rpc")))
        val agid = ByteArray(12).also { it[0] = 0xA4.toByte(); it[10] = 0x00 }
        assertTrue(ReadOnlyPolicy.check(ScsiCommand.dataIn(agid, 8, "agid")) != null)
        val aacs = ByteArray(12).also { it[0] = 0xA4.toByte(); it[7] = 0x02; it[10] = 0x08 }
        assertTrue(ReadOnlyPolicy.check(ScsiCommand.dataIn(aacs, 8, "aacs")) != null)
    }

    @Test
    fun guardedTransportNeverForwardsRefusedCommands() {
        val fake = FakeMmcTransport()
        val log = DiskLog()
        val guarded = GuardedScsiTransport(fake, ReadOnlyPolicy, log)
        val write10 = ByteArray(10).also { it[0] = 0x2A }
        val r = guarded.execute(ScsiCommand(write10, DataDirection.OUT, ByteArray(2048), 0, 2048, name = "WRITE(10)"))
        assertIs<ScsiResult.Refused>(r)
        assertTrue(fake.mmc.received.isEmpty())
    }
}

class LoggingScsiTransportTest {
    @Test
    fun logsCdbResultSenseAndCollapsesRepeatedPolls() {
        val ring = RingBufferSink(100)
        val log = DiskLog().apply { addSink(ring) }
        val fake = FakeMmcTransport()
        val t = LoggingScsiTransport(fake, log)

        val inquiry = ScsiCommand.dataIn(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36, "INQUIRY")
        t.execute(inquiry)
        val rec = ring.snapshot().last()
        assertEquals(LogLevel.DEBUG, rec.level)
        assertTrue(rec.message.contains("INQUIRY"))
        assertTrue(rec.fields.any { it.first == "CDB" && it.second == "12 00 00 00 24 00" })
        assertEquals(36, rec.data?.size)

        // 无盘：TUR 返回 CHECK CONDITION，日志里有 sense 原文和解码
        repeat(5) { t.execute(ScsiCommand.noData(ByteArray(6), "TEST UNIT READY", quiet = true)) }
        val turLogs = ring.snapshot().filter { it.message.contains("TEST UNIT READY") }
        assertEquals(1, turLogs.size, "重复的轮询结果只记一次")
        assertTrue(turLogs[0].fields.any { it.first == "解码" && it.second.contains("3A/01") })

        // 状态变化后：先补一条"省略了 N 次"，再记新结果
        fake.mmc.insertDisc(0x10, spinUpPolls = 0)
        t.execute(ScsiCommand.noData(ByteArray(6), "TEST UNIT READY", quiet = true))
        val messages = ring.snapshot().map { it.message }
        assertTrue(messages.any { it.contains("省略了 4 次") }, messages.toString())
    }
}
