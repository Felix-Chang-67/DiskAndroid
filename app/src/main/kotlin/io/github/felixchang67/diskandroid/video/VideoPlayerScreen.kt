package io.github.felixchang67.diskandroid.video

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.felixchang67.diskandroid.core.player.DiscPlayer
import io.github.felixchang67.diskandroid.core.video.Languages
import io.github.felixchang67.diskandroid.core.video.formatDuration
import kotlinx.coroutines.delay

/**
 * 播放画面 + 操作层。
 * - 菜单里（有按钮）：点画面 = 选中并确定那里的按钮；按住拖动 = 移动高亮；右上角"操作"打开控制栏；也可以打开方向键。
 * - 播放中：点画面显示 / 隐藏控制栏（几秒不动自动隐藏）。
 */
@Composable
fun VideoPlayerScreen(video: VideoPlaybackController, onClose: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val screen by video.screen.collectAsStateWithLifecycle()
        val sc = screen
        BackHandler(onBack = onClose)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            VideoSurface(video)
            if (sc != null) Overlay(video, sc, onClose)
        }
    }
}

@Composable
private fun VideoSurface(video: VideoPlaybackController) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = video.setSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = video.setSurface(holder.surface)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = video.setSurface(null)
                })
            }
        },
    )
}

@Composable
private fun BoxScope.Overlay(video: VideoPlaybackController, sc: VideoPlaybackController.Screen, onClose: () -> Unit) {
    val s = sc.state
    var controls by remember { mutableStateOf(true) }
    var dpad by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val inMenu = s?.inMenu == true
    val playing = s != null && !s.paused && !s.ended && sc.error == null

    // 播放中屏幕常亮（暂停、结束、出错后恢复正常息屏）
    val view = LocalView.current
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    // 进入菜单：收起控制栏，别挡住菜单按钮
    LaunchedEffect(inMenu) { if (inMenu) controls = false }
    // 播放中几秒不动就收起控制栏
    LaunchedEffect(controls, touched, playing, inMenu) {
        if (controls && playing && !inMenu) {
            delay(HIDE_MS)
            controls = false
        }
    }

    // 触摸层：和画面一样大，坐标就是 Surface 上的像素
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    touched = System.currentTimeMillis()
                    if (!video.tapMenu(pos.x, pos.y, size.width, size.height)) controls = !controls
                })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> video.hoverMenu(change.position.x, change.position.y, size.width, size.height) }
            },
    )

    when {
        sc.error != null -> CenterCard {
            Text(sc.error, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sc.error != VideoPlaybackController.PROTECTED_MESSAGE) OutlinedButton(onClick = { video.restart() }) { Text("重新播放") }
                Button(onClick = onClose) { Text("关闭") }
            }
        }
        sc.opening -> CenterCard {
            CircularProgressIndicator()
            Text("正在读取光盘…", style = MaterialTheme.typography.titleMedium)
        }
        s?.ended == true -> CenterCard {
            Text("播放结束", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { video.restart() }) { Text("从头再播") }
                Button(onClick = onClose) { Text("关闭") }
            }
        }
    }

    sc.notice?.let { Notice(it, Modifier.align(Alignment.TopCenter)) }

    AnimatedVisibility(visible = controls && s != null && sc.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
        if (s != null) TopBar(video, sc, s, dpad, onDpad = { dpad = !dpad }, onClose = onClose, onTouch = { touched = System.currentTimeMillis() })
    }
    AnimatedVisibility(visible = controls && s != null && sc.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
        if (s != null) BottomBar(video, sc, s, onTouch = { touched = System.currentTimeMillis() })
    }
    // 菜单里控制栏收起时：右上角留一个"操作"按钮
    if (!controls && inMenu && sc.error == null) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "菜单：点按钮选择，按住拖动移动高亮",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            FilledTonalButton(onClick = { controls = true }) { Text("操作") }
        }
    }
    if (dpad && inMenu) DPad(video, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 150.dp else 24.dp))
}

