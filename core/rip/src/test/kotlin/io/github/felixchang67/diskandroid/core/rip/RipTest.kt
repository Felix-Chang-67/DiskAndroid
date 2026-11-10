package io.github.felixchang67.diskandroid.core.rip

import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import io.github.felixchang67.diskandroid.core.testing.FakeTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内存里的输出，可以在指定位置之后模拟写入失败（例如 FAT32 的 4 GB 上限）。 */
private class MemoryOutput(private val failAt: Long = Long.MAX_VALUE) : RipOutput {
    var data = ByteArray(0)
    var closed = false

    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        if (position + length > failAt) throw IOException("File too large")
        val end = (position + length).toInt()
        if (end > this.data.size) this.data = this.data.copyOf(end)
        System.arraycopy(data, offset, this.data, position.toInt(), length)
    }

    override fun read(position: Long, data: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length.toLong(), this.data.size - position).toInt()
        if (n <= 0) return -1
        System.arraycopy(this.data, position.toInt(), data, offset, n)
        return n
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RipTest {
    private val log = DiskLog().apply { addSink(RingBufferSink(5000)) }

    /** 走一遍真实的盘片信息读取（DriveSession + DiscReader），拿到 DiscInfo。 */
    private fun TestScope.readDisc(mmc: FakeMmc): Pair<DriveSession, DiscInfo> {
        val session = DriveSession(FakeMmcTransport(mmc), log, "测试", backgroundScope, StandardTestDispatcher(testScheduler), wallClock = { testScheduler.currentTime })
        session.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        return session to requireNotNull(session.disc.value) { "盘片信息没有读出来" }
    }

    private fun mmcWith(disc: FakeDisc) = FakeMmc().apply {
        insertDisc(disc, spinUpPolls = 0)
        pendingUnitAttention.clear()
        pendingMediaEvents.clear()
    }

    private fun expected(mmc: FakeMmc, disc: FakeDisc, total: Long, zero: (Long) -> Boolean = { false }): ByteArray {
        val out = ByteArrayOutputStream()
        for (lba in 0 until total) out.write(if (zero(lba)) ByteArray(2048) else mmc.dataSector(disc, lba))
        return out.toByteArray()
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun cleanCdRomRipsToExactImageWithHashes() = runTest {
        val disc = FakeDisc.cdRom(sectors = 1_000, volumeId = "TEST_DISC")
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        assertEquals(1_000, plan.totalSectors)
        assertEquals(1, plan.areas.size)
        val out = MemoryOutput()
        val engine = RipEngine(session::read, out, log, RipOptions(blockSectors = 32), clock = { testScheduler.currentTime }, sleep = {})
        assertEquals("TEST_DISC", engine.probeVolume()?.volumeId)
        val progress = mutableListOf<RipProgress>()
        val r = engine.run(plan, { false }) { progress += it }
        assertEquals(RipResult.Status.COMPLETED, r.status)
        val image = expected(mmc, disc, 1_000)
        assertContentEquals(image, out.data)
        assertEquals(sha1(image), r.sha1)
        assertEquals("TEST_DISC", r.volume?.volumeId)
        assertEquals(1_000L, r.volume?.volumeSpaceSize)
        assertTrue(r.mapfile.contains("0x00000000  0x001F4000  +"), r.mapfile)
        assertTrue(progress.isNotEmpty())
        // 只读：只发过读类命令
        assertTrue(mmc.received.all { (it[0].toInt() and 0xFF) in setOf(0x00, 0x03, 0x12, 0x25, 0x28, 0x43, 0x46, 0x4A, 0x51, 0x52, 0x5A, 0xA4, 0xAC, 0xAD, 0xBD) })
    }

    @Test
    fun secondPassRecoversFlakySectorsAndZeroFillsBadOnes() = runTest {
        val disc = FakeDisc.cdRom(sectors = 400, badSectors = setOf(100L, 101L), flakySectors = mapOf(150L to 2))
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        val out = MemoryOutput()
        val r = RipEngine(session::read, out, log, RipOptions(blockSectors = 32, retries = 3), clock = { testScheduler.currentTime }, sleep = {})
            .run(plan, { false }) {}
        assertEquals(RipResult.Status.COMPLETED_WITH_ERRORS, r.status)
        assertEquals(listOf(100L, 101L), r.badSectors)
        // 块 96–127 中除坏扇区外的 30 个、块 128–159 全部 32 个都在第 2 遍补读回来
        assertEquals(62, r.recoveredSectors)
        val image = expected(mmc, disc, 400) { it == 100L || it == 101L }
        assertContentEquals(image, out.data)
        assertEquals(sha1(image), r.sha1, "补读后必须按最终内容重算校验值")
        assertTrue(r.mapfile.contains("0x00032000  0x00001000  -"), r.mapfile)
    }

    @Test
    fun cdRunOutSectorsAreGapsNotErrors() = runTest {
        val disc = FakeDisc.cdRom(sectors = 300, badSectors = setOf(298L, 299L))
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        val r = RipEngine(session::read, MemoryOutput(), log, RipOptions(blockSectors = 16), clock = { testScheduler.currentTime }, sleep = {})
            .run(plan, { false }) {}
        assertEquals(RipResult.Status.COMPLETED, r.status)
        assertEquals(listOf(298L, 299L), r.runOutSectors)
        assertTrue(r.badSectors.isEmpty())
    }

    @Test
    fun enhancedCdZeroFillsAudioAndSessionGap() = runTest {
        val disc = FakeDisc(
            0x0008,
            listOf(
                FakeTrack(1, 1, 0, 1_500, audio = true),
                FakeTrack(2, 2, 1_500 + 11_400, 600, audio = false),
            ),
            leadOutLba = 1_500 + 11_400 + 600,
        )
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        assertEquals(listOf(RipArea.Kind.GAP, RipArea.Kind.GAP, RipArea.Kind.DATA), plan.areas.map { it.kind })
        assertEquals(600, plan.dataSectors)
        assertTrue(plan.warnings.any { it.contains("音轨") })
        val out = MemoryOutput()
        val r = RipEngine(session::read, out, log, RipOptions(blockSectors = 64), clock = { testScheduler.currentTime }, sleep = {}).run(plan, { false }) {}
        assertEquals(RipResult.Status.COMPLETED, r.status)
        assertContentEquals(expected(mmc, disc, plan.totalSectors) { it < 12_900 }, out.data)
        assertEquals(600L, r.readSectors)
    }

    @Test
    fun cancelAndRemovalStopWithIncompleteMapfile() = runTest {
        val disc = FakeDisc.cdRom(sectors = 2_000)
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        var calls = 0
        val cancelled = RipEngine(session::read, MemoryOutput(), log, RipOptions(blockSectors = 32), clock = { testScheduler.currentTime }, sleep = {})
            .run(plan, { ++calls > 10 }) {}
        assertEquals(RipResult.Status.CANCELLED, cancelled.status)
        assertTrue(cancelled.mapfile.lines().any { it == "0x000A0000  0x00348000  ?" }, cancelled.mapfile) // 取消前读了 10 块 × 32 扇区
        assertNull(cancelled.sha1)

        var reads = 0
        val removed = RipEngine(
            { cmd -> if (++reads == 5) mmc.openTray(); session.read(cmd) }, MemoryOutput(), log, RipOptions(blockSectors = 32),
            clock = { testScheduler.currentTime }, sleep = {},
        ).run(plan, { false }) {}
        assertEquals(RipResult.Status.MEDIUM_REMOVED, removed.status)
    }

    @Test
    fun refusesAudioCdAndCssDvdButStopsOnUnexpectedProtection() = runTest {
        val audio = readDisc(mmcWith(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 5))).second
        assertTrue(assertIs<RipPlan.Build.Refused>(RipPlan.from(audio)).reason.contains("音乐 CD"))

        val cssMmc = mmcWith(FakeDisc.dvdVideo(sectors = 3_000, css = true))
        val (session, css) = readDisc(cssMmc)
        assertTrue(assertIs<RipPlan.Build.Refused>(RipPlan.from(css)).reason.contains("CSS"))
        // 万一盘片结构里没标 CSS、读到加密扇区（05/6F/03）时马上停，不去逐扇区重试几百万次
        val plan = RipPlan(css.family, "DVD-ROM", 3_000, listOf(RipArea(0, 3_000, RipArea.Kind.DATA, "整张盘")), emptyList())
        val r = RipEngine(session::read, MemoryOutput(), log, RipOptions(blockSectors = 64), clock = { testScheduler.currentTime }, sleep = {}).run(plan, { false }) {}
        assertEquals(RipResult.Status.PROTECTED, r.status)
    }

    @Test
    fun writeFailureNear4GbMentionsFat32() = runTest {
        val disc = FakeDisc.cdRom(sectors = 500)
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan
        val r = RipEngine(session::read, MemoryOutput(failAt = 300L * 2048), log, RipOptions(blockSectors = 32), clock = { testScheduler.currentTime }, sleep = {})
            .run(plan, { false }) {}
        assertEquals(RipResult.Status.OUTPUT_ERROR, r.status)
        assertTrue(r.message!!.contains("File too large"))
    }

    /** DVD+RW 上的小 ISO：PVD 卷标是 UTF-8 日文，卷大小 300 扇区；整张盘格式化成 2000 扇区。 */
    private fun rewritableWithSmallIso(): Pair<FakeDisc, ByteArray> {
        val img = ByteArray(300 * 2048)
        for (lba in 0 until 300) for (i in 0 until 2048) img[lba * 2048 + i] = ((lba * 7 + i) and 0xFF).toByte()
        val pvd = 16 * 2048
        img.fill(0, pvd, pvd + 2048)
        img[pvd] = 1
        "CD001".toByteArray().copyInto(img, pvd + 1)
        img[pvd + 6] = 1
        img.fill(0x20, pvd + 40, pvd + 72)
        "ライブ_LIVE".toByteArray(Charsets.UTF_8).copyInto(img, pvd + 40)
        for (k in 0 until 4) {
            img[pvd + 80 + k] = (300 ushr (8 * k)).toByte()
            img[pvd + 87 - k] = (300 ushr (8 * k)).toByte()
        }
        img[pvd + 128] = 0x00
        img[pvd + 129] = 0x08
        // 根目录记录（34 字节，指向扇区 18）
        img[pvd + 156] = 34
        img[pvd + 156 + 2] = 18
        img[pvd + 156 + 10] = 0x00
        img[pvd + 156 + 11] = 0x08
        img[pvd + 156 + 25] = 2
        img[pvd + 156 + 32] = 1
        val term = 17 * 2048
        img.fill(0, term, term + 2048)
        img[term] = 0xFF.toByte()
        "CD001".toByteArray().copyInto(img, term + 1)
        img[term + 6] = 1
        img.fill(0, 18 * 2048, 19 * 2048)
        val disc = FakeDisc(0x1A, listOf(FakeTrack(1, 1, 0, 2_000, audio = false)), 2_000, erasable = true, image = img)
        return disc to img
    }

    @Test
    fun rewritableDiscIsTrimmedToTheFileSystem() = runTest {
        val (disc, img) = rewritableWithSmallIso()
        val mmc = mmcWith(disc)
        val (session, info) = readDisc(mmc)
        assertEquals(2_000L, info.capacity?.blocks)

        val src = io.github.felixchang67.diskandroid.core.fs.CachedSectorSource(
            io.github.felixchang67.diskandroid.core.fs.ScsiSectorSource(session::read, info.capacity?.blocks, sleep = {}),
        )
        val vol = io.github.felixchang67.diskandroid.core.fs.DiscFs.probe(src)
        assertEquals("ライブ_LIVE", vol.label)
        val end = io.github.felixchang67.diskandroid.core.fs.DiscFs.volumeEnd(src, vol)
        assertEquals(300L, end)

        val plan = assertIs<RipPlan.Build.Ok>(RipPlan.from(info, end)).plan
        assertEquals(300, plan.totalSectors)
        assertTrue(plan.trimmed)
        assertEquals(2_000, plan.capacitySectors)
        assertTrue(plan.warnings.any { it.contains("可重写盘") }, plan.warnings.toString())
        val out = MemoryOutput()
        val r = RipEngine(session::read, out, log, RipOptions(blockSectors = 64), clock = { testScheduler.currentTime }, sleep = {}).run(plan, { false }) {}
        assertEquals(RipResult.Status.COMPLETED, r.status)
        assertContentEquals(img, out.data)
        assertEquals(sha1(img), r.sha1)
        assertEquals("ライブ_LIVE", r.volume?.volumeId)
        val report = RipReport.text("test", "now", "drive", info, plan, RipOptions(), "x.iso", r, vol, listOf("15:30:24 光驱自行复位"))
        assertTrue(report.contains("只读到文件系统结尾"), report)
        assertTrue(report.contains("ISO 9660：卷标「ライブ_LIVE」（原始字节 E3 83 A9"), report)
        assertTrue(report.contains("光驱自行复位"), report)

        // 不是可重写盘（或者没给文件系统结尾）时照旧读整张盘
        assertEquals(2_000, assertIs<RipPlan.Build.Ok>(RipPlan.from(info)).plan.totalSectors)
    }
}
