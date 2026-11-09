package io.github.felixchang67.diskandroid.core.mmc

import io.github.felixchang67.diskandroid.core.log.Hex

/** CD 地址换算：LBA 0 = 00:02:00（MSF），每秒 75 帧。 */
object Msf {
    const val FRAMES_PER_SECOND = 75

    fun toLba(m: Int, s: Int, f: Int): Long = (m * 60L + s) * FRAMES_PER_SECOND + f - 150

    fun format(lba: Long): String {
        val a = lba + 150
        if (a < 0) return "-" + format(-a - 150)
        val m = a / (60 * FRAMES_PER_SECOND)
        val s = (a / FRAMES_PER_SECOND) % 60
        val f = a % FRAMES_PER_SECOND
        return "%02d:%02d:%02d".format(m, s, f)
    }

    /** 扇区数 → 播放时长 m:ss（CD-DA 每秒 75 个扇区）。 */
    fun duration(sectors: Long): String {
        val totalSeconds = sectors / FRAMES_PER_SECOND
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}

/** READ CAPACITY(10) 的结果。 */
class ReadCapacity(val lastLba: Long, val blockLength: Long) {
    val blocks: Long get() = lastLba + 1
    val bytes: Long get() = blocks * blockLength

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): ReadCapacity? {
            if (minOf(length, data.size) < 8) return null
            val b = Bytes(data, 8)
            return ReadCapacity(b.be32(0), b.be32(4))
        }
    }
}

/** READ DISC INFORMATION（标准盘片信息，MMC-6；与 Linux disc_information 结构一致）。 */
class DiscInformation(
    val length: Int,
    val erasable: Boolean,
    /** 0 = 空，1 = 未完成，2 = 保留/损坏，3 = 已完成。 */
    val lastSessionState: Int,
    /** 0 = 空白，1 = 未完成（可追加），2 = 已完成（已封盘），3 = 其他（随机写入介质）。 */
    val discStatus: Int,
    val firstTrack: Int,
    val sessions: Int,
    val firstTrackInLastSession: Int,
    val lastTrackInLastSession: Int,
    val discIdValid: Boolean,
    val barCodeValid: Boolean,
    val unrestrictedUse: Boolean,
    val bgFormatStatus: Int,
    /** 00h = CD-DA/CD-ROM，10h = CD-I，20h = CD-ROM XA，FFh = 未定义。 */
    val discType: Int,
    val discId: Long,
    val lastSessionLeadIn: Long,
    val lastPossibleLeadOut: Long,
    val applicationCode: Int,
    val opcTables: Int,
) {
    fun discStatusName(): String = when (discStatus) {
        0 -> "空白盘"
        1 -> "未封盘（可追加写入）"
        2 -> "已封盘"
        else -> "随机写入介质（如 DVD-RAM / BD-RE）"
    }

    fun lastSessionStateName(): String = when (lastSessionState) {
        0 -> "空"
        1 -> "未完成"
        2 -> "保留/损坏"
        else -> "已完成"
    }

    fun discTypeName(): String = when (discType) {
        0x00 -> "CD-DA 或 CD-ROM"
        0x10 -> "CD-I"
        0x20 -> "CD-ROM XA"
        0xFF -> "未定义（非 CD 或无法判断）"
        else -> "未知（${Hex.byte(discType)}h）"
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): DiscInformation? {
            val n = minOf(length, data.size)
            if (n < 12) return null
            val b = Bytes(data, n)
            return DiscInformation(
                length = b.be16(0) + 2,
                erasable = b.bit(2, 4),
                lastSessionState = (b.u8(2) shr 2) and 0x03,
                discStatus = b.u8(2) and 0x03,
                firstTrack = b.u8(3),
                sessions = (b.u8(9) shl 8) or b.u8(4),
                firstTrackInLastSession = (b.u8(10) shl 8) or b.u8(5),
                lastTrackInLastSession = (b.u8(11) shl 8) or b.u8(6),
                discIdValid = b.bit(7, 7),
                barCodeValid = b.bit(7, 6),
                unrestrictedUse = b.bit(7, 5),
                bgFormatStatus = b.u8(7) and 0x03,
                discType = b.u8(8),
                discId = b.be32(12),
                lastSessionLeadIn = b.be32(16),
                lastPossibleLeadOut = b.be32(20),
                applicationCode = b.u8(32),
                opcTables = b.u8(33),
            )
        }
    }
}

