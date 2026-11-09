package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MsfTest {
    @Test
    fun conversions() {
        assertEquals(0L, Msf.toLba(0, 2, 0))
        assertEquals(-150L, Msf.toLba(0, 0, 0))
        assertEquals("00:02:00", Msf.format(0))
        assertEquals("00:00:00", Msf.format(-150))
        assertEquals("79:59:74", Msf.format(Msf.toLba(79, 59, 74)))
        assertEquals("4:23", Msf.duration(75L * (4 * 60 + 23)))
    }
}

class DiscCommandsTest {
    @Test
    fun cdbLayouts() {
        assertEquals("25 00 00 00 00 00 00 00 00 00", Hex.encode(MmcCommands.readCapacity().cdb))
        assertEquals("51 00 00 00 00 00 00 00 22 00", Hex.encode(MmcCommands.readDiscInformation(34).cdb))
        assertEquals("52 01 00 00 00 05 00 00 30 00", Hex.encode(MmcCommands.readTrackInformation(1, 5, 48).cdb))
        // 与 Linux sr_read_tocentry / libburn MMC_GET_TOC 一致
        assertEquals("43 00 00 00 00 00 00 00 04 00", Hex.encode(MmcCommands.readToc(0, msf = false, trackOrSession = 0, allocation = 4).cdb))
        assertEquals("43 02 02 00 00 00 00 10 00 00", Hex.encode(MmcCommands.readToc(2, msf = true, trackOrSession = 0, allocation = 4096).cdb))
        // 与 Linux dvd_read_physical / dvd_read_copyright 一致：字节 6 层、7 格式、8–9 分配长度
        assertEquals("AD 00 00 00 00 00 00 01 00 08 00 00", Hex.encode(MmcCommands.readDiscStructure(0, 0x01, 8).cdb))
        assertEquals("AD 01 00 00 00 00 00 00 00 04 00 00", Hex.encode(MmcCommands.readDiscStructure(1, 0x00, 4).cdb))
        // 与 libburn mmc_get_performance_al 一致：字节 1 = 10h，字节 8–9 = 描述符数，字节 10 = 类型
        assertEquals("AC 10 00 00 00 00 00 00 00 01 00 00", Hex.encode(MmcCommands.getReadPerformance(1).cdb))
    }
}

class DiscParsersTest {
    private fun run(fake: FakeMmc, cmd: ScsiCommand): ByteArray {
        val r = FakeMmcTransport(fake).execute(cmd)
        assertIs<ScsiResult.Good>(r, r.toString())
        return cmd.buffer!!.copyOf(r.transferred)
    }

    private fun ready(disc: FakeDisc) = FakeMmc().apply {
        insertDisc(disc, spinUpPolls = 0)
        pendingUnitAttention.clear()
        pendingMediaEvents.clear()
    }

    @Test
    fun audioCdTocAndTracks() {
        val fake = ready(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 200))
        val cap = assertNotNull(ReadCapacity.parse(run(fake, MmcCommands.readCapacity())))
        assertEquals(3 * 200 * 75L - 1, cap.lastLba)
        assertEquals(2352L, cap.blockLength)

        val toc = assertNotNull(Toc.parse(run(fake, MmcCommands.readToc(0, false, 0, 804))))
        assertEquals(1, toc.firstTrack)
        assertEquals(3, toc.lastTrack)
        assertEquals(listOf(1, 2, 3), toc.tracks.map { it.track })
        assertTrue(toc.tracks.none { it.isData })
        assertEquals(15_000L, toc.tracks[1].lba)
        assertEquals(45_000L, toc.leadOut?.lba)
        assertEquals(15_000L, toc.sectorsOf(toc.tracks[0]))

        // MSF 寻址
        val tocMsf = assertNotNull(Toc.parse(run(fake, MmcCommands.readToc(0, true, 0, 804)), msf = true))
        assertEquals(toc.tracks.map { it.lba }, tocMsf.tracks.map { it.lba })

