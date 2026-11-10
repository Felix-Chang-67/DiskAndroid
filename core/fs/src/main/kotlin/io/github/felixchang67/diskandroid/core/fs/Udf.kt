package io.github.felixchang67.diskandroid.core.fs

import io.github.felixchang67.diskandroid.core.fs.DiscText.le16
import io.github.felixchang67.diskandroid.core.fs.DiscText.le32
import io.github.felixchang67.diskandroid.core.fs.DiscText.le64
import io.github.felixchang67.diskandroid.core.fs.DiscText.u8

/*
 * UDF（ECMA-167 + OSTA UDF 1.02–2.60）只读实现，覆盖光盘上实际会遇到的几种结构：
 * - 物理分区（DVD-Video 的 UDF 1.02 桥接、BD-ROM、mkisofs / ImgBurn 做的盘）；
 * - 元数据分区（UDF 2.50 起，BD-ROM / BD-RE / BD 录像机）；
 * - 备用表（sparing）分区（DVD-RW / CD-RW 的包写入）；
 * - 虚拟分区 + VAT（CD-R / DVD-R / BD-R 的增量刻录，例如 Windows 的"类似于 USB 闪存驱动器"）。
 * 描述符的字节偏移见 ECMA-167 第 3 / 4 部分和 UDF 2.60 第 2 章。
 */

internal object UdfTag {
    const val PVD = 1
    const val AVDP = 2
    const val VDP = 3
    const val PD = 5
    const val LVD = 6
    const val TD = 8
    const val FSD = 256
    const val FID = 257
    const val AED = 258
    const val IE = 259
    const val FE = 261
    const val EFE = 266

    /** 描述符标签的标识；校验和不对返回 null。全零扇区（标识 0、校验和 0）也返回 null。 */
    fun id(b: ByteArray, off: Int = 0): Int? {
        if (b.size < off + 16) return null
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += b[off + i].toInt() and 0xFF
        if ((sum and 0xFF) != u8(b, off + 4)) return null
        if (sum == 0) return null
        return le16(b, off)
    }

    fun location(b: ByteArray, off: Int = 0): Long = le32(b, off + 12)
}

/** 逻辑块地址：分区引用号（逻辑卷描述符里分区映射表的下标）+ 分区内的块号。 */
data class LbAddr(val block: Long, val partition: Int)

/** 一段分配：长度（字节）、类型（0 已记录，1 已分配未记录，2 未分配）、位置（类型 1 / 2 没有）。 */
internal class UdfExtent(val length: Long, val type: Int, val location: LbAddr?)

internal sealed class UdfMap {
    abstract val partitionNumber: Int

    /** 分区内连续 [count] 块对应的盘上扇区段（扇区, 块数）。 */
    abstract fun sectors(block: Long, count: Long): List<Pair<Long, Long>>

    fun sector(block: Long): Long = sectors(block, 1).first().first

    abstract val description: String

    class Physical(val pd: UdfPartition) : UdfMap() {
        override val partitionNumber get() = pd.number
        override val description get() = "物理"
        override fun sectors(block: Long, count: Long): List<Pair<Long, Long>> {
            if (block < 0 || block + count > pd.length) throw DiscFsException("块 $block+$count 超出分区 ${pd.number}（${pd.length} 块）")
            return listOf(pd.start + block to count)
        }
    }

    /** 包写入：坏的包被重新映射到备用区（备用表里的原位置是分区内块号，新位置是盘上扇区号）。 */
    class Sparable(val pd: UdfPartition, val packetLength: Int, val remap: Map<Long, Long>) : UdfMap() {
        override val partitionNumber get() = pd.number
        override val description get() = "备用表（包长 $packetLength，已重映射 ${remap.size} 个包）"
        override fun sectors(block: Long, count: Long): List<Pair<Long, Long>> {
            if (block < 0 || block + count > pd.length) throw DiscFsException("块 $block+$count 超出分区 ${pd.number}")
            if (remap.isEmpty()) return listOf(pd.start + block to count)
            val out = mutableListOf<Pair<Long, Long>>()
            var b = block
            val end = block + count
            while (b < end) {
                val packet = b - b % packetLength
                val n = minOf(packet + packetLength, end) - b
                val s = remap[packet]?.let { it + (b - packet) } ?: (pd.start + b)
                merge(out, s, n)
                b += n
            }
            return out
        }
    }

