package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 回放 N1.2 真机日志里 BUFFALO BRXL-PTV6U3-SVB（0411:036D）的原始响应（2026-11-09，会话 session-20261109-235130）。
 * 这些字节直接来自用户导出的 .jsonl，用来锁定解析结果，防止以后改代码时出现回归。
 */
class RealDriveFixturesTest {
    companion object {
        const val INQUIRY = "058000325B00000042554646414C4F204F70746963616C20447269766520202020202020"
        const val GET_CONFIGURATION = "000001E8000000000000034800430000004200000041000000400000002B0000001B0000001A000000160000001500000014000000130000001200000011000000100000000A000000090000000800000002000000010B080000000701000000000207040200000000030B042B0000000004080402000000001000080000080000000100001D0000001E080403000000001F0804010001000020040C00000000000008000000010000210C0800000000000000000023080800000000000000000024040480000000002600000027000400000000002A040401000000002B000401000000002C000403000000002D08045F003F01002E04047F000D00002F08044E00000000330008000000000000000000370004000F00000038000400000000003B0004010000000040081C01000000000C000000000000000600000000000000020000000000000041041401000000000C000000000000000600000000000000800004000000000100030001010004000000000103000403000100010404040000000001050704000000000106000400000001010714041F0000000108030C4D4F414F434349313734322001090000010A000C4644430053444300544F4300010B000400000001010C031032313231303632313133333320200000010D08041F010201010F000400000202"
        const val MODE_SENSE_2A = "004A7000000000002A423F37F1772923108A01000FE0108A0010108A108A000100000000108A0000000010890000000000000000000000000000000000000000000000000000000000000000"
        const val RPC = "0006000064FD0100"
        const val GESN_IDLE = "0006045E00000000"
        const val GESN_REMOVAL_TRAY_OPEN = "0006045E03010000"
        const val CONFIG_HEADER_CD = "0000005000000008"
    }

    @Test
    fun inquiryShowsBridgeIdentityNotTheMechanism() {
        val inq = assertNotNull(InquiryData.parse(Hex.decode(INQUIRY)))
        assertTrue(inq.isMmcDevice)
        assertTrue(inq.removable)
        assertEquals("BUFFALO", inq.vendor)
        assertEquals("Optical Drive", inq.product)
        assertEquals("", inq.revision) // 桥接芯片没有给出固件版本
        assertEquals(0x5B, inq.additionalLength) // 声明共 96 字节，N1.3 会读完整 INQUIRY
    }

    @Test
    fun featureList() {
        val list = assertNotNull(FeatureList.parse(Hex.decode(GET_CONFIGURATION)))
        assertFalse(list.truncated)
        assertEquals(0x0000, list.currentProfile)
        assertEquals(42, list.features.size)
        assertEquals(
            listOf(0x43, 0x42, 0x41, 0x40, 0x2B, 0x1B, 0x1A, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, 0x10, 0x0A, 0x09, 0x08, 0x02),
            list.profiles.map { it.first },
        )
        assertEquals(7L, list.physicalInterface) // Serial ATAPI：USB 桥后面是 SATA 光驱
        assertEquals(1, list.loadingMechanism) // 托盘
        assertEquals(true, list.canEject)
        assertEquals(true, list.canLock) // 能力位，不是"当前已锁定"
        assertEquals(true, list.cdReadCdText)
        assertEquals(true, list.cdReadC2)
        assertEquals(false, list.cdReadDap)
        assertEquals(1, list.cssVersion)
        assertEquals("RBCB SCS MP2A WSPD SW", list.realTimeStreaming)
        assertEquals("MOAOCCI1742", list.driveSerialNumber)
        assertEquals("2021-06-21 13:33（原始 212106211333）", list.firmwareDate)
        val aacs = assertNotNull(list.aacs)
        assertEquals(1, aacs.version)
        assertEquals(2, aacs.agidCount)
        assertTrue(aacs.busEncryption)
        assertTrue(aacs.readDriveCertificate)
        assertTrue(aacs.bindingNonceGeneration)
        assertFalse(aacs.current) // 没有盘时 AACS 不是当前特性
    }

