package io.github.felixchang67.diskandroid.core.fs

import io.github.felixchang67.diskandroid.core.fs.DiscText.be32
import io.github.felixchang67.diskandroid.core.fs.DiscText.le16
import io.github.felixchang67.diskandroid.core.fs.DiscText.le32
import io.github.felixchang67.diskandroid.core.fs.DiscText.u8

/**
 * 卷识别：从 [base] + 16 开始逐扇区看描述符（ECMA-119 的 "CD001" 卷描述符与 ECMA-167 的卷识别序列格式相同：
 * 类型 1 字节 + 标识 5 字节 + 版本 1 字节），收集 ISO 9660 主卷描述符、Joliet 补充卷描述符，以及 UDF 的 NSR02 / NSR03 标记。
 */
class VolumeRecognition(
    val base: Long,
    val primary: IsoDescriptor?,
    val joliet: IsoDescriptor?,
    val bootRecord: Boolean,
    /** 2 = NSR02（UDF 1.02–1.50），3 = NSR03（UDF 2.00 起），null = 没有 UDF 卷识别序列。 */
    val udfNsr: Int?,
) {
    companion object {
        private const val MAX_DESCRIPTORS = 64

        suspend fun scan(src: CachedSectorSource, base: Long): VolumeRecognition {
            var primary: IsoDescriptor? = null
            var joliet: IsoDescriptor? = null
            var boot = false
            var nsr: Int? = null
            var s = base + 16
            var isoTerminated = false
            var inUdfSequence = false
            while (s < base + 16 + MAX_DESCRIPTORS) {
                val b = src.sectorOrNull(s) ?: break
                val id = String(b, 1, 5, Charsets.US_ASCII)
                when (id) {
                    "CD001" -> when (u8(b, 0)) {
                        0 -> boot = true
                        1 -> if (primary == null) primary = IsoDescriptor.parse(b, s, joliet = false)
                        2 -> {
                            val level = IsoDescriptor.jolietLevel(b)
                            if (level > 0 && joliet == null) joliet = IsoDescriptor.parse(b, s, joliet = true)
                        }
                        255 -> isoTerminated = true
                    }
                    "BEA01" -> inUdfSequence = true
                    "NSR02" -> nsr = 2
                    "NSR03" -> nsr = 3
                    "TEA01" -> break
                    "BOOT2", "CDW02" -> Unit
                    else -> if (!inUdfSequence || isoTerminated) break
                }
                s++
            }
            return VolumeRecognition(base, primary, joliet, boot, nsr)
        }
    }
}

/** ISO 9660 主卷描述符（类型 1）或 Joliet 补充卷描述符（类型 2，UCS-2 大端）。 */
class IsoDescriptor(
    val sector: Long,
    val jolietLevel: Int,
    val systemId: String,
    val volumeId: String,
    /** 卷标的原始字节（报告里用来解释乱码）。 */
    val volumeIdRaw: ByteArray,
    val volumeSpaceSize: Long,
    val blockSize: Int,
    val root: IsoDirRecord,
    val volumeSetId: String,
    val publisherId: String,
    val preparerId: String,
    val applicationId: String,
    val createdMillis: Long?,
    val modifiedMillis: Long?,
) {
    val isJoliet: Boolean get() = jolietLevel > 0

    companion object {
        /** Joliet 的转义序列（字节 88 起 32 字节）："%/@" 级别 1，"%/C" 级别 2，"%/E" 级别 3。 */
        fun jolietLevel(b: ByteArray): Int {
            for (i in 88 until 88 + 30) {
                if (b[i] == '%'.code.toByte() && b[i + 1] == '/'.code.toByte()) {
                    when (b[i + 2].toInt().toChar()) {
                        '@' -> return 1
                        'C' -> return 2
                        'E' -> return 3
                    }
                }
            }
            return 0
        }

        fun parse(b: ByteArray, sector: Long, joliet: Boolean): IsoDescriptor? {
            val root = IsoDirRecord.parse(b, 156) ?: return null
            val text: (Int, Int) -> String = if (joliet) { o, l -> DiscText.ucs2(b, o, l) } else { o, l -> DiscText.field(b, o, l) }
            val blockSize = le16(b, 128)
            return IsoDescriptor(
                sector = sector,
                jolietLevel = if (joliet) jolietLevel(b) else 0,
                systemId = text(8, 32),
                volumeId = text(40, 32),
                volumeIdRaw = b.copyOfRange(40, 72),
                volumeSpaceSize = le32(b, 80).takeIf { it == be32(b, 84) || be32(b, 84) == 0L } ?: le32(b, 80),
                blockSize = if (blockSize == 0) SECTOR_SIZE else blockSize,
                root = root,
                volumeSetId = text(190, 128),
                publisherId = text(318, 128),
                preparerId = text(446, 128),
                applicationId = text(574, 128),
                createdMillis = DiscText.isoVolumeTime(b, 813),
                modifiedMillis = DiscText.isoVolumeTime(b, 830),
            )
        }
    }
}

