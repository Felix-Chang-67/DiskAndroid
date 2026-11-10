package io.github.felixchang67.diskandroid.core.video

import io.github.felixchang67.diskandroid.core.fs.DiscFileSystem
import io.github.felixchang67.diskandroid.core.fs.DiscText
import io.github.felixchang67.diskandroid.core.fs.find
import io.github.felixchang67.diskandroid.core.fs.readAll

/*
 * DVD-Video 的 IFO 解析（VIDEO_TS.IFO = VMGI，VTS_nn_0.IFO = VTSI）。字节偏移对照 libdvdread 7.1.1 的 ifo_types.h，
 * 时长、章节的算法与 lsdvd 一致（测试里拿 lsdvd 的输出核对）。IFO 不加密，CSS 盘也能直接读。
 */

/** 一个单元（cell）：播放时间和它在标题 VOB 里的扇区范围（相对于标题集的标题 VOB 起点）。 */
data class DvdCell(
    val number: Int,
    val durationMs: Long,
    val firstSector: Long,
    val lastSector: Long,
    /** 0 = 不在块里，1 = 块的第一个单元，2 = 块中间，3 = 块的最后一个单元。 */
    val blockMode: Int,
    /** 1 = 角度块（多机位）。 */
    val blockType: Int,
    val vobId: Int,
    val cellId: Int,
) {
    val sectors: Long get() = (lastSector - firstSector + 1).coerceAtLeast(0)

    /** 多角度块里除第一个角度以外的单元（算时长、大小时不计）。 */
    val otherAngle: Boolean get() = blockType == 1 && blockMode > 1
}

data class DvdChapter(val number: Int, val startCell: Int, val durationMs: Long)

data class DvdVideoAttributes(val mpeg2: Boolean, val pal: Boolean, val wide: Boolean, val width: Int, val height: Int) {
    fun describe(): String = "MPEG-${if (mpeg2) 2 else 1} ${if (pal) "PAL" else "NTSC"} $width×$height ${if (wide) "16:9" else "4:3"}"
}

data class DvdTitle(
    /** 整张盘的标题号（遥控器上的标题号，1 起）。 */
    val number: Int,
    val titleSet: Int,
    val vtsTitle: Int,
    val angles: Int,
    val durationMs: Long,
    val fps: Double?,
    val chapters: List<DvdChapter>,
    val cells: List<DvdCell>,
    val pgcs: List<Int>,
    val video: DvdVideoAttributes,
    val audio: List<StreamInfo>,
    val subtitles: List<StreamInfo>,
    /** 标题集在盘上的起始扇区（VMGI 的标题表）。 */
    val titleSetSector: Long,
    /** 标题 VOB 相对标题集起点的偏移（VTSI 0xC4）。单元的扇区再加上这两个就是盘上的 LBA。 */
    val titleVobOffset: Long,
) {
    val sectors: Long get() = cells.filter { !it.otherAngle }.sumOf { it.sectors }

    /** 单元按扇区顺序排列（结构保护常见的假标题会把单元打乱）。 */
    val sequential: Boolean get() = cells.zipWithNext().all { (a, b) -> b.firstSector > a.firstSector }
}

class DvdVideoDisc(val titles: List<DvdTitle>, val titleSets: Int, val provider: String, val warnings: List<String>) {
    /** 主片：时长最长；时长差不到 1 秒的几个里，优先单元按顺序排列的，再按标题号（与 lsdvd 的"Longest track"一致）。 */
    val main: DvdTitle? = titles.maxOfOrNull { it.durationMs }?.let { max ->
        titles.filter { it.durationMs >= max - 1000 }
            .sortedWith(compareByDescending<DvdTitle> { it.durationMs == max }.thenByDescending { it.sequential }.thenBy { it.number })
            .first()
    }
}

object DvdIfo {
    class TitleEntry(val number: Int, val angles: Int, val chapters: Int, val titleSet: Int, val vtsTitle: Int, val startSector: Long)
    class Vmg(val titleSets: Int, val provider: String, val titles: List<TitleEntry>)

    class AudioAttr(val format: Int, val language: String?, val channels: Int, val sampleRate: Int, val quantization: Int, val codeExtension: Int)
    class SubpAttr(val language: String?, val codeExtension: Int)

