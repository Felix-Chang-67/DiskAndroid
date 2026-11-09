package io.github.felixchang67.diskandroid.core.drive

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.mmc.BdDiscInformation
import io.github.felixchang67.diskandroid.core.mmc.DiscInformation
import io.github.felixchang67.diskandroid.core.mmc.DvdCopyright
import io.github.felixchang67.diskandroid.core.mmc.DvdPhysical
import io.github.felixchang67.diskandroid.core.mmc.FullToc
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.mmc.Msf
import io.github.felixchang67.diskandroid.core.mmc.ReadCapacity
import io.github.felixchang67.diskandroid.core.mmc.ReadPerformance
import io.github.felixchang67.diskandroid.core.mmc.SessionInfo
import io.github.felixchang67.diskandroid.core.mmc.Toc
import io.github.felixchang67.diskandroid.core.mmc.TocEntry
import io.github.felixchang67.diskandroid.core.mmc.TrackInformation
import io.github.felixchang67.diskandroid.core.scsi.ScsiCommand
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult

/** 一张盘片的信息（盘片就绪时读取一次）。 */
class DiscInfo(
    val profile: Int,
    val capacity: ReadCapacity?,
    val discInformation: DiscInformation?,
    val toc: Toc?,
    val sessionInfo: SessionInfo?,
    val fullToc: FullToc?,
    val tracks: List<TrackInformation>,
    val dvdPhysical: DvdPhysical?,
    val dvdCopyright: DvdCopyright?,
    val bdInfo: BdDiscInformation?,
    val performance: ReadPerformance?,
    val raw: Map<String, ByteArray>,
    val errors: Map<String, String>,
    val readMillis: Long,
) {
    val family: MmcNames.DiscFamily get() = MmcNames.family(profile)
    val audioTracks: List<TocEntry> get() = toc?.tracks?.filter { !it.isData }.orEmpty()
    val dataTracks: List<TocEntry> get() = toc?.tracks?.filter { it.isData }.orEmpty()
    val hasAudio: Boolean get() = family == MmcNames.DiscFamily.CD && audioTracks.isNotEmpty()

    /**
     * 每条轨道的扇区数。CD-Extra 的第 1 区段最后一首音轨后面紧跟 lead-out/lead-in 间隙，
     * 用完整 TOC 里该区段的 lead-out（A2h）确定结尾；拿不到时退回"下一区段起点 − 11400"。
     */
    fun sectorsOf(entry: TocEntry): Long? {
        val t = toc ?: return null
        val info = tracks.firstOrNull { it.track == entry.track }
        val next = t.entries.getOrNull(t.entries.indexOf(entry) + 1) ?: return null
        val nextSession = tracks.firstOrNull { it.track == next.track }?.session
        val session = info?.session
        if (session != null && nextSession != null && nextSession != session) {
            fullToc?.leadOutLba(session)?.let { return it - entry.lba }
            return next.lba - 11_400 - entry.lba
        }
        return next.lba - entry.lba
    }

    fun summary(): String {
        val parts = mutableListOf(MmcNames.profileName(profile))
        capacity?.let { parts += formatBytes(it.bytes) }
        if (family == MmcNames.DiscFamily.CD) {
            toc?.let { parts += "${it.tracks.size} 轨（音频 ${audioTracks.size}，数据 ${dataTracks.size}）" }
        }
        discInformation?.let { if (it.sessions > 1) parts += "${it.sessions} 个区段" }
        dvdCopyright?.let { parts += if (it.isCss) "CSS 加密，${it.regionText()}" else it.regionText() }
        return parts.joinToString("，")
    }

    fun describeLines(): List<String> {
        val out = mutableListOf<String>()
        out += "盘片：${MmcNames.profileName(profile)}（profile ${Hex.word(profile)}h），读取盘片信息用时 $readMillis ms"
        capacity?.let {
            out += "  容量：${it.blocks} 块 × ${it.blockLength} 字节 = ${formatBytes(it.bytes)}（最后 LBA ${it.lastLba}）"
        }
        discInformation?.let { d ->
            out += "  盘片状态：${d.discStatusName()}，最后区段${d.lastSessionStateName()}，可擦写 ${yes(d.erasable)}，" +
                "区段数 ${d.sessions}，首轨 ${d.firstTrack}，最后区段轨道 ${d.firstTrackInLastSession}–${d.lastTrackInLastSession}" +
                if (family == MmcNames.DiscFamily.CD) "，类型 ${d.discTypeName()}" else ""
        }
        sessionInfo?.let { out += "  区段信息：第 ${it.firstSession}–${it.lastSession} 区段，最后区段首轨 ${it.firstTrackInLastSession}，起点 LBA ${it.lastSessionStartLba}" }
        fullToc?.let { f ->
            f.sessions.forEach { s ->
                out += "  完整 TOC 区段 $s：轨道 ${f.firstTrack(s) ?: "?"}–${f.lastTrack(s) ?: "?"}，lead-out LBA ${f.leadOutLba(s) ?: "?"}" +
                    (f.discTypeCode(s)?.let { "，盘片类型码 ${Hex.byte(it)}h" } ?: "")
            }
        }
        toc?.let { t ->
            out += "  TOC：轨道 ${t.firstTrack}–${t.lastTrack}，lead-out LBA ${t.leadOut?.lba ?: "?"}"
            trackLines().forEach { out += "    $it" }
        }
        dvdPhysical?.let { p ->
            out += "  DVD 物理格式：${p.bookTypeName()}（版本 ${p.partVersion}），${p.discSizeName()}，${p.layers} 层" +
                (if (p.layers > 1) if (p.oppositeTrackPath) "（OTP）" else "（PTP）" else "") +
                "，${p.layerTypeName()}，最大速率 ${p.maxRateName()}，数据区物理扇区 ${Hex.hex(p.startSector.toInt(), 6)}h–${Hex.hex(p.endSector.toInt(), 6)}h，BCA ${yes(p.bca)}"
        }
        dvdCopyright?.let { c -> out += "  DVD 版权信息：${c.protectionName()}，盘片地区 ${c.regionText()}（掩码 ${Hex.byte(c.regionMask)}h）" }
        bdInfo?.let { b -> out += "  BD 盘片信息：类型标识 ${b.discTypeId ?: "?"}（${b.discTypeName()}），DI 格式 ${b.diFormat}" }
        performance?.let { p ->
            val speed = ReadPerformance.speedFactor(p.maxKBps, family)?.let { "（约 $it）" } ?: ""
            out += "  读取速度：最高 ${p.maxKBps} kB/s$speed，${p.descriptors.size} 个区间"
        }
        errors.forEach { (k, v) -> out += "  ✗ $k：$v" }
        return out
    }

    fun trackLines(): List<String> = toc?.tracks.orEmpty().map { e ->
        val info = tracks.firstOrNull { it.track == e.track }
        val sectors = sectorsOf(e)
        val kind = if (e.isData) "数据" + (info?.let { "（${it.dataModeName()}）" } ?: "") else "音频"
        val length = when {
            sectors == null -> "?"
            e.isData -> "$sectors 扇区（${formatBytes(sectors * 2048)}）"
            else -> "${Msf.duration(sectors)}（$sectors 扇区）"
        }
        val flags = listOfNotNull(
            "预加重".takeIf { e.preEmphasis },
            "允许复制".takeIf { e.copyPermitted },
            "四声道".takeIf { e.fourChannel },
        ).joinToString("、")
        "轨道 ${"%02d".format(e.track)}  ${kind.padEnd(4)}  起点 LBA ${e.lba}（${Msf.format(e.lba)}）  长度 $length" +
            (info?.let { "  区段 ${it.session}" } ?: "") + (if (flags.isNotEmpty()) "  [$flags]" else "")
    }

    fun rawLines(): List<String> = raw.flatMap { (name, bytes) ->
        listOf("  $name（${bytes.size} 字节）：") + Hex.dump(bytes).lines().map { "    $it" }
    }

    private fun yes(b: Boolean) = if (b) "是" else "否"

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
            else -> "$bytes 字节"
        }
    }
}

