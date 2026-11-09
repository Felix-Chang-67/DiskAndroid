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
import io.github.felixchang67.diskandroid.core.usb.MassStorageSelection
import io.github.felixchang67.diskandroid.core.usb.ProbeReport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceMonitor
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceSnapshot
import io.github.felixchang67.diskandroid.core.usb.UsbEvent
import io.github.felixchang67.diskandroid.diag.Diagnostics
import io.github.felixchang67.diskandroid.log.LogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val devices: StateFlow<List<UsbDeviceSnapshot>> = c.usbMonitor.devices
    val logVersion: StateFlow<Long> = c.ring.version

    private val _reports = MutableStateFlow<Map<String, ProbeReport>>(emptyMap())
    val reports: StateFlow<Map<String, ProbeReport>> = _reports.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 本次插入已经自动测过的设备（只在主线程访问）。 */
    private val autoProbed = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            c.usbMonitor.events.collect { event ->
                when (event) {
                    is UsbEvent.Attached -> {
                        val gap = event.reconnectGapMs
                        if (gap != null && gap < UsbDeviceMonitor.QUICK_RECONNECT_MS) {
                            _messages.tryEmit("设备断开后 $gap ms 又重新连接：如果不是你手动插拔，请检查带供电的扩展坞 / Boost 线")
                        }
                        maybeAutoProbe(event.snapshot)
                    }

                    is UsbEvent.Detached -> {
                        autoProbed.remove(event.deviceName)
                        _messages.tryEmit("USB 设备已断开（${event.label}）")
                    }

                    is UsbEvent.PermissionResult -> if (event.granted) {
                        _messages.tryEmit("已获得 USB 权限，开始连接测试")
                        autoProbed.add(event.deviceName)
                        probe(event.deviceName)
                    } else {
                        _messages.tryEmit("USB 权限被拒绝。可以点“申请权限”重试，或者重新插拔光驱后在系统弹窗里选“确定”")
                    }
                }
            }
        }
        // App 启动前就已经插着且有权限的设备，也自动测一次
        viewModelScope.launch {
            c.usbMonitor.devices.collect { list -> list.forEach { maybeAutoProbe(it) } }
        }
    }

    private fun maybeAutoProbe(snapshot: UsbDeviceSnapshot) {
        if (!snapshot.hasPermission) return
        if (snapshot.massStorage !is MassStorageSelection.Selected) return
        if (!autoProbed.add(snapshot.deviceName)) return
        probe(snapshot.deviceName)
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

    fun probe(deviceName: String) {
        if (deviceName in _busy.value) return
        val device = c.usbMonitor.findDevice(deviceName)
        if (device == null) {
            _messages.tryEmit("设备已经断开")
            return
        }
        _busy.update { it + deviceName }
        viewModelScope.launch {
            try {
                val report = c.usbProbe.run(device)
                _reports.update { it + (deviceName to report) }
                _messages.tryEmit(
                    if (report.success) "连接测试成功：${report.effectiveSpeed.label}" else "连接测试失败，详情见设备卡片和日志",
                )
            } catch (e: Exception) {
                c.log.e(LogTags.PROBE, "连接测试协程异常", throwable = e)
                _messages.tryEmit("连接测试出现异常：${e.message}")
            } finally {
                _busy.update { it - deviceName }
            }
        }
    }

    fun logSnapshot(): List<LogRecord> = c.ring.snapshot()

    /** 只清空界面上的日志，文件里的日志不受影响。 */
    fun clearLogView() = c.ring.clear()

    private fun diagnostics(context: Context): String =
        Diagnostics.build(context, c, devices.value, reports.value)

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
                runCatching { LogExport.writeZip(appContext, target, c.fileSink.sessionFiles(), diag) }
            }
            result.onSuccess { bytes ->
                c.log.i(LogTags.UI, "日志已导出", "字节" to bytes, "位置" to target)
                _messages.tryEmit("日志已导出（约 ${bytes / 1024} KB）")
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
