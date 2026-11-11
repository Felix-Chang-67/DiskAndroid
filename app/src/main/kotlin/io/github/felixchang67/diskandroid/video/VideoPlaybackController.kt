package io.github.felixchang67.diskandroid.video

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.drive.DriveState
import io.github.felixchang67.diskandroid.core.drive.MediaStatus
import io.github.felixchang67.diskandroid.core.fs.ContentKind
import io.github.felixchang67.diskandroid.core.fs.DiscContent
import io.github.felixchang67.diskandroid.core.fs.SECTOR_SIZE
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.player.DiscPlayer
import io.github.felixchang67.diskandroid.core.player.NativeBridge
import io.github.felixchang67.diskandroid.core.player.PlayerListener
import io.github.felixchang67.diskandroid.core.video.Languages
import io.github.felixchang67.diskandroid.core.video.VideoDiscInfo
import io.github.felixchang67.diskandroid.core.video.formatDuration
import io.github.felixchang67.diskandroid.files.DiscBrowser
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * DVD-Video 播放（进程级单例）：打开原生播放器（读盘经光驱会话）、给全屏播放界面提供状态和操作、
 * 盘片取出 / 光驱断开时立即停止、音频焦点、拔耳机暂停、放入视频盘后自动播放。
 *
 * 加密盘：CSS 要用阶段 3 的可选解密组件，组件缺失时提示"该盘有版权保护，需要启用解密组件"，不尝试播放。
 */
