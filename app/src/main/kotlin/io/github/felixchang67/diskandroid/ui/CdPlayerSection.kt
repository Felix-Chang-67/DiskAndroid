@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.core.cdda.ByteOrderMode
import io.github.felixchang67.diskandroid.core.cdda.CdAlbum
import io.github.felixchang67.diskandroid.core.cdda.CdTrack
import io.github.felixchang67.diskandroid.core.cdda.PlayerState
import io.github.felixchang67.diskandroid.core.cdda.formatMs
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import io.github.felixchang67.diskandroid.player.stateFor
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

/** 音乐 CD：专辑信息（CD-TEXT）、播放控制、曲目列表、播放设置。 */
@Composable
fun CdPlayerSection(session: DriveSession, disc: DiscInfo, player: CdPlaybackController, onPlayTrack: (Int) -> Unit) {
    val album = remember(disc) { CdAlbum.from(disc) } ?: return
    // 日文 CD-TEXT：按日语区域选字形（系统是中文时，汉字默认会用简体中文字形，如"直""骨"的写法不同）
    val textLocale = if (disc.cdText?.preferred?.isJapanese() == true) JA else null
    val state by remember(session, disc) { player.stateFor(session, disc) }.collectAsStateWithLifecycle(null)
    val byteOrder by player.byteOrder.collectAsStateWithLifecycle()
    val deemphasis by player.deemphasis.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("音乐 CD", style = MaterialTheme.typography.labelLarge)
        Text(album.title ?: "（这张盘没有 CD-TEXT，曲名显示为 Track N）", style = MaterialTheme.typography.titleMedium.locale(textLocale))
        album.performer?.let { Text(it, style = MaterialTheme.typography.bodyMedium.locale(textLocale)) }
        disc.cdText?.secondary?.albumTitle?.takeIf { it != album.title }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${album.tracks.size} 首 · ${formatMs(album.durationMs)}" +
                (disc.cdText?.preferred?.let { " · CD-TEXT ${it.languageName()}（${it.charCodeName()}）" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PlayerBar(album, state, player, textLocale, onPlayTrack)

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            album.tracks.forEach { t ->
                TrackRow(t, album, current = state?.takeIf { it.active }?.track?.number == t.number, textLocale) { onPlayTrack(t.number) }
            }
        }

        Text("播放设置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("字节序", Modifier.align(Alignment.CenterVertically))
            ByteOrderMode.entries.forEach { mode ->
                FilterChip(selected = byteOrder == mode, onClick = { player.setByteOrder(mode) }, label = { Text(mode.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = deemphasis, onCheckedChange = { player.setDeemphasis(it) })
            Text("去加重（只作用于标有“预加重”的音轨，早期日本 CD 常见）", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "如果播放出来全是沙沙的噪音，把字节序改成“大端”试试，并告诉我。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayerBar(album: CdAlbum, state: PlayerState?, player: CdPlaybackController, textLocale: LocaleList?, onPlayTrack: (Int) -> Unit) {
    val s = state
    if (s == null || s.status == PlayerState.Status.IDLE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onPlayTrack(album.first.number) }) { Text("▶ 从头播放") }
            Text("或点下面的曲目", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val track = s.track
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            track?.let { "${it.number}. ${it.title}" } ?: "—",
            style = MaterialTheme.typography.titleSmall.locale(textLocale),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        track?.performer?.takeIf { it != album.performer }?.let { Text(it, style = MaterialTheme.typography.bodySmall.locale(textLocale)) }

        val duration = track?.durationMs ?: 0
        var dragging by remember(track?.number) { mutableStateOf<Float?>(null) }
        val fraction = dragging ?: if (duration > 0) (s.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { f -> player.seekTo((f * duration).toLong()) }
                dragging = null
            },
            enabled = s.active && duration > 0,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val shownMs = dragging?.let { (it * duration).toLong() } ?: s.positionMs
            Text("${formatMs(shownMs)} / ${formatMs(duration)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                "  ${s.status.label}" + (if (s.badSectors > 0) " · 已静音 ${s.badSectors} 个读不出的扇区" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = when (s.status) {
                    PlayerState.Status.ERROR -> MaterialTheme.colorScheme.error
                    PlayerState.Status.BUFFERING -> StatusColors.warn
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { player.previous() }, enabled = s.active) { Text("⏮ 上一首") }
            FilledTonalButton(onClick = { player.togglePause() }) {
                Text(if (s.active && s.status != PlayerState.Status.PAUSED) "⏸ 暂停" else "▶ 播放")
            }
            OutlinedButton(onClick = { player.next() }, enabled = s.active) { Text("下一首 ⏭") }
            OutlinedButton(onClick = { player.stop() }, enabled = s.active) { Text("⏹ 停止") }
        }
        Text(
            "字节序：${s.byteOrder}" + (if (track?.preEmphasis == true) " · 本轨有预加重，去加重${if (s.deemphasis) "已开启" else "已关闭"}" else "") +
                " · 缓冲 ${s.bufferedMs / 100 / 10.0} 秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackRow(t: CdTrack, album: CdAlbum, current: Boolean, textLocale: LocaleList?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (current) "▶" else "%02d".format(t.number)),
            Modifier.width(32.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge.locale(textLocale),
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            t.performer?.takeIf { it != album.performer }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.locale(textLocale), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.preEmphasis) Text("预加重", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatMs(t.durationMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

private val JA = LocaleList("ja-JP")

private fun TextStyle.locale(locale: LocaleList?): TextStyle = if (locale == null) this else copy(localeList = locale)