    class Pgc(
        val number: Int,
        val durationMs: Long,
        val fps: Double?,
        /** 每个节目（program）的入口单元号（1 起）。 */
        val programs: IntArray,
        val cells: List<DvdCell>,
        val audioControl: IntArray,
        val subpControl: LongArray,
    )

    class Vts(
        val video: DvdVideoAttributes,
        val audio: List<AudioAttr>,
        val subpictures: List<SubpAttr>,
        /** 每个标题（VTS 内）的章节：(PGC 号, 节目号)。 */
        val ptts: List<List<Pair<Int, Int>>>,
        val pgcs: List<Pgc>,
        val titleVobOffset: Long,
    )

    fun parseVmg(bytes: ByteArray): Vmg {
        val b = Be(bytes, "VIDEO_TS.IFO")
        if (b.ascii(0, 12) != "DVDVIDEO-VMG") throw VideoParseException("VIDEO_TS.IFO 的标识不对")
        val sets = b.u16(0x3E)
        val provider = DiscText.field(bytes, 0x40, 32)
        val tt = (b.u32(0xC4) * 2048).toInt()
        val n = b.u16(tt)
        val titles = (0 until n).map { i ->
            val o = tt + 8 + 12 * i
            TitleEntry(i + 1, b.u8(o + 1), b.u16(o + 2), b.u8(o + 6), b.u8(o + 7), b.u32(o + 8))
        }
        return Vmg(sets, provider, titles)
    }

    fun parseVts(bytes: ByteArray, name: String = "VTS IFO"): Vts {
        val b = Be(bytes, name)
        if (b.ascii(0, 12) != "DVDVIDEO-VTS") throw VideoParseException("$name 的标识不对")
        val v0 = b.u8(0x200)
        val v1 = b.u8(0x201)
        val pal = (v0 shr 4) and 3 == 1
        val size = (v1 shr 2) and 3
        val video = DvdVideoAttributes(
            mpeg2 = (v0 shr 6) and 3 == 1,
            pal = pal,
            wide = (v0 shr 2) and 3 == 3,
            width = when (size) {
                0 -> 720
                1 -> 704
                else -> 352
            },
            height = if (size == 3) (if (pal) 288 else 240) else (if (pal) 576 else 480),
        )
        val audio = (0 until minOf(b.u8(0x203), 8)).map { i ->
            val o = 0x204 + 8 * i
            val a0 = b.u8(o)
            val a1 = b.u8(o + 1)
            AudioAttr(
                format = (a0 shr 5) and 7,
                language = if ((a0 shr 2) and 3 == 1) lang(b, o + 2) else null,
                channels = (a1 and 7) + 1,
                sampleRate = if ((a1 shr 4) and 3 == 1) 96_000 else 48_000,
                quantization = (a1 shr 6) and 3,
                codeExtension = b.u8(o + 5),
            )
        }
        val subp = (0 until minOf(b.u8(0x255), 32)).map { i ->
            val o = 0x256 + 6 * i
            SubpAttr(if (b.u8(o) and 3 == 1) lang(b, o + 2) else null, b.u8(o + 5))
        }

        // 章节表（VTS_PTT_SRPT）
        val p = (b.u32(0xC8) * 2048).toInt()
        val nTitles = b.u16(p)
        val last = b.u32(p + 4).toInt()
        val offsets = (0 until nTitles).map { b.u32(p + 8 + 4 * it).toInt() }
        val ptts = offsets.mapIndexed { i, off ->
            val end = if (i + 1 < offsets.size) offsets[i + 1] else last + 1
            (0 until ((end - off) / 4).coerceIn(0, 999)).map { j -> b.u16(p + off + 4 * j) to b.u16(p + off + 4 * j + 2) }
        }

        // 节目链表（VTS_PGCIT）
        val g = (b.u32(0xCC) * 2048).toInt()
        val nPgc = b.u16(g)
        val pgcs = (0 until nPgc).map { i -> parsePgc(b, g + b.u32(g + 8 + 8 * i + 4).toInt(), i + 1) }
        return Vts(video, audio, subp, ptts, pgcs, b.u32(0xC4))
    }