/** ISO 9660 目录记录。 */
class IsoDirRecord(
    val extent: Long,
    val dataLength: Long,
    val flags: Int,
    val xarLength: Int,
    val recordedMillis: Long?,
    val rawName: ByteArray,
    val systemUse: ByteArray,
    val fileUnitSize: Int,
    val interleaveGap: Int,
) {
    val isDirectory: Boolean get() = flags and 0x02 != 0
    val isHidden: Boolean get() = flags and 0x01 != 0
    val isAssociated: Boolean get() = flags and 0x04 != 0
    val isMultiExtent: Boolean get() = flags and 0x80 != 0
    val isSelf: Boolean get() = rawName.size == 1 && rawName[0].toInt() == 0
    val isParent: Boolean get() = rawName.size == 1 && rawName[0].toInt() == 1

    /** 数据开始的扇区（扩展属性记录占用区段开头的几个逻辑块）。 */
    val dataStart: Long get() = extent + xarLength

    companion object {
        fun parse(b: ByteArray, off: Int): IsoDirRecord? {
            if (off + 34 > b.size) return null
            val len = u8(b, off)
            if (len < 34 || off + len > b.size) return null
            val lenFi = u8(b, off + 32)
            if (33 + lenFi > len) return null
            val name = b.copyOfRange(off + 33, off + 33 + lenFi)
            val su = 33 + lenFi + (if (lenFi % 2 == 0) 1 else 0)
            return IsoDirRecord(
                extent = le32(b, off + 2),
                dataLength = le32(b, off + 10),
                flags = u8(b, off + 25),
                xarLength = u8(b, off + 1),
                recordedMillis = DiscText.isoRecordTime(b, off + 18),
                rawName = name,
                systemUse = if (su < len) b.copyOfRange(off + su, off + len) else ByteArray(0),
                fileUnitSize = u8(b, off + 26),
                interleaveGap = u8(b, off + 27),
            )
        }
    }
}

/**
 * ISO 9660 文件系统（用 Joliet 补充卷描述符时文件名是 UCS-2；没有 Joliet 时用 Rock Ridge 的 NM 长文件名，
 * 两者都没有就用主卷描述符里的 8.3 文件名，非 ASCII 字节按 UTF-8 / Shift-JIS 猜）。
 * 超过 4 GB 的文件由多个同名目录记录（多区段标志）拼成。
 */