    /** 增量刻录：虚拟块号经 VAT 换成物理分区里的块号。 */
    class Virtual(val physical: Physical, val vat: LongArray) : UdfMap() {
        override val partitionNumber get() = physical.partitionNumber
        override val description get() = "虚拟（VAT，${vat.size} 项）"
        override fun sectors(block: Long, count: Long): List<Pair<Long, Long>> {
            val out = mutableListOf<Pair<Long, Long>>()
            for (b in block until block + count) {
                if (b < 0 || b >= vat.size) throw DiscFsException("虚拟块 $b 超出 VAT（${vat.size} 项）")
                val p = vat[b.toInt()]
                if (p == 0xFFFFFFFFL) throw DiscFsException("虚拟块 $b 未使用")
                merge(out, physical.sector(p), 1)
            }
            return out
        }
    }

    /** 元数据分区：块号是元数据文件里的偏移，元数据文件的各段在物理分区里。 */
    class Metadata(val physical: UdfMap, val extents: List<Pair<Long, Long>>, val mirror: Boolean) : UdfMap() {
        override val partitionNumber get() = physical.partitionNumber
        override val description get() = "元数据（${extents.size} 段${if (mirror) "，用的是镜像" else ""}）"
        override fun sectors(block: Long, count: Long): List<Pair<Long, Long>> {
            val out = mutableListOf<Pair<Long, Long>>()
            var b = block
            var left = count
            var base = 0L
            for ((start, len) in extents) {
                if (left == 0L) break
                if (b < base + len) {
                    val inExt = b - base
                    val n = minOf(len - inExt, left)
                    for ((s, c) in physical.sectors(start + inExt, n)) merge(out, s, c)
                    b += n
                    left -= n
                }
                base += len
            }
            if (left > 0) throw DiscFsException("元数据块 $block+$count 超出元数据文件")
            return out
        }
    }

    companion object {
        fun merge(out: MutableList<Pair<Long, Long>>, sector: Long, count: Long) {
            val last = out.lastOrNull()
            if (last != null && last.first + last.second == sector) out[out.size - 1] = last.first to last.second + count else out += sector to count
        }
    }
}

/** 分区描述符：分区号、起始扇区、长度（块）、访问类型。 */
class UdfPartition(val number: Int, val start: Long, val length: Long, val accessType: Int, val contents: String) {
    val end: Long get() = start + length

    fun accessName(): String = when (accessType) {
        1 -> "只读"
        2 -> "一次写入"
        3 -> "可重写（需擦除）"
        4 -> "可覆写"
        else -> "访问类型 $accessType"
    }
}

/** UDF 卷：从锚点、卷描述符序列、逻辑卷描述符和文件集描述符读出的信息。 */
class UdfVolume internal constructor(
    /** UDF 版本（BCD，0x0250 = 2.50）。 */
    val revision: Int,
    val logicalVolumeId: String,
    val volumeId: String,
    val fileSetId: String,
    val partitions: List<UdfPartition>,
    val anchors: List<Long>,
    /** 已知结构（分区、卷描述符序列、完整性序列、锚点）覆盖到的扇区（不含）。 */
    val structuresEnd: Long,
    val recordedMillis: Long?,
    internal val maps: List<UdfMap>,
    internal val rootIcb: LbAddr,
    /** VAT 头里的逻辑卷标识（UDF 2.00 起的 VAT 可以改卷标，优先于逻辑卷描述符）。 */
    val vatVolumeId: String?,
) {
    val revisionText: String get() = "%x.%02x".format(revision shr 8, revision and 0xFF)
    val label: String? get() = (vatVolumeId ?: logicalVolumeId).ifBlank { null } ?: volumeId.ifBlank { null }
    val mapDescriptions: List<String> get() = maps.map { it.description }
    val hasMetadataPartition: Boolean get() = maps.any { it is UdfMap.Metadata }
    val hasVat: Boolean get() = maps.any { it is UdfMap.Virtual }
    val hasSparing: Boolean get() = maps.any { it is UdfMap.Sparable }

    fun features(): String = listOfNotNull(
        "元数据分区".takeIf { hasMetadataPartition },
        "VAT 增量刻录".takeIf { hasVat },
        "备用表".takeIf { hasSparing },
    ).joinToString("、")
}

