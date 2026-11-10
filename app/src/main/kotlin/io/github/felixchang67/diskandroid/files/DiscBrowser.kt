package io.github.felixchang67.diskandroid.files

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.fs.CachedSectorSource
import io.github.felixchang67.diskandroid.core.fs.ContentDetector
import io.github.felixchang67.diskandroid.core.fs.DiscContent
import io.github.felixchang67.diskandroid.core.fs.DiscEntry
import io.github.felixchang67.diskandroid.core.fs.DiscFileSystem
import io.github.felixchang67.diskandroid.core.fs.DiscFs
import io.github.felixchang67.diskandroid.core.fs.DiscText
import io.github.felixchang67.diskandroid.core.fs.DiscVolume
import io.github.felixchang67.diskandroid.core.fs.ProbeOptions
import io.github.felixchang67.diskandroid.core.fs.SECTOR_SIZE
import io.github.felixchang67.diskandroid.core.fs.ScsiSectorSource
import io.github.felixchang67.diskandroid.core.fs.SectorReadException
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.rip.RipPlan
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import io.github.felixchang67.diskandroid.rip.OutputFolder
import io.github.felixchang67.diskandroid.rip.RipService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览数据盘上的文件（进程级单例）：探测文件系统（ISO 9660 / Joliet / UDF）、识别盘片内容（DVD-Video、BDMV…）、
 * 逐级列目录、把文件 / 文件夹复制到保存位置（只限没有版权保护的盘）。
 * 探测结果按光驱缓存，读成 ISO 也用它来取卷标、决定可重写盘读到哪里。
 */
