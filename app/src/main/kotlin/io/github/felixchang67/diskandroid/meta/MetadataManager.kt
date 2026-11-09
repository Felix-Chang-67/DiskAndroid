package io.github.felixchang67.diskandroid.meta

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.meta.DiscIdentifier
import io.github.felixchang67.diskandroid.core.meta.DiscMetadata
import io.github.felixchang67.diskandroid.core.meta.DiscToc
import io.github.felixchang67.diskandroid.core.meta.HttpClient
import io.github.felixchang67.diskandroid.core.meta.LrcLibClient
import io.github.felixchang67.diskandroid.core.meta.Lyrics
import io.github.felixchang67.diskandroid.core.meta.LyricsFinder
import io.github.felixchang67.diskandroid.core.meta.MetadataCandidate
import io.github.felixchang67.diskandroid.core.meta.MetadataStore
import io.github.felixchang67.diskandroid.core.meta.MusicBrainzClient
import io.github.felixchang67.diskandroid.core.meta.NetEaseClient
import io.github.felixchang67.diskandroid.core.meta.UrlConnectionHttpClient
import io.github.felixchang67.diskandroid.core.mmc.MmcCommands
import io.github.felixchang67.diskandroid.core.mmc.SubChannel
import io.github.felixchang67.diskandroid.core.scsi.ScsiResult
import io.github.felixchang67.diskandroid.drive.ActiveDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 音乐 CD 的在线信息（曲名、艺人、封面、歌词）。状态只在主线程修改，网络请求在 IO 线程。
 * - 新的音乐 CD 就绪：先查本机缓存，没有就在线识别（MusicBrainz → 条码 → 网易云补全，见 [DiscIdentifier]）；
 * - 歌词按需获取（正在播放的那首），结果缓存到本机；
 * - 封面下载后缓存成文件。
 * 联网只发送光盘 TOC（光盘 ID）、条码、搜索词和歌曲 / 专辑 ID，可以在设置里关掉。
 */
