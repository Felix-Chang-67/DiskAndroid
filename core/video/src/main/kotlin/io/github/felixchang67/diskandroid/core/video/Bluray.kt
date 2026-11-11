package io.github.felixchang67.diskandroid.core.video

import io.github.felixchang67.diskandroid.core.fs.DiscFileSystem
import io.github.felixchang67.diskandroid.core.fs.SectorReadException
import io.github.felixchang67.diskandroid.core.fs.find
import io.github.felixchang67.diskandroid.core.fs.readAll

/*
 * BD（BDMV）/ AVCHD 的播放列表（.mpls / .MPL）解析，字节布局对照 libbluray 1.5.0 的 mpls_parse.c；
 * 过滤重复的播放列表、挑主片的规则移植自 libbluray 的 navigation.c（测试里拿 bd_list_titles 的输出核对）。
 * 播放列表不加密（AACS 只加密 .m2ts），商业 BD 也能直接读。时间单位是 45 kHz。
 */

data class BdStream(val kind: Kind, val pid: Int, val codingType: Int, val format: Int, val rate: Int, val language: String?) {
    enum class Kind { VIDEO, AUDIO, PG, IG, SECONDARY_AUDIO, SECONDARY_VIDEO, DV }

    val codec: String
        get() = when (codingType) {
            0x01 -> "MPEG-1"
            0x02 -> "MPEG-2"
            0x1B -> "H.264/AVC"
            0x20 -> "H.264/MVC（3D）"
            0x24 -> "H.265/HEVC"
            0xEA -> "VC-1"
            0x03 -> "MPEG-1 音频"
            0x04 -> "MPEG-2 音频"
            0x80 -> "LPCM"
            0x81 -> "AC-3"
            0x82 -> "DTS"
            0x83 -> "Dolby TrueHD"
            0x84 -> "E-AC-3"
            0x85 -> "DTS-HD HR"
            0x86 -> "DTS-HD MA"
            0xA1 -> "E-AC-3（副音轨）"
            0xA2 -> "DTS-HD（副音轨）"
            0x90 -> "PGS 字幕"
            0x91 -> "交互图形"
            0x92 -> "文本字幕"
            else -> "类型 %02Xh".format(codingType)
        }

    val isVideo: Boolean get() = codingType in setOf(0x01, 0x02, 0x1B, 0x20, 0x24, 0xEA)

    fun info(): StreamInfo = StreamInfo(language, codec, detail())

    private fun detail(): String = if (isVideo) {
        val res = when (format) {
            1 -> "480i"
            2 -> "576i"
            3 -> "480p"
            4 -> "1080i"
            5 -> "720p"
            6 -> "1080p"
            7 -> "576p"
            8 -> "2160p"
            else -> ""
        }
        val fps = when (rate) {
            1 -> "23.976"
            2 -> "24"
            3 -> "25"
            4 -> "29.97"
            6 -> "50"
            7 -> "59.94"
            else -> ""
        }
        listOf(res, fps).filter { it.isNotEmpty() }.joinToString(" ")
    } else if (kind == Kind.AUDIO || kind == Kind.SECONDARY_AUDIO) {
        val ch = when (format) {
            1 -> "单声道"
            3 -> "立体声"
            6 -> "多声道"
            12 -> "立体声 + 多声道"
            else -> ""
        }
        val hz = when (rate) {
            1 -> "48 kHz"
            4 -> "96 kHz"
            5 -> "192 kHz"
            12 -> "48/192 kHz"
            14 -> "48/96 kHz"
            else -> ""
        }
        listOf(ch, hz).filter { it.isNotEmpty() }.joinToString(" ")
    } else {
        ""
    }
}

data class BdPlayItem(
    val clip: String,
    val inTime: Long,
    val outTime: Long,
    val angles: Int,
    val connection: Int,
    val streams: List<BdStream>,
) {
    val durationTicks: Long get() = outTime - inTime
    fun of(kind: BdStream.Kind) = streams.filter { it.kind == kind }
}

/** 播放列表标记：类型 1 = 章节入口，2 = 链接点。时间是所在播放项的时间轴。 */
data class BdMark(val type: Int, val item: Int, val time: Long)