@Composable
private fun TopBar(
    video: VideoPlaybackController,
    sc: VideoPlaybackController.Screen,
    s: DiscPlayer.State,
    dpad: Boolean,
    onDpad: () -> Unit,
    onClose: () -> Unit,
    onTouch: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = onClose) { Text("← 关闭") }
        Column(Modifier.weight(1f)) {
            Text(sc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(where(s), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        }
        if (s.isBluray) {
            // 蓝光：顶层菜单（光盘程序导航时才有）、弹出菜单（光盘说有的时候；再按一次关闭）
            if (s.bdMode == DiscPlayer.BD_MODE_MENU) {
                TextButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_MENU, DiscPlayer.BD_MENU_TOP) }) { Text("顶层菜单") }
            }
            if (s.popup) {
                TextButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_MENU, DiscPlayer.BD_MENU_POPUP) }) { Text("弹出菜单") }
            }
        } else {
            TextButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_MENU, DiscPlayer.MENU_ROOT) }) { Text("光盘菜单") }
            TextButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_MENU, DiscPlayer.MENU_TITLE) }) { Text("标题菜单") }
            if (s.domain == DiscPlayer.Domain.VTS_MENU || s.domain == DiscPlayer.Domain.VMG_MENU) {
                TextButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_GO_UP) }) { Text("返回上级") }
            }
        }
        if (s.audio.isNotEmpty()) {
            Picker(
                label = "音轨：" + (s.audio.getOrNull(s.audioCurrent)?.let { audioName(it) } ?: "无"),
                items = s.audio.mapIndexed { i, a -> "${i + 1}. ${audioName(a)}" },
                selected = s.audioCurrent,
                onTouch = onTouch,
            ) { i -> video.command(DiscPlayer.CMD_AUDIO, i) }
        }
        if (s.subtitles.isNotEmpty()) {
            val cur = if (s.subtitleShown) s.subtitleCurrent else -1
            Picker(
                label = "字幕：" + (if (cur >= 0) s.subtitles.getOrNull(cur)?.let { subtitleName(it) } ?: "?" else "关"),
                items = listOf("关闭") + s.subtitles.mapIndexed { i, l -> "${i + 1}. ${subtitleName(l)}" },
                selected = cur + 1,
                onTouch = onTouch,
            ) { i -> video.command(DiscPlayer.CMD_SUBTITLE, i - 1) }
        }
        if (s.angles > 1) {
            Picker(
                label = "角度 ${s.angle}/${s.angles}",
                items = (1..s.angles).map { "角度 $it" },
                selected = s.angle - 1,
                onTouch = onTouch,
            ) { i -> video.command(DiscPlayer.CMD_ANGLE, i + 1) }
        }
        TextButton(onClick = { onTouch(); onDpad() }) { Text(if (dpad) "收起方向键" else "方向键") }
    }
}