/**
 * 读取盘片信息。变长响应一律"先读头部拿到长度，再按确切长度读"：
 * 请求比实际数据长时，这台光驱的桥接芯片会在发完数据后 STALL（BOT 规范允许，N1.2 真机日志里见过），
 * 按确切长度读可以避开这一步。
 */
internal class DiscReader(
    private val execIn: (ScsiCommand) -> Pair<ScsiResult, ByteArray?>,
    private val log: DiskLog,
    private val clock: () -> Long,
) {
    private val raw = linkedMapOf<String, ByteArray>()
    private val errors = linkedMapOf<String, String>()

    fun read(profile: Int): DiscInfo {
        val t0 = clock()
        val family = MmcNames.family(profile)

        val capacity = fixed("READ CAPACITY", MmcCommands.readCapacity())?.let { ReadCapacity.parse(it) }
        val discInformation = variable("READ DISC INFORMATION", 4, 2, 2048) { MmcCommands.readDiscInformation(it) }
            ?.let { DiscInformation.parse(it) }
        val toc = variable("READ TOC 格式 0", 4, 2, 4 + 8 * 101) { MmcCommands.readToc(0, msf = false, trackOrSession = 0, allocation = it) }
            ?.let { Toc.parse(it) }

        var sessionInfo: SessionInfo? = null
        var fullToc: FullToc? = null
        if (family == MmcNames.DiscFamily.CD) {
            sessionInfo = variable("READ TOC 格式 1", 4, 2, 12) { MmcCommands.readToc(1, msf = false, trackOrSession = 0, allocation = it) }
                ?.let { SessionInfo.parse(it) }
            fullToc = variable("READ TOC 格式 2", 4, 2, 4096) { MmcCommands.readToc(2, msf = true, trackOrSession = 0, allocation = it) }
                ?.let { FullToc.parse(it) }
        }

        val tracks = mutableListOf<TrackInformation>()
        var trackInfoLength = 0
        for (entry in toc?.tracks.orEmpty().take(99)) {
            val key = "READ TRACK INFORMATION 轨道 ${entry.track}"
            val data = if (trackInfoLength == 0) {
                variable(key, 4, 2, 64) { MmcCommands.readTrackInformation(1, entry.track.toLong(), it) }
            } else {
                fixed(key, MmcCommands.readTrackInformation(1, entry.track.toLong(), trackInfoLength), keepRaw = false)
            }
            data?.let { d ->
                if (trackInfoLength == 0) trackInfoLength = d.size
                TrackInformation.parse(d)?.let { tracks += it }
            }
        }

        var dvdPhysical: DvdPhysical? = null
        var dvdCopyright: DvdCopyright? = null
        var bdInfo: BdDiscInformation? = null
        when (family) {
            MmcNames.DiscFamily.DVD -> {
                // 物理格式信息实际有 2052 字节，只取前 24 字节（足够解析层描述符；与 Linux 取 21 字节同理）
                dvdPhysical = fixed("READ DISC STRUCTURE DVD 物理格式", MmcCommands.readDiscStructure(0, 0x00, 24))?.let { DvdPhysical.parse(it) }
                dvdCopyright = fixed("READ DISC STRUCTURE DVD 版权信息", MmcCommands.readDiscStructure(0, 0x01, 8))?.let { DvdCopyright.parse(it) }
            }
            MmcNames.DiscFamily.BD -> {
                bdInfo = variable("READ DISC STRUCTURE BD 盘片信息", 4, 2, 4100) { MmcCommands.readDiscStructure(1, 0x00, it) }
                    ?.let { BdDiscInformation.parse(it) }
            }
            else -> Unit
        }

        var performance = fixed("GET PERFORMANCE", MmcCommands.getReadPerformance(1))?.let { ReadPerformance.parse(it) }
        val declared = raw["GET PERFORMANCE"]?.let { d -> if (d.size >= 4) ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF) else 0 } ?: 0
        val count = ((declared - 4) / 16).toInt()
        if (count > 1) {
            performance = fixed("GET PERFORMANCE", MmcCommands.getReadPerformance(minOf(count, 32)))?.let { ReadPerformance.parse(it) } ?: performance
        }

        val info = DiscInfo(
            profile, capacity, discInformation, toc, sessionInfo, fullToc, tracks, dvdPhysical, dvdCopyright, bdInfo, performance,
            raw.toMap(), errors.toMap(), clock() - t0,
        )
        info.describeLines().forEach { log.i("DISC", it) }
        return info
    }

    private fun fixed(key: String, cmd: ScsiCommand, keepRaw: Boolean = true): ByteArray? {
        val (r, d) = execIn(cmd)
        if (d == null) {
            errors[key] = describe(r)
            return null
        }
        if (keepRaw) raw[key] = d
        return d
    }

    /** @param lengthSize 长度字段的字节数（2 或 4），长度字段不含自身。 */
    private fun variable(key: String, headerAlloc: Int, lengthSize: Int, maxAlloc: Int, build: (Int) -> ScsiCommand): ByteArray? {
        val (r1, d1) = execIn(build(headerAlloc))
        if (d1 == null) {
            errors[key] = describe(r1)
            return null
        }
        if (d1.size < lengthSize) {
            errors[key] = "响应只有 ${d1.size} 字节"
            raw[key] = d1
            return null
        }
        val declared = if (lengthSize == 2) {
            (((d1[0].toInt() and 0xFF) shl 8) or (d1[1].toInt() and 0xFF)) + 2
        } else {
            ((((d1[0].toLong() and 0xFF) shl 24) or ((d1[1].toLong() and 0xFF) shl 16) or ((d1[2].toLong() and 0xFF) shl 8) or (d1[3].toLong() and 0xFF)) + 4).toInt()
        }
        val total = declared.coerceIn(lengthSize, maxAlloc)
        if (total <= d1.size) {
            raw[key] = d1
            return d1.copyOf(total)
        }
        val (r2, d2) = execIn(build(total))
        if (d2 == null) {
            errors[key] = describe(r2)
            raw[key] = d1
            return d1
        }
        raw[key] = d2
        return d2
    }

    private fun describe(r: ScsiResult): String = when (r) {
        is ScsiResult.CheckCondition -> r.sense?.describe() ?: "CHECK CONDITION（无 sense）"
        else -> r.toString()
    }
}