class BdPlaylist(val name: String, val items: List<BdPlayItem>, val marks: List<BdMark>, val subPaths: Int) {
    val durationTicks: Long = items.sumOf { it.durationTicks }
    val durationMs: Long get() = durationTicks / 45
    val chapterCount: Int get() = marks.count { it.type == 1 }
    val angles: Int get() = items.maxOfOrNull { it.angles } ?: 1
    val clips: List<String> get() = items.map { it.clip }

    /** 每个章节的起点（相对播放列表开头，毫秒）。 */
    fun chapterStartsMs(): List<Long> {
        val starts = mutableListOf<Long>()
        for (m in marks) {
            if (m.type != 1 || m.item !in items.indices) continue
            val before = items.take(m.item).sumOf { it.durationTicks }
            starts += (before + (m.time - items[m.item].inTime).coerceAtLeast(0)) / 45
        }
        return starts
    }

    /** 第一个播放项的流（libbluray 同样以第一项为准）。 */
    fun streams(kind: BdStream.Kind): List<BdStream> = items.firstOrNull()?.of(kind).orEmpty()
}

object Mpls {
    fun parse(name: String, bytes: ByteArray): BdPlaylist {
        val b = Be(bytes, name)
        if (b.ascii(0, 4) != "MPLS") throw VideoParseException("$name 不是播放列表（标识 ${b.ascii(0, 4)}）")
        val listPos = b.u32(8).toInt()
        val markPos = b.u32(12).toInt()
        var p = listPos + 4 + 2
        val nItems = b.u16(p)
        val nSub = b.u16(p + 2)
        p += 4
        val items = (0 until nItems).map {
            val len = b.u16(p)
            val item = playItem(b, p + 2, len)
            p += 2 + len
            item
        }
        val marks = mutableListOf<BdMark>()
        if (markPos > 0 && b.has(markPos, 6)) {
            val n = b.u16(markPos + 4)
            for (i in 0 until n) {
                val o = markPos + 6 + 14 * i
                if (!b.has(o, 14)) break
                marks += BdMark(b.u8(o + 1), b.u16(o + 2), b.u32(o + 4))
            }
        }
        return BdPlaylist(name, items, marks, nSub)
    }

    private fun playItem(b: Be, start: Int, len: Int): BdPlayItem {
        if (len < 18) throw VideoParseException("播放项长度 $len 无效")
        val clip = b.ascii(start, 5)
        val flags = b.u16(start + 9)
        val multiAngle = (flags shr 4) and 1 == 1
        val connection = flags and 0x0F
        val inTime = b.u32(start + 12)
        val outTime = b.u32(start + 16)
        var p = start + 20 + 8 + 1 + 1 + 2 // UO 掩码、随机访问标志、静止模式、静止时间
        var angles = 1
        if (multiAngle) {
            angles = b.u8(p).coerceAtLeast(1)
            p += 2 + 10 * (angles - 1)
        }
        return BdPlayItem(clip, inTime, outTime, angles, connection, stn(b, p))
    }

    private fun stn(b: Be, start: Int): List<BdStream> {
        val counts = IntArray(8) { b.u8(start + 4 + it) }
        var p = start + 2 + 2 + 8 + 4
        val out = mutableListOf<BdStream>()
        val kinds = listOf(
            BdStream.Kind.VIDEO to counts[0],
            BdStream.Kind.AUDIO to counts[1],
            BdStream.Kind.PG to counts[2] + counts[6],
            BdStream.Kind.IG to counts[3],
            BdStream.Kind.SECONDARY_AUDIO to counts[4],
            BdStream.Kind.SECONDARY_VIDEO to counts[5],
            BdStream.Kind.DV to counts[7],
        )
        for ((kind, n) in kinds) {
            repeat(n) {
                val (s, next) = stream(b, p, kind)
                out += s
                p = next
                if (kind == BdStream.Kind.SECONDARY_AUDIO) p = refs(b, p)
                if (kind == BdStream.Kind.SECONDARY_VIDEO) p = refs(b, refs(b, p))
            }
        }
        return out
    }