class DiscBrowser(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
    val folder: OutputFolder,
) {
    /** 一张盘的文件系统信息。 */
    class VolumeInfo(
        val volume: DiscVolume,
        /** 文件系统结尾（只对可重写盘算）。 */
        val fileSystemEnd: Long?,
        val fs: DiscFileSystem?,
        val content: DiscContent?,
        /** 打开文件系统失败的原因。 */
        val error: String?,
        val probeMillis: Long,
    )

    data class State(
        val session: DriveSession,
        val fingerprint: String,
        val loading: Boolean = true,
        val info: VolumeInfo? = null,
        val error: String? = null,
        /** 当前目录（从根目录开始的每一级）。 */
        val path: List<DiscEntry> = emptyList(),
        val entries: List<DiscEntry>? = null,
        val listing: Boolean = false,
        val listError: String? = null,
    )

    data class CopyState(
        val running: Boolean,
        val session: DriveSession,
        val title: String,
        val totalBytes: Long = 0,
        val doneBytes: Long = 0,
        val totalFiles: Int = 0,
        val doneFiles: Int = 0,
        val currentFile: String? = null,
        val bytesPerSecond: Double = 0.0,
        val cancelling: Boolean = false,
        val result: String? = null,
        val failed: Boolean = false,
        val problems: List<String> = emptyList(),
        val startedAtMs: Long = System.currentTimeMillis(),
    )

    private val _states = MutableStateFlow<Map<DriveSession, State>>(emptyMap())
    val states: StateFlow<Map<DriveSession, State>> = _states.asStateFlow()

    private val _copy = MutableStateFlow<CopyState?>(null)
    val copy: StateFlow<CopyState?> = _copy.asStateFlow()
    val copying: Boolean get() = _copy.value?.running == true

    /** 读盘 / 播放是否占用着这台光驱（由 AppContainer 设置）。 */
    var otherJobRunning: () -> Boolean = { false }

    private class Probe(val fingerprint: String, val result: CompletableDeferred<VolumeInfo?>)

    private val probes = mutableMapOf<DriveSession, Probe>()
    private var copyJob: Job? = null

    @Volatile
    private var cancelFlag = false
    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiskAndroid:copy").apply { setReferenceCounted(false) }

    /** 这张盘适不适合浏览文件（CD 要有数据轨）。 */
    fun browsable(disc: DiscInfo): Boolean =
        disc.capacity != null && when (disc.family) {
            MmcNames.DiscFamily.CD -> disc.dataTracks.isNotEmpty()
            MmcNames.DiscFamily.DVD, MmcNames.DiscFamily.BD -> true
            else -> false
        }

    /** 界面显示这张盘时调用：还没探测过就开始探测。 */
    fun ensureLoaded(session: DriveSession, disc: DiscInfo) {
        if (!browsable(disc)) return
        val fp = CdPlaybackController.fingerprint(disc)
        val current = _states.value[session]
        if (current != null && current.fingerprint == fp) return
        _states.update { it + (session to State(session, fp)) }
        scope.launch {
            val info = volumeInfo(session, disc)
            _states.update { m ->
                val s = m[session]?.takeIf { it.fingerprint == fp } ?: return@update m
                m + (session to s.copy(loading = false, info = info, error = if (info == null) "读不出文件系统（光驱不可用或读盘出错，见日志）" else null))
            }
            val fs = info?.fs ?: return@launch
            openDirectory(session, fp, listOf(fs.root))
        }
    }

    /** 探测文件系统（按光驱和盘片缓存；读成 ISO 也调用）。返回 null 表示读不出来。 */
    suspend fun volumeInfo(session: DriveSession, disc: DiscInfo): VolumeInfo? {
        if (!browsable(disc)) return null
        val fp = CdPlaybackController.fingerprint(disc)
        val probe = withContext(Dispatchers.Main.immediate) {
            probes[session]?.takeIf { it.fingerprint == fp } ?: Probe(fp, CompletableDeferred()).also { p ->
                probes[session] = p
                scope.launch(Dispatchers.IO) {
                    val info = runProbe(session, disc)
                    // 读失败（例如中途弹盘）不缓存，下次重新读
                    if (info == null) withContext(Dispatchers.Main.immediate) { if (probes[session] === p) probes.remove(session) }
                    p.result.complete(info)
                }
            }
        }
        return probe.result.await()
    }

    /** 读文件系统失败后重试。 */
    fun retry(session: DriveSession, disc: DiscInfo) {
        if (_states.value[session]?.loading == true) return
        _states.update { it - session }
        ensureLoaded(session, disc)
    }

    private suspend fun runProbe(session: DriveSession, disc: DiscInfo): VolumeInfo? {
        val t0 = System.currentTimeMillis()
        val src = CachedSectorSource(ScsiSectorSource(session::read, disc.capacity?.blocks))
        return try {
            val volume = DiscFs.probe(src, probeOptions(disc))
            val end = if (RipPlan.isOverwritable(disc.profile)) DiscFs.volumeEnd(src, volume) else null
            var fs: DiscFileSystem? = null
            var content: DiscContent? = null
            var error: String? = null
            try {
                fs = DiscFs.open(src, volume)
                content = fs?.let { ContentDetector.detect(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val ms = System.currentTimeMillis() - t0
            log.i(TAG, "盘上的文件系统：${volume.description}", "卷标" to volume.label, "内容" to content?.summary, "读命令" to src.commands, "耗时ms" to ms)
            volume.describeLines().drop(1).forEach { log.i(TAG, it) }
            if (end != null) log.i(TAG, "文件系统结尾：扇区 $end（READ CAPACITY ${disc.capacity?.blocks}）")
            content?.notes?.forEach { log.i(TAG, it) }
            error?.let { log.w(TAG, "打开文件系统失败：$it") }
            VolumeInfo(volume, end, fs, content, error, ms)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "探测文件系统失败：${e.message}")
            null
        }
    }

    private fun probeOptions(disc: DiscInfo): ProbeOptions {
        val start = if (disc.family == MmcNames.DiscFamily.CD) disc.sessionInfo?.lastSessionStartLba ?: 0 else 0
        val last = disc.tracks.lastOrNull()
        val written = buildList {
            last?.let { t ->
                if (t.lraValid) add(t.lastRecorded)
                if (t.size > 0) add(t.start + t.size - 1)
            }
            disc.capacity?.let { add(it.lastLba) }
        }.filter { it > 0 }.distinct()
        return ProbeOptions(sessionStart = start, lastWritten = written)
    }

    // ---------------- 目录 ----------------

    fun enter(session: DriveSession, dir: DiscEntry) {
        val s = _states.value[session] ?: return
        if (!dir.isDirectory) return
        openDirectory(session, s.fingerprint, s.path + dir)
    }

    /** 回到路径里的第 [index] 级（0 = 根目录）。 */
    fun goTo(session: DriveSession, index: Int) {
        val s = _states.value[session] ?: return
        if (index < 0 || index >= s.path.size) return
        openDirectory(session, s.fingerprint, s.path.take(index + 1))
    }

    private fun openDirectory(session: DriveSession, fp: String, path: List<DiscEntry>) {
        val fs = _states.value[session]?.info?.fs ?: return
        _states.update { m -> m[session]?.let { m + (session to it.copy(path = path, listing = true, listError = null)) } ?: m }
        scope.launch {
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { runCatching { fs.list(path.last()) } }
            result.onFailure { log.w(TAG, "列目录失败：${path.last().path}：${it.message}") }
            result.onSuccess { list ->
                log.i(
                    TAG, "打开目录 ${path.last().path}",
                    "文件夹" to list.count { it.isDirectory }, "文件" to list.count { !it.isDirectory }, "耗时ms" to System.currentTimeMillis() - t0,
                )
            }
            _states.update { m ->
                val s = m[session]?.takeIf { it.fingerprint == fp && it.path == path } ?: return@update m
                m + (session to s.copy(listing = false, entries = result.getOrNull() ?: s.entries, listError = result.exceptionOrNull()?.message))
            }
        }
    }

    // ---------------- 复制 ----------------

    /** 为什么不能复制这张盘上的文件（null = 可以）。 */
    fun copyRefusal(disc: DiscInfo, info: VolumeInfo?): String? = when {
        disc.dvdCopyright?.isCss == true -> "这张 DVD 有 CSS 加密：视频文件的扇区要认证后才能读，不提供复制（可以读成 ISO，阶段 3 再支持播放）"
        disc.dvdCopyright?.protectionType == 0x02 -> "这张盘有 CPRM 保护（数字电视录像），不提供复制"
        info?.content?.aacs == true -> "这张盘有 AACS 加密（BD 的视频文件是密文），不提供复制文件；可以读成 ISO（原样密文）"
        info?.content?.kinds?.any { it == io.github.felixchang67.diskandroid.core.fs.ContentKind.VCD || it == io.github.felixchang67.diskandroid.core.fs.ContentKind.SVCD } == true ->
            "Video CD 的视频在 Mode 2 Form 2 扇区里，暂不支持复制"
        else -> null
    }

    fun cancelCopy() {
        if (!copying) return
        cancelFlag = true
        _copy.update { it?.copy(cancelling = true) }
        log.i(TAG, "用户请求取消复制")
    }

    fun dismissCopy() {
        if (_copy.value?.running == false) _copy.value = null
    }

    /** 把 [entries]（文件或文件夹）复制到保存位置。返回给用户看的错误（null 表示已开始）。 */
    fun startCopy(session: DriveSession, disc: DiscInfo, entries: List<DiscEntry>, stopPlayback: () -> Unit): String? {
        if (copying) return "已经在复制了"
        if (otherJobRunning()) return "正在读盘，读完（或取消）之后再复制"
        val tree = folder.uri.value ?: return "请先选择保存位置"
        val state = _states.value[session] ?: return "盘上的文件还没读出来"
        val info = state.info ?: return "盘上的文件还没读出来"
        val fs = info.fs ?: return "这张盘的文件系统打不开"
        copyRefusal(disc, info)?.let {
            log.i(TAG, "不提供复制：$it")
            return it
        }
        if (entries.isEmpty()) return "没有要复制的东西"
        stopPlayback()
        cancelFlag = false
        val title = if (entries.size == 1) "复制「${entries[0].name.ifEmpty { info.volume.label ?: "整张盘" }}」" else "复制 ${entries.size} 项"
        _copy.value = CopyState(true, session, title)
        if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)
        RipService.start(app, log)
        copyJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { runCopy(fs, info, entries, tree) } }
            outcome.onFailure { e ->
                val cancelled = e is CopyCancelled
                if (!cancelled) log.e(TAG, "复制出错", throwable = e)
                _copy.update {
                    it?.copy(
                        running = false, cancelling = false, failed = !cancelled,
                        result = if (cancelled) "已取消（已复制的文件保留在保存位置，最后一个文件不完整）" else "出错：${e.message}",
                    )
                }
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
        return null
    }

    private class CopyCancelled : Exception("已取消")

    private class Item(val entry: DiscEntry, val parentDoc: Uri)

    private suspend fun runCopy(fs: DiscFileSystem, info: VolumeInfo, entries: List<DiscEntry>, tree: Uri) {
        val resolver = app.contentResolver
        val t0 = System.currentTimeMillis()
        val top = folder.documentUri(tree)
        // 先建好目录结构、数出文件和字节数
        val items = mutableListOf<Item>()
        suspend fun walk(e: DiscEntry, parent: Uri, depth: Int) {
            if (cancelFlag) throw CopyCancelled()
            if (!e.isDirectory) {
                items += Item(e, parent)
                return
            }
            val name = DiscText.safeFileName(e.name.ifEmpty { info.volume.label ?: "DISC" })
            val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IllegalStateException("无法在保存位置建文件夹「$name」")
            if (depth > 32) return
            for (child in fs.list(e)) walk(child, dir, depth + 1)
        }
        for (e in entries) walk(e, top, 0)
        val totalBytes = items.sumOf { it.entry.size }
        log.i(TAG, "开始复制", "文件" to items.size, "字节" to totalBytes, "目标" to folder.label(tree))
        _copy.update { it?.copy(totalBytes = totalBytes, totalFiles = items.size) }

        val buf = ByteArray(CHUNK)
        var done = 0L
        var lastUi = 0L
        val samples = ArrayDeque<Pair<Long, Long>>()
        val problems = mutableListOf<String>()
        for ((index, item) in items.withIndex()) {
            val e = item.entry
            _copy.update { it?.copy(currentFile = e.path, doneFiles = index) }
            val name = DiscText.safeFileName(e.name, "file")
            val doc = DocumentsContract.createDocument(resolver, item.parentDoc, OutputFolder.MIME, name)
                ?: throw IllegalStateException("无法在保存位置建文件「$name」")
            var badBytes = 0L
            val reader = try {
                fs.open(e)
            } catch (x: Exception) {
                if (x is SectorReadException && x.fatal) throw x
                problems += "${e.path}：打不开（${x.message}）"
                log.w(TAG, "打不开文件", "文件" to e.path, "原因" to x.message)
                continue
            }
            resolver.openOutputStream(doc, "w")?.use { out ->
                var pos = 0L
                while (pos < e.size) {
                    if (cancelFlag) throw CopyCancelled()
                    val want = minOf(CHUNK.toLong(), e.size - pos).toInt()
                    var n = try {
                        reader.read(pos, buf, 0, want)
                    } catch (x: SectorReadException) {
                        if (x.fatal) throw x
                        -2
                    }
                    if (n == -2) {
                        // 这一块读不出：逐扇区重试，仍然不行的扇区补零
                        val piece = minOf((SECTOR_SIZE - (pos % SECTOR_SIZE)).toLong(), e.size - pos).toInt()
                        var ok = false
                        repeat(RETRIES) {
                            if (!ok) {
                                ok = try {
                                    reader.read(pos, buf, 0, piece) == piece
                                } catch (x: SectorReadException) {
                                    if (x.fatal) throw x
                                    false
                                }
                            }
                        }
                        if (!ok) {
                            buf.fill(0, 0, piece)
                            badBytes += piece
                        }
                        n = piece
                    }
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 300) {
                        lastUi = now
                        samples.addLast(now to done)
                        while (samples.size > 2 && now - samples.first().first > 5_000) samples.removeFirst()
                        val (t1, d1) = samples.first()
                        val bps = if (now > t1) (done - d1) * 1000.0 / (now - t1) else 0.0
                        _copy.update { it?.copy(doneBytes = done, bytesPerSecond = bps) }
                    }
                }
            } ?: throw IllegalStateException("无法写入「$name」")
            if (badBytes > 0) {
                problems += "${e.path}：$badBytes 字节读不出，已补零"
                log.w(TAG, "文件有读不出的扇区（已补零）", "文件" to e.path, "字节" to badBytes)
            }
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000
        val summary = "已复制 ${items.size} 个文件（${DiscInfo.formatBytes(done)}），用时 ${elapsed / 60} 分 ${elapsed % 60} 秒" +
            if (problems.isNotEmpty()) "；${problems.size} 个文件有问题" else ""
        log.i(TAG, "复制完成：$summary")
        withContext(Dispatchers.Main) {
            _copy.update {
                it?.copy(running = false, doneBytes = done, doneFiles = items.size, currentFile = null, result = summary, problems = problems, cancelling = false)
            }
        }
    }

    companion object {
        const val TAG = "FS"
        private const val CHUNK = 64 * SECTOR_SIZE
        private const val RETRIES = 3
    }
}