@Composable
private fun BottomBar(video: VideoPlaybackController, sc: VideoPlaybackController.Screen, s: DiscPlayer.State, onTouch: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val seekable = s.domain == DiscPlayer.Domain.TITLE && s.durationMs > 0
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        if (seekable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val pos = if (dragging) dragValue.toLong() else s.positionMs
                Text(formatDuration(pos), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = (if (dragging) dragValue else s.positionMs.toFloat()).coerceIn(0f, s.durationMs.toFloat()),
                    onValueChange = {
                        onTouch()
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        video.command(DiscPlayer.CMD_SEEK_MS, dragValue.toInt())
                        dragging = false
                    },
                    valueRange = 0f..s.durationMs.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text(formatDuration(s.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_PREV_CHAPTER) }) { Text("上一章") }
            if (seekable) {
                OutlinedButton(onClick = { onTouch(); seek(video, s, -10_000) }) { Text("−10 秒") }
            }
            Button(onClick = { onTouch(); video.togglePause() }, modifier = Modifier.widthIn(min = 96.dp)) { Text(if (s.paused) "播放" else "暂停") }
            if (seekable) {
                OutlinedButton(onClick = { onTouch(); seek(video, s, 30_000) }) { Text("+30 秒") }
            }
            OutlinedButton(onClick = { onTouch(); video.command(DiscPlayer.CMD_NEXT_CHAPTER) }) { Text("下一章") }
            if (s.chapters > 1 && !s.inMenu && (s.isBluray || s.title > 0)) {
                Picker(
                    label = "第 ${s.chapter}/${s.chapters} 章",
                    items = (1..s.chapters).map { "第 $it 章" },
                    selected = s.chapter - 1,
                    onTouch = onTouch,
                ) { i -> video.command(DiscPlayer.CMD_PART_PLAY, s.title, i + 1) }
            }
            if (s.isBluray && s.bdMode == DiscPlayer.BD_MODE_PLAYLIST) {
                // 直接播放模式（没有菜单 / BD-J 盘）：列盘上的播放列表，换着播
                val playlists = sc.titleInfo.keys.sorted()
                if (playlists.isNotEmpty()) {
                    Picker(
                        label = if (s.playlist >= 0) "播放列表 %05d".format(s.playlist) else "播放列表",
                        items = playlists.map { n -> sc.titleInfo[n] ?: "%05d.mpls".format(n) },
                        selected = playlists.indexOf(s.playlist),
                        onTouch = onTouch,
                    ) { i -> video.command(DiscPlayer.CMD_TITLE_PLAY, playlists[i]) }
                }
            } else if (s.titles > 0) {
                val playingTitle = s.domain == DiscPlayer.Domain.TITLE && s.title in 1..s.titles
                Picker(
                    label = if (playingTitle) "标题 ${s.title}/${s.titles}" else "标题",
                    items = (1..s.titles).map { n -> "标题 $n" + (if (s.isBluray) "" else sc.titleInfo[n]?.let { " · $it" } ?: "") },
                    selected = if (playingTitle) s.title - 1 else -1,
                    onTouch = onTouch,
                ) { i -> video.command(DiscPlayer.CMD_TITLE_PLAY, i + 1) }
            }
            Spacer(Modifier.weight(1f))
            Text(stats(s), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.End)
        }
    }
}

private fun seek(video: VideoPlaybackController, s: DiscPlayer.State, deltaMs: Long) {
    video.command(DiscPlayer.CMD_SEEK_MS, (s.positionMs + deltaMs).coerceIn(0, (s.durationMs - 1000).coerceAtLeast(0)).toInt())
}

/** 下拉选择（音轨、字幕、角度、章节、标题）。 */
@Composable
private fun Picker(label: String, items: List<String>, selected: Int, onTouch: () -> Unit, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { onTouch(); open = true }) { Text("$label ▾", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, text ->
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onTouch()
                        onPick(i)
                    },
                )
            }
        }
    }
}

