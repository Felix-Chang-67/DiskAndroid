package io.github.felixchang67.diskandroid.core.cdda

import io.github.felixchang67.diskandroid.core.drive.DiscInfo

/** 一首可播放的音轨。[startLba] 是 TOC 里的起点（INDEX 01），[sectors] 到下一轨起点（或区段 lead-out）为止。 */
class CdTrack(
    val number: Int,
    val startLba: Long,
    val sectors: Long,
    /** TOC 控制位：录音时做了预加重（早期日本 CD 常见），播放时要做去加重。 */
    val preEmphasis: Boolean,
    val title: String,
    val performer: String?,
) {
    val endLba: Long get() = startLba + sectors
    val durationMs: Long get() = sectors * 1000 / SECTORS_PER_SECOND

    fun contains(lba: Long) = lba in startLba until endLba

    override fun toString() = "Track $number（LBA $startLba，$sectors 扇区）"
}

/**
 * 一张盘上可以播放的音轨（数据轨跳过；CD-Extra 第 1 区段最后一首按区段 lead-out 截止，见 [DiscInfo.sectorsOf]）。
 * 标题来自 CD-TEXT（优先日文块），没有 CD-TEXT 时为 "Track N"。
 */
class CdAlbum(
    val tracks: List<CdTrack>,
    val title: String?,
    val performer: String?,
) {
    init {
        require(tracks.isNotEmpty())
    }

    val first: CdTrack get() = tracks.first()

    fun track(number: Int): CdTrack? = tracks.firstOrNull { it.number == number }

    fun trackAt(lba: Long): CdTrack? = tracks.firstOrNull { it.contains(lba) }

    fun next(t: CdTrack): CdTrack? = tracks.getOrNull(tracks.indexOf(t) + 1)

    fun previous(t: CdTrack): CdTrack? = tracks.getOrNull(tracks.indexOf(t) - 1)

    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    companion object {
        fun from(disc: DiscInfo): CdAlbum? {
            if (!disc.hasAudio) return null
            val tracks = disc.audioTracks.mapNotNull { e ->
                val sectors = disc.sectorsOf(e) ?: return@mapNotNull null
                if (sectors <= 0) return@mapNotNull null
                CdTrack(e.track, e.lba, sectors, e.preEmphasis, disc.trackTitle(e.track), disc.cdText?.performer(e.track))
            }
            if (tracks.isEmpty()) return null
            return CdAlbum(tracks, disc.albumTitle, disc.albumPerformer)
        }
    }
}

const val SECTORS_PER_SECOND = 75L
const val FRAMES_PER_SECTOR = 588
const val BYTES_PER_FRAME = 4
const val SECTOR_BYTES = FRAMES_PER_SECTOR * BYTES_PER_FRAME

fun formatMs(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
