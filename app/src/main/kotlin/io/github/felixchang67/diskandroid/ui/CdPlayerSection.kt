@file:OptIn(ExperimentalLayoutApi::class)

package io.github.felixchang67.diskandroid.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import io.github.felixchang67.diskandroid.core.meta.DiscMetadata
import io.github.felixchang67.diskandroid.core.meta.MetadataCandidate
import io.github.felixchang67.diskandroid.meta.MetadataManager
import io.github.felixchang67.diskandroid.meta.TrackNames
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import io.github.felixchang67.diskandroid.player.stateFor
import io.github.felixchang67.diskandroid.ui.theme.StatusColors

/** 音乐 CD：专辑信息（在线识别 / CD-TEXT）、播放控制、歌词、曲目列表、播放设置。 */
@Composable
fun CdPlayerSection(
    session: DriveSession,
    disc: DiscInfo,
    player: CdPlaybackController,
    meta: MetadataManager,
    onPlayTrack: (Int) -> Unit,
) {
    val album = remember(disc) { CdAlbum.from(disc) } ?: return
    val discId = remember(disc) { meta.discIdOf(disc) }
    val discs by meta.discs.collectAsStateWithLifecycle()
    val ds = discId?.let { discs[it] }
    val md = ds?.metadata
    val covers by meta.covers.collectAsStateWithLifecycle()
    val online by meta.online.collectAsStateWithLifecycle()
    val state by remember(session, disc) { player.stateFor(session, disc) }.collectAsStateWithLifecycle(null)
    val byteOrder by player.byteOrder.collectAsStateWithLifecycle()
    val deemphasis by player.deemphasis.collectAsStateWithLifecycle()
    val autoplayEnabled by player.autoplayEnabled.collectAsStateWithLifecycle()
    val autoplay by player.autoplay.collectAsStateWithLifecycle()
    LaunchedEffect(md?.coverUrl) { md?.coverUrl?.let { meta.requestCover(it) } }

    // 日文内容按日语区域选字形（系统是中文时，汉字默认会用简体中文字形，如"直""骨"的写法不同）
    val japanese = disc.cdText?.preferred?.isJapanese() == true ||
        TrackNames.looksJapanese(md?.album, md?.artist, *md?.tracks?.map { it.title }.orEmpty().toTypedArray())
    val textLocale = if (japanese) JA else null
    val names = Names(disc, md)

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("音乐 CD", style = MaterialTheme.typography.labelLarge)
        autoplay?.takeIf { it.session === session }?.let { pending ->
            AutoplayBanner(pending.startAtMs, TrackNames.album(disc, md), textLocale, onCancel = { player.cancelAutoplay() }, onNow = { player.autoplayNow() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            md?.coverUrl?.let { covers[it] }?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), contentDescription = "专辑封面",
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    TrackNames.album(disc, md) ?: if (ds?.busy != null) "正在识别…" else "（未识别：曲名显示为 Track N）",
                    style = MaterialTheme.typography.titleMedium.locale(textLocale),
                )
                TrackNames.albumArtist(disc, md)?.let { Text(it, style = MaterialTheme.typography.bodyMedium.locale(textLocale)) }
                val extra = listOfNotNull(md?.date, md?.label, md?.edition).joinToString(" · ")
                if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall.locale(textLocale), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${album.tracks.size} 首 · ${formatMs(album.durationMs)}" +
                        (disc.cdText?.preferred?.let { " · CD-TEXT ${it.languageName()}" } ?: " · 无 CD-TEXT"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (discId != null) IdentifyBlock(discId, ds, md, online, meta, textLocale)

        PlayerBar(album, names, state, player, textLocale, onPlayTrack)
        val current = state?.takeIf { it.active }?.track
        if (current != null && discId != null && md != null) {
            LyricsBlock(meta, discId, current, state?.positionMs ?: 0, textLocale)
        }

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            album.tracks.forEach { t ->
                TrackRow(t, names, current = current?.number == t.number, textLocale) { onPlayTrack(t.number) }
            }
        }

        Text("播放设置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        Text(
            "无缝播放（gapless）：一首播完紧接着下一首，中间不留空白；播放中按“下一首”（提前预读好开头）、点别的曲目或拖动进度，声音都不中断。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = autoplayEnabled, onCheckedChange = { player.setAutoplay(it) })
            Text("放入音乐 CD 后自动播放（2 秒后开始，可取消）", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = online, onCheckedChange = { meta.setOnline(it) })
            Text(
                "联网识别曲名、封面和歌词（MusicBrainz、网易云音乐；只发送光盘目录、条码和搜索词）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
    }
}

/** "2 秒后播放"横幅：倒计时、取消、立即播放。 */
@Composable
private fun AutoplayBanner(startAtMs: Long, album: String?, textLocale: LocaleList?, onCancel: () -> Unit, onNow: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(100)
        }
    }
    val left = ((startAtMs - now).coerceAtLeast(0) + 999) / 1000
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$left 秒后播放" + (album?.let { "「$it」" } ?: ""),
            style = MaterialTheme.typography.bodyMedium.locale(textLocale),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("取消") }
        FilledTonalButton(onClick = onNow) { Text("立即播放") }
    }
}

/** 显示名：在线信息 > CD-TEXT > Track N。 */
private class Names(val disc: DiscInfo, val md: DiscMetadata?) {
    fun title(n: Int) = TrackNames.title(disc, md, n)
    fun translated(n: Int) = TrackNames.translated(md, n)
    fun artist(n: Int) = TrackNames.artist(disc, md, n)
    val albumArtist: String? get() = TrackNames.albumArtist(disc, md)
}

