package io.github.felixchang67.diskandroid.diag

import android.content.Context
import io.github.felixchang67.diskandroid.AppContainer
import io.github.felixchang67.diskandroid.core.log.LogFormat

/**
 * "复制诊断信息"的内容：系统信息、USB 设备与连接报告、光驱信息（含原始响应 hex）与事件、
 * 已结束的会话、上一个会话的警告/错误、最近的日志。纯文本，长度可控，直接粘贴到对话里。
 */
object Diagnostics {
    const val DEFAULT_LOG_LINES = 200

    fun build(context: Context, container: AppContainer, maxLogLines: Int = DEFAULT_LOG_LINES): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')

        val devices = container.usbMonitor.devices.value
        val manager = container.driveManager
        val reports = manager.reports.value
        val drives = manager.drives.value

        line("===== DiskAndroid 诊断信息 =====")
        line("生成时间：${LogFormat.isoTime(System.currentTimeMillis())}")
        line("日志会话：${container.sessionId}")
        SystemInfo.lines(context).forEach { line(it) }
        line()

        line("===== USB 设备（${devices.size}）=====")
        if (devices.isEmpty()) line("（没有检测到 USB 设备）")
        for (d in devices) {
            d.describeLines().forEach { line(it) }
            reports[d.deviceName]?.describeLines()?.forEach { line("  $it") }
            drives[d.deviceName]?.let { drive ->
                val s = drive.session
                line("  ----- 光驱会话 -----")
                line("  传输：${s.description}")
                line("  会话状态：${s.state.value.label()}；盘片：${s.media.value.label()}")
                line("  BOT 统计：${drive.bot.stats}")
                s.info.value?.let { info ->
                    info.describeLines().forEach { line("  $it") }
                    line("  原始响应：")
                    info.rawLines().forEach { line("  $it") }
                }
                line("  事件：")
                s.events.value.forEach { line("    $it") }
            }
            line()
        }

        val detached = reports.filterKeys { name -> devices.none { it.deviceName == name } }
        if (detached.isNotEmpty()) {
            line("===== 已断开设备的连接报告 =====")
            detached.values.forEach { r -> r.describeLines().forEach { line(it) }; line() }
        }

        val history = manager.history.value
        if (history.isNotEmpty()) {
            line("===== 已结束的光驱会话 =====")
            history.forEach { h -> h.forEach { line(it) }; line() }
        }

        container.previousSessionIssues?.let { prev ->
            line("===== 上一个会话（${prev.sessionId}）的警告和错误：${prev.lines.size} 行 =====")
            prev.lines.forEach { line(it) }
            line()
        }

        val records = container.ring.snapshot().takeLast(maxLogLines)
        line("===== 最近 ${records.size} 条日志 =====")
        records.forEach { line(LogFormat.text(it, maxDataBytes = 64)) }
        line("===== 结束 =====")
        return sb.toString()
    }
}
