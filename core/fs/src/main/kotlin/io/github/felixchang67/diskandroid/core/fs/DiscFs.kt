package io.github.felixchang67.diskandroid.core.fs

import io.github.felixchang67.diskandroid.core.fs.DiscText.le32

/**
 * 盘上文件系统的探测结果：ISO 9660 / Joliet 卷描述符、UDF 卷（解析失败时有原因），以及据此得到的卷标。
 */
class DiscVolume internal constructor(
    val recognition: VolumeRecognition,
    val udf: UdfVolume?,
    /** 有 UDF 卷识别序列但打不开时的原因。 */
    val udfError: String?,
) {
    val primary: IsoDescriptor? get() = recognition.primary
    val joliet: IsoDescriptor? get() = recognition.joliet

    /** 有没有能读的文件系统。 */
    val hasFileSystem: Boolean get() = udf != null || primary != null || joliet != null

    /** 给人看、给文件起名用的卷标：UDF 逻辑卷标识（最长、Unicode）→ Joliet（UCS-2，16 字）→ ISO 9660 主卷描述符（猜编码）。 */
    val label: String?
        get() = udf?.label ?: joliet?.volumeId?.ifBlank { null } ?: primary?.volumeId?.ifBlank { null }

    /** "UDF 2.50（元数据分区）+ ISO 9660 + Joliet" 之类。 */
    val description: String
        get() {
            val parts = mutableListOf<String>()
            udf?.let { parts += "UDF ${it.revisionText}" + it.features().let { f -> if (f.isEmpty()) "" else "（$f）" } }
            if (udf == null && recognition.udfNsr != null) parts += "UDF（打不开）"
            if (primary != null) parts += "ISO 9660"
            if (joliet != null) parts += "Joliet"
            if (recognition.bootRecord) parts += "El Torito 引导"
            return parts.joinToString(" + ").ifEmpty { "没有可识别的文件系统" }
        }

    /** ISO 9660 卷大小（扇区）。 */
    val isoVolumeSize: Long? get() = primary?.volumeSpaceSize?.takeIf { it > 0 }

    /** 报告用的详细信息。 */
    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        out += "文件系统：$description"
        udf?.let { u ->
            out += "  UDF ${u.revisionText}：逻辑卷「${u.logicalVolumeId}」，卷「${u.volumeId}」，文件集「${u.fileSetId}」" +
                (u.vatVolumeId?.let { "，VAT 卷标「$it」" } ?: "")
            u.partitions.forEach { p -> out += "  UDF 分区 ${p.number}：扇区 ${p.start}–${p.end - 1}（${p.length} 块，${p.accessName()}，${p.contents}）" }
            out += "  UDF 分区映射：" + u.mapDescriptions.joinToString("；") + "；锚点 " + u.anchors.joinToString("、")
        }
        udfError?.let { out += "  UDF 打不开：$it" }
        primary?.let { p ->
            val raw = p.volumeIdRaw.dropLastWhile { it.toInt() == 0x20 || it.toInt() == 0 }
            out += "  ISO 9660：卷标「${p.volumeId}」（原始字节 ${raw.joinToString(" ") { "%02X".format(it) }}），" +
                "卷大小 ${p.volumeSpaceSize} 扇区" + (p.applicationId.takeIf { it.isNotBlank() }?.let { "，制作程序「$it」" } ?: "")
        }
        joliet?.let { out += "  Joliet（级别 ${it.jolietLevel}）：卷标「${it.volumeId}」" }
        return out
    }
}

/** 探测选项。 */
class ProbeOptions(
    /** 卷描述符所在区段的起点（多区段 CD 用最后一个区段的起点）。 */
    val sessionStart: Long = 0,
    /** 可能的"最后写入的扇区"（找 VAT 用）：最后一轨的最后记录地址、轨道末尾、容量 − 1。 */
    val lastWritten: List<Long> = emptyList(),
)

/** 盘片文件系统的入口：探测、打开、算文件系统的结尾。 */
object DiscFs {
    suspend fun probe(src: CachedSectorSource, options: ProbeOptions = ProbeOptions()): DiscVolume {
        val rec = VolumeRecognition.scan(src, options.sessionStart)
        var udf: UdfVolume? = null
        var udfError: String? = null
        // 没有 NSR 标记也试一下锚点：个别刻录软件的卷识别序列不规范，但 LBA 256 有锚点
        if (rec.udfNsr != null || rec.primary == null) {
            try {
                udf = UdfProbe.open(src, options.sessionStart, options.lastWritten)
            } catch (e: DiscFsException) {
                if (rec.udfNsr != null) udfError = e.message
            } catch (e: SectorReadException) {
                if (e.fatal) throw e
                if (rec.udfNsr != null) udfError = "读不出：${e.message}"
            }
        }
        return DiscVolume(rec, udf, udfError)
    }

    /**
     * 打开文件系统：优先 UDF（DVD-Video / BD 的标准，文件名是 Unicode），然后 Joliet，最后 ISO 9660（可能带 Rock Ridge）。
     * UDF 打开失败（结构损坏）时退回 ISO 9660。
     */
    suspend fun open(src: CachedSectorSource, volume: DiscVolume): DiscFileSystem? {
        volume.udf?.let { u ->
            try {
                return UdfFileSystem(src, u).init()
            } catch (e: DiscFsException) {
                if (volume.joliet == null && volume.primary == null) throw e
            }
        }
        volume.joliet?.let { return IsoFileSystem.open(src, it) }
        volume.primary?.let { return IsoFileSystem.open(src, it) }
        return null
    }

    /**
     * 文件系统在盘上的结尾（扇区号，不含）：可重写盘（DVD+RW、DVD-RW 限制性覆写、BD-RE、DVD-RAM）的 READ CAPACITY
     * 是整张盘格式化后的容量，读成 ISO 只需要读到文件系统结尾。取 ISO 9660 卷大小与 UDF 各结构结尾的最大值，
     * 再在其后 [searchWindow] 个扇区里找 UDF 的结尾锚点（UDF 规定卷的最后一个扇区或倒数第 257 个扇区有锚点）。
     * 算不出返回 null。
     */
    suspend fun volumeEnd(src: CachedSectorSource, volume: DiscVolume, searchWindow: Int = 512): Long? {
        val iso = volume.isoVolumeSize
        val udf = volume.udf
        var end = maxOf(iso ?: 0L, udf?.structuresEnd ?: 0L)
        if (end <= 16) return null
        val capacity = src.sectorCount
        if (udf != null) {
            val from = (udf.structuresEnd - 1).coerceAtLeast(257)
            var to = from + searchWindow
            if (capacity != null) to = minOf(to, capacity)
            var s = from
            while (s < to) {
                val b = src.sectorOrNull(s)
                if (b != null && UdfTag.id(b) == UdfTag.AVDP && le32(b, 12) == s) {
                    end = maxOf(end, s + 1)
                    // 这个锚点可能是 N − 256：再看 256 个扇区之后是否还有一个
                    val far = s + 256
                    if (capacity == null || far < capacity) {
                        val fb = src.sectorOrNull(far)
                        if (fb != null && UdfTag.id(fb) == UdfTag.AVDP && le32(fb, 12) == far) end = maxOf(end, far + 1)
                    }
                }
                s++
            }
        }
        if (capacity != null && end > capacity) return null
        return end
    }
}