/** UDF 的一个文件项（File Entry / Extended File Entry）。 */
internal class UdfNode(
    val icb: LbAddr,
    val fileType: Int,
    val size: Long,
    val modifiedMillis: Long?,
    val extents: List<UdfExtent>,
    /** 数据直接嵌在文件项里（小目录常见）。 */
    val embedded: ByteArray?,
) {
    val isDirectory: Boolean get() = fileType == 4
}

internal class UdfReader(private val src: CachedSectorSource) {

    /** 读一个 ICB（文件项）。策略 4096 的 ICB 后面可能跟着间接项，指向更新的版本。 */
    suspend fun node(icb: LbAddr, maps: List<UdfMap>): UdfNode {
        var loc = icb
        var b = src.sector(mapOf(maps, loc).sector(loc.block))
        var id = UdfTag.id(b)
        var hops = 0
        while (id == UdfTag.IE && hops++ < 64) {
            loc = longAd(b, 36).location ?: throw DiscFsException("间接项没有目标")
            b = src.sector(mapOf(maps, loc).sector(loc.block))
            id = UdfTag.id(b)
        }
        if (id != UdfTag.FE && id != UdfTag.EFE) throw DiscFsException("块 ${loc.block}（分区 ${loc.partition}）不是文件项（标签 ${id ?: "无效"}）")
        if (le16(b, 20) == 4096 && hops == 0) {
            // 策略 4096：同一个 ICB 的下一块如果是间接项，就跟过去（最多 64 次）
            var next = LbAddr(loc.block + 1, loc.partition)
            var chain = 0
            while (chain++ < 64) {
                val nb = runCatching { src.sector(mapOf(maps, next).sector(next.block)) }.getOrNull() ?: break
                if (UdfTag.id(nb) != UdfTag.IE) break
                val target = longAd(nb, 36).location ?: break
                val tb = runCatching { src.sector(mapOf(maps, target).sector(target.block)) }.getOrNull() ?: break
                val tid = UdfTag.id(tb)
                if (tid != UdfTag.FE && tid != UdfTag.EFE) break
                loc = target
                b = tb
                id = tid
                next = LbAddr(target.block + 1, target.partition)
            }
        }
        return parseEntry(b, id!!, loc, maps)
    }

    suspend fun parseEntry(b: ByteArray, id: Int, loc: LbAddr, maps: List<UdfMap>): UdfNode {
        val fileType = u8(b, 27)
        val adType = le16(b, 34) and 7
        val size = le64(b, 56)
        val extended = id == UdfTag.EFE
        val mtime = DiscText.udfTime(b, if (extended) 92 else 84)
        val lEa = le32(b, if (extended) 208 else 168).toInt()
        val lAd = le32(b, if (extended) 212 else 172).toInt()
        val adStart = (if (extended) 216 else 176) + lEa
        if (lEa < 0 || lAd < 0 || adStart + lAd > b.size) throw DiscFsException("文件项的分配描述符越界（扩展属性 $lEa，分配描述符 $lAd）")
        if (adType == 3) return UdfNode(loc, fileType, size, mtime, emptyList(), b.copyOfRange(adStart, adStart + minOf(lAd.toLong(), size).toInt()))
        val extents = allocationDescriptors(b, adStart, lAd, adType, loc.partition, maps)
        return UdfNode(loc, fileType, size, mtime, extents, null)
    }