class MetadataManager(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
    /** 测试时换成返回固定数据的假实现。 */
    private val http: HttpClient = UrlConnectionHttpClient(),
    storeDir: File = File(app.filesDir, "metadata"),
) {
    data class DiscState(
        val discId: String,
        val toc: DiscToc,
        val metadata: DiscMetadata?,
        val candidates: List<MetadataCandidate> = emptyList(),
        val notes: List<String> = emptyList(),
        /** 正在进行的操作（显示用），null 表示空闲。 */
        val busy: String? = null,
        val error: String? = null,
        val fromCache: Boolean = false,
        /** 手动搜索的结果（null 表示还没搜过）。 */
        val searchResults: List<MetadataCandidate>? = null,
        val searchNotes: List<String> = emptyList(),
    )

    sealed interface LyricsState {
        data object Loading : LyricsState
        class Ready(val lyrics: Lyrics) : LyricsState
        data object None : LyricsState
        data object Offline : LyricsState
        class Failed(val message: String) : LyricsState
    }

    private val prefs = app.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private val _online = MutableStateFlow(prefs.getBoolean("online", true))
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val store = MetadataStore(storeDir)
    private val userAgent = "DiskAndroid/${BuildConfig.VERSION_NAME} ( https://github.com/Felix-Chang-67/DiskAndroid )"
    private val netEase = NetEaseClient(http)
    private val identifier = DiscIdentifier(MusicBrainzClient(http, userAgent), netEase, log)
    private val lyricsFinder = LyricsFinder(netEase, LrcLibClient(http, userAgent), log)
    private val sessions = mutableMapOf<String, DriveSession>()

    private val _discs = MutableStateFlow<Map<String, DiscState>>(emptyMap())
    val discs: StateFlow<Map<String, DiscState>> = _discs.asStateFlow()

    private val _lyrics = MutableStateFlow<Map<String, LyricsState>>(emptyMap())
    val lyrics: StateFlow<Map<String, LyricsState>> = _lyrics.asStateFlow()

    private val coverDir = File(app.cacheDir, "covers").apply { mkdirs() }
    private val _covers = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val covers: StateFlow<Map<String, Bitmap>> = _covers.asStateFlow()
    private val coverRequests = mutableSetOf<String>()

    /** 跟踪所有光驱的盘片：音乐 CD 就绪时自动识别。 */
    fun start(drives: StateFlow<Map<String, ActiveDrive>>) {
        scope.launch {
            drives.collectLatest { map ->
                coroutineScope {
                    map.values.forEach { d ->
                        launch { d.session.disc.collect { disc -> if (disc != null && disc.hasAudio) onDiscReady(d.session, disc) } }
                    }
                }
            }
        }
    }

    fun discIdOf(disc: DiscInfo): String? = DiscToc.from(disc)?.musicBrainzId

    fun metadataFor(disc: DiscInfo): DiscMetadata? = discIdOf(disc)?.let { _discs.value[it]?.metadata }

    fun onDiscReady(session: DriveSession, disc: DiscInfo) {
        val toc = DiscToc.from(disc) ?: return
        val id = toc.musicBrainzId
        sessions[id] = session
        val existing = _discs.value[id]
        if (existing != null && (existing.metadata != null || existing.busy != null)) return
        val cached = store.load(id)
        if (cached != null) {
            log.i(TAG, "使用本机缓存的曲目信息", "光盘 ID" to id, "专辑" to cached.summary())
            put(DiscState(id, toc, cached, notes = listOf("来自本机缓存（${cached.sources.joinToString(" + ")}）"), fromCache = true))
            cached.coverUrl?.let { requestCover(it) }
            return
        }
        if (!_online.value) {
            put(DiscState(id, toc, null, notes = listOf("联网识别已关闭（在播放设置里打开）")))
            return
        }
        identify(id, toc)
    }

    /** 重新联网识别（会覆盖本机缓存）。 */
    fun reidentify(discId: String) {
        val st = _discs.value[discId] ?: return
        store.delete(discId)
        put(st.copy(metadata = null, candidates = emptyList(), notes = emptyList(), error = null, fromCache = false))
        identify(discId, st.toc)
    }

    fun search(discId: String, keywords: String) {
        val st = _discs.value[discId] ?: return
        if (keywords.isBlank()) return
        put(st.copy(busy = "正在搜索「$keywords」…", error = null))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { identifier.searchManual(keywords.trim(), st.toc) } }
            val cur = _discs.value[discId] ?: return@launch
            r.onSuccess { o -> put(cur.copy(busy = null, searchResults = o.candidates, searchNotes = o.notes)) }
                .onFailure { e -> put(cur.copy(busy = null, error = "搜索失败：${e.message}")) }
        }
    }

    fun choose(discId: String, candidate: MetadataCandidate) {
        val st = _discs.value[discId] ?: return
        put(st.copy(busy = "正在补全信息…", error = null))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { identifier.finalizeChoice(candidate, st.toc).also { store.save(it) } } }
            val cur = _discs.value[discId] ?: return@launch
            r.onSuccess { meta ->
                log.i(TAG, "用户选择了曲目信息", "专辑" to meta.summary(), "来源" to meta.sources.joinToString(" + "))
                put(cur.copy(busy = null, metadata = meta, notes = listOf("已采用你选的结果（${candidate.source}）"), fromCache = false))
                clearLyrics(discId)
                meta.coverUrl?.let { requestCover(it) }
            }.onFailure { e -> put(cur.copy(busy = null, error = "保存失败：${e.message}")) }
        }
    }

    fun setOnline(enabled: Boolean) {
        _online.value = enabled
        prefs.edit { putBoolean("online", enabled) }
        log.i(TAG, "联网识别：${if (enabled) "开" else "关"}")
    }

    fun clearCache() {
        store.clear()
        coverDir.listFiles()?.forEach { it.delete() }
        _lyrics.value = emptyMap()
        log.i(TAG, "已清除本机缓存的曲目信息、歌词与封面")
    }

    // ---------------- 歌词 ----------------

    fun lyricsKey(discId: String, track: Int) = "$discId#$track"

    /** 请求某首的歌词（已有结果或正在获取时不重复请求）。 */
    fun requestLyrics(discId: String, track: Int, durationMs: Long) {
        val key = lyricsKey(discId, track)
        val current = _lyrics.value[key]
        if (current != null && current !is LyricsState.Failed && current !is LyricsState.Offline) return
        val meta = _discs.value[discId]?.metadata ?: return
        val t = meta.track(track) ?: return
        val artist = t.artist ?: meta.artist
        val cacheKey = MetadataStore.lyricsKey(t.neteaseSongId, t.title, artist, durationMs)
        when (val cached = store.loadLyrics(cacheKey)) {
            is MetadataStore.CachedLyrics.Found -> return putLyrics(key, LyricsState.Ready(cached.lyrics))
            MetadataStore.CachedLyrics.None -> return putLyrics(key, LyricsState.None)
            null -> Unit
        }
        if (!_online.value) return putLyrics(key, LyricsState.Offline)
        putLyrics(key, LyricsState.Loading)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { lyricsFinder.find(t.title, artist, meta.album, durationMs, t.neteaseSongId).also { store.saveLyrics(cacheKey, it) } }
            }
            putLyrics(
                key,
                r.fold({ l -> if (l != null) LyricsState.Ready(l) else LyricsState.None }, { e -> LyricsState.Failed(e.message ?: e.toString()) }),
            )
        }
    }

    private fun clearLyrics(discId: String) {
        _lyrics.update { m -> m.filterKeys { !it.startsWith("$discId#") } }
    }

    private fun putLyrics(key: String, state: LyricsState) {
        _lyrics.update { it + (key to state) }
    }

    // ---------------- 封面 ----------------

    fun requestCover(url: String) {
        if (url in _covers.value || !coverRequests.add(url)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { loadCover(url) }.onFailure { log.w(TAG, "封面下载失败：${it.message}") }.getOrNull() }
            if (bitmap != null) _covers.update { it + (url to bitmap) } else coverRequests.remove(url)
        }
    }

    private fun loadCover(url: String): Bitmap? {
        val file = File(coverDir, sha1(url) + ".img")
        if (!file.isFile) {
            if (!_online.value) return null
            val r = http.get(url, mapOf("User-Agent" to userAgent))
            if (r.code != 200 || r.body.isEmpty()) return null
            file.writeBytes(r.body)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------- 内部 ----------------

    private fun identify(id: String, toc: DiscToc) {
        val base = _discs.value[id] ?: DiscState(id, toc, null)
        put(base.copy(busy = "正在联网识别…", error = null))
        val session = sessions[id]
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { identifier.identify(toc) { session?.let { s -> runBlocking { readMcn(s) } } } }
            }
            val cur = _discs.value[id] ?: base
            r.onSuccess { o ->
                o.chosen?.let { store.save(it) }
                put(cur.copy(busy = null, metadata = o.chosen ?: cur.metadata, candidates = o.candidates, notes = o.notes))
                o.chosen?.coverUrl?.let { requestCover(it) }
            }.onFailure { e ->
                log.w(TAG, "在线识别失败：${e.message}")
                put(cur.copy(busy = null, error = "识别失败：${e.message}"))
            }
        }
    }

    private suspend fun readMcn(session: DriveSession): String? {
        val cmd = MmcCommands.readMcn()
        val r = session.read(cmd) ?: return null
        if (r !is ScsiResult.Good) return null
        return SubChannel.parseMcn(cmd.buffer!!.copyOf(r.transferred)).also { log.i(TAG, "读盘上条码（MCN）", "结果" to (it ?: "没有")) }
    }

    private fun put(state: DiscState) {
        _discs.update { it + (state.discId to state) }
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 诊断信息用。 */
    fun describeLines(): List<String> = _discs.value.values.flatMap { st ->
        listOf("光盘 ID ${st.discId}：${st.metadata?.summary() ?: "未识别"}" + (if (st.fromCache) "（缓存）" else "") + (st.error?.let { "；$it" } ?: "")) +
            st.notes.map { "  $it" }
    }

    companion object {
        const val TAG = "META"
    }
}

/** 界面、通知栏共用的显示名：在线信息 > CD-TEXT > "Track N"。 */
object TrackNames {
    fun title(disc: DiscInfo, meta: DiscMetadata?, track: Int): String =
        meta?.track(track)?.title?.takeIf { it.isNotBlank() } ?: disc.cdText?.title(track) ?: "Track $track"

    fun translated(meta: DiscMetadata?, track: Int): String? = meta?.track(track)?.translatedTitle

    fun artist(disc: DiscInfo, meta: DiscMetadata?, track: Int): String? =
        meta?.track(track)?.artist ?: meta?.artist ?: disc.cdText?.performer(track)

    fun album(disc: DiscInfo, meta: DiscMetadata?): String? = meta?.album ?: disc.albumTitle

    fun albumArtist(disc: DiscInfo, meta: DiscMetadata?): String? = meta?.artist ?: disc.albumPerformer

    /** 有假名就按日语选字形。 */
    fun looksJapanese(vararg texts: String?): Boolean = texts.any { t -> t != null && t.any { it in '぀'..'ヿ' || it in 'ㇰ'..'ㇿ' } }
}
