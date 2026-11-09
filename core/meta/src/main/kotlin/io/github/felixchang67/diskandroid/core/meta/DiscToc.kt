package io.github.felixchang67.diskandroid.core.meta

import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import java.security.MessageDigest
import java.util.Base64

/**
 * 用来在线识别光盘的 TOC（与 MusicBrainz libdiscid 的 mb_disc_load_toc 规则相同）：
 * - 起点、lead-out 都以"帧"为单位，等于 LBA + 150；
 * - 只算音轨：最后一轨是数据轨（加强版 CD）时，lead-out 取该数据轨起点 − 11400 + 150（XA_INTERVAL）；
 * - 第一轨是数据轨（混合模式盘）时照常从第 1 轨算起；
 * - 有的防复制盘 lead-out 比最后一轨起点还小，就去掉最后一轨重算。
 */
class DiscToc(
    val firstTrack: Int,
    val lastTrack: Int,
    /** lead-out 帧（LBA + 150）。 */
    val leadOut: Int,
    /** 第 [firstTrack]..[lastTrack] 轨的起点帧（LBA + 150）。 */
    val offsets: List<Int>,
) {
    init {
        require(firstTrack in 1..99 && lastTrack in firstTrack..99 && offsets.size == lastTrack - firstTrack + 1)
    }

    val trackCount: Int get() = offsets.size

    /**
     * MusicBrainz 光盘 ID：对 "%02X 首轨 + %02X 末轨 + %08X lead-out + 99 个 %08X 轨道起点（没有的填 0）" 做 SHA-1，
     * 再做 Base64，并把 '+' '/' '=' 换成 '.' '_' '-'（libdiscid create_disc_id / rfc822_binary）。
     */
    val musicBrainzId: String by lazy {
        val sb = StringBuilder()
        sb.append("%02X".format(firstTrack)).append("%02X".format(lastTrack)).append("%08X".format(leadOut))
        for (track in 1..99) {
            val off = if (track in firstTrack..lastTrack) offsets[track - firstTrack] else 0
            sb.append("%08X".format(off))
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        Base64.getEncoder().encodeToString(digest).replace('+', '.').replace('/', '_').replace('=', '-')
    }

    /** freedb / CDDB 光盘 ID（libdiscid create_freedb_disc_id）。 */
    val freedbId: String by lazy {
        var n = 0
        for (off in offsets) {
            var m = off / 75
            while (m > 0) {
                n += m % 10
                m /= 10
            }
        }
        val t = leadOut / 75 - offsets.first() / 75
        "%08x".format(((n % 0xFF) shl 24) or (t shl 8) or lastTrack)
    }

    /** MusicBrainz 的 toc 参数（"首轨+末轨+lead-out+各轨起点"），光盘 ID 查不到时据此做模糊匹配。 */
    fun musicBrainzToc(): String = (listOf(firstTrack, lastTrack, leadOut) + offsets).joinToString("+")

    /** 每轨时长（毫秒），按相邻起点之差计算（与 MusicBrainz 根据 TOC 算出的时长一致）。 */
    fun trackLengthsMs(): List<Long> = offsets.indices.map { i ->
        val end = offsets.getOrNull(i + 1) ?: leadOut
        (end - offsets[i]).toLong() * 1000 / 75
    }

    override fun toString() = "TOC $firstTrack–$lastTrack，lead-out $leadOut，MusicBrainz ID $musicBrainzId，freedb $freedbId"

    companion object {
        const val XA_INTERVAL = (60 + 90 + 2) * 75

        fun from(disc: DiscInfo): DiscToc? {
            val toc = disc.toc ?: return null
            val tracks = toc.tracks.sortedBy { it.track }
            if (tracks.isEmpty()) return null
            val first = tracks.first().track
            if (first !in 1..99) return null
            var last = tracks.lastOrNull { !it.isData }?.track ?: return null
            val byNumber = tracks.associateBy { it.track }
            val offsets = (first..last).map { n ->
                val lba = byNumber[n]?.lba ?: return null
                if (lba > 0) (lba + 150).toInt() else 150
            }.toMutableList()
            fun dataTrackLeadOut(next: Int): Int? = byNumber[next]?.let { (it.lba - XA_INTERVAL + 150).toInt() }
            var leadOut = if (last < tracks.last().track) {
                dataTrackLeadOut(last + 1) ?: return null
            } else {
                ((toc.leadOut?.lba ?: return null) + 150).toInt()
            }
            while (offsets.isNotEmpty() && leadOut < offsets.last()) {
                offsets.removeAt(offsets.size - 1)
                last--
                leadOut = dataTrackLeadOut(last + 1) ?: return null
            }
            if (offsets.isEmpty() || last < first) return null
            return DiscToc(first, last, leadOut, offsets)
        }
    }
}