        val di = assertNotNull(DiscInformation.parse(run(fake, MmcCommands.readDiscInformation(34))))
        assertEquals(2, di.discStatus)
        assertEquals(3, di.lastSessionState)
        assertEquals(1, di.sessions)
        assertEquals("已封盘", di.discStatusName())

        val ti = assertNotNull(TrackInformation.parse(run(fake, MmcCommands.readTrackInformation(1, 2, 48))))
        assertEquals(2, ti.track)
        assertEquals(1, ti.session)
        assertEquals(15_000L, ti.start)
        assertEquals(15_000L, ti.size)
        assertTrue(!ti.isData)
    }

    @Test
    fun cdExtraFullTocGivesSessionOneLeadOut() {
        val fake = ready(FakeDisc.cdExtra())
        val full = assertNotNull(FullToc.parse(run(fake, MmcCommands.readToc(2, true, 0, 4096))))
        assertEquals(listOf(1, 2), full.sessions)
        assertEquals(27_000L, full.leadOutLba(1))
        assertEquals(1, full.firstTrack(1))
        assertEquals(2, full.lastTrack(1))
        assertEquals(3, full.firstTrack(2))
        val session = assertNotNull(SessionInfo.parse(run(fake, MmcCommands.readToc(1, false, 0, 12))))
        assertEquals(2, session.lastSession)
        assertEquals(3, session.firstTrackInLastSession)
        assertEquals(38_400L, session.lastSessionStartLba)
    }

    @Test
    fun dvdPhysicalAndCopyright() {
        val fake = ready(FakeDisc.dvdVideo(regionMask = 0xDF, css = true))
        val phys = assertNotNull(DvdPhysical.parse(run(fake, MmcCommands.readDiscStructure(0, 0x00, 24))))
        assertEquals("DVD-ROM", phys.bookTypeName())
        assertEquals(1, phys.layers)
        assertEquals("120 mm", phys.discSizeName())
        assertEquals("10.08 Mbit/s", phys.maxRateName())
        assertEquals(0x030000L, phys.startSector)
        val cr = assertNotNull(DvdCopyright.parse(run(fake, MmcCommands.readDiscStructure(0, 0x01, 8))))
        assertTrue(cr.isCss)
        assertEquals(listOf(6), cr.regions) // 掩码 DFh：只有地区 6 可播放
        assertEquals("全区（区域码 0）", DvdCopyright(0, 0x00).regionText())
    }

    @Test
    fun bdDiscInformationAndPerformance() {
        val fake = ready(FakeDisc.bdRom())
        val di = assertNotNull(BdDiscInformation.parse(run(fake, MmcCommands.readDiscStructure(1, 0x00, 68))))
        assertEquals("BDO", di.discTypeId)
        assertEquals("BD-ROM（压制盘）", di.discTypeName())
        val perf = assertNotNull(ReadPerformance.parse(run(fake, MmcCommands.getReadPerformance(1))))
        assertEquals(1, perf.descriptors.size)
        assertEquals(26_970L, perf.maxKBps)
        assertEquals("6.0x", ReadPerformance.speedFactor(perf.maxKBps, MmcNames.DiscFamily.BD))
    }

    @Test
    fun cdHasNoDvdStructure() {
        val fake = ready(FakeDisc.cdRom())
        val cmd = MmcCommands.readDiscStructure(0, 0x00, 24)
        val r = FakeMmcTransport(fake).execute(cmd)
        assertIs<ScsiResult.CheckCondition>(r)
        assertEquals("5/30/02", r.sense?.code())
    }

    @Test
    fun truncatedResponsesDoNotCrash() {
        assertNull(ReadCapacity.parse(ByteArray(4)))
        assertNull(DiscInformation.parse(ByteArray(8)))
        assertNull(TrackInformation.parse(ByteArray(20)))
        assertNull(DvdPhysical.parse(ByteArray(10)))
        assertNotNull(Toc.parse(Hex.decode("00 02 01 01")))
        assertTrue(Toc.parse(Hex.decode("00 02 01 01"))!!.entries.isEmpty())
    }
}