/** 菜单用的方向键（触摸点不准的菜单、或者习惯用遥控器的人）。 */
@Composable
private fun DPad(video: VideoPlaybackController, modifier: Modifier) {
    val bg = Color.Black.copy(alpha = 0.55f)
    Column(
        modifier
            .background(bg, RoundedCornerShape(16.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PadKey("▲") { video.command(DiscPlayer.CMD_UP) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadKey("◀") { video.command(DiscPlayer.CMD_LEFT) }
            Box(Modifier.padding(4.dp)) {
                Button(onClick = { video.command(DiscPlayer.CMD_ACTIVATE) }, shape = CircleShape, modifier = Modifier.size(64.dp)) { Text("确定") }
            }
            PadKey("▶") { video.command(DiscPlayer.CMD_RIGHT) }
        }
        PadKey("▼") { video.command(DiscPlayer.CMD_DOWN) }
    }
}

@Composable
private fun PadKey(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = CircleShape, modifier = Modifier.size(56.dp).padding(2.dp)) { Text(text) }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                .padding(24.dp)
                .widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFFFE082),
        modifier = modifier
            .padding(top = 72.dp)
            .widthIn(max = 720.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** "正片 · 标题 2/5 · 第 3/12 章" / "主菜单（4 个按钮）" / 蓝光 "标题 1/3 · 00800.mpls · 第 2/24 章"。 */
internal fun where(s: DiscPlayer.State): String {
    if (s.isBluray) return whereBluray(s)
    return when {
        s.inMenu -> "${s.domain.label}（${s.buttons} 个按钮）" + if (s.still) " · 静止画面" else ""
        s.domain == DiscPlayer.Domain.TITLE -> "标题 ${s.title}/${s.titles} · 第 ${s.chapter}/${s.chapters} 章" +
            (if (s.wide) " · 16:9" else "") + if (s.paused) " · 已暂停" else ""
        else -> s.domain.label + if (s.still) " · 静止画面" else ""
    }
}

private fun whereBluray(s: DiscPlayer.State): String {
    val parts = mutableListOf<String>()
    when {
        s.inMenu -> parts += if (s.title == 0) "顶层菜单" else "光盘菜单"
        s.domain == DiscPlayer.Domain.STOP -> parts += "停止"
    }
    if (s.bdMode == DiscPlayer.BD_MODE_MENU && s.title in 1..s.titles) parts += "标题 ${s.title}/${s.titles}"
    if (s.playlist >= 0) parts += "%05d.mpls".format(s.playlist)
    if (!s.inMenu && s.chapters > 0 && s.chapter > 0) parts += "第 ${s.chapter}/${s.chapters} 章"
    if (s.still) parts += "静止画面"
    if (s.paused) parts += "已暂停"
    if (s.bdj) parts += "BD-J 菜单不支持，直接播放主片"
    return parts.joinToString(" · ").ifEmpty { "蓝光" }
}

/** "ja AC-3 2ch" → "日语 AC-3 2ch"（蓝光是 "jpn LPCM 2ch"；没有语言的是 "--" / "---"）。 */
internal fun audioName(desc: String): String {
    val lang = desc.substringBefore(' ')
    val rest = desc.substringAfter(' ', "")
    return (if (lang.isEmpty() || lang.all { it == '-' }) "" else Languages.name(lang) + " ") + rest
}

/** 字幕语言（"---" = 没标语言）。 */
private fun subtitleName(lang: String): String = if (lang.isEmpty() || lang.all { it == '-' }) "未标语言" else Languages.name(lang)

/** FFmpeg 解码器名 → 常见叫法。 */
internal fun codecLabel(name: String): String = when (name) {
    "h264" -> "H.264"
    "hevc" -> "HEVC"
    "mpeg2video" -> "MPEG-2"
    "mpeg1video" -> "MPEG-1"
    "vc1" -> "VC-1"
    "ac3" -> "AC-3"
    "eac3" -> "E-AC-3"
    "truehd" -> "TrueHD"
    "mlp" -> "MLP"
    "dca", "dts" -> "DTS"
    "pcm_bluray", "pcm_dvd" -> "LPCM"
    "mp1", "mp2", "mp3" -> "MPEG 音频"
    else -> name
}

internal fun stats(s: DiscPlayer.State): String {
    val audio = s.audioCodec.takeIf { it.isNotEmpty() }?.let { codecLabel(it) } ?: when (s.audioStream) {
        in 0x80..0x87 -> "AC-3"
        in 0x88..0x8F -> "DTS"
        in 0xA0..0xA7 -> "LPCM"
        in 0xC0..0xDF -> "MPEG"
        else -> null
    }
    val video = s.videoCodec.takeIf { it.isNotEmpty() }?.let { codecLabel(it) }
    return listOfNotNull(video, "${s.videoWidth}×${s.videoHeight}", audio).joinToString(" ") +
        " · 读盘 ${s.bytesRead / 1_048_576} MB" + (if (s.readErrors > 0) " · 补零 ${s.readErrors} 扇区" else "") +
        " · 丢帧 ${s.framesDropped}"
}

private const val HIDE_MS = 4_000L
