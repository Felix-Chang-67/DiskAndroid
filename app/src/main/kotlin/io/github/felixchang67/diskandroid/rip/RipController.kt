package io.github.felixchang67.diskandroid.rip

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveEvent
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.fs.DiscText
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.LogFormat
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.rip.ChannelRipOutput
import io.github.felixchang67.diskandroid.core.rip.RipEngine
import io.github.felixchang67.diskandroid.core.rip.RipOptions
import io.github.felixchang67.diskandroid.core.rip.RipPlan
import io.github.felixchang67.diskandroid.core.rip.RipProgress
import io.github.felixchang67.diskandroid.core.rip.RipReport
import io.github.felixchang67.diskandroid.core.rip.RipResult
import io.github.felixchang67.diskandroid.files.DiscBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 读成 ISO 的总控（进程级单例，状态只在主线程修改）：选择保存位置（SAF 目录，持久授权）、检查空间、
 * 先读文件系统（取卷标；可重写盘只读到文件系统结尾）、建文件、在 IO 线程跑 [RipEngine]（读盘经光驱会话线程），
 * 期间运行前台服务并持有 CPU 唤醒锁；结束后写 .log 报告和 .mapfile。同一时间只读一张盘；读盘时不播放、不复制。
 */
class RipController(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
    private val output: OutputFolder,
    private val browser: DiscBrowser,
) {
    data class State(
        val running: Boolean,
        val session: DriveSession,
        val fingerprint: String,
        val family: MmcNames.DiscFamily,
        val imageName: String? = null,
        val plan: RipPlan? = null,
        val progress: RipProgress? = null,
        val result: RipResult? = null,
        val files: List<String> = emptyList(),
        val error: String? = null,
        val cancelling: Boolean = false,
        val startedAtMs: Long = 0,
        /** 开始前的准备（读文件系统）。 */
        val phase: String? = null,
        /** 读盘过程中的意外（复位等），都已自动恢复。 */
        val incidents: List<String> = emptyList(),
        val label: String? = null,
    )

    val folder: StateFlow<Uri?> get() = output.uri

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    val running: Boolean get() = _state.value?.running == true

    private var job: Job? = null

    @Volatile
    private var cancelFlag = false
    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiskAndroid:rip").apply { setReferenceCounted(false) }

    fun setFolder(uri: Uri) = output.set(uri)
    fun folderLabel(uri: Uri): String = output.label(uri)
    fun freeBytes(uri: Uri): Long? = output.freeBytes(uri)

    fun dismissResult() {
        if (_state.value?.running == false) _state.value = null
    }

    fun cancel() {
        if (!running) return
        cancelFlag = true
        _state.update { it?.copy(cancelling = true) }
        log.i(TAG, "用户请求取消读盘")
    }

    /** 开始读成 ISO。返回给用户看的错误（null 表示已开始）。 */
    fun start(session: DriveSession, disc: DiscInfo, fingerprint: String, options: RipOptions, stopPlayback: () -> Unit): String? {
        if (running) return "已经在读一张盘了"
        if (browser.copying) return "正在复制文件，复制完（或取消）之后再读盘"
        val tree = output.uri.value ?: return "请先选择保存位置"
        (RipPlan.from(disc) as? RipPlan.Build.Refused)?.let { return it.reason }
        stopPlayback()
        cancelFlag = false
        val startedAt = System.currentTimeMillis()
        _state.value = State(true, session, fingerprint, disc.family, startedAtMs = startedAt, phase = "读取文件系统…")
        if (!wakeLock.isHeld) wakeLock.acquire(12 * 60 * 60 * 1000L)
        RipService.start(app, log)
        job = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { rip(session, disc, tree, options, startedAt) } }
            outcome.onFailure { e ->
                log.e(TAG, "读盘出错", throwable = e)
                _state.update { it?.copy(running = false, error = "出错：${e.message}", phase = null) }
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
        return null
    }

    private suspend fun rip(session: DriveSession, disc: DiscInfo, tree: Uri, options: RipOptions, startedAt: Long) {
        val resolver = app.contentResolver
        val dir = output.documentUri(tree)
        // 先读文件系统：卷标用来起文件名，可重写盘只读到文件系统结尾
        val info = browser.volumeInfo(session, disc)
        val end = if (options.trimRewritable) info?.fileSystemEnd else null
        val plan = when (val b = RipPlan.from(disc, end)) {
            is RipPlan.Build.Refused -> return fail(b.reason)
            is RipPlan.Build.Ok -> b.plan
        }
        if (plan.trimmed) log.i(TAG, "可重写盘只读到文件系统结尾", "扇区" to plan.totalSectors, "整张盘" to plan.capacitySectors)
        output.freeBytes(tree)?.let { free ->
            if (free < plan.bytes + 16L * 1024 * 1024) {
                return fail("保存位置剩余空间不够：需要 ${DiscInfo.formatBytes(plan.bytes)}，只剩 ${DiscInfo.formatBytes(free)}")
            }
        }
        if (cancelFlag) return fail("已取消")
        val label = info?.volume?.label
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val base = DiscText.safeFileName(label ?: "DISC", maxLength = 60).replace(' ', '_') + "_" + stamp
        val isoUri = DocumentsContract.createDocument(resolver, dir, OutputFolder.MIME, "$base.iso") ?: error("无法在保存位置创建文件（目录还能写吗？）")
        val name = output.displayName(isoUri) ?: "$base.iso"
        _state.update { it?.copy(imageName = name, plan = plan, phase = null, label = label) }
        log.i(TAG, "镜像文件：$name", "目录" to output.label(tree), "扇区" to plan.totalSectors, "卷标" to label)

        val resetsBefore = session.resetCount
        val pfd = resolver.openFileDescriptor(isoUri, "rw") ?: error("无法打开镜像文件")
        val out = ChannelRipOutput(FileOutputStream(pfd.fileDescriptor).channel, FileInputStream(pfd.fileDescriptor).channel) { pfd.close() }
        var lastUi = 0L
        val result = out.use { o ->
            RipEngine(session::read, o, log, options).run(plan, { cancelFlag }) { p ->
                val now = System.currentTimeMillis()
                if (now - lastUi >= 500 || p.doneSectors >= p.totalSectors) {
                    lastUi = now
                    scope.launch { _state.update { it?.copy(progress = p) } }
                }
            }
        }
        val incidents = incidents(session, startedAt, session.resetCount - resetsBefore)
        val started = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneId.systemDefault()))
        val drive = session.info.value?.inquiry?.let { i -> "${i.vendor} ${i.product} ${i.revision}" + (i.ataString.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: "") } ?: session.label
        val report = RipReport.text("DiskAndroid ${BuildConfig.VERSION_NAME}", started, drive, disc, plan, options, name, result, info?.volume, incidents)
        val files = mutableListOf(name)
        writeText(dir, "$base.iso.log", report)?.let { files += it }
        writeText(dir, "$base.iso.mapfile", result.mapfile)?.let { files += it }
        withContext(Dispatchers.Main) {
            _state.update { it?.copy(running = false, result = result, files = files, cancelling = false, incidents = incidents) }
        }
    }

    /** 读盘期间光驱的意外：App 做的 BOT 复位恢复、光驱自己复位（多半是供电不足）。 */
    private fun incidents(session: DriveSession, since: Long, botResets: Long): List<String> {
        val out = mutableListOf<String>()
        if (botResets > 0) out += "光驱 $botResets 次长时间没有回应，App 复位了 USB 连接后接着读"
        val selfResets = session.events.value.filter { it.kind == DriveEvent.Kind.UNEXPECTED_RESET && it.timeMs >= since }
        if (selfResets.isNotEmpty()) {
            out += "光驱自行复位 ${selfResets.size} 次（${selfResets.joinToString("、") { LogFormat.time(it.timeMs).substringBefore('.') }}）：" +
                "多发生在光驱提速的时候，通常是供电不足，换带供电的扩展坞 / 接上光驱的辅助供电线可以避免"
        }
        return out
    }

    private suspend fun fail(message: String) {
        log.w(TAG, "读盘没有开始：$message")
        withContext(Dispatchers.Main) { _state.update { it?.copy(running = false, error = message, phase = null) } }
    }

    private fun writeText(dir: Uri, name: String, text: String): String? = runCatching {
        val uri = DocumentsContract.createDocument(app.contentResolver, dir, OutputFolder.MIME, name) ?: return null
        app.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        output.displayName(uri) ?: name
    }.onFailure { log.w(TAG, "写 $name 失败：${it.message}") }.getOrNull()

    companion object {
        const val TAG = "RIP"
    }
}
