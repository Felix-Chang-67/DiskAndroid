package io.github.felixchang67.diskandroid.rip

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.mmc.MmcNames
import io.github.felixchang67.diskandroid.core.rip.ChannelRipOutput
import io.github.felixchang67.diskandroid.core.rip.RipEngine
import io.github.felixchang67.diskandroid.core.rip.RipOptions
import io.github.felixchang67.diskandroid.core.rip.RipPlan
import io.github.felixchang67.diskandroid.core.rip.RipProgress
import io.github.felixchang67.diskandroid.core.rip.RipReport
import io.github.felixchang67.diskandroid.core.rip.RipResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 读成 ISO 的总控（进程级单例，状态只在主线程修改）：选择保存位置（SAF 目录，持久授权）、检查空间、
 * 建文件、在 IO 线程跑 [RipEngine]（读盘经光驱会话线程），期间运行前台服务并持有 CPU 唤醒锁；
 * 结束后写 .log 报告和 .mapfile。同一时间只读一张盘；读盘时不播放。
 */
class RipController(
    private val app: Application,
    private val log: DiskLog,
    private val scope: CoroutineScope,
) {
    data class State(
        val running: Boolean,
        val session: DriveSession?,
        val fingerprint: String,
        val family: MmcNames.DiscFamily,
        val imageName: String? = null,
        val progress: RipProgress? = null,
        val result: RipResult? = null,
        val files: List<String> = emptyList(),
        val error: String? = null,
        val cancelling: Boolean = false,
        val startedAtMs: Long = 0,
    )

    private val prefs = app.getSharedPreferences("rip", Context.MODE_PRIVATE)
    private val _folder = MutableStateFlow(prefs.getString("folder", null)?.toUri())
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    val running: Boolean get() = _state.value?.running == true

    private var job: Job? = null
    @Volatile
    private var cancelFlag = false
    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiskAndroid:rip").apply { setReferenceCounted(false) }

    fun setFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { log.w(TAG, "无法保存目录授权：${it.message}") }
        _folder.value = uri
        prefs.edit { putString("folder", uri.toString()) }
        log.i(TAG, "ISO 保存位置：${folderLabel(uri)}")
    }

    /** 目录的显示名（查不到就用 URI 里的路径）。 */
    fun folderLabel(uri: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "存储根目录" }

    /** 可用空间（只对本机存储 / SD 卡能算出来），算不出返回 null。 */
    fun freeBytes(uri: Uri): Long? = runCatching {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
        val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        StatFs(root.path).availableBytes
    }.getOrNull()

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
        val folder = _folder.value ?: return "请先选择保存位置"
        val plan = when (val b = RipPlan.from(disc)) {
            is RipPlan.Build.Refused -> return b.reason
            is RipPlan.Build.Ok -> b.plan
        }
        freeBytes(folder)?.let { free ->
            if (free < plan.bytes + 16L * 1024 * 1024) {
                return "保存位置剩余空间不够：需要 ${DiscInfo.formatBytes(plan.bytes)}，只剩 ${DiscInfo.formatBytes(free)}"
            }
        }
        stopPlayback()
        cancelFlag = false
        val startedAt = System.currentTimeMillis()
        _state.value = State(true, session, fingerprint, plan.family, startedAtMs = startedAt)
        if (!wakeLock.isHeld) wakeLock.acquire(12 * 60 * 60 * 1000L)
        RipService.start(app, log)
        job = scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { rip(session, disc, plan, folder, options, startedAt) } }
            outcome.onFailure { e ->
                log.e(TAG, "读盘出错", throwable = e)
                _state.update { it?.copy(running = false, error = "出错：${e.message}") }
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
        return null
    }

    private suspend fun rip(session: DriveSession, disc: DiscInfo, plan: RipPlan, folder: Uri, options: RipOptions, startedAt: Long) {
        val resolver = app.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val probe = RipEngine(session::read, NoOutput, log, options)
        val label = probe.probeVolume()?.volumeId
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val base = sanitize(label ?: "DISC") + "_" + stamp
        val isoUri = DocumentsContract.createDocument(resolver, dir, MIME, "$base.iso") ?: error("无法在保存位置创建文件（目录还能写吗？）")
        val name = queryName(isoUri) ?: "$base.iso"
        _state.update { it?.copy(imageName = name) }
        log.i(TAG, "镜像文件：$name", "目录" to folderLabel(folder), "扇区" to plan.totalSectors)

        val pfd = resolver.openFileDescriptor(isoUri, "rw") ?: error("无法打开镜像文件")
        val output = ChannelRipOutput(FileOutputStream(pfd.fileDescriptor).channel, FileInputStream(pfd.fileDescriptor).channel) { pfd.close() }
        var lastUi = 0L
        val result = output.use { out ->
            RipEngine(session::read, out, log, options).run(plan, { cancelFlag }) { p ->
                val now = System.currentTimeMillis()
                if (now - lastUi >= 500 || p.doneSectors >= p.totalSectors) {
                    lastUi = now
                    scope.launch { _state.update { it?.copy(progress = p) } }
                }
            }
        }
        val started = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneId.systemDefault()))
        val drive = session.info.value?.inquiry?.let { i -> "${i.vendor} ${i.product} ${i.revision}" + (i.ataString.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: "") } ?: session.label
        val report = RipReport.text("DiskAndroid ${BuildConfig.VERSION_NAME}", started, drive, disc, plan, options, name, result)
        val files = mutableListOf(name)
        writeText(dir, "$base.iso.log", report)?.let { files += it }
        writeText(dir, "$base.iso.mapfile", result.mapfile)?.let { files += it }
        withContext(Dispatchers.Main) {
            _state.update { it?.copy(running = false, result = result, files = files, cancelling = false) }
        }
    }

    private fun writeText(dir: Uri, name: String, text: String): String? = runCatching {
        val uri = DocumentsContract.createDocument(app.contentResolver, dir, MIME, name) ?: return null
        app.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        queryName(uri) ?: name
    }.onFailure { log.w(TAG, "写 $name 失败：${it.message}") }.getOrNull()

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sanitize(s: String) = s.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("").take(60).ifEmpty { "DISC" }

    /** 只用来探测卷标，不写任何东西。 */
    private object NoOutput : io.github.felixchang67.diskandroid.core.rip.RipOutput {
        override fun write(position: Long, data: ByteArray, offset: Int, length: Int) = Unit
        override fun read(position: Long, data: ByteArray, offset: Int, length: Int) = -1
        override fun flush() = Unit
        override fun close() = Unit
    }

    companion object {
        const val TAG = "RIP"
        /** 用 octet-stream：文件管理类的提供者不会因为类型给文件名乱加扩展名。 */
        private const val MIME = "application/octet-stream"
    }
}
