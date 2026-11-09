@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.MainViewModel
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.usb.MassStorageSelection
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot
import io.github.felixchang67.diskandroid.core.usb.UsbNames
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

@Composable
fun DevicePane(vm: MainViewModel, actions: MainActions, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val detached = reports.values.filter { r -> devices.none { it.deviceName == r.deviceName } }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "guide") { GuideCard() }
        item(key = "actions") { ActionsRow(actions) }
        if (devices.isEmpty()) {
            item(key = "empty") { EmptyCard() }
        }
        items(devices, key = { it.deviceName }) { d ->
            DeviceCard(
                device = d,
                report = reports[d.deviceName],
                busy = d.deviceName in busy,
                onRequestPermission = { vm.requestPermission(d.deviceName) },
                onProbe = { vm.probe(d.deviceName) },
            )
        }
        if (detached.isNotEmpty()) {
            item(key = "detached-title") {
                Text("已断开设备的测试记录", style = MaterialTheme.typography.titleSmall)
            }
            items(detached, key = { "old:${it.deviceName}:${it.startedAtMillis}" }) { r ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${r.displayName}（已断开）", style = MaterialTheme.typography.titleMedium)
                        ReportSection(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("测试节点 N1.1：USB 枚举与权限", style = MaterialTheme.typography.titleMedium)
            Text("1. 用带供电的扩展坞连接光驱（光驱的两个 USB 头都要插上），可以先不放光盘。")
            Text("2. 系统询问是否用 DiskAndroid 打开时选“确定”（可以勾选默认打开）。")
            Text("3. 获得权限后 App 会自动做一次“连接测试”，结果显示在下面的设备卡片里。")
            Text("4. 点“复制诊断信息”，把内容粘贴到对话里发给我。")
            Text(
                "本版本只做 USB 层面的只读检查，不会向光驱发送任何 SCSI 命令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsRow(actions: MainActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = actions.onCopyDiagnostics) { Text("复制诊断信息") }
        OutlinedButton(onClick = actions.onExport) { Text("导出日志（zip）") }
        OutlinedButton(onClick = actions.onShare) { Text("分享日志") }
    }
}

@Composable
private fun EmptyCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("未检测到 USB 设备", style = MaterialTheme.typography.titleSmall)
            Text(
                "请通过带供电的扩展坞连接光驱。已经插好但这里没有显示时，请检查扩展坞供电和线材，或者重新插拔一次。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: UsbDeviceSnapshot,
    report: ProbeReport?,
    busy: Boolean,
    onRequestPermission: () -> Unit,
    onProbe: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            val vendor = UsbNames.vendorName(device.vendorId)?.let { "（$it）" } ?: ""
            Text(
                "VID:PID ${device.vidPid}$vendor · ${device.deviceName} · 版本 ${device.version ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            when (val sel = device.massStorage) {
                is MassStorageSelection.Selected -> StatusLine(true, "大容量存储（Bulk-Only）：${sel.summary()}")
                is MassStorageSelection.Unsupported -> StatusLine(false, "大容量存储，但暂不支持：${sel.reason}")
                MassStorageSelection.NotMassStorage -> StatusLine(null, "不是存储设备（本 App 会忽略它）")
            }
            StatusLine(device.hasPermission, if (device.hasPermission) "已获得 USB 权限" else "尚未获得 USB 权限")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!device.hasPermission) {
                    Button(onClick = onRequestPermission) { Text("申请权限") }
                } else {
                    Button(onClick = onProbe, enabled = !busy) { Text(if (report == null) "连接测试" else "重新测试") }
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("测试中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (report != null) {
                HorizontalDivider()
                ReportSection(report)
            }
        }
    }
}

@Composable
private fun ReportSection(report: ProbeReport) {
    var showDescriptors by rememberSaveable(report.startedAtMillis) { mutableStateOf(false) }
    var showRaw by rememberSaveable(report.startedAtMillis) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "连接测试${if (report.success) "成功" else "失败"}（${LogFormat.time(report.startedAtMillis)}，耗时 ${report.durationMs} ms）",
            style = MaterialTheme.typography.titleSmall,
            color = if (report.success) StatusColors.ok else MaterialTheme.colorScheme.error,
        )
        KeyValue("链路速度", report.effectiveSpeed.label)
        report.speedHint?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
        report.maxPowerMilliAmps?.let { KeyValue("声明最大电流", "$it mA") }
        report.maxLun?.let { KeyValue("Max LUN", "$it") }
        if (report.driversBefore.isNotEmpty()) {
            val before = report.driversBefore.entries.joinToString("；") { "接口 ${it.key}：${it.value}" }
            val after = report.driversAfter.entries.joinToString("；") { "接口 ${it.key}：${it.value}" }
            KeyValue("内核驱动", before + if (after.isNotEmpty()) " → 占用后 $after" else "")
        }
        for (s in report.steps) {
            StatusLine(if (s.ok) true else if (s.critical) false else null, "${s.title}（${s.millis} ms）：${s.detail}")
        }
        TextButton(onClick = { showDescriptors = !showDescriptors }) {
            Text(if (showDescriptors) "收起描述符详情" else "展开描述符详情")
        }
        if (showDescriptors) {
            report.parsed?.let { MonoBlock(it.describe().joinToString("\n")) }
        }
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "收起原始描述符" else "展开原始描述符（hex）")
        }
        if (showRaw) {
            report.rawDescriptors?.let { MonoBlock(Hex.dump(it)) }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text("$key：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** ok：true = 成功（绿 ✓），false = 失败（红 ✗），null = 提示（灰 ·）。 */
@Composable
private fun StatusLine(ok: Boolean?, text: String) {
    val mark: String
    val color: Color
    when (ok) {
        true -> {
            mark = "✓"
            color = StatusColors.ok
        }
        false -> {
            mark = "✗"
            color = MaterialTheme.colorScheme.error
        }
        null -> {
            mark = "·"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonoBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}