    private fun lang(b: Be, off: Int): String? = b.ascii(off, 2).lowercase().takeIf { it.all { c -> c in 'a'..'z' } }

    private fun parsePgc(b: Be, off: Int, number: Int): Pgc {
        val nPrograms = b.u8(off + 2)
        val nCells = b.u8(off + 3)
        val (ms, fps) = time(b, off + 4)
        val audioControl = IntArray(8) { b.u16(off + 0x0C + 2 * it) }
        val subpControl = LongArray(32) { b.u32(off + 0x1C + 4 * it) }
        val programMap = off + b.u16(off + 0xE6)
        val playback = off + b.u16(off + 0xE8)
        val position = off + b.u16(off + 0xEA)
        val programs = IntArray(nPrograms) { b.u8(programMap + it) }
        val cells = (0 until nCells).map { i ->
            val c = playback + 24 * i
            val c0 = b.u8(c)
            val pos = position + 4 * i
            DvdCell(
                number = i + 1,
                durationMs = time(b, c + 4).first,
                firstSector = b.u32(c + 8),
                lastSector = b.u32(c + 20),
                blockMode = (c0 shr 6) and 3,
                blockType = (c0 shr 4) and 3,
                vobId = if (b.has(pos, 4)) b.u16(pos) else 0,
                cellId = if (b.has(pos, 4)) b.u8(pos + 3) else 0,
            )
        }
        return Pgc(number, ms, fps, programs, cells, audioControl, subpControl)
    }

    /** BCD 时分秒 + 帧（帧字节高两位：1 = 25 帧/秒，3 = 29.97 帧/秒），换算方法同 lsdvd。 */
    internal fun time(b: Be, off: Int): Pair<Long, Double?> {
        fun bcd(v: Int) = ((v shr 4) and 0x0F) * 10 + (v and 0x0F)
        val h = bcd(b.u8(off))
        val m = bcd(b.u8(off + 1))
        val s = bcd(b.u8(off + 2))
        val f = b.u8(off + 3)
        val fps = when ((f shr 6) and 3) {
            1 -> 25.0
            3 -> 29.97
            else -> null
        }
        var ms = (h * 3600L + m * 60L + s) * 1000L
        if (fps != null) ms += (bcd(f and 0x3F) * 1000.0 / fps).toLong()
        return ms to fps
    }

    private fun audioInfo(a: AudioAttr, control: Int?): StreamInfo {
        val codec = when (a.format) {
            0 -> "AC-3"
            2 -> "MPEG-1"
            3 -> "MPEG-2"
            4 -> "LPCM"
            6 -> "DTS"
            7 -> "SDDS"
            else -> "格式 ${a.format}"
        }
        val bits = when (a.quantization) {
            0 -> " 16 bit"
            1 -> " 20 bit"
            2 -> " 24 bit"
            else -> ""
        }
        val note = when (a.codeExtension) {
            2 -> "视障解说"
            3, 4 -> "导演评论"
            else -> null
        }
        val streamId = control?.let { c ->
            val base = when (a.format) {
                0 -> 0x80
                2, 3 -> 0xC0
                4 -> 0xA0
                6 -> 0x88
                else -> null
            }
            base?.let { it + ((c shr 8) and 7) }
        }
        val detail = channels(a.channels) + " ${a.sampleRate / 1000} kHz" + (if (a.format == 4) bits else "") +
            (streamId?.let { " · 流 %02Xh".format(it) } ?: "")
        return StreamInfo(a.language, codec, detail, note)
    }

    private fun channels(n: Int) = when (n) {
        1 -> "单声道"
        2 -> "立体声"
        6 -> "5.1 声道"
        else -> "$n 声道"
    }

    private fun subpInfo(s: SubpAttr): StreamInfo {
        val note = when (s.codeExtension) {
            2, 6 -> "大号字"
            3, 7 -> "儿童用"
            5 -> "听障字幕"
            9 -> "强制字幕"
            13, 14, 15 -> "导演评论"
            else -> null
        }
        return StreamInfo(s.language, "字幕", "", note)
    }

