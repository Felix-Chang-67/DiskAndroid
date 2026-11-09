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
import androidx.compose.material3.FilledTonalButton
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
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.MainViewModel
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.drive.DriveState
import io.github.felixchang67.diskandroid.core.drive.MediaStatus
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.usb.MassStorageSelection
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot
import io.github.felixchang67.diskandroid.core.usb.UsbNames
import io.github.felixchang67.diskandroid.drive.ActiveDrive
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

@Composable
fun DevicePane(vm: MainViewModel, actions: MainActions, modifier: Modifier = Modifier) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val drives by vm.drives.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
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
                drive = drives[d.deviceName],
                connecting = d.deviceName in connecting,
                onRequestPermission = { vm.requestPermission(d.deviceName) },
                onReconnect = { vm.reconnect(d.deviceName) },
                onEject = { vm.eject(d.deviceName) },
                onRefresh = { vm.refreshDrive(d.deviceName) },
            )
        }
        if (detached.isNotEmpty()) {
            item(key = "detached-title") {
                Text("已断开设备的连接报告", style = MaterialTheme.typography.titleSmall)
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
            Text("测试节点 ${BuildConfig.TEST_NODE}：光驱通信（BOT 传输 + 基础命令）", style = MaterialTheme.typography.titleMedium)
            Text("1. 接上光驱后 App 会自动连接，读取光驱型号、支持的盘片、功能和 DVD 地区码。")
            Text("2. 依次测试：不放盘 → 打开托盘 → 放入 CD / DVD / BD（等它显示“就绪”）→ 点“弹出光盘”或按光驱上的弹出键。")
            Text("3. 每一步看“盘片”状态和“事件”是否对得上。")
            Text("4. 最后点“复制诊断信息”，粘贴到对话里发给我。")
            Text(
                "本版本只发送白名单里的只读命令，不会写盘，也不会修改地区码。",
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
    drive: ActiveDrive?,
    connecting: Boolean,
    onRequestPermission: () -> Unit,
    onReconnect: () -> Unit,
    onEject: () -> Unit,
    onRefresh: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            val vendor = UsbNames.vendorName(device.vendorId)?.let { "（$it）" } ?: ""
            Text(
                "VID:PID ${device.vidPid}$vendor · ${device.deviceName} · 版本 ${device.version ?: "?"}" +
                    (report?.let { " · ${it.effectiveSpeed.label}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            when (val sel = device.massStorage) {
                is MassStorageSelection.Selected -> StatusLine(true, "大容量存储（Bulk-Only）：${sel.summary()}")
                is MassStorageSelection.Unsupported -> StatusLine(false, "大容量存储，但暂不支持：${sel.reason}")
                MassStorageSelection.NotMassStorage -> StatusLine(null, "不是存储设备（本 App 会忽略它）")
            }
            StatusLine(device.hasPermission, if (device.hasPermission) "已获得 USB 权限" else "尚未获得 USB 权限")
            when {
                !device.hasPermission -> Button(onClick = onRequestPermission) { Text("申请权限") }
                connecting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在连接光驱…")
                }
                drive != null -> DriveSection(drive.session, onEject, onRefresh, onReconnect)
                device.massStorage is MassStorageSelection.Selected -> {
                    if (report != null && !report.success) StatusLine(false, "连接失败，详情见下方 USB 连接详情")
                    Button(onClick = onReconnect) { Text("连接光驱") }
                }
            }
            report?.let { r ->
                Expandable("USB 连接详情", key = "usb:${r.startedAtMillis}") { ReportSection(r) }
            }
        }
    }
}

@Composable
private fun DriveSection(session: DriveSession, onEject: () -> Unit, onRefresh: () -> Unit, onReconnect: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val media by session.media.collectAsStateWithLifecycle()
    val info by session.info.collectAsStateWithLifecycle()
    val events by session.events.collectAsStateWithLifecycle()

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    val inquiry = info?.inquiry
    Text(
        if (inquiry != null) "光驱：${inquiry.vendor} ${inquiry.product}（固件 ${inquiry.revision}）" else "光驱：读取中…",
        style = MaterialTheme.typography.titleSmall,
    )
    KeyValue("会话", state.label())
    val mediaColor = when (media) {
        is MediaStatus.Ready -> StatusColors.ok
        is MediaStatus.BecomingReady -> StatusColors.warn
        is MediaStatus.NotReady -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("盘片：${media.label()}", style = MaterialTheme.typography.titleMedium, color = mediaColor)
    info?.features?.let { f ->
        val families = f.profiles.map { MmcNames.family(it.first) }.toSet()
        val names = listOf(MmcNames.DiscFamily.CD to "CD", MmcNames.DiscFamily.DVD to "DVD", MmcNames.DiscFamily.BD to "BD", MmcNames.DiscFamily.HD_DVD to "HD DVD")
            .filter { it.first in families }.joinToString(" / ") { it.second }
        KeyValue("支持的盘片", names.ifEmpty { "未知" } + "（共 ${f.profiles.size} 种 profile）")
        KeyValue("版权保护", "DVD CSS ${if (f.cssVersion != null) "支持" else "不支持"}；AACS ${f.aacs?.let { "支持（版本 ${it.version}）" } ?: "不支持"}")
    }
    info?.rpc?.let { KeyValue("DVD 地区码", it.summary()) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onEject, enabled = state is DriveState.Online) { Text("弹出光盘") }
        OutlinedButton(onClick = onRefresh, enabled = state is DriveState.Online) { Text("刷新信息") }
        OutlinedButton(onClick = onReconnect) { Text("重新连接") }
    }

    if (events.isNotEmpty()) {
        Text("事件（最新在上）", style = MaterialTheme.typography.labelLarge)
        events.takeLast(8).asReversed().forEach { e ->
            Text("${LogFormat.time(e.timeMs)}  ${e.message}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    info?.let { i ->
        Expandable("光驱详细信息", key = "info:${session.label}") { MonoBlock(i.describeLines().joinToString("\n")) }
        Expandable("原始响应（hex）", key = "raw:${session.label}") { MonoBlock(i.rawLines().joinToString("\n")) }
    }
}

@Composable
private fun ReportSection(report: ProbeReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "USB 连接${if (report.success) "成功" else "失败"}（${LogFormat.time(report.startedAtMillis)}，耗时 ${report.durationMs} ms）",
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
        Expandable("描述符详情", key = "desc:${report.startedAtMillis}") {
            report.parsed?.let { MonoBlock(it.describe().joinToString("\n")) }
        }
        Expandable("原始描述符（hex）", key = "rawdesc:${report.startedAtMillis}") {
            report.rawDescriptors?.let { MonoBlock(Hex.dump(it)) }
        }
    }
}

@Composable
private fun Expandable(title: String, key: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text((if (open) "收起" else "展开") + title) }
    if (open) content()
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
