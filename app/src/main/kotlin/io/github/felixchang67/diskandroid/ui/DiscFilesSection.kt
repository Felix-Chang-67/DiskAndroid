@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.fs.DiscEntry
import io.github.felixchang67.diskandroid.files.DiscBrowser
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import io.github.felixchang67.diskandroid.ui.theme.StatusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 盘上的文件：文件系统、卷标、盘片内容，逐级浏览目录，复制文件 / 文件夹到保存位置。 */
@Composable
fun DiscFilesSection(session: DriveSession, disc: DiscInfo, browser: DiscBrowser, onCopy: (List<DiscEntry>) -> Unit) {
    val fingerprint = CdPlaybackController.fingerprint(disc)
    val states by browser.states.collectAsStateWithLifecycle()
    val s = states[session]?.takeIf { it.fingerprint == fingerprint }
    // 音乐 CD（加强版 CD）上自动读会和自动播放抢光驱：等用户点了再读
    val autoLoad = !disc.hasAudio
    LaunchedEffect(session, fingerprint) { if (autoLoad) browser.ensureLoaded(session, disc) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("盘上的文件", style = MaterialTheme.typography.labelLarge)
        when {
            s == null && !autoLoad -> OutlinedButton(onClick = { browser.ensureLoaded(session, disc) }) { Text("查看数据区段里的文件") }
            s == null || s.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在读取文件系统…", style = MaterialTheme.typography.bodySmall)
            }
            s.info == null -> {
                Text(s.error ?: "读不出文件系统", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { browser.retry(session, disc) }) { Text("重试") }
            }
            else -> Browser(s, s.info, session, disc, browser, onCopy)
        }
    }
}

@Composable
private fun Browser(s: DiscBrowser.State, info: DiscBrowser.VolumeInfo, session: DriveSession, disc: DiscInfo, browser: DiscBrowser, onCopy: (List<DiscEntry>) -> Unit) {
    val volume = info.volume
    Text(
        (volume.label?.let { "「$it」 · " } ?: "") + volume.description,
        style = MaterialTheme.typography.bodySmall,
    )
    info.content?.let { c ->
        Text(
            "内容：${c.summary}" + (if (info.fileSystemEnd != null) " · 文件系统占 ${DiscInfo.formatBytes(info.fileSystemEnd * 2048)}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (c.isVideo) StatusColors.ok else MaterialTheme.colorScheme.onSurface,
        )
        c.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (c.isVideo) Text("· 视频播放在下一个测试节点（N2.2）加入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info.error?.let { Text("打不开文件系统：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val fs = info.fs ?: return
    val refusal = browser.copyRefusal(disc, info)
    val copy by browser.copy.collectAsStateWithLifecycle()
    val folder by browser.folder.uri.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { browser.folder.set(it) } }
    val mine = copy?.takeIf { it.session === session }

    // 路径
    FlowRow(verticalArrangement = Arrangement.Center) {
        s.path.forEachIndexed { i, dir ->
            if (i > 0) Text(" › ", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { browser.goTo(session, i) }, enabled = i < s.path.size - 1) {
                Text(if (i == 0) (volume.label ?: "根目录") else dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (s.listing) LinearProgressIndicator(Modifier.fillMaxWidth())
    s.listError?.let { Text("列目录失败：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val entries = s.entries.orEmpty()
    var shown by rememberSaveable(s.path.lastOrNull()?.path) { mutableIntStateOf(PAGE) }
    if (!s.listing && entries.isEmpty() && s.listError == null) Text("（空文件夹）", style = MaterialTheme.typography.bodySmall)
    val canCopy = refusal == null && folder != null && mine?.running != true
    entries.take(shown).forEach { e -> EntryRow(e, canCopy, onOpen = { browser.enter(session, e) }, onCopy = { onCopy(listOf(e)) }) }
    if (entries.size > shown) {
        TextButton(onClick = { shown += PAGE }) { Text("再显示 ${minOf(PAGE, entries.size - shown)} 个（共 ${entries.size} 个）") }
    }
    if (entries.isNotEmpty()) {
        val files = entries.count { !it.isDirectory }
        val bytes = entries.filter { !it.isDirectory }.sumOf { it.size }
        Text("${entries.size - files} 个文件夹，$files 个文件（${size(bytes)}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()

    // 复制
    if (refusal != null) {
        Text(refusal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        when {
            mine?.running == true -> CopyProgress(mine, browser)
            mine?.result != null -> CopyResult(mine, browser)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "复制到：" + (folder?.let { f -> browser.folder.label(f) + (browser.folder.freeBytes(f)?.let { " · 剩余 ${DiscInfo.formatBytes(it)}" } ?: "") } ?: "（还没选）"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { picker.launch(folder) }) { Text(if (folder == null) "选择文件夹" else "更改") }
                }
                val here = s.path.lastOrNull()
                if (here != null) {
                    FilledTonalButton(onClick = { onCopy(listOf(here)) }, enabled = canCopy && entries.isNotEmpty()) {
                        Text(if (s.path.size == 1) "复制整张盘的文件" else "复制「${here.name}」文件夹")
                    }
                }
                Text(
                    "复制到和读成 ISO 相同的保存位置；文件夹会原样建好。读不出的扇区重试 3 次，仍不行就补零并在结果里列出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(e: DiscEntry, canCopy: Boolean, onOpen: () -> Unit, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = e.isDirectory, onClick = onOpen)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (e.isDirectory) "📁" else "📄", style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(if (e.isDirectory) null else size(e.size), e.modifiedMillis?.let { date(it) }, "隐藏".takeIf { e.hidden }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (!e.isDirectory) TextButton(onClick = onCopy, enabled = canCopy) { Text("复制") }
    }
}

@Composable
private fun CopyProgress(c: DiscBrowser.CopyState, browser: DiscBrowser) {
    val fraction = if (c.totalBytes > 0) (c.doneBytes.toFloat() / c.totalBytes).coerceIn(0f, 1f) else 0f
    Text(c.title, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Text(
        "${c.doneFiles}/${c.totalFiles} 个文件 · ${size(c.doneBytes)} / ${size(c.totalBytes)} · ${"%.1f".format(c.bytesPerSecond / 1e6)} MB/s",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    c.currentFile?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    OutlinedButton(onClick = { browser.cancelCopy() }, enabled = !c.cancelling) { Text(if (c.cancelling) "正在取消…" else "取消") }
}

@Composable
private fun CopyResult(c: DiscBrowser.CopyState, browser: DiscBrowser) {
    Text(
        c.result ?: "",
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            c.failed -> MaterialTheme.colorScheme.error
            c.problems.isNotEmpty() -> StatusColors.warn
            else -> StatusColors.ok
        },
    )
    c.problems.take(10).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }
    if (c.problems.size > 10) Text("……另有 ${c.problems.size - 10} 个，见日志", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { browser.dismissCopy() }) { Text("好的") }
}

private const val PAGE = 100

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun date(ms: Long): String = DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes 字节"
}
