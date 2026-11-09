package io.github.felixchang67.diskandroid.core.cdda

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.scsi.GuardedScsiTransport
import io.github.felixchang67.diskandroid.core.scsi.ReadOnlyPolicy
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CddaReaderTest {
    private val log = DiskLog().apply { addSink(RingBufferSink(2000)) }

    private fun fake(disc: FakeDisc) = FakeMmc().apply {
        insertDisc(disc, spinUpPolls = 0)
        pendingUnitAttention.clear()
        pendingMediaEvents.clear()
    }

    private fun reader(mmc: FakeMmc, clock: () -> Long = { 0L }, dap: Boolean = false): CddaReader {
        // 经过只读守卫，确认 READ CD / SET CD SPEED 都在白名单内
        val transport = GuardedScsiTransport(FakeMmcTransport(mmc), ReadOnlyPolicy, log)
        return CddaReader({ transport.execute(it) }, log, useDap = dap, clock = clock, sleep = {})
    }

    @Test
    fun readsWholeChunk() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 2))
        val r = reader(mmc)
        val buf = ByteArray(24 * SECTOR_BYTES)
        assertEquals(CddaReader.Result.Ok, r.read(10, 24, buf))
        assertContentEquals(mmc.expectedAudio(10, 24), buf)
        assertEquals(1, r.commands)
        assertTrue(r.setSpeed(706))
        assertEquals(listOf(706), mmc.speedRequests)
    }

    @Test
    fun badSectorsAreSilencedAndReported() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 2, badSectors = setOf(15L, 16L)))
        val r = reader(mmc)
        val buf = ByteArray(24 * SECTOR_BYTES)
        val result = r.read(10, 24, buf)
        assertIs<CddaReader.Result.Partial>(result)
        assertEquals(listOf(15L, 16L), result.bad)
        val expected = mmc.expectedAudio(10, 24)
        expected.fill(0, 5 * SECTOR_BYTES, 7 * SECTOR_BYTES)
        assertContentEquals(expected, buf)
        // 2 次整块 + 22 个好扇区各 1 次 + 2 个坏扇区各 3 次
        assertEquals(2L + 22 + 2 * CddaReader.SECTOR_TRIES, r.commands)
    }

    @Test
    fun timeBudgetBoundsRetriesOnBadlyDamagedDisc() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 2, badSectors = (0L until 24L).toSet()))
        var now = 0L
        val r = CddaReader(
            { cmd -> now += 1_000; FakeMmcTransport(mmc).execute(cmd) }, log, budgetMs = 6_000, clock = { now }, sleep = {},
        )
        val result = r.read(0, 24, ByteArray(24 * SECTOR_BYTES))
        assertIs<CddaReader.Result.Partial>(result)
        assertEquals(24, result.bad.size)
        assertTrue(r.commands <= 8, "命令数 ${r.commands}")
    }

    @Test
    fun removedDiscStopsReading() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 2))
        mmc.openTray()
        val result = reader(mmc).read(0, 24, ByteArray(24 * SECTOR_BYTES))
        assertIs<CddaReader.Result.Stop>(result)
        assertEquals(CddaReader.StopReason.NO_MEDIUM, result.reason)
    }

    @Test
    fun driveRejectingDapFallsBackOnce() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 2)).apply { rejectDap = true }
        val r = reader(mmc, dap = true)
        val buf = ByteArray(4 * SECTOR_BYTES)
        assertEquals(CddaReader.Result.Ok, r.read(0, 4, buf))
        assertFalse(r.useDap)
        assertEquals(CddaReader.Result.Ok, r.read(4, 4, buf))
        assertEquals(3, r.commands)
    }

    @Test
    fun goneDriveStops() = runTest {
        val r = CddaReader({ null }, log)
        val result = r.read(0, 1, ByteArray(SECTOR_BYTES))
        assertIs<CddaReader.Result.Stop>(result)
        assertEquals(CddaReader.StopReason.DRIVE_GONE, result.reason)
    }

    @Test
    fun transportErrorRereadsChunkWithoutSilencingGoodData() = runTest {
        val mmc = fake(FakeDisc.audioCd(trackCount = 1, secondsPerTrack = 20))
        val transport = FakeMmcTransport(mmc)
        var now = 0L
        var failNext = 1
        val r = CddaReader(
            { cmd ->
                if (failNext-- > 0) {
                    now += 6_014 // 真机上一次复位恢复耗时约 6 秒
                    io.github.felixchang67.diskandroid.core.scsi.ScsiResult.TransportError(
                        0, io.github.felixchang67.diskandroid.core.scsi.TransportErrorKind.IO_ERROR, "数据阶段 I/O 错误：ENOMEM",
                    )
                } else {
                    transport.execute(cmd)
                }
            },
            log, budgetMs = 6_000, clock = { now }, sleep = {},
        )
        val buf = ByteArray(24 * SECTOR_BYTES)
        assertEquals(CddaReader.Result.Ok, r.read(864, 24, buf))
        assertContentEquals(mmc.expectedAudio(864, 24), buf)
        assertEquals(2, r.commands)
    }
}