    /** 副音轨 / 副视频后面的引用表：个数、保留字节、引用（个数为奇数时补一个字节）。 */
    private fun refs(b: Be, p: Int): Int {
        val n = b.u8(p)
        return p + 2 + n + (n % 2)
    }

    private fun stream(b: Be, start: Int, kind: BdStream.Kind): Pair<BdStream, Int> {
        val entryLen = b.u8(start)
        val type = b.u8(start + 1)
        val pid = when (type) {
            1 -> b.u16(start + 2)
            2 -> b.u16(start + 4)
            3, 4 -> b.u16(start + 3)
            else -> 0
        }
        val a = start + 1 + entryLen
        val attrLen = b.u8(a)
        val coding = b.u8(a + 1)
        var format = 0
        var rate = 0
        var lang: String? = null
        when (coding) {
            0x01, 0x02, 0xEA, 0x1B, 0x20, 0x24 -> {
                format = b.u8(a + 2) shr 4
                rate = b.u8(a + 2) and 0x0F
            }
            0x03, 0x04, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xA1, 0xA2 -> {
                format = b.u8(a + 2) shr 4
                rate = b.u8(a + 2) and 0x0F
                lang = b.ascii(a + 3, 3)
            }
            0x90, 0x91 -> lang = b.ascii(a + 2, 3)
            0x92 -> lang = b.ascii(a + 3, 3)
        }
        val language = lang?.trim()?.lowercase()?.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } }
        return BdStream(kind, pid, coding, format, rate, language) to (a + 1 + attrLen)
    }
}

/** 过滤与挑主片（移植自 libbluray navigation.c 的 _filter_dup / _filter_repeats / _pl_guess_main_title）。 */
object BdTitles {
    private fun samePlaylist(a: BdPlaylist, b: BdPlaylist): Boolean =
        a.items.size == b.items.size && a.marks == b.marks && a.subPaths == b.subPaths &&
            a.items.zip(b.items).all { (x, y) -> x.clip == y.clip && x.inTime == y.inTime && x.outTime == y.outTime && x.streams == y.streams }

    /** 同一段（剪辑 + 入点 + 出点）出现超过 [repeats] 次：反盗版用的乱序 / 循环播放列表。 */
    fun hasRepeats(pl: BdPlaylist, repeats: Int = 2): Boolean =
        pl.items.groupingBy { Triple(it.clip, it.inTime, it.outTime) }.eachCount().values.any { it > repeats }

    /** 去掉重复的和有循环片段的播放列表（顺序按文件名）。返回（保留的，被过滤的数量）。 */
    fun filter(playlists: List<BdPlaylist>): Pair<List<BdPlaylist>, Int> {
        val kept = mutableListOf<BdPlaylist>()
        for (pl in playlists.sortedBy { it.name }) {
            if (pl.items.isEmpty() || hasRepeats(pl)) continue
            if (kept.any { samePlaylist(it, pl) }) continue
            kept += pl
        }
        return kept to (playlists.size - kept.size)
    }

    private fun videoProps(pl: BdPlaylist): Pair<Int, Int> {
        var format = 0
        var codec = 0
        for (v in pl.streams(BdStream.Kind.VIDEO)) {
            if (v.codingType > 4 && codec < 1) codec = 1
            if (v.codingType == 0x24) codec = 2
            if ((v.format == 4 || v.format == 6) && format < 1) format = 1
            if (v.format == 8) format = 2
        }
        return format to codec
    }

    private fun hdAudio(pl: BdPlaylist): Int =
        if (pl.streams(BdStream.Kind.AUDIO).any { it.codingType == 0x80 || it.codingType in 0x83..0x86 }) 1 else 0

    private fun streamsScore(pl: BdPlaylist): Int =
        (pl.items.maxOfOrNull { it.of(BdStream.Kind.AUDIO).size } ?: 0) * 2 + (pl.items.maxOfOrNull { it.of(BdStream.Kind.PG).size } ?: 0)

