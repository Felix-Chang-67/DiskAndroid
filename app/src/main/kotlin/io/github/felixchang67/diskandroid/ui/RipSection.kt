@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.rip.RipEngine
import io.github.felixchang67.diskandroid.core.rip.RipOptions
import io.github.felixchang67.diskandroid.core.rip.RipPlan
import io.github.felixchang67.diskandroid.core.rip.RipResult
import io.github.felixchang67.diskandroid.rip.RipController
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

/** 读成 ISO：保存位置、块大小 / 重试次数、开始 / 取消、进度、结果。 */
@Composable
fun RipSection(session: DriveSession, disc: DiscInfo, rip: RipController, onStart: (RipOptions) -> Unit) {
    val build = remember(disc) { RipPlan.from(disc) }
    val folder by rip.folder.collectAsStateWithLifecycle()
    val state by rip.state.collectAsStateWithLifecycle()
    val mine = state?.takeIf { it.session === session }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { rip.setFolder(it) } }
    val defaultBlock = if (disc.family == MmcNames.DiscFamily.CD) 32 else 64
    var block by rememberSaveable(disc.family) { mutableIntStateOf(defaultBlock) }
    var retries by rememberSaveable { mutableIntStateOf(3) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("读成 ISO", style = MaterialTheme.typography.labelLarge)
        if (build is RipPlan.Build.Refused) {
            Text(build.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val plan = (build as RipPlan.Build.Ok).plan
        val maxKBps = disc.performance?.maxKBps?.takeIf { it > 0 }
        Text(
            "${DiscInfo.formatBytes(plan.bytes)}（${plan.totalSectors} 扇区）" +
                (if (plan.gapSectors > 0) "，其中 ${plan.gapSectors} 扇区补零" else "") +
                (maxKBps?.let { "；按光驱报告的最高 $it kB/s，至少需要 ${plan.bytes / (it * 1000) / 60 + 1} 分钟" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        plan.warnings.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }

        when {
            mine?.running == true -> RipProgressView(mine, rip)
            mine?.result != null || mine?.error != null -> RipResultView(mine, rip)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "保存到：" + (folder?.let { f -> rip.folderLabel(f) + (rip.freeBytes(f)?.let { " · 剩余 ${DiscInfo.formatBytes(it)}" } ?: "") } ?: "（还没选）"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { picker.launch(folder) }) { Text(if (folder == null) "选择文件夹" else "更改") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("每次读", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                    listOf(16, 32, 64, 128).forEach { n -> FilterChip(selected = block == n, onClick = { block = n }, label = { Text("$n 扇区") }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("坏扇区重试", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                    listOf(1, 3, 5).forEach { n -> FilterChip(selected = retries == n, onClick = { retries = n }, label = { Text("$n 次") }) }
                }
                FilledTonalButton(onClick = { onStart(RipOptions(blockSectors = block, retries = retries)) }, enabled = folder != null && !rip.running) {
                    Text("开始读取")
                }
                Text(
                    "读盘期间请不要在最近任务里划掉 DiskAndroid（HyperOS 会强制停止它）。镜像、.log 报告和 .mapfile 保存在同一个文件夹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RipProgressView(s: RipController.State, rip: RipController) {
    val p = s.progress
    val fraction = p?.let { (it.doneSectors.toFloat() / it.totalSectors.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    Text("正在读：${s.imageName ?: "准备中…"}", style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    if (p != null) {
        val elapsed = (System.currentTimeMillis() - s.startedAtMs) / 1000
        Text(
            "第 ${p.pass} 遍 · ${"%.1f".format(fraction * 100)}% · ${"%.1f".format(p.bytesPerSecond / 1e6)} MB/s（约 ${"%.1f".format(RipEngine.speedFactor(p.bytesPerSecond, s.family))}x）",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "已用 ${mmss(elapsed)}" + (p.etaSeconds?.let { " · 剩余约 ${mmss(it)}" } ?: "") +
                " · 坏扇区 ${p.badSectors}" + (if (p.pendingSectors > 0) " · 待重读 ${p.pendingSectors}" else "") + " · ${p.phase}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(onClick = { rip.cancel() }, enabled = !s.cancelling) { Text(if (s.cancelling) "正在取消…" else "取消") }
}

@Composable
private fun RipResultView(s: RipController.State, rip: RipController) {
    val r = s.result
    if (r == null) {
        Text(s.error ?: "出错", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else {
        Text(
            r.status.label + (r.message?.let { "：$it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = when (r.status) {
                RipResult.Status.COMPLETED -> StatusColors.ok
                RipResult.Status.COMPLETED_WITH_ERRORS -> StatusColors.warn
                else -> MaterialTheme.colorScheme.error
            },
        )
        Text(
            "用时 ${mmss(r.elapsedMs / 1000)} · 读出 ${DiscInfo.formatBytes(r.readSectors * RipPlan.SECTOR)}" +
                (if (r.badSectors.isNotEmpty()) " · 坏扇区 ${r.badSectors.size}（已补零）" else "") +
                (if (r.recoveredSectors > 0) " · 第 2 遍补读回 ${r.recoveredSectors} 扇区" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        r.volume?.volumeId?.let { Text("卷标：$it", style = MaterialTheme.typography.bodySmall) }
        if (r.sha1 != null) {
            SelectionContainer {
                Text("MD5 ${r.md5}\nSHA-1 ${r.sha1}\nCRC32 ${r.crc32}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Text("已保存：" + s.files.joinToString("、"), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { rip.dismissResult() }) { Text("好的") }
}

private fun mmss(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