    /** 解析分配描述符（short_ad / long_ad / ext_ad），遇到"下一段描述符"（类型 3）就读分配扩展描述符接着解析。 */
    private suspend fun allocationDescriptors(b: ByteArray, start: Int, length: Int, adType: Int, partition: Int, maps: List<UdfMap>): List<UdfExtent> {
        val out = mutableListOf<UdfExtent>()
        var buf = b
        var pos = start
        var end = start + length
        var hops = 0
        val size = when (adType) {
            0 -> 8
            1 -> 16
            2 -> 20
            else -> throw DiscFsException("不认识的分配描述符类型 $adType")
        }
        while (pos + size <= end) {
            val raw = le32(buf, pos)
            val len = raw and 0x3FFFFFFF
            val type = (raw ushr 30).toInt()
            if (len == 0L) break
            val loc = when (adType) {
                0 -> LbAddr(le32(buf, pos + 4), partition)
                1 -> LbAddr(le32(buf, pos + 4), le16(buf, pos + 8))
                else -> LbAddr(le32(buf, pos + 12), le16(buf, pos + 16))
            }
            if (type == 3) {
                if (++hops > 1024) throw DiscFsException("分配扩展描述符链太长")
                val aed = src.sector(mapOf(maps, loc).sector(loc.block))
                if (UdfTag.id(aed) != UdfTag.AED) throw DiscFsException("分配扩展描述符无效（块 ${loc.block}）")
                val l = le32(aed, 20).toInt()
                if (l < 0 || 24 + l > aed.size) throw DiscFsException("分配扩展描述符长度无效")
                buf = aed
                pos = 24
                end = 24 + l
                continue
            }
            out += UdfExtent(len, type, if (type == 0) loc else null)
            pos += size
        }
        return out
    }

    /** 文件内容在盘上的各段。 */
    fun runs(node: UdfNode, maps: List<UdfMap>): List<DataRun> {
        val out = mutableListOf<DataRun>()
        var offset = 0L
        for (e in node.extents) {
            if (offset >= node.size) break
            val len = minOf(e.length, node.size - offset)
            val loc = e.location
            if (loc == null) {
                out += DataRun(offset, len, null)
            } else {
                val blocks = (len + SECTOR_SIZE - 1) / SECTOR_SIZE
                var o = offset
                var left = len
                for ((sector, count) in mapOf(maps, loc).sectors(loc.block, blocks)) {
                    val n = minOf(count * SECTOR_SIZE, left)
                    val last = out.lastOrNull()
                    if (last?.sector != null && last.fileOffset + last.length == o && last.length % SECTOR_SIZE == 0L &&
                        last.sector + last.length / SECTOR_SIZE == sector
                    ) {
                        out[out.size - 1] = DataRun(last.fileOffset, last.length + n, last.sector)
                    } else {
                        out += DataRun(o, n, sector)
                    }
                    o += n
                    left -= n
                }
            }
            offset += e.length
        }
        return out
    }

    /** 读出一个文件项的全部数据（目录、VAT、元数据之类不大的东西）。 */
    suspend fun data(node: UdfNode, maps: List<UdfMap>, limit: Long = 64L shl 20): ByteArray {
        node.embedded?.let { return it }
        if (node.size > limit) throw DiscFsException("数据太大（${node.size} 字节）")
        val out = ByteArray(node.size.toInt())
        for (r in runs(node, maps)) {
            val s = r.sector ?: continue
            val bytes = src.bytes(s, r.length.toInt())
            System.arraycopy(bytes, 0, out, r.fileOffset.toInt(), r.length.toInt())
        }
        return out
    }

    companion object {
        fun mapOf(maps: List<UdfMap>, loc: LbAddr): UdfMap =
            maps.getOrNull(loc.partition) ?: throw DiscFsException("分区引用号 ${loc.partition} 不存在（共 ${maps.size} 个分区映射）")

        fun longAd(b: ByteArray, off: Int): UdfExtent {
            val raw = le32(b, off)
            val len = raw and 0x3FFFFFFF
            return UdfExtent(len, (raw ushr 30).toInt(), LbAddr(le32(b, off + 4), le16(b, off + 8)))
        }
    }
}

/** 打开 UDF 卷：找锚点 → 卷描述符序列 → 分区映射（含 VAT / 元数据 / 备用表）→ 文件集描述符 → 根目录。 */
internal object UdfProbe {
    private const val MAX_VDS_SECTORS = 64

