package io.github.felixchang67.diskandroid.core.testing

/** 模拟盘片的一条轨道。 */
data class FakeTrack(val number: Int, val session: Int, val startLba: Long, val sectors: Long, val audio: Boolean, val preEmphasis: Boolean = false)

/**
 * 模拟盘片：profile、轨道、lead-out，以及 DVD/BD 的盘片结构。数值按 MMC 布局编码，给 FakeMmc 回答
 * READ CAPACITY / READ DISC INFORMATION / READ TRACK INFORMATION / READ TOC / READ DISC STRUCTURE / GET PERFORMANCE。
 */
class FakeDisc(
    val profile: Int,
    val tracks: List<FakeTrack>,
    /** 最后一个区段的 lead-out 起点（READ CAPACITY 的最后 LBA = 它减 1）。 */
    val leadOutLba: Long,
    val blockLength: Int = 2048,
    val finalized: Boolean = true,
    val erasable: Boolean = false,
    /** DVD：版权保护类型（01h = CSS）与地区掩码。 */
    val dvdProtection: Int = 0,
    val dvdRegionMask: Int = 0,
    val dvdLayers: Int = 1,
    /** BD：DI 里的盘片类型标识。 */
    val bdTypeId: String = "BDO",
    val readSpeedKBps: Int = 4234,
    /** 导入区 CD-TEXT 数据包（不含 4 字节头），null 表示没有 CD-TEXT。 */
    val cdTextPacks: ByteArray? = null,
    /** READ CD 读到这些 LBA 时返回介质错误（3/11/05）。 */
    val badSectors: Set<Long> = emptySet(),
    /** 子通道里的 MCN（JAN 条码），null 表示盘上没有。 */
    val mcn: String? = null,
) {
    val sessions: List<Int> get() = tracks.map { it.session }.distinct().sorted()

    companion object {
        /** 纯音乐 CD：每轨 [secondsPerTrack] 秒。 */
        fun audioCd(
            trackCount: Int = 3,
            secondsPerTrack: Int = 180,
            cdText: ByteArray? = null,
            badSectors: Set<Long> = emptySet(),
            preEmphasisTracks: Set<Int> = emptySet(),
            mcn: String? = null,
        ): FakeDisc {
            val tracks = (1..trackCount).map { n ->
                FakeTrack(n, 1, (n - 1) * secondsPerTrack * 75L, secondsPerTrack * 75L, audio = true, preEmphasis = n in preEmphasisTracks)
            }
            return FakeDisc(
                0x0008, tracks, trackCount * secondsPerTrack * 75L, blockLength = 2352, readSpeedKBps = 4234,
                cdTextPacks = cdText, badSectors = badSectors, mcn = mcn,
            )
        }

        /** CD-Extra：第 1 区段 2 首音轨，第 2 区段 1 条数据轨（中间隔 11400 扇区）。 */
        fun cdExtra(): FakeDisc {
            val t1 = FakeTrack(1, 1, 0, 13_500, audio = true)
            val t2 = FakeTrack(2, 1, 13_500, 13_500, audio = true)
            val dataStart = 27_000L + 11_400
            val t3 = FakeTrack(3, 2, dataStart, 30_000, audio = false)
            return FakeDisc(0x0008, listOf(t1, t2, t3), dataStart + 30_000)
        }

        fun cdRom(sectors: Long = 300_000) = FakeDisc(0x0008, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors)

        fun dvdVideo(sectors: Long = 2_295_104, regionMask: Int = 0xFD, css: Boolean = true) =
            FakeDisc(0x0010, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors, dvdProtection = if (css) 1 else 0, dvdRegionMask = regionMask, readSpeedKBps = 11_080)

        fun bdRom(sectors: Long = 12_219_392) =
            FakeDisc(0x0040, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors, readSpeedKBps = 26_970)

        /** 指定 profile 的默认盘片（保留传入的 profile，例如 0009h CD-R）。 */
        fun forProfile(profile: Int): FakeDisc {
            val base = when (profile) {
                in 0x0010..0x002B -> dvdVideo(css = false, regionMask = 0)
                in 0x0040..0x0043 -> bdRom()
                else -> cdRom()
            }
            return FakeDisc(profile, base.tracks, base.leadOutLba, base.blockLength, dvdProtection = base.dvdProtection, dvdRegionMask = base.dvdRegionMask, readSpeedKBps = base.readSpeedKBps)
        }
    }
}

/** 按真实曲目时长（毫秒）造一张音乐 CD：第 1 轨从 LBA 0 开始，轨间没有空隙。 */
fun FakeDisc.Companion.audioCdWithDurations(durationsMs: List<Long>, mcn: String? = null): FakeDisc {
    var lba = 0L
    val tracks = durationsMs.mapIndexed { i, ms ->
        val sectors = ms * 75 / 1000
        FakeTrack(i + 1, 1, lba, sectors, audio = true).also { lba += sectors }
    }
    return FakeDisc(0x0008, tracks, lba, blockLength = 2352, mcn = mcn)
}
