package io.github.felixchang67.diskandroid.diag

import android.content.Context
import io.github.felixchang67.diskandroid.AppContainer
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot

/**
 * "复制诊断信息"的内容：系统信息 + USB 设备 + 连接测试结果 + 最近的日志。
 * 设计成可以直接粘贴到对话里（纯文本、长度可控）。
 */
object Diagnostics {
    const val DEFAULT_LOG_LINES = 300

    fun build(
        context: Context,
        container: AppContainer,
        devices: List<UsbDeviceSnapshot>,
        reports: Map<String, ProbeReport>,
        maxLogLines: Int = DEFAULT_LOG_LINES,
    ): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')

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
            line()
        }
        val detached = reports.filterKeys { name -> devices.none { it.deviceName == name } }
        if (detached.isNotEmpty()) {
            line("===== 已断开设备的最近一次连接测试 =====")
            detached.values.forEach { r -> r.describeLines().forEach { line(it) }; line() }
        }

        val records = container.ring.snapshot().takeLast(maxLogLines)
        line("===== 最近 ${records.size} 条日志 =====")
        records.forEach { line(LogFormat.text(it, maxDataBytes = 256)) }
        line("===== 结束 =====")
        return sb.toString()
    }
}