/** READ TRACK INFORMATION（与 Linux track_information 结构一致，MMC-6 还有字节 32 之后的扩展字段）。 */
class TrackInformation(
    val length: Int,
    val track: Int,
    val session: Int,
    val damage: Boolean,
    val copy: Boolean,
    /** 轨道模式（CD 的 CONTROL 位）：bit 2 = 数据轨。 */
    val trackMode: Int,
    val reservedTrack: Boolean,
    val blank: Boolean,
    val packet: Boolean,
    val fixedPacket: Boolean,
    /** 1 = Mode 1，2 = Mode 2，Fh = 未知。 */
    val dataMode: Int,
    val lraValid: Boolean,
    val nwaValid: Boolean,
    val start: Long,
    val nextWritable: Long,
    val freeBlocks: Long,
    val fixedPacketSize: Long,
    val size: Long,
    val lastRecorded: Long,
) {
    val isData: Boolean get() = (trackMode and 0x04) != 0

    fun dataModeName(): String = when (dataMode) {
        1 -> "Mode 1"
        2 -> "Mode 2"
        0x0F -> "未知"
        else -> "数据模式 $dataMode"
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): TrackInformation? {
            val n = minOf(length, data.size)
            if (n < 28) return null
            val b = Bytes(data, n)
            return TrackInformation(
                length = b.be16(0) + 2,
                track = (b.u8(32) shl 8) or b.u8(2),
                session = (b.u8(33) shl 8) or b.u8(3),
                damage = b.bit(5, 5),
                copy = b.bit(5, 4),
                trackMode = b.u8(5) and 0x0F,
                reservedTrack = b.bit(6, 7),
                blank = b.bit(6, 6),
                packet = b.bit(6, 5),
                fixedPacket = b.bit(6, 4),
                dataMode = b.u8(6) and 0x0F,
                lraValid = b.bit(7, 1),
                nwaValid = b.bit(7, 0),
                start = b.be32(8),
                nextWritable = b.be32(12),
                freeBlocks = b.be32(16),
                fixedPacketSize = b.be32(20),
                size = b.be32(24),
                lastRecorded = if (n >= 32) b.be32(28) else 0,
            )
        }
    }
}

/** READ TOC 格式 0 的一项。地址：MSF=0 时为 LBA（有符号），MSF=1 时已换算成 LBA。 */
class TocEntry(val track: Int, val adr: Int, val control: Int, val lba: Long) {
    val isLeadOut: Boolean get() = track == 0xAA
    val isData: Boolean get() = (control and 0x04) != 0
    val preEmphasis: Boolean get() = !isData && (control and 0x01) != 0
    val copyPermitted: Boolean get() = (control and 0x02) != 0
    val fourChannel: Boolean get() = !isData && (control and 0x08) != 0
}

/** READ TOC 格式 0（TOC）。 */
class Toc(val firstTrack: Int, val lastTrack: Int, val entries: List<TocEntry>) {
    val tracks: List<TocEntry> get() = entries.filter { !it.isLeadOut }
    val leadOut: TocEntry? get() = entries.firstOrNull { it.isLeadOut }

    /** 某轨道到下一轨道（或 lead-out）之间的扇区数。 */
    fun sectorsOf(entry: TocEntry): Long? {
        val i = entries.indexOf(entry)
        if (i < 0 || i + 1 >= entries.size) return null
        return entries[i + 1].lba - entry.lba
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size, msf: Boolean = false): Toc? {
            val n = minOf(length, data.size)
            if (n < 4) return null
            val b = Bytes(data, n)
            val end = minOf(n, b.be16(0) + 2)
            val entries = mutableListOf<TocEntry>()
            var pos = 4
            while (pos + 8 <= end) {
                val address = if (msf) {
                    Msf.toLba(b.u8(pos + 5), b.u8(pos + 6), b.u8(pos + 7))
                } else {
                    b.be32(pos + 4).toInt().toLong() // 有符号
                }
                entries += TocEntry(b.u8(pos + 2), b.u8(pos + 1) shr 4, b.u8(pos + 1) and 0x0F, address)
                pos += 8
            }
            return Toc(b.u8(2), b.u8(3), entries)
        }
    }
}

/** READ TOC 格式 1（多区段信息）。 */
class SessionInfo(
    val firstSession: Int,
    val lastSession: Int,
    val firstTrackInLastSession: Int,
    val lastSessionStartLba: Long,
) {
    companion object {
        fun parse(data: ByteArray, length: Int = data.size): SessionInfo? {
            val n = minOf(length, data.size)
            if (n < 12) return null
            val b = Bytes(data, n)
            return SessionInfo(b.u8(2), b.u8(3), b.u8(6), b.be32(8).toInt().toLong())
        }
    }
}

