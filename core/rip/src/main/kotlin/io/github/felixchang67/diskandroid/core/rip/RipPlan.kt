package io.github.felixchang67.diskandroid.core.rip

import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.mmc.MmcNames

/** 镜像里的一段 [start, end)（扇区号 = LBA）。 */
data class RipArea(val start: Long, val end: Long, val kind: Kind, val note: String, val track: Int? = null) {
    enum class Kind {
        /** 数据轨：要读。 */
        DATA,

        /** 不读、补零：区段之间的导入 / 导出区、轨道之间的空隙、音轨。 */
        GAP,
    }

    val sectors: Long get() = end - start
}

/**
 * 读成 ISO 的计划：镜像覆盖 LBA 0 到 READ CAPACITY 的最后一个扇区（与 Linux 下对 /dev/sr0 做 dd 一致），
 * 每扇区 2048 字节。数据轨照读；音轨、轨道 / 区段之间的空隙补零并在报告里列出（不算坏扇区）。
 */
class RipPlan(
    val family: MmcNames.DiscFamily,
    val profileName: String,
    val totalSectors: Long,
    val areas: List<RipArea>,
    val warnings: List<String>,
    /** READ CAPACITY 报告的扇区数；可重写盘只读到文件系统结尾时比 [totalSectors] 大。 */
    val capacitySectors: Long = totalSectors,
) {
    val trimmed: Boolean get() = totalSectors < capacitySectors
    val bytes: Long get() = totalSectors * SECTOR
    val dataSectors: Long get() = areas.filter { it.kind == RipArea.Kind.DATA }.sumOf { it.sectors }
    val gapSectors: Long get() = totalSectors - dataSectors

    /** CD 数据轨的末尾两个扇区：TAO 刻录的 run-out，读不出时算间隙不算坏扇区。 */
    fun isRunOut(lba: Long): Boolean {
        if (family != MmcNames.DiscFamily.CD) return false
        val area = areas.firstOrNull { it.kind == RipArea.Kind.DATA && lba >= it.start && lba < it.end } ?: return false
        return lba >= area.end - 2
    }

    sealed interface Build {
        class Ok(val plan: RipPlan) : Build
        class Refused(val reason: String) : Build
    }

    companion object {
        const val SECTOR = 2048

        /** 可覆写的盘：READ CAPACITY 是整张盘格式化后的容量，和刻进去的数据多少无关。 */
        fun isOverwritable(profile: Int) = profile in setOf(0x12, 0x13, 0x1A, 0x2A, 0x43)

        /**
         * @param fileSystemEnd 文件系统在盘上的结尾（见 DiscFs.volumeEnd）。可覆写的盘有它时只读到这里：
         *   这样读出来的镜像与当初刻进去的 ISO 一致，不会带上几 GB 没用的旧数据。
         */
        fun from(disc: DiscInfo, fileSystemEnd: Long? = null): Build {
            val family = disc.family
            if (family != MmcNames.DiscFamily.CD && family != MmcNames.DiscFamily.DVD && family != MmcNames.DiscFamily.BD) {
                return Build.Refused("暂不支持这种盘（${MmcNames.profileName(disc.profile)}）")
            }
            if (disc.dvdCopyright?.isCss == true) {
                return Build.Refused(
                    "这是 CSS 加密的 DVD：光驱要先和主机完成 CSS 认证，才肯读出加密扇区（否则返回 05/6F/03）。" +
                        "认证属于阶段 3 的解密组件；到那时读成 ISO 仍然输出原始密文，不解密。",
                )
            }
            val capacity = disc.capacity ?: return Build.Refused("读不到盘片容量（READ CAPACITY 失败）")
            val full = capacity.blocks
            if (full <= 0) return Build.Refused("盘片是空的")
            val warnings = mutableListOf<String>()
            val total = if (fileSystemEnd != null && isOverwritable(disc.profile) && fileSystemEnd in 17 until full) {
                warnings += "可重写盘：READ CAPACITY 是整张盘格式化后的 ${DiscInfo.formatBytes(full * SECTOR)}，文件系统只用到 " +
                    "${DiscInfo.formatBytes(fileSystemEnd * SECTOR)}（$fileSystemEnd 扇区），只读到这里（后面是没用的旧数据或空白）"
                fileSystemEnd
            } else {
                full
            }

            val areas = mutableListOf<RipArea>()
            if (family == MmcNames.DiscFamily.CD) {
                val toc = disc.toc ?: return Build.Refused("读不到 TOC")
                val tracks = toc.tracks.sortedBy { it.track }
                if (tracks.none { it.isData }) return Build.Refused("这是音乐 CD（没有数据轨），不能读成 ISO；音乐 CD 的抓轨在阶段 5")
                var pos = 0L
                for ((i, t) in tracks.withIndex()) {
                    val start = t.lba.coerceAtLeast(0)
                    if (start >= total) break
                    val info = disc.tracks.firstOrNull { it.track == t.track }
                    val next = tracks.getOrNull(i + 1)?.lba ?: total
                    // 轨道长度：优先用 READ TRACK INFORMATION 的轨道大小（多区段盘的区段间隙不在轨道内），否则到下一轨起点
                    val end = minOf(info?.size?.takeIf { it > 0 }?.let { start + it } ?: next, next, total)
                    if (start > pos) areas += RipArea(pos, start, RipArea.Kind.GAP, "轨道之间 / 区段之间的空隙")
                    if (end > start) {
                        areas += if (t.isData) {
                            RipArea(start, end, RipArea.Kind.DATA, "第 ${t.track} 轨（数据）", t.track)
                        } else {
                            RipArea(start, end, RipArea.Kind.GAP, "第 ${t.track} 轨是音轨（ISO 里补零）", t.track)
                        }
                    }
                    pos = maxOf(pos, end)
                }
                if (pos < total) areas += RipArea(pos, total, RipArea.Kind.GAP, "最后一轨之后")
                if (tracks.any { !it.isData }) {
                    warnings += "这张盘有音轨（加强版 CD / 混合模式盘）：ISO 只含数据轨，音轨部分补零；电脑上打开时选数据所在的区段"
                }
                if ((disc.discInformation?.sessions ?: 1) > 1) warnings += "多区段盘：区段之间的导出 / 导入区补零"
            } else {
                // DVD / BD：按 READ TRACK INFORMATION 的轨道读，轨道之外补零；拿不到轨道信息就整盘读
                val tracks = disc.tracks.filter { it.size > 0 }.sortedBy { it.start }
                if (tracks.isEmpty()) {
                    areas += RipArea(0, total, RipArea.Kind.DATA, "整张盘")
                } else {
                    var pos = 0L
                    for (t in tracks) {
                        val start = t.start.coerceIn(0, total)
                        val end = (t.start + t.size).coerceIn(start, total)
                        if (start > pos) areas += RipArea(pos, start, RipArea.Kind.GAP, "轨道之间的空隙")
                        if (end > start) areas += RipArea(start, end, RipArea.Kind.DATA, "第 ${t.track} 轨", t.track)
                        pos = maxOf(pos, end)
                    }
                    if (pos < total) areas += RipArea(pos, total, RipArea.Kind.GAP, "最后一轨之后")
                }
                if (family == MmcNames.DiscFamily.BD) {
                    warnings += "商业 BD 读出的是原样的加密数据（AACS），镜像本身不解密"
                }
            }
            if (areas.none { it.kind == RipArea.Kind.DATA }) return Build.Refused("找不到可读的数据区")
            disc.performance?.maxKBps?.takeIf { it in 1L..3_000L && family != MmcNames.DiscFamily.CD }?.let {
                warnings += "光驱对这张盘只报 ${it} kB/s（视频盘常见的读速限制），读完需要较长时间"
            }
            return Build.Ok(RipPlan(family, MmcNames.profileName(disc.profile), total, areas, warnings, full))
        }
    }
}