    suspend fun open(src: CachedSectorSource, base: Long, lastWritten: List<Long>): UdfVolume {
        val reader = UdfReader(src)
        val count = src.sectorCount
        val anchorCandidates = buildList {
            add(base + 256)
            if (base != 0L) add(256L)
            lastWritten.forEach { add(it); add(it - 256) }
            if (count != null) {
                add(count - 1)
                add(count - 257)
            }
        }.filter { it >= 0 && (count == null || it < count) }.distinct()
        val anchors = mutableListOf<Long>()
        var avdp: ByteArray? = null
        for (s in anchorCandidates) {
            val b = src.sectorOrNull(s) ?: continue
            if (UdfTag.id(b) == UdfTag.AVDP && (UdfTag.location(b) == s || UdfTag.location(b) == s - base)) {
                anchors += s
                if (avdp == null) avdp = b
                if (anchors.size >= 1 && s == base + 256) break
            }
        }
        val anchor = avdp ?: throw DiscFsException("找不到 UDF 锚点（LBA 256 / 盘尾）")
        val mainLen = le32(anchor, 16)
        val mainLoc = le32(anchor, 20)
        val resLen = le32(anchor, 24)
        val resLoc = le32(anchor, 28)

        var vds = readVds(src, mainLoc, mainLen)
        if (vds.lvd == null || vds.pds.isEmpty()) vds = readVds(src, resLoc, resLen)
        val lvd = vds.lvd ?: throw DiscFsException("卷描述符序列里没有逻辑卷描述符")
        if (vds.pds.isEmpty()) throw DiscFsException("卷描述符序列里没有分区描述符")

        val blockSize = le32(lvd, 212)
        if (blockSize != SECTOR_SIZE.toLong()) throw DiscFsException("逻辑块大小是 $blockSize，不是 2048")
        val revision = le16(lvd, 240)
        val lvi = DiscText.dstring(lvd, 84, 128)
        val fsdAd = UdfReader.longAd(lvd, 248)
        val nMaps = le32(lvd, 268).toInt()
        val mapTableLength = le32(lvd, 264).toInt()
        val lvidLen = le32(lvd, 432)
        val lvidLoc = le32(lvd, 436)
        if (nMaps !in 1..8 || 440 + mapTableLength > lvd.size) throw DiscFsException("分区映射表无效（$nMaps 个，$mapTableLength 字节）")

        // ---- 分区映射：先建物理 / 备用表映射，再建依赖它们的虚拟 / 元数据映射 ----
        class Pending(val index: Int, val kind: String, val partitionNumber: Int, val raw: ByteArray)
        val pendings = mutableListOf<Pending>()
        var off = 440
        repeat(nMaps) { i ->
            val type = u8(lvd, off)
            val len = u8(lvd, off + 1)
            if (len < 6 || off + len > 440 + mapTableLength) throw DiscFsException("分区映射 $i 长度无效")
            val raw = lvd.copyOfRange(off, off + len)
            pendings += when (type) {
                1 -> Pending(i, "type1", le16(raw, 4), raw)
                2 -> {
                    val ident = String(raw, 5, 23, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    Pending(i, ident, le16(raw, 38), raw)
                }
                else -> throw DiscFsException("不认识的分区映射类型 $type")
            }
            off += len
        }
        val maps = arrayOfNulls<UdfMap>(nMaps)
        fun pd(number: Int) = vds.pds[number] ?: throw DiscFsException("找不到分区号 $number 的分区描述符")
        for (p in pendings) {
            when (p.kind) {
                "type1" -> maps[p.index] = UdfMap.Physical(pd(p.partitionNumber))
                "*UDF Sparable Partition" -> maps[p.index] = sparable(src, pd(p.partitionNumber), p.raw)
            }
        }
        fun physicalFor(number: Int): UdfMap = maps.firstOrNull { (it is UdfMap.Physical || it is UdfMap.Sparable) && it.partitionNumber == number }
            ?: UdfMap.Physical(pd(number))
        var vatVolumeId: String? = null
        for (p in pendings) {
            when (p.kind) {
                "type1", "*UDF Sparable Partition" -> Unit
                "*UDF Virtual Partition" -> {
                    val phys = physicalFor(p.partitionNumber) as? UdfMap.Physical ?: UdfMap.Physical(pd(p.partitionNumber))
                    val vat = findVat(src, reader, phys, lastWritten, count, revision)
                        ?: throw DiscFsException("这是增量刻录（VAT）的 UDF，但在最后写入的扇区附近找不到 VAT")
                    maps[p.index] = UdfMap.Virtual(phys, vat.first)
                    vatVolumeId = vat.second
                }
                "*UDF Metadata Partition" -> maps[p.index] = metadata(src, reader, physicalFor(p.partitionNumber), p.raw)
                else -> throw DiscFsException("不支持的分区映射「${p.kind}」")
            }
        }
        val mapList = maps.map { it ?: throw DiscFsException("分区映射没有建立") }

        // ---- 文件集描述符 → 根目录 ----
        val fsdLoc = fsdAd.location ?: throw DiscFsException("逻辑卷描述符没有文件集描述符位置")
        val fsd = src.sector(UdfReader.mapOf(mapList, fsdLoc).sector(fsdLoc.block))
        if (UdfTag.id(fsd) != UdfTag.FSD) throw DiscFsException("文件集描述符无效（块 ${fsdLoc.block}，分区 ${fsdLoc.partition}）")
        val root = UdfReader.longAd(fsd, 400).location ?: throw DiscFsException("文件集描述符没有根目录")

        val pvdId = vds.pvd?.let { DiscText.dstring(it, 24, 32) } ?: ""
        val recorded = vds.pvd?.let { DiscText.udfTime(it, 376) }
        var end = 0L
        vds.pds.values.forEach { end = maxOf(end, it.end) }
        end = maxOf(end, mainLoc + (mainLen + SECTOR_SIZE - 1) / SECTOR_SIZE, resLoc + (resLen + SECTOR_SIZE - 1) / SECTOR_SIZE)
        if (lvidLen > 0) end = maxOf(end, lvidLoc + (lvidLen + SECTOR_SIZE - 1) / SECTOR_SIZE)
        anchors.forEach { end = maxOf(end, it + 1) }
        return UdfVolume(
            revision, lvi, pvdId, DiscText.dstring(fsd, 304, 32), vds.pds.values.sortedBy { it.number }, anchors.sorted(),
            end, recorded, mapList, root, vatVolumeId,
        )
    }

    private class Vds(val pvd: ByteArray?, val lvd: ByteArray?, val pds: Map<Int, UdfPartition>)

    private suspend fun readVds(src: CachedSectorSource, location: Long, length: Long): Vds {
        var pvd: ByteArray? = null
        var pvdSeq = -1L
        var lvd: ByteArray? = null
        var lvdSeq = -1L
        val pds = mutableMapOf<Int, UdfPartition>()
        val pdSeq = mutableMapOf<Int, Long>()
        var loc = location
        var left = minOf((length + SECTOR_SIZE - 1) / SECTOR_SIZE, MAX_VDS_SECTORS.toLong())
        var hops = 0
        loop@ while (left > 0) {
            val b = src.sectorOrNull(loc) ?: break
            val seq = le32(b, 16)
            when (UdfTag.id(b)) {
                UdfTag.PVD -> if (seq > pvdSeq) {
                    pvd = b
                    pvdSeq = seq
                }
                UdfTag.LVD -> if (seq > lvdSeq) {
                    lvd = b
                    lvdSeq = seq
                }
                UdfTag.PD -> {
                    val number = le16(b, 22)
                    if (seq > (pdSeq[number] ?: -1L)) {
                        pdSeq[number] = seq
                        val contents = String(b, 25, 23, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                        pds[number] = UdfPartition(number, le32(b, 188), le32(b, 192), le32(b, 184).toInt(), contents)
                    }
                }
                UdfTag.VDP -> {
                    if (++hops > 8) break@loop
                    left = minOf((le32(b, 20) + SECTOR_SIZE - 1) / SECTOR_SIZE, MAX_VDS_SECTORS.toLong())
                    loc = le32(b, 24)
                    continue@loop
                }
                UdfTag.TD, null -> break@loop
                else -> Unit
            }
            loc++
            left--
        }
        return Vds(pvd, lvd, pds)
    }

    /** 备用表：映射里给出几份表的位置（盘上扇区），取第一份有效的。 */
    private suspend fun sparable(src: CachedSectorSource, pd: UdfPartition, raw: ByteArray): UdfMap.Sparable {
        val packet = le16(raw, 40)
        val tables = u8(raw, 42)
        val tableSize = le32(raw, 44)
        if (packet <= 0) throw DiscFsException("备用表分区的包长无效")
        var best: Map<Long, Long>? = null
        var bestSeq = -1L
        for (i in 0 until minOf(tables, 4)) {
            if (48 + 4 * i + 4 > raw.size) break
            val loc = le32(raw, 48 + 4 * i)
            val bytes = runCatching { src.bytes(loc, maxOf(tableSize, 56L).coerceAtMost(64L * SECTOR_SIZE).toInt()) }.getOrNull() ?: continue
            // 备用表的标签标识是 0（校验和照常）
            if (UdfTag.id(bytes) != 0) continue
            val ident = String(bytes, 17, 23, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            if (ident != "*UDF Sparing Table") continue
            val entries = le16(bytes, 48)
            val seq = le32(bytes, 52)
            if (seq <= bestSeq) continue
            val map = mutableMapOf<Long, Long>()
            for (e in 0 until entries) {
                val p = 56 + 8 * e
                if (p + 8 > bytes.size) break
                val orig = le32(bytes, p)
                if (orig >= 0xFFFFFFF0L) continue
                map[orig] = le32(bytes, p + 4)
            }
            best = map
            bestSeq = seq
        }
        return UdfMap.Sparable(pd, packet, best ?: emptyMap())
    }

    /**
     * 找 VAT：VAT 的文件项写在最后写入的那个块（物理分区里），UDF 2.00 起文件类型 248，
     * UDF 1.50 是类型 0 且数据末尾有 "*UDF Virtual Alloc Tbl"。和 Linux 一样，候选位置往前再试 3 块。
     * 返回（VAT 表，VAT 头里的卷标）。
     */
    private suspend fun findVat(
        src: CachedSectorSource, reader: UdfReader, phys: UdfMap.Physical, lastWritten: List<Long>, count: Long?, revision: Int,
    ): Pair<LongArray, String?>? {
        val starts = (lastWritten + listOfNotNull(count?.minus(1))).distinct()
        val tried = mutableSetOf<Long>()
        for (start in starts) {
            for (s in start downTo maxOf(phys.pd.start, start - 3)) {
                if (!tried.add(s)) continue
                val b = src.sectorOrNull(s) ?: continue
                val id = UdfTag.id(b)
                if (id != UdfTag.FE && id != UdfTag.EFE) continue
                val block = s - phys.pd.start
                val node = runCatching { reader.parseEntry(b, id, LbAddr(block, 0), listOf(phys)) }.getOrNull() ?: continue
                val data = runCatching { reader.data(node, listOf(phys), limit = 16L shl 20) }.getOrNull() ?: continue
                if (node.fileType == 248 && data.size >= 152) {
                    val headerLen = le16(data, 0)
                    if (headerLen < 152 || headerLen > data.size) continue
                    val label = DiscText.dstring(data, 4, 128).ifBlank { null }
                    val n = (data.size - headerLen) / 4
                    return LongArray(n) { le32(data, headerLen + 4 * it) } to label
                }
                if (node.fileType == 0 && data.size >= 36) {
                    val ident = String(data, data.size - 36 + 1, 23, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    if (ident == "*UDF Virtual Alloc Tbl") {
                        val n = (data.size - 36) / 4
                        return LongArray(n) { le32(data, 4 * it) } to null
                    }
                }
            }
        }
        return null
    }

    /** 元数据分区：读元数据文件（类型 250）的分配描述符；读不出时用镜像文件（类型 251）。 */
    private suspend fun metadata(src: CachedSectorSource, reader: UdfReader, physical: UdfMap, raw: ByteArray): UdfMap.Metadata {
        val fileLoc = le32(raw, 40)
        val mirrorLoc = le32(raw, 44)
        var lastError: Exception? = null
        for ((loc, mirror) in listOf(fileLoc to false, mirrorLoc to true)) {
            if (loc == 0xFFFFFFFFL) continue
            try {
                val node = reader.node(LbAddr(loc, 0), listOf(physical))
                if (node.fileType != 250 && node.fileType != 251) throw DiscFsException("元数据文件类型是 ${node.fileType}")
                val extents = node.extents.filter { it.type == 0 && it.location != null }.map { e ->
                    e.location!!.block to (e.length + SECTOR_SIZE - 1) / SECTOR_SIZE
                }
                if (extents.isEmpty()) throw DiscFsException("元数据文件是空的")
                return UdfMap.Metadata(physical, extents, mirror)
            } catch (e: DiscFsException) {
                lastError = e
            } catch (e: SectorReadException) {
                if (e.fatal) throw e
                lastError = e
            }
        }
        throw DiscFsException("读不出元数据文件：${lastError?.message}")
    }
}

/** UDF 文件系统。 */
class UdfFileSystem internal constructor(private val src: CachedSectorSource, val volume: UdfVolume) : DiscFileSystem {
    private val reader = UdfReader(src)
    private class Handle(val node: UdfNode)

    override val kind: String = "UDF ${volume.revisionText}" + volume.features().let { if (it.isEmpty()) "" else "（$it）" }
    override val label: String? = volume.label
    override lateinit var root: DiscEntry
        private set

    internal suspend fun init(): UdfFileSystem {
        val node = reader.node(volume.rootIcb, volume.maps)
        if (!node.isDirectory) throw DiscFsException("根目录的文件项不是目录（类型 ${node.fileType}）")
        root = DiscEntry("", "/", true, node.size, node.modifiedMillis, false, Handle(node))
        return this
    }

    override suspend fun list(dir: DiscEntry): List<DiscEntry> {
        require(dir.isDirectory)
        val node = (dir.handle as Handle).node
        val data = reader.data(node, volume.maps)
        val out = mutableListOf<DiscEntry>()
        var p = 0
        while (p + 38 <= data.size) {
            if (UdfTag.id(data, p) != UdfTag.FID) break
            val chars = u8(data, p + 18)
            val lFi = u8(data, p + 19)
            val icb = UdfReader.longAd(data, p + 20).location
            val lIu = le16(data, p + 36)
            val nameOff = p + 38 + lIu
            if (nameOff + lFi > data.size) break
            val name = DiscText.cs0(data, nameOff, lFi)
            p += (38 + lIu + lFi + 3) and 3.inv()
            if (chars and 0x08 != 0 || chars and 0x04 != 0 || icb == null) continue // 父目录、已删除
            val child = try {
                reader.node(icb, volume.maps)
            } catch (e: DiscFsException) {
                null
            } catch (e: SectorReadException) {
                if (e.fatal) throw e
                null
            }
            val isDir = child?.isDirectory ?: (chars and 0x02 != 0)
            if (child == null) {
                // 文件项读不出来：仍然列出来（大小未知），打开时会报错
                out += DiscEntry(name, DiscEntry.childPath(dir.path, name), isDir, 0, null, chars and 0x01 != 0, BrokenHandle(icb))
                continue
            }
            out += DiscEntry(name, DiscEntry.childPath(dir.path, name), isDir, child.size, child.modifiedMillis, chars and 0x01 != 0, Handle(child))
        }
        return sortEntries(out)
    }

    private class BrokenHandle(val icb: LbAddr)

    override suspend fun runs(file: DiscEntry): List<DataRun> {
        val h = file.handle as? Handle ?: throw DiscFsException("「${file.name}」的文件项读不出来")
        if (h.node.embedded != null) throw DiscFsException("「${file.name}」的数据嵌在文件项里，不是连续扇区")
        return reader.runs(h.node, volume.maps)
    }

    override suspend fun open(file: DiscEntry, cached: Boolean): DiscFileReader {
        val h = file.handle as? Handle ?: throw DiscFsException("「${file.name}」的文件项读不出来")
        val embedded = h.node.embedded
        if (embedded != null) return DiscFileReader(EmbeddedSource(embedded), embedded.size.toLong(), listOf(DataRun(0, embedded.size.toLong(), 0)))
        return DiscFileReader(src, h.node.size, reader.runs(h.node, volume.maps), cache = if (cached) src else null)
    }

    /** 嵌在文件项里的小文件：当成从扇区 0 开始的一小段内存。 */
    private class EmbeddedSource(private val data: ByteArray) : SectorSource {
        override val sectorCount: Long = ((data.size + SECTOR_SIZE - 1) / SECTOR_SIZE).toLong()
        override suspend fun read(lba: Long, count: Int, dst: ByteArray, offset: Int) {
            val from = (lba * SECTOR_SIZE).toInt()
            val n = minOf(count * SECTOR_SIZE, data.size - from).coerceAtLeast(0)
            System.arraycopy(data, from, dst, offset, n)
            dst.fill(0, offset + n, offset + count * SECTOR_SIZE)
        }
    }
}