class VideoPlaybackController(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
    private val files: DiscBrowser,
    private val decrypt: DecryptComponents,
) {
    /** 播放界面要显示的东西；null = 没在播放（界面应关闭）。 */
    data class Screen(
        val title: String,
        val opening: Boolean = true,
        val error: String? = null,
        /** 开始播放时的提示（地区不匹配之类），几秒后自动消失。 */
        val notice: String? = null,
        val state: DiscPlayer.State? = null,
        /** 标题号 → 说明（"1:52:10 · 12 章"），来自 IFO 分析，界面的标题列表用。 */
        val titleInfo: Map<Int, String> = emptyMap(),
    )

    /** 视频盘的自动播放从哪里开始。 */
    enum class AutoStart(val label: String) {
        MENU("光盘菜单（和 DVD 机一样从片头 / 菜单开始）"),
        MAIN("直接播放主片"),
    }

    class AutoplayPending(val session: DriveSession, val disc: DiscInfo, val fingerprint: String, val startAtMs: Long, val label: String?)

    private class Current(
        val session: DriveSession,
        val disc: DiscInfo,
        val fingerprint: String,
        val label: String,
        val startTitle: Int,
        val reader: DriveSectorReader,
        val openedAtMs: Long,
        /** CSS 加密盘（经 DVD 解密组件认证）。 */
        val css: Boolean,
        val kind: DiscPlayer.Kind = DiscPlayer.Kind.DVD,
        /** AACS 加密的蓝光（经蓝光解密组件认证）。 */
        val aacs: Boolean = false,
    ) {
        @Volatile
        var player: DiscPlayer? = null
        var firstFrameLogged = false
        var subtitleChoiceTitle = -1
        var userTouchedSubtitles = false
        var lastLoggedTitle = -1
        var lastLoggedPlaylist = -2
        var lastLoggedDomain: DiscPlayer.Domain? = null
    }

    private val prefs = app.getSharedPreferences("video", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _autoplayEnabled = MutableStateFlow(prefs.getBoolean("autoplay", true))
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()
    private val _autoStart = MutableStateFlow(runCatching { AutoStart.valueOf(prefs.getString("autoStart", null) ?: "") }.getOrDefault(AutoStart.MENU))
    val autoStart: StateFlow<AutoStart> = _autoStart.asStateFlow()
    private val _autoplay = MutableStateFlow<AutoplayPending?>(null)
    val autoplay: StateFlow<AutoplayPending?> = _autoplay.asStateFlow()
    private var autoplayJob: Job? = null

    /** 主线程用。 */
    private var current: Current? = null
    private var pollJob: Job? = null
    private var watchJob: Job? = null
    private var surface: Surface? = null

    /** 关闭原生播放器要等它的线程结束（可能在等一次读盘），放到单独的线程上。 */
    private val closer = Executors.newSingleThreadExecutor { r -> Thread(r, "video-close").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                log.i(TAG, "耳机 / 蓝牙音频断开，暂停")
                pause()
            }
        }
    }

    /** 读盘 / 复制在占用光驱时不能播放（由 AppContainer 设置）。 */
    var busyReason: () -> String? = { null }

    /** 开始播放视频前要停掉的东西（音乐 CD 播放器）。 */
    var beforePlay: () -> Unit = {}

    init {
        NativeBridge.logger = { level, tag, msg ->
            log.log(
                when (level) {
                    0 -> LogLevel.DEBUG
                    1 -> LogLevel.INFO
                    2 -> LogLevel.WARN
                    else -> LogLevel.ERROR
                },
                if (tag == "DVD" || tag == "VIDEO") TAG else "$TAG/$tag", msg,
            )
        }
        log.i(TAG, "视频播放器原生库：" + (DiscPlayer.version() ?: "加载失败（${NativeBridge.loadErrorText}）"))
    }

    val playing: Boolean get() = current != null

    /** 这台光驱是不是正被视频播放占用。 */
    fun isPlaying(session: DriveSession): Boolean = current?.session === session

    // ---------------- 能不能播 ----------------

    /** 原生播放库不能用的原因（null = 能用）。测试里替换掉（电脑上没有 Android 的 .so）。 */
    var nativeProblem: () -> String? = { if (NativeBridge.isAvailable) null else "播放器原生库加载失败：${NativeBridge.loadErrorText}" }

    /** 盘上的视频格式：DVD-Video、蓝光（BDMV / AVCHD），没有视频为 null。 */
    fun videoKind(content: DiscContent?): DiscPlayer.Kind? = when {
        content == null -> null
        ContentKind.DVD_VIDEO in content.kinds -> DiscPlayer.Kind.DVD
        ContentKind.BDMV in content.kinds || ContentKind.AVCHD in content.kinds -> DiscPlayer.Kind.BLURAY
        else -> null
    }

    /** 不能播放这张盘的原因（null = 可以）。[video] 是读出来的视频结构（有的话能提前认出 4K UHD）。 */
    fun refusal(disc: DiscInfo, content: DiscContent?, video: VideoDiscInfo? = null): String? {
        val kind = videoKind(content)
        return when {
            disc.dvdCopyright?.isCss == true && decrypt.file(DecryptComponents.Kind.CSS) == null -> PROTECTED_MESSAGE
            disc.dvdCopyright?.protectionType == 0x02 -> "这张盘有 CPRM 保护（数字电视录像），暂不支持播放"
            content != null && kind == null -> "这张盘上没有视频（VIDEO_TS / BDMV）"
            kind == DiscPlayer.Kind.BLURAY && content?.aacs == true && video?.bd?.uhd == true -> AACS2_MESSAGE
            kind == DiscPlayer.Kind.BLURAY && content?.aacs == true -> aacsMissing()
            else -> null
        } ?: nativeProblem()
    }

    /** AACS 盘需要的组件缺了哪个（null = 都有）。 */
    private fun aacsMissing(): String? = when {
        decrypt.file(DecryptComponents.Kind.AACS) == null -> "$PROTECTED_MESSAGE（AACS：要导入蓝光解密组件和 KEYDB.cfg）"
        decrypt.file(DecryptComponents.Kind.KEYDB) == null -> "$PROTECTED_MESSAGE（AACS：还要导入 KEYDB.cfg 密钥库）"
        else -> null
    }

    /** 看盘上的内容决定 DVD 还是蓝光；[target] 对 DVD 是标题号（0 = 光盘菜单），对蓝光是播放列表号（-1 = 光盘菜单）。 */
    fun playDisc(session: DriveSession, disc: DiscInfo, menu: Boolean, target: Int, label: String?, why: String): String? {
        val content = files.states.value[session]?.info?.content
        return when (videoKind(content)) {
            DiscPlayer.Kind.BLURAY -> playBluray(session, disc, if (menu) -1 else target, label, why)
            else -> play(session, disc, if (menu) 0 else target, label, why)
        }
    }

    // ---------------- 播放 ----------------

    /**
     * 播放 [session] 里的 DVD-Video：[startTitle] = 0 从光盘自己的首播开始（片头、菜单），> 0 直接播放这个标题。
     * 返回给用户看的错误（null = 已开始，播放界面会打开）。
     */
    fun play(session: DriveSession, disc: DiscInfo, startTitle: Int, label: String?, why: String): String? {
        cancelAutoplay(log = false)
        val content = files.states.value[session]?.info?.content
        refusal(disc, content)?.let {
            log.i(TAG, "不播放：$it")
            return it
        }
        busyReason()?.let { return it }
        val sectors = disc.capacity?.blocks ?: return "读不到盘片容量"
        val css = disc.dvdCopyright?.isCss == true
        if (css) {
            // CSS：启用用户导入的 DVD 解密组件（libdvdcss），打开盘时它经光驱会话做认证
            val component = decrypt.file(DecryptComponents.Kind.CSS) ?: return PROTECTED_MESSAGE
            val problem = NativeBridge.nativeAttachCss(component.path, decrypt.cssCacheDir.path)
            if (problem != null) {
                log.w(TAG, "DVD 解密组件加载失败：$problem")
                return "DVD 解密组件加载失败：$problem"
            }
        }
        stop("开始新的播放")
        beforePlay()
        val region = DvdRegion.decide(session.info.value?.rpc, disc.dvdCopyright)
        val options = DiscPlayer.Options(
            regionMask = region.playerMask,
            menuLanguage = prefs.getString("menuLang", null) ?: "zh",
            audioLanguage = prefs.getString("audioLang", null) ?: "ja",
            subtitleLanguage = prefs.getString("subLang", null) ?: "zh",
            startTitle = startTitle,
        )
        val title = label?.takeIf { it.isNotBlank() } ?: "DVD"
        val c = Current(
            session, disc, CdPlaybackController.fingerprint(disc), title, startTitle, DriveSectorReader(session, sectors, log),
            System.currentTimeMillis(), css,
        )
        current = c
        log.i(
            TAG, "播放 DVD：${if (startTitle > 0) "直接播放标题 $startTitle" else "从光盘菜单开始"}（$why）",
            "盘" to title, "地区" to region.explanation, "语言" to "菜单 ${options.menuLanguage}，音轨 ${options.audioLanguage}，字幕 ${options.subtitleLanguage}",
            "原生库" to DiscPlayer.version(), "加密" to if (css) "CSS（用 DVD 解密组件认证）" else "无",
        )
        region.warning?.let { log.w(TAG, it) }
        val titleInfo = files.states.value[session]?.video?.info?.titles.orEmpty()
            .mapNotNull { t -> t.dvd?.let { d -> d.number to "${formatDuration(t.durationMs)} · ${t.chapters} 章" + if (t.angles > 1) " · ${t.angles} 个角度" else "" } }
            .toMap()
        launch(c, title, region.warning, titleInfo) { DiscPlayer.open(c.reader, sectors * SECTOR_SIZE, listener(c), options) }
        return null
    }

    /**
     * 播放 [session] 里的蓝光：[startPlaylist] < 0 用光盘菜单（First Play、顶层菜单），>= 0 直接播放这个播放列表。
     * BD-J 菜单的盘会自动改成直接播放主片。返回给用户看的错误（null = 已开始）。
     */
    fun playBluray(session: DriveSession, disc: DiscInfo, startPlaylist: Int, label: String?, why: String): String? {
        cancelAutoplay(log = false)
        val content = files.states.value[session]?.info?.content
        refusal(disc, content, files.states.value[session]?.video?.info)?.let {
            log.i(TAG, "不播放：$it")
            return it
        }
        busyReason()?.let { return it }
        val sectors = disc.capacity?.blocks ?: return "读不到盘片容量"
        val aacs = content?.aacs == true
        if (aacs) attachAacs()?.let { return it }
        stop("开始新的播放")
        beforePlay()
        val options = DiscPlayer.BlurayOptions(
            audioLanguage = prefs.getString("bdAudioLang", null) ?: "jpn",
            subtitleLanguage = prefs.getString("bdSubLang", null) ?: "chi",
            menuLanguage = prefs.getString("bdMenuLang", null) ?: "jpn",
            region = prefs.getInt("bdRegion", 1),
            startPlaylist = startPlaylist,
        )
        val title = label?.takeIf { it.isNotBlank() } ?: "蓝光"
        val c = Current(
            session, disc, CdPlaybackController.fingerprint(disc), title, startPlaylist, DriveSectorReader(session, sectors, log),
            System.currentTimeMillis(), css = false, kind = DiscPlayer.Kind.BLURAY, aacs = aacs,
        )
        current = c
        log.i(
            TAG, "播放蓝光：${if (startPlaylist >= 0) "直接播放 %05d.mpls".format(startPlaylist) else "从光盘菜单开始"}（$why）",
            "盘" to title, "语言" to "音轨 ${options.audioLanguage}，字幕 ${options.subtitleLanguage}，菜单 ${options.menuLanguage}",
            "地区" to when (options.region) { 1 -> "A"; 2 -> "B"; 4 -> "C"; else -> options.region.toString() },
            "原生库" to DiscPlayer.version(), "加密" to if (aacs) "AACS（用蓝光解密组件认证）" else "无",
        )
        // 播放列表号 → 说明（直接播放模式下的"标题"列表）
        val titleInfo = files.states.value[session]?.video?.info?.titles.orEmpty()
            .mapNotNull { t -> t.bd?.let { b -> (b.name.take(5).toIntOrNull() ?: return@let null) to "${b.name} · ${formatDuration(t.durationMs)} · ${t.chapters} 章" } }
            .toMap()
        launch(c, title, null, titleInfo) { DiscPlayer.openBluray(c.reader, sectors * SECTOR_SIZE, listener(c), options) }
        return null
    }

    /**
     * 启用蓝光解密组件（libaacs）：把宿主接口交给它（认证命令经光驱会话发出），告诉 libbluray 用它，
     * KEYDB.cfg 在 [DecryptComponents.aacsConfigHome]，算出的密钥缓存在 [DecryptComponents.aacsCacheDir]。
     * 返回给用户看的错误，null = 成功。
     */
    private fun attachAacs(): String? {
        aacsMissing()?.let { return it }
        val component = decrypt.file(DecryptComponents.Kind.AACS) ?: return PROTECTED_MESSAGE
        val problem = NativeBridge.nativeAttachAacs(component.path, decrypt.aacsConfigHome.path, decrypt.aacsCacheDir.path)
        if (problem != null) {
            log.w(TAG, "蓝光解密组件加载失败：$problem")
            return "蓝光解密组件加载失败：$problem"
        }
        return null
    }

    /** 蓝光打不开时给用户的说明：光驱认证失败的，补上光驱返回的 sense（原文在日志的 SCSI 标签里）。 */
    private fun blurayReason(c: Current, msg: String): String {
        val sense = c.reader.lastAuthSense
        return if (c.aacs && msg.startsWith(DRIVE_AUTH_MESSAGE) && sense != null) {
            "$msg\n光驱返回：${sense.describe()}（sense ${sense.code()}；完整记录在日志的 SCSI 和 AACS 标签里）"
        } else {
            msg
        }
    }

    /** 打开播放器（IO 线程）、接上画面、开始播放。 */
    private fun launch(c: Current, title: String, notice: String?, titleInfo: Map<Int, String>, open: () -> DiscPlayer) {
        _screen.value = Screen(title, opening = true, notice = notice, titleInfo = titleInfo)
        watch(c)
        openActivity()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { open() } }
            val player = result.getOrNull()
            if (current !== c) {
                if (player != null) withContext(closer) { player.close() }
                return@launch
            }
            if (player == null) {
                val msg = result.exceptionOrNull()?.message ?: "打不开"
                log.w(TAG, "打开失败：$msg")
                fail(
                    c,
                    when {
                        c.reader.protectedHit -> protectedReason(c)
                        c.reader.fatalReason != null -> c.reader.fatalReason!!
                        c.kind == DiscPlayer.Kind.BLURAY -> blurayReason(c, msg)
                        else -> msg
                    },
                )
                return@launch
            }
            c.player = player
            surface?.let { player.setSurface(it) }
            player.start()
            requestFocus()
            registerNoisy(true)
            _screen.update { it?.copy(opening = false) }
            startPolling(c)
            if (notice != null) {
                delay(8_000)
                _screen.update { s -> if (current === c) s?.copy(notice = null) else s }
            }
        }
    }

    private fun openActivity() {
        val intent = Intent(app, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { app.startActivity(intent) }.onFailure { log.e(TAG, "打不开播放界面", throwable = it) }
    }

    private fun listener(c: Current) = object : PlayerListener {
        override fun onEvent(event: Int, a1: Int, a2: Int, text: String?) {
            scope.launch { onEvent(c, event, a1, a2, text) }
        }
    }

    private fun onEvent(c: Current, event: Int, a1: Int, a2: Int, text: String?) {
        if (current !== c) return
        when (event) {
            DiscPlayer.EV_FIRST_FRAME -> if (!c.firstFrameLogged) {
                c.firstFrameLogged = true
                log.i(TAG, "第一帧画面：${a1}×$a2，打开后 ${System.currentTimeMillis() - c.openedAtMs} ms")
            }
            DiscPlayer.EV_END -> {
                log.i(TAG, "光盘播放结束（${c.reader.summary()}）")
                poll(c)
            }
            DiscPlayer.EV_ERROR -> {
                val reason = when {
                    c.reader.protectedHit -> protectedReason(c)
                    c.reader.fatalReason != null -> "读盘出错：${c.reader.fatalReason}"
                    else -> text ?: "播放出错"
                }
                log.w(TAG, "播放出错：$reason")
                fail(c, reason)
            }
            else -> poll(c)
        }
    }

    /** 光驱拒绝读加密扇区时给用户的说明：没有组件 / 地区不符 / 认证没成功。 */
    private fun protectedReason(c: Current): String {
        if (!c.css) return PROTECTED_MESSAGE
        val sense = c.reader.lastAuthSense
        return when {
            sense != null && sense.asc == 0x6F && sense.ascq == 0x04 ->
                "光驱拒绝认证：盘片地区和光驱地区不符（sense ${sense.code()}）。App 不会修改光驱的地区码。"
            sense != null && sense.asc == 0x6F && sense.ascq == 0x05 ->
                "光驱拒绝认证：光驱还没有设置地区（sense ${sense.code()}）。App 不会修改光驱的地区码。"
            sense != null -> "CSS 认证没有成功，光驱不让读加密的扇区（最后一次认证错误：${sense.describe()}；完整记录在日志的 SCSI 和 CSS 标签里）"
            c.reader.authCommands.get() == 0L -> "解密组件没有向光驱发认证命令，光驱不让读加密的扇区（日志的 CSS 标签里有原因）"
            else -> "CSS 认证没有成功，光驱不让读加密的扇区（日志的 SCSI 和 CSS 标签里有完整记录）"
        }
    }

    private fun fail(c: Current, reason: String) {
        if (current !== c) return
        _screen.update { it?.copy(opening = false, error = reason) }
        val p = c.player
        c.player = null
        c.reader.cancelled = true
        pollJob?.cancel()
        abandonFocus()
        registerNoisy(false)
        if (p != null) scope.launch(closer) { p.close() }
    }

    private fun startPolling(c: Current) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && current === c) {
                poll(c)
                delay(POLL_MS)
            }
        }
    }

    private fun poll(c: Current) {
        val p = c.player ?: return
        scope.launch {
            val s = withContext(Dispatchers.Default) { p.state() }
            if (current !== c || c.player !== p) return@launch
            _screen.update { it?.copy(state = s) }
            onState(c, s)
        }
    }

    /** 状态变化时：记日志、按偏好自动打开字幕。 */
    private fun onState(c: Current, s: DiscPlayer.State) {
        val changed = s.domain != c.lastLoggedDomain || (s.domain == DiscPlayer.Domain.TITLE && s.title != c.lastLoggedTitle) ||
            (s.isBluray && s.playlist != c.lastLoggedPlaylist)
        if (changed) {
            c.lastLoggedDomain = s.domain
            c.lastLoggedTitle = s.title
            c.lastLoggedPlaylist = s.playlist
            val what = if (s.isBluray) {
                val parts = mutableListOf(
                    when {
                        s.inMenu -> if (s.title == 0) "顶层菜单" else "光盘菜单"
                        s.domain == DiscPlayer.Domain.STOP -> "停止"
                        else -> "播放"
                    },
                )
                if (s.bdMode == DiscPlayer.BD_MODE_MENU && s.title in 1..s.titles) parts += "标题 ${s.title}/${s.titles}"
                if (s.playlist >= 0) parts += "%05d.mpls".format(s.playlist) + if (s.chapters > 0) "（${s.chapters} 章）" else ""
                parts.joinToString("，")
            } else {
                s.domain.label + if (s.domain == DiscPlayer.Domain.TITLE) "，标题 ${s.title}/${s.titles}，${s.chapters} 章" else ""
            }
            log.i(
                TAG, "现在：$what",
                "音轨" to s.audio.getOrNull(s.audioCurrent), "字幕" to if (s.subtitleShown) s.subtitles.getOrNull(s.subtitleCurrent) else "关",
                "画面" to "${s.videoWidth}×${s.videoHeight}${if (s.wide) " 16:9" else ""}",
                "BD-J" to if (s.bdj) "光盘菜单是 BD-J（Java），做不了，直接播放主片" else null,
            )
        }
        // 光盘没打开字幕、有偏好语言的字幕：自动打开（每个标题 / 播放列表一次；用户自己选过就不再管）
        val lang = if (s.isBluray) prefs.getString("bdSubLang", null) ?: "chi" else prefs.getString("subLang", null) ?: "zh"
        val key = if (s.isBluray) s.playlist else s.title
        val feature = s.domain == DiscPlayer.Domain.TITLE && !s.inMenu &&
            (!s.isBluray || (s.playlist >= 0 && (s.bdMode == DiscPlayer.BD_MODE_PLAYLIST || s.title > 0)))
        if (feature && !c.userTouchedSubtitles && c.subtitleChoiceTitle != key && s.subtitles.isNotEmpty()) {
            c.subtitleChoiceTitle = key
            val idx = s.subtitles.indexOfFirst { Languages.same(it, lang) }
            if (!s.subtitleShown && idx >= 0) {
                log.i(TAG, "按偏好打开字幕：第 ${idx + 1} 条（$lang）")
                c.player?.command(DiscPlayer.CMD_SUBTITLE, idx)
            }
        }
    }

    /** 盘片取出 / 更换、光驱断开时立即停止。 */
    private fun watch(c: Current) {
        watchJob?.cancel()
        watchJob = scope.launch {
            combine(c.session.state, c.session.media, c.session.disc) { st, m, d -> Triple(st, m, d) }.collect { (st, m, d) ->
                val reason = when {
                    st !is DriveState.Online -> "光驱已断开"
                    m is MediaStatus.NoMedium -> "盘片已取出"
                    m is MediaStatus.BecomingReady -> "盘片已更换"
                    d != null && CdPlaybackController.fingerprint(d) != c.fingerprint -> "盘片已更换"
                    else -> null
                }
                if (reason != null && current === c) {
                    _messages.tryEmit("$reason，停止播放")
                    stop(reason)
                }
            }
        }
    }

    // ---------------- 操作（播放界面调用） ----------------

    fun command(cmd: Int, a1: Int = 0, a2: Int = 0) {
        val c = current ?: return
        if (cmd == DiscPlayer.CMD_SUBTITLE) c.userTouchedSubtitles = true
        c.player?.command(cmd, a1, a2)
        poll(c)
    }

    fun togglePause() {
        val s = _screen.value?.state ?: return
        if (s.paused) resume() else pause()
    }

    fun pause() {
        val c = current ?: return
        val p = c.player ?: return
        if (_screen.value?.state?.paused == true) return
        p.command(DiscPlayer.CMD_PAUSE)
        poll(c)
    }

    fun resume() {
        val c = current ?: return
        val p = c.player ?: return
        requestFocus()
        p.command(DiscPlayer.CMD_RESUME)
        poll(c)
    }

    /** 播放界面的 Surface（null = 销毁了）。销毁时会等视频线程放开它（最多 0.5 秒）。 */
    fun setSurface(s: Surface?) {
        surface = s
        current?.player?.setSurface(s)
    }

    /** 点了画面上的 ([x], [y])（相对 Surface 的像素）：在菜单里就选中并确定那里的按钮。返回 true 表示点在了菜单上。 */
    fun tapMenu(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val s = _screen.value?.state ?: return false
        val pt = toFrame(s, x, y, viewWidth, viewHeight) ?: return false
        if (!s.inMenu) return false
        command(DiscPlayer.CMD_MOUSE_ACTIVATE, pt.first, pt.second)
        return true
    }

    /** 手指在菜单上移动：高亮手指下的按钮。 */
    fun hoverMenu(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val s = _screen.value?.state ?: return
        if (!s.inMenu) return
        val pt = toFrame(s, x, y, viewWidth, viewHeight) ?: return
        current?.player?.command(DiscPlayer.CMD_MOUSE_SELECT, pt.first, pt.second)
    }

    private fun toFrame(s: DiscPlayer.State, x: Float, y: Float, viewWidth: Int, viewHeight: Int): Pair<Int, Int>? {
        if (s.displayWidth <= 0 || s.displayHeight <= 0 || s.frameWidth <= 0 || s.frameHeight <= 0) return null
        val left = (viewWidth - s.displayWidth) / 2f
        val top = (viewHeight - s.displayHeight) / 2f
        val fx = ((x - left) * s.frameWidth / s.displayWidth).toInt()
        val fy = ((y - top) * s.frameHeight / s.displayHeight).toInt()
        if (fx !in 0 until s.frameWidth || fy !in 0 until s.frameHeight) return null
        return fx to fy
    }

    /** 播放结束 / 出错后"从头再播"。 */
    fun restart() {
        val c = current ?: return
        if (c.kind == DiscPlayer.Kind.BLURAY) playBluray(c.session, c.disc, c.startTitle, c.label, "重新播放")
        else play(c.session, c.disc, c.startTitle, c.label, "重新播放")
    }

    /** 停止播放、关闭播放界面。 */
    fun stop(reason: String) {
        val c = current ?: run {
            _screen.value = null
            return
        }
        current = null
        pollJob?.cancel()
        watchJob?.cancel()
        c.reader.cancelled = true
        abandonFocus()
        registerNoisy(false)
        _screen.value = null
        val p = c.player
        c.player = null
        val elapsed = (System.currentTimeMillis() - c.openedAtMs) / 1000
        scope.launch {
            val st = withContext(closer) {
                val s = runCatching { p?.state() }.getOrNull()
                runCatching { p?.close() }.onFailure { log.e(TAG, "关闭播放器出错", throwable = it) }
                s
            }
            log.i(
                TAG, "停止播放：$reason（播放了 ${elapsed / 60} 分 ${elapsed % 60} 秒）",
                "读盘" to c.reader.summary(),
                "画面" to st?.let { "显示 ${it.framesShown} 帧，丢 ${it.framesDropped} 帧" },
                "声音" to st?.let { "${it.audioFrames / 48_000} 秒" },
                "补零扇区" to st?.readErrors,
                "认证命令" to if (c.css || c.aacs) "${c.reader.authCommands.get()} 条（失败 ${c.reader.authFailures.get()}）" else null,
            )
        }
    }

    // ---------------- 自动播放 ----------------

    fun setAutoplay(enabled: Boolean) {
        _autoplayEnabled.value = enabled
        prefs.edit { putBoolean("autoplay", enabled) }
        if (!enabled) cancelAutoplay()
        log.i(TAG, "放入视频盘后自动播放：${if (enabled) "开" else "关"}")
    }

    fun setAutoStart(start: AutoStart) {
        _autoStart.value = start
        prefs.edit { putString("autoStart", start.name) }
        log.i(TAG, "视频盘自动播放从：${start.label}")
    }

    /**
     * 跟踪各光驱：会话开始之后"新放进去"的 DVD（经历过无盘 / 正在读取 → 就绪），读出文件系统确认有 VIDEO_TS，
     * 界面可见、没在读盘 / 播放时，显示"2 秒后播放"，到时打开播放界面。启动 App 时盘已经在里面不算。
     */
    fun watchForAutoplay(sessions: Flow<List<DriveSession>>, uiVisible: StateFlow<Boolean>) {
        scope.launch {
            sessions.collectLatest { list ->
                coroutineScope {
                    list.forEach { session ->
                        launch {
                            var armed = false
                            session.media.collect { m ->
                                when (m) {
                                    is MediaStatus.NoMedium, is MediaStatus.BecomingReady -> {
                                        armed = true
                                        if (_autoplay.value?.session === session) cancelAutoplay()
                                    }
                                    is MediaStatus.Ready -> if (armed) {
                                        armed = false
                                        val disc = withTimeoutOrNull(15_000) { session.disc.first { it != null } }
                                        if (disc != null && (disc.family == MmcNames.DiscFamily.DVD || disc.family == MmcNames.DiscFamily.BD)) {
                                            checkAutoplay(session, disc, uiVisible)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun checkAutoplay(session: DriveSession, disc: DiscInfo, uiVisible: StateFlow<Boolean>) {
        val info = files.volumeInfo(session, disc) ?: return
        val kind = videoKind(info.content) ?: return
        val inserted = if (kind == DiscPlayer.Kind.BLURAY) "放入了蓝光" else "放入了 DVD 视频盘"
        val label = info.volume.label
        val why = when {
            !_autoplayEnabled.value -> "自动播放已关闭"
            !uiVisible.value -> "App 不在前台"
            current != null -> "正在播放别的盘"
            busyReason() != null -> busyReason()
            else -> null
        }
        if (why != null) {
            log.i(TAG, "$inserted，但不自动播放：$why")
            return
        }
        refusal(disc, info.content)?.let {
            log.i(TAG, "$inserted，不能播放：$it")
            _messages.tryEmit(it)
            return
        }
        val pending = AutoplayPending(session, disc, CdPlaybackController.fingerprint(disc), System.currentTimeMillis() + AUTOPLAY_DELAY_MS, label)
        _autoplay.value = pending
        log.i(TAG, "$inserted：${AUTOPLAY_DELAY_MS / 1000} 秒后自动播放（${_autoStart.value.label}）")
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            delay(AUTOPLAY_DELAY_MS)
            if (_autoplay.value === pending) startAutoplay(pending)
        }
    }

    fun autoplayNow() {
        _autoplay.value?.let { startAutoplay(it) }
    }

    fun cancelAutoplay(log: Boolean = true) {
        if (_autoplay.value == null) return
        autoplayJob?.cancel()
        autoplayJob = null
        _autoplay.value = null
        if (log) this.log.i(TAG, "已取消视频盘自动播放")
    }

    private fun startAutoplay(p: AutoplayPending) {
        autoplayJob?.cancel()
        _autoplay.value = null
        if (p.session.disc.value?.let { CdPlaybackController.fingerprint(it) } != p.fingerprint) return
        val main = if (_autoStart.value == AutoStart.MAIN) mainTitle(p.session) else null
        playDisc(p.session, p.disc, menu = main == null, target = main ?: 0, label = p.label, why = "自动播放")?.let { _messages.tryEmit(it) }
    }

    /** 自动挑的主片：DVD 的标题号 / 蓝光的播放列表号（视频结构还没读出来时为 null）。 */
    fun mainTitle(session: DriveSession): Int? {
        val main = files.states.value[session]?.video?.info?.main ?: return null
        return main.dvd?.number ?: main.bd?.name?.take(5)?.toIntOrNull()
    }

    // ---------------- 音频焦点 ----------------

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener({ change -> scope.launch { onFocusChange(change) } })
            .build()
        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        } else {
            log.i(TAG, "没拿到音频焦点（例如正在通话）")
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        resumeOnFocusGain = false
    }

    private fun onFocusChange(change: Int) {
        if (current == null) return
        val playing = _screen.value?.state?.paused == false
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.i(TAG, "音频焦点被其他 App 拿走，暂停")
                pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log.i(TAG, "音频焦点临时失去（例如来电），暂停")
                resumeOnFocusGain = playing
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    log.i(TAG, "重新获得音频焦点，继续播放")
                    resume()
                }
                resumeOnFocusGain = false
            }
        }
    }

    private fun registerNoisy(on: Boolean) {
        if (on && !noisyRegistered) {
            ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            runCatching { app.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    companion object {
        const val TAG = "VIDEO"
        const val PROTECTED_MESSAGE = "该盘有版权保护，需要启用解密组件"
        const val AACS2_MESSAGE = "不支持 AACS 2.0（4K UHD 蓝光）"

        /** 光驱拒绝 AACS 认证时原生层给的说明的开头（dp_bd.c）。 */
        const val DRIVE_AUTH_MESSAGE = "该光驱不支持此盘的认证"
        const val AUTOPLAY_DELAY_MS = 2_000L
        private const val POLL_MS = 250L
    }
}