    /** 小于 0：[a] 更像主片；大于 0：[b] 更像主片。 */
    fun compare(a: BdPlaylist, b: BdPlaylist): Int {
        val d1 = a.durationTicks
        val d2 = b.durationTicks
        val halfHour = 30L * 60 * 45000
        if (d1 > halfHour && d2 > halfHour) {
            val c1 = a.chapterCount
            val c2 = b.chapterCount
            val diff = c2 - c1
            if ((c1 < 2 || c2 < 2) && (diff < -5 || diff > 5)) return diff
            val (f1, k1) = videoProps(a)
            val (f2, k2) = videoProps(b)
            if (f1 != f2) return f2 - f1
            if (k1 != k2) return k2 - k1
            val h = hdAudio(b) - hdAudio(a)
            if (h != 0) return h
        }
        if (d1 < d2) return 1
        if (d1 > d2) return -1
        return streamsScore(b) - streamsScore(a)
    }

    fun main(playlists: List<BdPlaylist>): BdPlaylist? {
        var best: BdPlaylist? = null
        for (pl in playlists) if (best == null || compare(best, pl) > 0) best = pl
        return best
    }
}

class BdDisc(
    val avchd: Boolean,
    val playlists: List<BdPlaylist>,
    val titles: List<BdPlaylist>,
    val filtered: Int,
    val main: BdPlaylist?,
    /** 剪辑号 → .m2ts 文件大小。 */
    val clipSizes: Map<String, Long>,
    val warnings: List<String>,
    /** index.bdmv 的版本（100 / 200 / 300），读不出为 null。300 = Ultra HD Blu-ray。 */
    val indexVersion: Int? = null,
) {
    fun sizeOf(pl: BdPlaylist): Long? = pl.clips.distinct().map { clipSizes[it] ?: return null }.sum()

    /** Ultra HD Blu-ray（4K）：index.bdmv 版本 0300，加密的话是 AACS 2.0。 */
    val uhd: Boolean get() = (indexVersion ?: 0) >= 300
}

object BdmvReader {
    /** @param root "/BDMV" 或 AVCHD 的 "/PRIVATE/AVCHD/BDMV" */
    suspend fun read(fs: DiscFileSystem, root: String, avchd: Boolean = false): BdDisc {
        val warnings = mutableListOf<String>()
        val dir = fs.find("$root/PLAYLIST") ?: throw VideoParseException("找不到 $root/PLAYLIST")
        val files = fs.list(dir).filter { !it.isDirectory && (it.name.endsWith(".mpls", true) || it.name.endsWith(".mpl", true)) }
        val playlists = mutableListOf<BdPlaylist>()
        for (f in files) {
            try {
                playlists += Mpls.parse(f.name, fs.readAll(f, limit = 4 shl 20))
            } catch (e: SectorReadException) {
                if (e.fatal) throw e
                warnings += "${f.name} 读不出：${e.message}"
            } catch (e: java.io.IOException) {
                warnings += "${f.name} 解析失败：${e.message}"
            }
        }
        val sizes = mutableMapOf<String, Long>()
        fs.find("$root/STREAM")?.let { s ->
            runCatching { fs.list(s) }.getOrDefault(emptyList()).filter { !it.isDirectory }.forEach { e ->
                sizes[e.name.substringBefore('.')] = e.size
            }
        }
        val (titles, filtered) = BdTitles.filter(playlists)
        return BdDisc(avchd, playlists.sortedBy { it.name }, titles, filtered, BdTitles.main(titles), sizes, warnings, indexVersion(fs, root))
    }

    /** index.bdmv（AVCHD 是 INDEX.BDM）开头的 "INDX0200" 之类 → 200。 */
    private suspend fun indexVersion(fs: DiscFileSystem, root: String): Int? {
        val f = fs.find("$root/index.bdmv") ?: fs.find("$root/INDEX.BDM") ?: return null
        return try {
            val head = ByteArray(8)
            val n = fs.open(f, cached = true).read(0, head, 0, head.size)
            parseIndexVersion(head.copyOf(n.coerceAtLeast(0)))
        } catch (e: SectorReadException) {
            if (e.fatal) throw e
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    fun parseIndexVersion(head: ByteArray): Int? {
        if (head.size < 8 || String(head, 0, 4, Charsets.ISO_8859_1) != "INDX") return null
        return String(head, 4, 4, Charsets.ISO_8859_1).toIntOrNull()
    }
}
