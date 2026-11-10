package io.github.felixchang67.diskandroid.player

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.felixchang67.diskandroid.core.cdda.ByteOrderMode
import io.github.felixchang67.diskandroid.core.cdda.CdAlbum
import io.github.felixchang67.diskandroid.core.cdda.CdPlayerEngine
import io.github.felixchang67.diskandroid.core.cdda.CdTrack
import io.github.felixchang67.diskandroid.core.drive.MediaStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import io.github.felixchang67.diskandroid.core.cdda.CddaReader
import io.github.felixchang67.diskandroid.core.cdda.PlayerState
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.drive.DriveState
import io.github.felixchang67.diskandroid.core.log.DiskLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 音乐 CD 播放的总控（进程级单例，状态只在主线程修改）：
 * - 为"哪台光驱的哪张盘"创建 [CdPlayerEngine]（读盘走该光驱的会话线程，音频处理在独立的播放器线程）；
 * - 盘片被取出 / 更换、光驱断开时停止并释放；
 * - 音频焦点（来电、其他 App 播放时暂停，导航播报时压低音量）、拔耳机自动暂停、播放期间持有 CPU 唤醒锁；
 * - 播放期间运行前台服务（通知栏 / 锁屏控制，防止息屏后被系统冻结）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CdPlaybackController(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
) {
    /** 正在使用的播放器：属于哪个光驱会话、哪张盘。 */
    class Current(
        val session: DriveSession,
        val disc: DiscInfo,
        val album: CdAlbum,
        val engine: CdPlayerEngine,
        val fingerprint: String,
    )

    private val playerDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "cd-player").apply { priority = Thread.MAX_PRIORITY - 1 } }
        .asCoroutineDispatcher()

    private val prefs = app.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val _byteOrder = MutableStateFlow(runCatching { ByteOrderMode.valueOf(prefs.getString("byteOrder", null) ?: "") }.getOrDefault(ByteOrderMode.AUTO))
    val byteOrder: StateFlow<ByteOrderMode> = _byteOrder.asStateFlow()
    private val _deemphasis = MutableStateFlow(prefs.getBoolean("deemphasis", true))
    val deemphasis: StateFlow<Boolean> = _deemphasis.asStateFlow()

    private val _current = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = _current.asStateFlow()

    /** 曲名来源（在线识别结果），只用于日志和播放器内部；界面另行组合。 */
    var titleResolver: ((DiscInfo, Int) -> String?)? = null

    /** 等待自动播放的盘（显示"2 秒后播放"横幅）。 */
    class AutoplayPending(val session: DriveSession, val disc: DiscInfo, val fingerprint: String, val startAtMs: Long)

    private val _autoplayEnabled = MutableStateFlow(prefs.getBoolean("autoplay", true))
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()
    private val _autoplay = MutableStateFlow<AutoplayPending?>(null)
    val autoplay: StateFlow<AutoplayPending?> = _autoplay.asStateFlow()
    private var autoplayJob: Job? = null

    val state: StateFlow<PlayerState?> = _current
        .flatMapLatest { c -> c?.engine?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var watcher: Job? = null
    private var serviceRequested = false
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private val wakeLock: PowerManager.WakeLock =
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiskAndroid:cd-player").apply { setReferenceCounted(false) }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                log.i(TAG, "耳机 / 蓝牙音频断开，暂停播放")
                pause()
            }
        }
    }

    init {
        scope.launch {
            state.collect { s -> onStateChanged(s) }
        }
    }

    // ---------------- 控制 ----------------

    /** 播放 [session] 当前盘片的第 [track] 轨（同一张盘复用现有播放器）。 */
    fun play(session: DriveSession, disc: DiscInfo, track: Int, positionMs: Long = 0, fadeInMs: Int? = null) {
        cancelAutoplay(log = false)
        val fingerprint = fingerprint(disc)
        val cur = _current.value
        if (cur != null && cur.session === session && cur.fingerprint == fingerprint) {
            requestFocus()
            if (fadeInMs != null) cur.engine.play(track, positionMs, fadeInMs) else cur.engine.play(track, positionMs)
            return
        }
        cur?.let { release(it, "切换到另一张盘") }
        val base = CdAlbum.from(disc) ?: run {
            log.w(TAG, "这张盘没有可以播放的音轨")
            return
        }
        // 日志里用在线识别出的曲名（识别结果晚到时仍是 CD-TEXT / Track N，不影响界面）
        val album = titleResolver?.let { resolve ->
            CdAlbum(
                base.tracks.map { t -> CdTrack(t.number, t.startLba, t.sectors, t.preEmphasis, resolve(disc, t.number) ?: t.title, t.performer) },
                base.title,
                base.performer,
            )
        } ?: base
        val engine = createEngine(session, album)
        val c = Current(session, disc, album, engine, fingerprint)
        _current.value = c
        watch(c)
        requestFocus()
        if (fadeInMs != null) engine.play(track, positionMs, fadeInMs) else engine.play(track, positionMs)
        log.i(TAG, "音乐 CD 播放器已创建", "专辑" to (album.title ?: "（无 CD-TEXT）"), "音轨数" to album.tracks.size, "光驱" to session.label)
    }

    // ---------------- 自动播放 ----------------

    fun setAutoplay(enabled: Boolean) {
        _autoplayEnabled.value = enabled
        prefs.edit { putBoolean("autoplay", enabled) }
        if (!enabled) cancelAutoplay()
        log.i(TAG, "放入音乐 CD 后自动播放：${if (enabled) "开" else "关"}")
    }

    /**
     * 跟踪各光驱：会话开始之后"新放进去"的音乐 CD（经历过无盘 / 正在读取 → 就绪），在界面可见、没在播放、没在读盘时，
     * 显示"2 秒后播放"，到时从第 1 首淡入播放。启动 App 时盘已经在里面不算。
     */
    fun watchForAutoplay(sessions: Flow<List<DriveSession>>, uiVisible: StateFlow<Boolean>, busy: () -> Boolean) {
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
                                        if (disc != null) maybeAutoplay(session, disc, uiVisible.value, busy())
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

    private fun maybeAutoplay(session: DriveSession, disc: DiscInfo, visible: Boolean, busy: Boolean) {
        val why = when {
            !disc.hasAudio -> return
            !_autoplayEnabled.value -> "自动播放已关闭"
            !visible -> "App 不在前台"
            busy -> "正在读盘"
            state.value?.let { it.active && it.status != PlayerState.Status.PAUSED } == true -> "正在播放别的盘"
            else -> null
        }
        if (why != null) {
            log.i(TAG, "放入了音乐 CD，但不自动播放：$why")
            return
        }
        val pending = AutoplayPending(session, disc, fingerprint(disc), System.currentTimeMillis() + AUTOPLAY_DELAY_MS)
        _autoplay.value = pending
        log.i(TAG, "放入了音乐 CD：${AUTOPLAY_DELAY_MS / 1000} 秒后自动播放")
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            delay(AUTOPLAY_DELAY_MS)
            if (_autoplay.value === pending) startAutoplay(pending)
        }
    }

    /** 横幅上的"立即播放"。 */
    fun autoplayNow() {
        _autoplay.value?.let { startAutoplay(it) }
    }

    fun cancelAutoplay(log: Boolean = true) {
        if (_autoplay.value == null) return
        autoplayJob?.cancel()
        autoplayJob = null
        _autoplay.value = null
        if (log) this.log.i(TAG, "已取消自动播放")
    }

    private fun startAutoplay(p: AutoplayPending) {
        autoplayJob?.cancel()
        _autoplay.value = null
        if (p.session.disc.value?.let { fingerprint(it) } != p.fingerprint) return
        val first = CdAlbum.from(p.disc)?.first?.number ?: return
        log.i(TAG, "自动播放第 $first 轨（1 秒淡入）")
        play(p.session, p.disc, first, 0, CdPlayerEngine.AUTOPLAY_FADE_MS)
    }

    fun togglePause() {
        val c = _current.value ?: return
        if (state.value?.status == PlayerState.Status.PAUSED || state.value?.active != true) requestFocus()
        c.engine.togglePause()
    }

    fun pause() {
        _current.value?.engine?.pause()
    }

    fun resume() {
        val c = _current.value ?: return
        requestFocus()
        c.engine.resume()
    }

    fun next() = _current.value?.engine?.next()

    fun previous() = _current.value?.engine?.previous()

    fun seekTo(positionMs: Long) = _current.value?.engine?.seekTo(positionMs)

    fun stop() = _current.value?.engine?.stop()

    fun setByteOrder(mode: ByteOrderMode) {
        if (mode == _byteOrder.value) return
        _byteOrder.value = mode
        prefs.edit { putString("byteOrder", mode.name) }
        log.i(TAG, "字节序设置改为：${mode.label}")
        restartWithNewOptions()
    }

    fun setDeemphasis(enabled: Boolean) {
        if (enabled == _deemphasis.value) return
        _deemphasis.value = enabled
        prefs.edit { putBoolean("deemphasis", enabled) }
        log.i(TAG, "去加重设置改为：${if (enabled) "开" else "关"}")
        restartWithNewOptions()
    }

    // ---------------- 内部 ----------------

    private fun createEngine(session: DriveSession, album: CdAlbum): CdPlayerEngine {
        val dap = session.info.value?.features?.cdReadDap == true
        val reader = CddaReader(session::read, log, useDap = dap)
        val options = CdPlayerEngine.Options(byteOrder = _byteOrder.value, deemphasis = _deemphasis.value)
        return CdPlayerEngine(album, reader, AudioTrackOutput(log), log, scope, playerDispatcher, options)
    }

    /** 设置变了：在当前位置用新设置重新开始（只在正在播放时）。 */
    private fun restartWithNewOptions() {
        val c = _current.value ?: return
        val s = state.value ?: return
        val track = s.track?.number
        val position = s.positionMs
        val wasActive = s.active
        c.engine.release()
        val engine = createEngine(c.session, c.album)
        val replaced = Current(c.session, c.disc, c.album, engine, c.fingerprint)
        _current.value = replaced
        watch(replaced)
        if (wasActive && track != null) engine.play(track, position)
    }

    /** 盘片被取出 / 更换、光驱断开时停止播放并释放。 */
    private fun watch(c: Current) {
        watcher?.cancel()
        watcher = scope.launch {
            combine(c.session.state, c.session.disc) { st, disc -> st to disc }.collect { (st, disc) ->
                val reason = when {
                    st !is DriveState.Online -> "光驱已断开或不可用（${st.label()}）"
                    disc == null -> "盘片已取出"
                    fingerprint(disc) != c.fingerprint -> "盘片已更换"
                    else -> null
                }
                if (reason != null && _current.value === c) {
                    c.engine.abort(reason)
                    release(c, reason)
                }
            }
        }
    }

    private fun release(c: Current, reason: String) {
        log.i(TAG, "释放播放器：$reason")
        c.engine.release()
        if (_current.value === c) {
            _current.value = null
            watcher?.cancel()
            watcher = null
        }
    }

    private fun onStateChanged(s: PlayerState?) {
        val running = s != null && s.active && s.status != PlayerState.Status.PAUSED
        val active = s != null && s.active
        // CPU 唤醒锁：只在真正播放（含缓冲）时持有
        if (running) {
            if (!wakeLock.isHeld) wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
        // 拔耳机自动暂停
        if (running && !noisyRegistered) {
            ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } else if (!running && noisyRegistered) {
            runCatching { app.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
        // 播放结束 / 停止：放弃音频焦点
        if (!active) abandonFocus()
        // 前台服务：播放流程进行中（含暂停）就保持；结束后服务自己停止并移除通知
        if (active && !serviceRequested) {
            serviceRequested = true
            CdPlaybackService.start(app, log)
        } else if (!active) {
            serviceRequested = false
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change -> scope.launch { onFocusChange(change) } })
            .setWillPauseWhenDucked(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        log.i(TAG, "申请音频焦点", "结果" to when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "已获得"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "延后"
            else -> "被拒绝（例如正在通话）"
        })
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        resumeOnFocusGain = false
    }

    private fun onFocusChange(change: Int) {
        val engine = _current.value?.engine ?: return
        val playing = state.value?.let { it.active && it.status != PlayerState.Status.PAUSED } == true
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.i(TAG, "音频焦点被其他 App 拿走，暂停")
                resumeOnFocusGain = false
                engine.pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log.i(TAG, "音频焦点临时失去（例如来电），暂停")
                resumeOnFocusGain = playing
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.setDucked(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.setDucked(false)
                if (resumeOnFocusGain) {
                    log.i(TAG, "重新获得音频焦点，继续播放")
                    engine.resume()
                }
                resumeOnFocusGain = false
            }
        }
    }

    /** 关闭整个 App 进程前调用（目前只在测试里用）。 */
    @SuppressLint("WakelockTimeout")
    fun shutdown() {
        _current.value?.let { release(it, "关闭") }
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        const val TAG = "PLAYER"
        const val AUTOPLAY_DELAY_MS = 2_000L

        /** 唤醒锁兜底超时：一张 CD 最长约 80 分钟，按 3 小时算，播放中会不断续期（每次状态变化重新 acquire）。 */
        private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        /** 判断"还是不是同一张盘"：TOC（每轨起点）+ lead-out。刷新信息重读盘片时不算换盘。 */
        fun fingerprint(disc: DiscInfo): String =
            (disc.toc?.entries.orEmpty().map { "${it.track}@${it.lba}" } + "end@${disc.capacity?.lastLba}").joinToString(",")
    }
}

/** 给界面用：当前播放器是否属于这台光驱的这张盘。 */
fun CdPlaybackController.Current?.matches(session: DriveSession, disc: DiscInfo): Boolean =
    this != null && this.session === session && this.fingerprint == CdPlaybackController.fingerprint(disc)

/** 方便界面直接拿到某张盘的播放状态。 */
fun CdPlaybackController.stateFor(session: DriveSession, disc: DiscInfo) =
    combine(current, state) { c, s -> if (c.matches(session, disc)) s else null }
