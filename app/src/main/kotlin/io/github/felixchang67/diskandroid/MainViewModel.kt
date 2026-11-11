package io.github.felixchang67.diskandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.felixchang67.diskandroid.core.log.LogRecord
import io.github.felixchang67.diskandroid.core.log.LogTags
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceMonitor
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot
import io.github.felixchang67.diskandroid.core.usb.UsbEvent
import io.github.felixchang67.diskandroid.diag.Diagnostics
import io.github.felixchang67.diskandroid.drive.ActiveDrive
import io.github.felixchang67.diskandroid.log.LogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val devices: StateFlow<List<UsbDeviceSnapshot>> = c.usbMonitor.devices
    val logVersion: StateFlow<Long> = c.ring.version
    val reports: StateFlow<Map<String, ProbeReport>> = c.driveManager.reports
    val drives: StateFlow<Map<String, ActiveDrive>> = c.driveManager.drives
    val connecting: StateFlow<Set<String>> = c.driveManager.connecting
    val player = c.player
    val metadata = c.metadata
    val rip = c.rip
    val files = c.files
    val video = c.video

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { c.driveManager.messages.collect { _messages.tryEmit(it) } }
        viewModelScope.launch { c.video.messages.collect { _messages.tryEmit(it) } }
        viewModelScope.launch {
            c.usbMonitor.events.collect { event ->
                when (event) {
                    is UsbEvent.Attached -> {
                        val gap = event.reconnectGapMs
                        if (gap != null && gap < UsbDeviceMonitor.QUICK_RECONNECT_MS) {
                            _messages.tryEmit("设备断开后 $gap ms 又重新连接：如果不是你手动插拔，请检查带供电的扩展坞 / Boost 线")
                        }
                    }
                    is UsbEvent.PermissionResult -> if (!event.granted) {
                        _messages.tryEmit("USB 权限被拒绝。可以点“申请权限”重试，或者重新插拔光驱后在系统弹窗里选“确定”")
                    }
                    is UsbEvent.Detached -> Unit
                }
            }
        }
    }

    fun onUsbAttachedIntent(device: UsbDevice?) = c.usbMonitor.onAttachedIntent(device)

    fun onResume() = c.usbMonitor.refresh("回到前台")

    fun requestPermission(deviceName: String) {
        val device = c.usbMonitor.findDevice(deviceName)
        if (device == null) {
            _messages.tryEmit("设备已经断开")
            return
        }
        c.usbMonitor.requestPermission(device)
    }

    fun reconnect(deviceName: String) = c.driveManager.reconnect(deviceName)

    fun eject(deviceName: String) = c.driveManager.eject(deviceName)

    fun refreshDrive(deviceName: String) = c.driveManager.refresh(deviceName)

    /** 播放这台光驱当前盘片的第 [track] 轨。 */
    fun playTrack(deviceName: String, track: Int) {
        val drive = c.driveManager.drives.value[deviceName]
        val disc = drive?.session?.disc?.value
        if (drive == null || disc == null) {
            _messages.tryEmit("光驱或盘片已经不在了")
            return
        }
        if (c.rip.running) {
            _messages.tryEmit("正在读盘，读完（或取消）之后才能播放")
            return
        }
        if (c.files.copying) {
            _messages.tryEmit("正在从盘上复制文件，复制完（或取消）之后才能播放")
            return
        }
        c.player.play(drive.session, disc, track)
    }

    /** 播放这台光驱里的 DVD：[startTitle] = 0 从光盘菜单开始，> 0 直接播放这个标题。 */
    fun playVideo(deviceName: String, startTitle: Int) {
        val drive = c.driveManager.drives.value[deviceName]
        val disc = drive?.session?.disc?.value
        if (drive == null || disc == null) {
            _messages.tryEmit("光驱或盘片已经不在了")
            return
        }
        val label = c.files.states.value[drive.session]?.info?.volume?.label
        c.video.play(drive.session, disc, startTitle, label, "用户点了播放")?.let { _messages.tryEmit(it) }
    }

    /** 把盘上的文件 / 文件夹复制到保存位置。 */
    fun startCopy(deviceName: String, entries: List<io.github.felixchang67.diskandroid.core.fs.DiscEntry>) {
        val drive = c.driveManager.drives.value[deviceName]
        val disc = drive?.session?.disc?.value
        if (drive == null || disc == null) {
            _messages.tryEmit("光驱或盘片已经不在了")
            return
        }
        c.files.startCopy(drive.session, disc, entries) { stopPlayback("开始复制文件") }?.let { _messages.tryEmit(it) }
    }

    /** 把这台光驱当前的盘读成 ISO。 */
    fun startRip(deviceName: String, options: io.github.felixchang67.diskandroid.core.rip.RipOptions) {
        val drive = c.driveManager.drives.value[deviceName]
        val disc = drive?.session?.disc?.value
        if (drive == null || disc == null) {
            _messages.tryEmit("光驱或盘片已经不在了")
            return
        }
        val fingerprint = io.github.felixchang67.diskandroid.player.CdPlaybackController.fingerprint(disc)
        c.rip.start(drive.session, disc, fingerprint, options) { stopPlayback("开始读盘") }?.let { _messages.tryEmit(it) }
    }

    private fun stopPlayback(reason: String) {
        c.player.stop()
        c.video.stop(reason)
    }

    fun logSnapshot(): List<LogRecord> = c.ring.snapshot()

    /** 只清空界面上的日志，文件里的日志不受影响。 */
    fun clearLogView() = c.ring.clear()

    private fun diagnostics(context: Context): String = Diagnostics.build(context, c)

    fun copyDiagnostics(context: Context) {
        val text = diagnostics(context)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("DiskAndroid 诊断信息", text))
        c.log.i(LogTags.UI, "已复制诊断信息", "字符数" to text.length)
        _messages.tryEmit("已复制诊断信息（${text.length} 字符），请粘贴到对话里发给我")
    }

    fun suggestedExportName(): String = LogExport.suggestedZipName()

    fun exportLogs(context: Context, target: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val diag = diagnostics(appContext)
            c.log.flush()
            val result = withContext(Dispatchers.IO) {
                runCatching { LogExport.writeZip(appContext, target, c.fileSink.recentSessionFiles(5), diag) }
            }
            result.onSuccess { bytes ->
                c.log.i(LogTags.UI, "日志已导出", "字节" to bytes, "位置" to target)
                _messages.tryEmit("日志已导出（约 ${bytes / 1024} KB，含最近 5 次启动的日志）")
            }.onFailure {
                c.log.e(LogTags.UI, "导出日志失败", throwable = it)
                _messages.tryEmit("导出失败：${it.message}")
            }
        }
    }

    fun shareLogs(context: Context) {
        viewModelScope.launch {
            val diag = diagnostics(context)
            c.log.flush()
            val file = withContext(Dispatchers.IO) {
                runCatching { LogExport.prepareShareFile(context.applicationContext, c.fileSink.textFiles(), diag) }
            }
            file.onSuccess { f ->
                runCatching { LogExport.startShare(context, f) }
                    .onFailure { c.log.e(LogTags.UI, "启动分享失败", throwable = it) }
            }.onFailure {
                c.log.e(LogTags.UI, "准备分享文件失败", throwable = it)
                _messages.tryEmit("分享失败：${it.message}")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiskApp
                MainViewModel(app.container)
            }
        }
    }
}