class IsoFileSystem private constructor(
    private val src: CachedSectorSource,
    private val vd: IsoDescriptor,
    private val suspSkip: Int?,
) : DiscFileSystem {
    private class Handle(val records: List<IsoDirRecord>)

    private val rockRidge = suspSkip != null && !vd.isJoliet

    override val kind: String = when {
        vd.isJoliet -> "ISO 9660 + Joliet"
        rockRidge -> "ISO 9660 + Rock Ridge"
        else -> "ISO 9660"
    }
    override val label: String? = vd.volumeId.ifBlank { null }
    override val root = DiscEntry("", "/", true, vd.root.dataLength, vd.root.recordedMillis, false, Handle(listOf(vd.root)))

    override suspend fun list(dir: DiscEntry): List<DiscEntry> {
        require(dir.isDirectory)
        val rec = (dir.handle as Handle).records.first()
        if (rec.dataLength > MAX_DIRECTORY_BYTES) throw DiscFsException("目录数据太大（${rec.dataLength} 字节），可能是损坏的目录记录")
        val data = src.bytes(rec.dataStart, rec.dataLength.toInt())
        val out = mutableListOf<DiscEntry>()
        var pending: MutableList<IsoDirRecord>? = null
        var pos = 0
        while (pos < data.size) {
            val len = u8(data, pos)
            if (len == 0) {
                // 目录记录不跨扇区：本扇区剩下的是填充
                pos = (pos / SECTOR_SIZE + 1) * SECTOR_SIZE
                continue
            }
            val r = IsoDirRecord.parse(data, pos) ?: break
            pos += len
            if (r.isSelf || r.isParent || r.isAssociated) continue
            val rr = if (rockRidge) rockRidge(r) else null
            if (rr?.relocated == true) continue
            if (r.isMultiExtent) {
                (pending ?: mutableListOf<IsoDirRecord>().also { pending = it }) += r
                continue
            }
            val parts = pending?.let { it + r } ?: listOf(r)
            pending = null
            var records = parts
            var isDir = r.isDirectory
            rr?.childLink?.let { link ->
                // Rock Ridge 深层目录重定位：真正的目录在 CL 指向的位置，它的 "." 记录给出目录大小
                val self = IsoDirRecord.parse(src.sector(link), 0)
                if (self != null) {
                    records = listOf(self)
                    isDir = true
                }
            }
            val name = rr?.name ?: displayName(r.rawName, isDir)
            out += DiscEntry(
                name, DiscEntry.childPath(dir.path, name), isDir, records.sumOf { it.dataLength },
                r.recordedMillis, r.isHidden, Handle(records),
            )
        }
        return sortEntries(out)
    }

    override suspend fun runs(file: DiscEntry): List<DataRun> {
        val out = mutableListOf<DataRun>()
        var offset = 0L
        for (r in (file.handle as Handle).records) {
            if (r.dataLength > 0) out += DataRun(offset, r.dataLength, r.dataStart)
            offset += r.dataLength
        }
        return out
    }

    override suspend fun open(file: DiscEntry, cached: Boolean): DiscFileReader =
        DiscFileReader(src, file.size, runs(file), cache = if (cached) src else null)

    private fun displayName(raw: ByteArray, isDir: Boolean): String {
        var name = if (vd.isJoliet) DiscText.ucs2(raw, 0, raw.size) else DiscText.decode(raw)
        if (!isDir) {
            val semi = name.lastIndexOf(';')
            if (semi > 0 && name.substring(semi + 1).all { it.isDigit() }) name = name.substring(0, semi)
            if (name.endsWith('.') && name.length > 1) name = name.dropLast(1)
        }
        return name.ifEmpty { "_" }
    }

    // ---------------- Rock Ridge（SUSP）----------------

    private class RockRidgeInfo(val name: String?, val childLink: Long?, val relocated: Boolean)

    private suspend fun rockRidge(r: IsoDirRecord): RockRidgeInfo? {
        val skip = suspSkip ?: return null
        var area = r.systemUse
        var pos = skip
        val nameBytes = java.io.ByteArrayOutputStream()
        var haveName = false
        var childLink: Long? = null
        var relocated = false
        var hops = 0
        while (true) {
            var next: Triple<Long, Int, Int>? = null
            while (pos + 4 <= area.size) {
                val sig = String(area, pos, 2, Charsets.US_ASCII)
                val len = u8(area, pos + 2)
                if (len < 4 || pos + len > area.size) break
                when (sig) {
                    "NM" -> {
                        val flags = u8(area, pos + 4)
                        if (flags and 0x06 == 0) {
                            nameBytes.write(area, pos + 5, len - 5)
                            haveName = true
                        }
                    }
                    "CL" -> childLink = le32(area, pos + 4)
                    "RE" -> relocated = true
                    "CE" -> next = Triple(le32(area, pos + 4), le32(area, pos + 12).toInt(), le32(area, pos + 20).toInt())
                    "ST" -> break
                }
                pos += len
            }
            val ce = next ?: break
            if (++hops > 8 || ce.third <= 0 || ce.second < 0 || ce.second + ce.third > SECTOR_SIZE * 4) break
            area = src.bytes(ce.first, ce.second + ce.third).copyOfRange(ce.second, ce.second + ce.third)
            pos = 0
        }
        val name = if (haveName) DiscText.decode(nameBytes.toByteArray()).takeIf { it.isNotEmpty() } else null
        return RockRidgeInfo(name, childLink, relocated)
    }

    companion object {
        private const val MAX_DIRECTORY_BYTES = 32L shl 20

        /** 打开 [vd] 描述的目录树；用主卷描述符时检查根目录 "." 记录里有没有 SUSP 的 SP 项（Rock Ridge）。 */
        suspend fun open(src: CachedSectorSource, vd: IsoDescriptor): IsoFileSystem {
            var skip: Int? = null
            if (!vd.isJoliet) {
                val first = src.sector(vd.root.dataStart)
                val self = IsoDirRecord.parse(first, 0)
                val su = self?.systemUse
                if (su != null && su.size >= 7 && su[0] == 'S'.code.toByte() && su[1] == 'P'.code.toByte() &&
                    (su[4].toInt() and 0xFF) == 0xBE && (su[5].toInt() and 0xFF) == 0xEF
                ) {
                    skip = u8(su, 6)
                }
            }
            return IsoFileSystem(src, vd, skip)
        }
    }
}
