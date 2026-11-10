@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.video.VideoDiscInfo
import io.github.felixchang67.diskandroid.core.video.VideoFormat
import io.github.felixchang67.diskandroid.core.video.VideoTitle
import io.github.felixchang67.diskandroid.core.video.formatDuration
import io.github.felixchang67.diskandroid.diag.Decoders
import io.github.felixchang67.diskandroid.files.DiscBrowser
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

/** 视频盘：标题 / 播放列表、自动挑的主片（可以手动改选）、音轨和字幕、这台平板有没有对应的解码器。 */
@Composable
fun VideoSection(session: DriveSession, disc: DiscInfo, browser: DiscBrowser) {
    val states by browser.states.collectAsStateWithLifecycle()
    val s = states[session]?.takeIf { it.fingerprint == CdPlaybackController.fingerprint(disc) } ?: return
    val v = s.video ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("视频内容", style = MaterialTheme.typography.labelLarge)
        val info = v.info
        when {
            v.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在读取标题列表…", style = MaterialTheme.typography.bodySmall)
            }
            info == null -> Text(v.error?.let { "读不出标题列表：$it" } ?: "没有识别出视频结构", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Titles(v, info, session, disc, browser)
        }
    }
}

@Composable
private fun Titles(v: DiscBrowser.VideoState, info: VideoDiscInfo, session: DriveSession, disc: DiscInfo, browser: DiscBrowser) {
    Text(
        "${info.format.label} · ${info.titles.size} 个标题" +
            (if (info.filtered > 0) "（另有 ${info.filtered} 个重复 / 循环的播放列表已略过）" else "") + " · 读取用时 ${v.millis} ms",
        style = MaterialTheme.typography.bodySmall,
    )
    val protection = when {
        disc.dvdCopyright?.isCss == true -> "CSS 加密：标题列表可以看，播放要等阶段 3 的解密组件"
        info.aacs -> "AACS 加密：标题列表可以看，播放要等阶段 3 的解密组件"
        info.format == VideoFormat.DVD_VIDEO -> "没有加密：播放在下一个测试节点（N2.3）加入"
        else -> "没有加密：播放在 N2.4 加入"
    }
    Text(protection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    info.warnings.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }

    val chosen = v.chosen
    if (chosen != null) {
        TitleCard(chosen, auto = chosen === info.main && v.selected == null)
        DecoderLine(chosen)
        if (v.selected != null) TextButton(onClick = { browser.selectTitle(session, null) }) { Text("改回自动挑的主片（${info.main?.id ?: "无"}）") }
    }
    HorizontalDivider()
    var showShort by rememberSaveable(info) { mutableStateOf(false) }
    var showAll by rememberSaveable(info) { mutableStateOf(false) }
    val others = info.titles.filter { it !== chosen && (showShort || it.durationMs >= VideoDiscInfo.SHORT_MS) }
    if (others.isNotEmpty()) Text("其他标题", style = MaterialTheme.typography.labelMedium)
    others.take(if (showAll) others.size else 8).forEach { t ->
        TitleRow(t, isMain = t === info.main) { browser.selectTitle(session, t.id) }
    }
    if (!showAll && others.size > 8) TextButton(onClick = { showAll = true }) { Text("显示全部 ${others.size} 个") }
    val hiddenShort = info.titles.count { it !== chosen && it.durationMs < VideoDiscInfo.SHORT_MS }
    if (!showShort && hiddenShort > 0) {
        TextButton(onClick = { showShort = true }) { Text("显示 $hiddenShort 个不到 10 秒的标题（片头警告、菜单背景之类）") }
    }
}

@Composable
private fun TitleCard(t: VideoTitle, auto: Boolean) {
    Text(
        (if (auto) "主片（自动挑选）：" else "选中的标题：") + "${t.id} · ${formatDuration(t.durationMs)} · ${t.chapters} 章" +
            (if (t.angles > 1) " · ${t.angles} 个角度" else "") + (t.sizeBytes?.let { " · ${DiscInfo.formatBytes(it)}" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text("视频：${t.video}", style = MaterialTheme.typography.bodySmall)
    if (t.audio.isEmpty()) {
        Text("音轨：无", style = MaterialTheme.typography.bodySmall)
    } else {
        t.audio.forEachIndexed { i, a -> Text("音轨 ${i + 1}：$a", style = MaterialTheme.typography.bodySmall) }
    }
    if (t.subtitles.isNotEmpty()) {
        Text("字幕：" + t.subtitles.joinToString("、") { it.languageName + (it.note?.let { n -> "（$n）" } ?: "") }, style = MaterialTheme.typography.bodySmall)
    }
    t.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun TitleRow(t: VideoTitle, isMain: Boolean, onChoose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "${t.id}${if (isMain) "（自动挑的主片）" else ""} · ${formatDuration(t.durationMs)} · ${t.chapters} 章",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                (listOf(t.video) + t.audio.take(3).map { it.toString() }).joinToString(" · ") + if (t.audio.size > 3) " …" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        TextButton(onClick = onChoose) { Text("选这个") }
    }
}

/** 选中标题要用到的解码器在这台平板上有没有。 */
@Composable
private fun DecoderLine(t: VideoTitle) {
    // 视频说明的第一个词是编码（"MPEG-2 PAL …"、"H.264/AVC 1080i …"）
    val codecs = listOf(t.video.substringBefore(' ')) + t.audio.map { it.codec }
    val keys = codecs.mapNotNull { Decoders.keyFor(it) }.distinct()
    if (keys.isEmpty()) return
    val parts = keys.mapNotNull { k -> Decoders.find(k) }.map { f ->
        (if (f.available) "✓ " else "✗ ") + f.need.label + if (f.available) "" else "（设备没有，播放时用软件解码）"
    }
    Text("解码器：" + parts.joinToString("；"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