    @Test
    fun capabilitiesPage() {
        val c = assertNotNull(CapabilitiesPage.parse(Hex.decode(MODE_SENSE_2A)))
        assertTrue(c.readCdR && c.readCdRw && c.readDvdRom && c.readDvdR && c.readDvdRam)
        assertTrue(c.cdDaCommands && c.cdDaAccurate && c.c2Pointers && c.rwSupported)
        assertFalse(c.rwDeinterleaved) // 字节 5 = 77h：不提供去交错纠错后的 R-W 子通道（CD-TEXT 走 READ TOC 格式 5）
        assertTrue(c.mode2Form1 && c.mode2Form2)
        assertEquals(1, c.loadingMechanism)
        assertTrue(c.eject)
        assertTrue(c.lock)
        assertFalse(c.lockState)
        assertEquals(4064, c.bufferSizeKB)
        assertEquals(4234, c.maxReadSpeedKBps)
    }

    @Test
    fun rpcStateRegion2WithFourChangesLeft() {
        val rpc = assertNotNull(RpcState.parse(Hex.decode(RPC)))
        assertEquals(1, rpc.rpcScheme)
        assertEquals(1, rpc.typeCode)
        assertEquals(listOf(2), rpc.regions)
        assertEquals(4, rpc.userChangesAvailable)
        assertEquals(4, rpc.vendorResetsAvailable)
    }

    @Test
    fun eventStatus() {
        val idle = assertNotNull(EventStatus.parse(Hex.decode(GESN_IDLE)))
        assertEquals(0, idle.mediaEventCode)
        assertEquals(false, idle.mediaPresent)
        assertEquals(false, idle.trayOpen)
        assertEquals(0x5E, idle.supportedClasses)
        val removal = assertNotNull(EventStatus.parse(Hex.decode(GESN_REMOVAL_TRAY_OPEN)))
        assertEquals(3, removal.mediaEventCode)
        assertEquals(true, removal.trayOpen)
    }

    @Test
    fun currentProfileHeaderWithCd() {
        assertEquals(0x0008, FeatureList.parse(Hex.decode(CONFIG_HEADER_CD))?.currentProfile)
    }

    /**
     * N1.3 真机：READ TOC 格式 2 用奇数分配长度 147 时，桥接芯片只把前 128 字节传对，
     * 后面是缓冲区里的旧数据（CSW 仍报告 GOOD、residue 0）。校验必须把坏条目挑出来。
     */
    @Test
    fun corruptedFullTocFromN13IsDetected() {
        val data = Hex.decode(
            "00910101011000A000000000010000011000A1000000000A0000011000A200000000290E2501100001000000000002000110000200000000031E0201" +
                "1000030000000007031C01100004000000000A1A4901100005000000000F044A011000060000000013182A011000070000000016323A011000080000" +
                "00001C153B01100000000100001D0000001E080403000000001F08",
        )
        assertEquals(147, data.size)
        val toc = assertNotNull(FullToc.parse(data))
        assertEquals(2, toc.discarded)
        assertEquals(listOf(1), toc.sessions) // 不再出现假的"区段 0"
        assertEquals(185_437L, toc.leadOutLba(1))
        assertEquals(1, toc.firstTrack(1))
        assertEquals(10, toc.lastTrack(1))
        assertEquals(listOf(9, 10), toc.missingTracks(1))
    }

    /** N1.3 真机 96 字节 INQUIRY：附加区里是字节对调的 ATA 型号，修订号只在长响应里出现。 */
    @Test
    fun fullInquiryRevealsDriveModel() {
        val data = Hex.decode(
            "058000325B00000042554646414C4F204F70746963616C204472697665202020424E3134303030303430304D4F414F43434931373432202000" +
                "000000000000002D444552422034554E3020202020202020202020202020202020202020200000",
        )
        val inq = assertNotNull(InquiryData.parse(data))
        assertEquals("BUFFALO", inq.vendor)
        assertEquals("BN14", inq.revision)
        assertEquals("D-RE BU40N", inq.ataString)
    }
}
