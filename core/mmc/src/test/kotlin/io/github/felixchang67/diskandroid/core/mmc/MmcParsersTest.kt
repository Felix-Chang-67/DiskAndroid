package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MmcCommandsTest {
    @Test
    fun cdbLayouts() {
        assertEquals("12 00 00 00 24 00", Hex.encode(MmcCommands.inquiry().cdb))
        assertEquals("00 00 00 00 00 00", Hex.encode(MmcCommands.testUnitReady().cdb))
        assertEquals("46 02 00 00 00 00 00 00 08 00", Hex.encode(MmcCommands.getConfiguration(2, 0x0000, 8).cdb))
        assertEquals("46 00 01 0D 00 00 00 10 00 00", Hex.encode(MmcCommands.getConfiguration(0, 0x010D, 4096).cdb))
        assertEquals("5A 00 2A 00 00 00 00 01 00 00", Hex.encode(MmcCommands.modeSense10(0x2A).cdb))
        // GESN：Polled=1，媒体类，分配 8（与 Linux cdrom_get_media_event 相同）
        assertEquals("4A 01 00 00 10 00 00 00 08 00", Hex.encode(MmcCommands.getEventStatus().cdb))
        assertEquals("BD 00 00 00 00 00 00 00 00 08 00 00", Hex.encode(MmcCommands.mechanismStatus().cdb))
        // REPORT KEY：KEY CLASS 00h，分配 8，KEY FORMAT 08h（与 libdvdcss ioctl_ReportRPC 相同）
        assertEquals("A4 00 00 00 00 00 00 00 00 08 08 00", Hex.encode(MmcCommands.reportRpcState().cdb))
        // 弹出 / 装载（与 Linux sr_tray_move 相同）
        assertEquals("1B 00 00 00 02 00", Hex.encode(MmcCommands.startStopUnit(loadEject = true, start = false).cdb))
        assertEquals("1B 00 00 00 03 00", Hex.encode(MmcCommands.startStopUnit(loadEject = true, start = true).cdb))
    }
}

class MmcParsersTest {
    private fun run(fake: FakeMmc, cmd: io.github.felixchang67.diskandroid.core.scsi.ScsiCommand): ByteArray {
        val r = FakeMmcTransport(fake).execute(cmd)
        assertIs<ScsiResult.Good>(r)
        return cmd.buffer!!.copyOf(r.transferred)
    }

    @Test
    fun inquiry() {
        val data = run(FakeMmc(), MmcCommands.inquiry())
        val inq = assertNotNull(InquiryData.parse(data))
        assertTrue(inq.isMmcDevice)
        assertTrue(inq.removable)
        assertEquals("HL-DT-ST", inq.vendor)
        assertEquals("BD-RE  BU40N", inq.product)
        assertEquals("1.03", inq.revision)
        assertNull(InquiryData.parse(ByteArray(3)))
    }

    @Test
    fun featureListWithDiscInserted() {
        val fake = FakeMmc().apply { insertDisc(0x0040, spinUpPolls = 0); pendingUnitAttention.clear() }
        val list = assertNotNull(FeatureList.parse(run(fake, MmcCommands.getConfiguration(0, 0, 4096))))
        assertEquals(0x0040, list.currentProfile)
        assertFalse(list.truncated)
        assertEquals(17, list.profiles.size)
        assertTrue(list.profiles.contains(0x0040 to true))
        assertTrue(list.profiles.contains(0x0010 to false))
        assertEquals(7L, list.physicalInterface)
        assertEquals(1, list.loadingMechanism)
        assertEquals(true, list.canEject)
        assertEquals(true, list.cdReadCdText)
        assertEquals(true, list.cdReadC2)
        assertEquals(1, list.cssVersion)
        val aacs = assertNotNull(list.aacs)
        assertTrue(aacs.current)
        assertEquals(4, aacs.agidCount)
        assertEquals("BD-ROM", MmcNames.profileName(list.currentProfile))
        assertEquals(MmcNames.DiscFamily.BD, MmcNames.family(list.currentProfile))
    }

    @Test
    fun featureListTruncatedByAllocation() {
        val fake = FakeMmc().apply { pendingUnitAttention.clear() }
        val list = assertNotNull(FeatureList.parse(run(fake, MmcCommands.getConfiguration(0, 0, 16))))
        assertTrue(list.truncated)
        assertEquals(0, list.currentProfile)
        assertTrue(list.dataLength > 16)
    }

    @Test
    fun capabilitiesPage() {
        val page = assertNotNull(CapabilitiesPage.parse(run(FakeMmc(), MmcCommands.modeSense10(0x2A))))
        assertTrue(page.readDvdRom)
        assertTrue(page.writeCdR)
        assertTrue(page.cdDaCommands)
        assertTrue(page.cdDaAccurate)
        assertTrue(page.c2Pointers)
        assertEquals(1, page.loadingMechanism)
        assertTrue(page.eject)
        assertEquals(4234, page.maxReadSpeedKBps)
        assertEquals(1024, page.bufferSizeKB)
    }

    @Test
    fun eventStatusReportsNewMediaAndTray() {
        val fake = FakeMmc().apply { pendingUnitAttention.clear(); insertDisc(0x0010); pendingUnitAttention.clear() }
        val ev = assertNotNull(EventStatus.parse(run(fake, MmcCommands.getEventStatus())))
        assertFalse(ev.noEventAvailable)
        assertEquals(4, ev.notificationClass)
        assertTrue(ev.supportsMediaClass)
        assertEquals(2, ev.mediaEventCode)
        assertEquals(true, ev.mediaPresent)
        assertEquals(false, ev.trayOpen)

        fake.openTray()
        val ev2 = assertNotNull(EventStatus.parse(run(fake, MmcCommands.getEventStatus())))
        assertEquals(3, ev2.mediaEventCode)
        assertEquals(false, ev2.mediaPresent)
        assertEquals(true, ev2.trayOpen)
    }

    @Test
    fun eventStatusWithNoEventAvailable() {
        val ev = assertNotNull(EventStatus.parse(Hex.decode("00 02 80 5E")))
        assertTrue(ev.noEventAvailable)
        assertNull(ev.mediaEventCode)
        assertEquals("操作变化、电源管理、外部请求、媒体、设备忙", MmcNames.eventClasses(ev.supportedClasses))
    }

    @Test
    fun mechanismStatus() {
        val fake = FakeMmc().apply { pendingUnitAttention.clear(); openTray() }
        val m = assertNotNull(MechanismStatus.parse(run(fake, MmcCommands.mechanismStatus())))
        assertTrue(m.doorOpen)
        assertFalse(m.fault)
        assertEquals("空闲", m.mechanismStateName())
    }

    @Test
    fun rpcState() {
        val rpc = assertNotNull(RpcState.parse(run(FakeMmc().apply { pendingUnitAttention.clear() }, MmcCommands.reportRpcState())))
        assertEquals(1, rpc.typeCode)
        assertEquals(5, rpc.userChangesAvailable)
        assertEquals(0, rpc.vendorResetsAvailable)
        assertEquals(listOf(2), rpc.regions)
        assertEquals(1, rpc.rpcScheme)
        assertTrue(rpc.summary().contains("地区 2"))
        // 未设置地区的 RPC-II 光驱：掩码 FFh，没有可播放地区
        val unset = assertNotNull(RpcState.parse(Hex.decode("00 06 00 00 05 FF 01 00")))
        assertEquals(0, unset.typeCode)
        assertTrue(unset.regions.isEmpty())
    }
}