/** READ TOC 格式 2（完整 TOC，即 lead-in 里的 Q 子通道）的一项，11 字节。 */
class FullTocEntry(
    val session: Int,
    val adr: Int,
    val control: Int,
    val tno: Int,
    val point: Int,
    val min: Int,
    val sec: Int,
    val frame: Int,
    val pmin: Int,
    val psec: Int,
    val pframe: Int,
) {
    val pointLba: Long get() = Msf.toLba(pmin, psec, pframe)
}

class FullToc(val firstSession: Int, val lastSession: Int, val entries: List<FullTocEntry>) {
    val sessions: List<Int> get() = entries.map { it.session }.distinct().sorted()

    private fun point(session: Int, point: Int) = entries.firstOrNull { it.session == session && it.adr == 1 && it.point == point }

    /** 该区段的 lead-out 起点（A2h）。CD-Extra 用它确定第 1 区段最后一首音轨的结尾。 */
    fun leadOutLba(session: Int): Long? = point(session, 0xA2)?.pointLba

    fun firstTrack(session: Int): Int? = point(session, 0xA0)?.pmin

    fun lastTrack(session: Int): Int? = point(session, 0xA1)?.pmin

    /** A0h 的 PSEC：00h = CD-DA/CD-ROM，10h = CD-I，20h = CD-ROM XA。 */
    fun discTypeCode(session: Int): Int? = point(session, 0xA0)?.psec

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): FullToc? {
            val n = minOf(length, data.size)
            if (n < 4) return null
            val b = Bytes(data, n)
            val end = minOf(n, b.be16(0) + 2)
            val entries = mutableListOf<FullTocEntry>()
            var pos = 4
            while (pos + 11 <= end) {
                entries += FullTocEntry(
                    session = b.u8(pos),
                    adr = b.u8(pos + 1) shr 4,
                    control = b.u8(pos + 1) and 0x0F,
                    tno = b.u8(pos + 2),
                    point = b.u8(pos + 3),
                    min = b.u8(pos + 4),
                    sec = b.u8(pos + 5),
                    frame = b.u8(pos + 6),
                    pmin = b.u8(pos + 8),
                    psec = b.u8(pos + 9),
                    pframe = b.u8(pos + 10),
                )
                pos += 11
            }
            return FullToc(b.u8(2), b.u8(3), entries)
        }
    }
}

/** DVD 物理格式信息（READ DISC STRUCTURE 格式 00h），与 Linux dvd_read_physical 的解析一致。 */
class DvdPhysical(
    val bookType: Int,
    val partVersion: Int,
    val discSize: Int,
    val maxRate: Int,
    val layers: Int,
    val oppositeTrackPath: Boolean,
    val layerType: Int,
    val linearDensity: Int,
    val trackDensity: Int,
    val startSector: Long,
    val endSector: Long,
    val endSectorLayer0: Long,
    val bca: Boolean,
) {
    fun bookTypeName(): String = when (bookType) {
        0x0 -> "DVD-ROM"
        0x1 -> "DVD-RAM"
        0x2 -> "DVD-R"
        0x3 -> "DVD-RW"
        0x4 -> "HD DVD-ROM"
        0x5 -> "HD DVD-RAM"
        0x6 -> "HD DVD-R"
        0x9 -> "DVD+RW"
        0xA -> "DVD+R"
        0xD -> "DVD+RW DL"
        0xE -> "DVD+R DL"
        else -> "未知（${Hex.hex(bookType, 1)}h）"
    }

    fun discSizeName(): String = when (discSize) {
        0 -> "120 mm"
        1 -> "80 mm"
        else -> "未知"
    }

    fun maxRateName(): String = when (maxRate) {
        0 -> "2.52 Mbit/s"
        1 -> "5.04 Mbit/s"
        2 -> "10.08 Mbit/s"
        3 -> "20.16 Mbit/s"
        4 -> "30.24 Mbit/s"
        0xF -> "未指定"
        else -> "保留（$maxRate）"
    }

    fun layerTypeName(): String = listOfNotNull(
        "压制".takeIf { layerType and 0x1 != 0 },
        "可记录".takeIf { layerType and 0x2 != 0 },
        "可重写".takeIf { layerType and 0x4 != 0 },
    ).ifEmpty { listOf("未知") }.joinToString("/")

    companion object {
        /** @param data 完整响应（4 字节头 + 层描述符）。 */
        fun parse(data: ByteArray, length: Int = data.size): DvdPhysical? {
            val n = minOf(length, data.size)
            if (n < 21) return null
            val b = Bytes(data, n)
            val base = 4
            return DvdPhysical(
                bookType = b.u8(base) shr 4,
                partVersion = b.u8(base) and 0x0F,
                discSize = b.u8(base + 1) shr 4,
                maxRate = b.u8(base + 1) and 0x0F,
                layers = ((b.u8(base + 2) shr 5) and 0x03) + 1,
                oppositeTrackPath = b.bit(base + 2, 4),
                layerType = b.u8(base + 2) and 0x0F,
                linearDensity = b.u8(base + 3) shr 4,
                trackDensity = b.u8(base + 3) and 0x0F,
                startSector = b.be24(base + 5).toLong(),
                endSector = b.be24(base + 9).toLong(),
                endSectorLayer0 = b.be24(base + 13).toLong(),
                bca = b.bit(base + 16, 7),
            )
        }
    }
}