    /** 从盘上读 VIDEO_TS.IFO 和各标题集的 VTS_nn_0.IFO（读不出或损坏时用 .BUP 备份）。 */
    suspend fun read(fs: DiscFileSystem): DvdVideoDisc {
        val warnings = mutableListOf<String>()
        val vmg = load(fs, "VIDEO_TS", warnings) { parseVmg(it) }
            ?: throw VideoParseException("VIDEO_TS.IFO 和 VIDEO_TS.BUP 都读不出来")
        val vtsCache = mutableMapOf<Int, Vts?>()
        val titles = mutableListOf<DvdTitle>()
        for (t in vmg.titles) {
            val vts = vtsCache.getOrPut(t.titleSet) {
                load(fs, "VTS_%02d_0".format(t.titleSet), warnings) { parseVts(it, "VTS_%02d_0.IFO".format(t.titleSet)) }
            } ?: continue
            val ptt = vts.ptts.getOrNull(t.vtsTitle - 1)
            if (ptt.isNullOrEmpty()) {
                warnings += "标题 ${t.number} 在标题集 ${t.titleSet} 里找不到章节表"
                continue
            }
            val pgcNumbers = ptt.map { it.first }.distinct()
            val pgcs = pgcNumbers.mapNotNull { vts.pgcs.getOrNull(it - 1) }
            if (pgcs.isEmpty()) {
                warnings += "标题 ${t.number} 指向不存在的节目链"
                continue
            }
            val chapters = ptt.mapIndexed { i, (pgcn, pgn) ->
                val pgc = vts.pgcs.getOrNull(pgcn - 1)
                if (pgc == null || pgn < 1 || pgn > pgc.programs.size) {
                    DvdChapter(i + 1, 0, 0)
                } else {
                    val start = pgc.programs[pgn - 1]
                    val end = if (pgn < pgc.programs.size) pgc.programs[pgn] - 1 else pgc.cells.size
                    val ms = pgc.cells.filter { it.number in start..end && !it.otherAngle }.sumOf { it.durationMs }
                    DvdChapter(i + 1, start, ms)
                }
            }
            val first = pgcs.first()
            val audio = vts.audio.mapIndexedNotNull { i, a ->
                val control = first.audioControl[i]
                // PGC 里没有标记"可用"的音轨不列（与播放器看到的一致）；整个 PGC 都没标记时全部列出
                if (first.audioControl.any { it and 0x8000 != 0 } && control and 0x8000 == 0) null else audioInfo(a, control)
            }
            val subtitles = vts.subpictures.filterIndexed { i, _ ->
                !first.subpControl.any { it and 0x80000000L != 0L } || first.subpControl[i] and 0x80000000L != 0L
            }.map { subpInfo(it) }
            titles += DvdTitle(
                number = t.number,
                titleSet = t.titleSet,
                vtsTitle = t.vtsTitle,
                angles = t.angles,
                durationMs = pgcs.sumOf { it.durationMs },
                fps = first.fps,
                chapters = chapters,
                cells = pgcs.flatMap { it.cells },
                pgcs = pgcNumbers,
                video = vts.video,
                audio = audio,
                subtitles = subtitles,
                titleSetSector = t.startSector,
                titleVobOffset = vts.titleVobOffset,
            )
        }
        return DvdVideoDisc(titles, vmg.titleSets, vmg.provider, warnings)
    }

    private suspend fun <T> load(fs: DiscFileSystem, base: String, warnings: MutableList<String>, parse: (ByteArray) -> T): T? {
        var firstError: String? = null
        for (ext in listOf("IFO", "BUP")) {
            val entry = fs.find("/VIDEO_TS/$base.$ext") ?: continue
            try {
                val r = parse(fs.readAll(entry, limit = 8 shl 20))
                if (ext == "BUP") warnings += "$base.IFO 读不出（$firstError），改用备份 $base.BUP"
                return r
            } catch (e: java.io.IOException) {
                if (e is io.github.felixchang67.diskandroid.core.fs.SectorReadException && e.fatal) throw e
                if (firstError == null) firstError = e.message
            }
        }
        warnings += "$base.IFO / .BUP 都读不出：${firstError ?: "找不到文件"}"
        return null
    }
}