@Composable
private fun IdentifyBlock(
    discId: String,
    ds: MetadataManager.DiscState?,
    md: DiscMetadata?,
    online: Boolean,
    meta: MetadataManager,
    textLocale: LocaleList?,
) {
    var expanded by rememberSaveable(discId) { mutableStateOf(false) }
    var keywords by rememberSaveable(discId) { mutableStateOf("") }
    val busy = ds?.busy
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            when {
                busy != null -> busy
                ds?.error != null -> ds.error ?: ""
                md != null -> "曲目信息：${md.sources.joinToString(" + ")}" + if (ds?.fromCache == true) "（本机缓存）" else ""
                !online -> "联网识别已关闭"
                else -> ds?.notes?.lastOrNull() ?: "等待识别"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (ds?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else if (md == null) "手动识别" else "结果不对？") }
    }
    if (!expanded) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ds?.notes?.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val others = ds?.candidates.orEmpty().filter { it.metadata.mbReleaseId != md?.mbReleaseId || it.metadata.neteaseAlbumId != md?.neteaseAlbumId }
        if (others.isNotEmpty()) {
            Text("其他候选", style = MaterialTheme.typography.labelLarge)
            others.forEach { c -> CandidateRow(c, textLocale) { meta.choose(discId, c) } }
        }
        Text("手动搜索（输入专辑名、艺人，日文或中文都可以）", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                singleLine = true,
                placeholder = { Text(listOfNotNull(md?.artist, md?.album).joinToString(" ").ifEmpty { "例如：ヒトリエ DEEPER" }) },
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { meta.search(discId, keywords) }, enabled = online && busy == null && keywords.isNotBlank()) { Text("搜索") }
        }
        ds?.searchNotes?.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ds?.searchResults?.let { results ->
            if (results.isEmpty()) Text("没有找到，换个关键词试试", style = MaterialTheme.typography.bodySmall)
            results.forEach { c -> CandidateRow(c, textLocale) { meta.choose(discId, c) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { meta.reidentify(discId) }, enabled = online && busy == null) { Text("重新联网识别") }
        }
    }
}

@Composable
private fun CandidateRow(c: MetadataCandidate, textLocale: LocaleList?, onChoose: () -> Unit) {
    val m = c.metadata
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(m.album, m.artist).joinToString(" · "), style = MaterialTheme.typography.bodyMedium.locale(textLocale), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(m.date, m.edition, m.country, c.score.label(), c.source).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (c.score.value >= 0.8) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onChoose) { Text("选用") }
    }
}

@Composable
private fun LyricsBlock(meta: MetadataManager, discId: String, track: CdTrack, positionMs: Long, textLocale: LocaleList?) {
    LaunchedEffect(discId, track.number) { meta.requestLyrics(discId, track.number, track.durationMs) }
    val all by meta.lyrics.collectAsStateWithLifecycle()
    val st = all[meta.lyricsKey(discId, track.number)]
    var showAll by rememberSaveable(discId, track.number) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (st) {
            null, MetadataManager.LyricsState.Loading -> Text("正在获取歌词…", style = MaterialTheme.typography.bodySmall)
            MetadataManager.LyricsState.None -> Text("没有找到这首歌的歌词", style = MaterialTheme.typography.bodySmall)
            MetadataManager.LyricsState.Offline -> Text("联网已关闭，没有歌词", style = MaterialTheme.typography.bodySmall)
            is MetadataManager.LyricsState.Failed -> Text("获取歌词失败：${st.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is MetadataManager.LyricsState.Ready -> {
                val lyrics = st.lyrics
                val index = lyrics.indexAt(positionMs)
                val range = when {
                    showAll || !lyrics.synced -> if (showAll) lyrics.lines.indices else 0 until minOf(6, lyrics.lines.size)
                    else -> (index - 2).coerceAtLeast(0)..(index + 3).coerceAtMost(lyrics.lines.size - 1)
                }
                for (i in range) {
                    val line = lyrics.lines[i]
                    val isCurrent = i == index
                    Text(
                        line.text,
                        style = (if (isCurrent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium).locale(textLocale),
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    line.translation?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "歌词来自 ${lyrics.source}" + (lyrics.credits?.let { " · $it" } ?: "") + (if (!lyrics.synced) " · 无时间轴" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "收起" else "全部歌词") }
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(album: CdAlbum, names: Names, state: PlayerState?, player: CdPlaybackController, textLocale: LocaleList?, onPlayTrack: (Int) -> Unit) {
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
            track?.let { "${it.number}. ${names.title(it.number)}" } ?: "—",
            style = MaterialTheme.typography.titleSmall.locale(textLocale),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        track?.let { t ->
            // 中文译名不套日语字形；艺人名套
            names.translated(t.number)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            names.artist(t.number)?.takeIf { it != names.albumArtist }?.let { Text(it, style = MaterialTheme.typography.bodySmall.locale(textLocale)) }
        }

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
private fun TrackRow(t: CdTrack, names: Names, current: Boolean, textLocale: LocaleList?, onClick: () -> Unit) {
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
                names.title(t.number),
                style = MaterialTheme.typography.bodyLarge.locale(textLocale),
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            names.translated(t.number)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val sub = listOfNotNull(names.artist(t.number)?.takeIf { it != names.albumArtist }, "预加重".takeIf { t.preEmphasis }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall.locale(textLocale), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatMs(t.durationMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

private val JA = LocaleList("ja-JP")

private fun TextStyle.locale(locale: LocaleList?): TextStyle = if (locale == null) this else copy(localeList = locale)