/** DVD 版权信息（READ DISC STRUCTURE 格式 01h），与 Linux dvd_read_copyright 一致：buf[4] = 保护类型，buf[5] = 地区掩码。 */
class DvdCopyright(val protectionType: Int, val regionMask: Int) {
    /** 盘片可播放的地区（掩码某位为 0 = 该地区可播放；掩码 00h = 全区）。 */
    val regions: List<Int> get() = (0 until 8).filter { (regionMask shr it) and 1 == 0 }.map { it + 1 }

    val isCss: Boolean get() = protectionType == 0x01

    fun protectionName(): String = when (protectionType) {
        0x00 -> "无版权保护"
        0x01 -> "CSS/CPPM 加密"
        0x02 -> "CPRM"
        0x03 -> "AACS（HD DVD）"
        else -> "保护类型 ${Hex.byte(protectionType)}h"
    }

    fun regionText(): String {
        val r = regions
        return when {
            r.size == 8 -> "全区（区域码 0）"
            r.isEmpty() -> "无可播放地区"
            else -> "地区 " + r.joinToString("、")
        }
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): DvdCopyright? {
            if (minOf(length, data.size) < 6) return null
            val b = Bytes(data, 6)
            return DvdCopyright(b.u8(4), b.u8(5))
        }
    }
}

/** BD 盘片信息（READ DISC STRUCTURE 介质类型 1、格式 00h）。只解析 DI 单元的签名和盘片类型标识，其余保留原始数据。 */
class BdDiscInformation(val diFormat: Int, val discTypeId: String?, val raw: ByteArray) {
    fun discTypeName(): String = when (discTypeId) {
        "BDO" -> "BD-ROM（压制盘）"
        "BDR" -> "BD-R"
        "BDW" -> "BD-RE"
        null -> "未知"
        else -> discTypeId
    }

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): BdDiscInformation? {
            val n = minOf(length, data.size)
            if (n < 4 + 12) return null
            val b = Bytes(data, n)
            val di = 4
            if (b.u8(di) != 'D'.code || b.u8(di + 1) != 'I'.code) return BdDiscInformation(-1, null, data.copyOf(n))
            val id = b.ascii(di + 8, 3).takeIf { it.length == 3 && it.all { c -> c.isLetter() } }
            return BdDiscInformation(b.u8(di + 2), id, data.copyOf(n))
        }
    }
}

/** GET PERFORMANCE Type 00h 的一个描述符（读取性能，kB/s，1 kB = 1000 字节）。 */
class PerformanceDescriptor(val startLba: Long, val startKBps: Long, val endLba: Long, val endKBps: Long)

class ReadPerformance(val write: Boolean, val except: Boolean, val descriptors: List<PerformanceDescriptor>) {
    val maxKBps: Long get() = descriptors.maxOfOrNull { maxOf(it.startKBps, it.endKBps) } ?: 0

    companion object {
        /** 1x 的速度（kB/s）：CD 176.4（按 2352 字节/扇区计），DVD 1385，BD 4495.5。 */
        fun speedFactor(kBps: Long, family: MmcNames.DiscFamily): String? {
            val base = when (family) {
                MmcNames.DiscFamily.CD -> 176.4
                MmcNames.DiscFamily.DVD -> 1385.0
                MmcNames.DiscFamily.BD -> 4495.5
                else -> return null
            }
            return "%.1fx".format(kBps / base)
        }

        fun parse(data: ByteArray, length: Int = data.size): ReadPerformance? {
            val n = minOf(length, data.size)
            if (n < 8) return null
            val b = Bytes(data, n)
            val dataLength = b.be32(0)
            val end = minOf(n.toLong(), dataLength + 4).toInt()
            val list = mutableListOf<PerformanceDescriptor>()
            var pos = 8
            while (pos + 16 <= end) {
                list += PerformanceDescriptor(b.be32(pos), b.be32(pos + 4), b.be32(pos + 8), b.be32(pos + 12))
                pos += 16
            }
            return ReadPerformance(b.bit(4, 1), b.bit(4, 0), list)
        }
    }
}
