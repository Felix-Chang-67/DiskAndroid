package io.github.felixchang67.diskandroid.core.rip

import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.fs.DiscVolume

/** 读盘报告（.log，纯文本）：和镜像放在一起，方便以后核对。 */
object RipReport {
    fun text(
        appVersion: String,
        startedAt: String,
        drive: String,
        disc: DiscInfo,
        plan: RipPlan,
        options: RipOptions,
        imageName: String,
        result: RipResult,
        volume: DiscVolume? = null,
        incidents: List<String> = emptyList(),
    ): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')
        line("DiskAndroid 读盘报告（$appVersion）")
        line("开始时间：$startedAt")
        line("光驱：$drive")
        line("盘片：${disc.summary()}")
        line("镜像：$imageName（${plan.totalSectors} 扇区 × ${RipPlan.SECTOR} 字节 = ${DiscInfo.formatBytes(plan.bytes)}）" +
            if (plan.trimmed) "，整张盘 ${plan.capacitySectors} 扇区，只读到文件系统结尾" else "")
        volume?.describeLines()?.forEach { line(it) }
        result.volume?.let { v ->
            line("ISO 9660 卷标：${v.volumeId ?: "（空）"}；卷大小 ${v.volumeSpaceSize ?: "?"} 扇区" + when {
                v.volumeSpaceSize == null -> ""
                v.volumeSpaceSize == plan.totalSectors -> "（与 READ CAPACITY 一致）"
                else -> "（READ CAPACITY 为 ${plan.totalSectors}，相差 ${plan.totalSectors - v.volumeSpaceSize}）"
            })
        } ?: line("没有 ISO 9660 主卷描述符（例如只有 UDF 的 BD，或者不是从头开始的多区段盘）")
        line("设置：每次读 ${options.blockSectors} 扇区，第 2 遍每扇区最多重试 ${options.retries} 次")
        line()
        line("区域：")
        plan.areas.forEach { a ->
            line("  LBA ${a.start}–${a.end - 1}（${a.sectors} 扇区）：${if (a.kind == RipArea.Kind.DATA) "读取" else "补零"}，${a.note}")
        }
        plan.warnings.forEach { line("  注意：$it") }
        line()
        line("结果：${result.status.label}" + (result.message?.let { "（$it）" } ?: ""))
        line("用时：${result.elapsedMs / 1000 / 60} 分 ${result.elapsedMs / 1000 % 60} 秒；读出 ${result.readSectors} 扇区" +
            (if (result.elapsedMs > 0) "，平均 ${"%.2f".format(result.readSectors * RipPlan.SECTOR / 1e6 / (result.elapsedMs / 1000.0))} MB/s" else ""))
        line("第 2 遍补读回来：${result.recoveredSectors} 扇区")
        if (incidents.isNotEmpty()) {
            line("读盘过程中的意外（都已自动恢复，镜像数据不受影响）：")
            incidents.forEach { line("  $it") }
        }
        line("坏扇区（已补零）：${result.badSectors.size} 个" + if (result.badSectors.isNotEmpty()) "：" + ranges(result.badSectors) else "")
        if (result.runOutSectors.isNotEmpty()) line("CD 轨道末尾读不出的 run-out 扇区（按间隙补零，不算坏扇区）：" + ranges(result.runOutSectors))
        if (result.md5 != null) {
            line()
            line("校验值（整个镜像）：")
            line("  MD5    ${result.md5}")
            line("  SHA-1  ${result.sha1}")
            line("  CRC32  ${result.crc32}")
        } else {
            line("镜像不完整，没有计算校验值；mapfile 里标 ? 和 * 的部分没有读")
        }
        return sb.toString()
    }

    fun ranges(lbas: List<Long>): String {
        if (lbas.isEmpty()) return ""
        val out = mutableListOf<String>()
        var start = lbas.first()
        var prev = start
        for (l in lbas.drop(1) + Long.MIN_VALUE) {
            if (l != prev + 1) {
                out += if (start == prev) "$start" else "$start–$prev"
                start = l
            }
            prev = l
        }
        return out.joinToString("，")
    }
}
