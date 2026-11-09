package io.github.felixchang67.diskandroid

import android.app.Application
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.FileLogSink
import io.github.felixchang67.diskandroid.core.log.LogTags
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.usb.UsbConnectionProbe
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceMonitor
import io.github.felixchang67.diskandroid.diag.ExitReasons
import io.github.felixchang67.diskandroid.diag.SystemInfo
import io.github.felixchang67.diskandroid.log.LogcatSink
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 手工依赖注入：进程级单例都放在这里。 */
class AppContainer(val app: Application) {
    val sessionId: String = "session-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())

    val logDir: File = (app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")).apply { mkdirs() }

    val log = DiskLog()
    val ring = RingBufferSink(capacity = 8000)
    val fileSink = FileLogSink(logDir, sessionId)

    val usbMonitor: UsbDeviceMonitor
    val usbProbe: UsbConnectionProbe

    init {
        log.addSink(ring)
        log.addSink(LogcatSink())
        log.addSink(fileSink)
        installCrashHandler()
        usbMonitor = UsbDeviceMonitor(app, log)
        usbProbe = UsbConnectionProbe(usbMonitor.usbManager, log)
    }

    fun start() {
        log.i(LogTags.APP, "DiskAndroid 启动", "会话" to sessionId, "日志目录" to logDir.absolutePath)
        SystemInfo.lines(app).forEach { log.i(LogTags.APP, it) }
        ExitReasons.logRecent(app, log)
        usbMonitor.start()
    }

    /** 未捕获异常先写进日志并同步落盘，再交给系统默认处理（弹"已停止运行"）。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log.e(LogTags.CRASH, "未捕获异常，App 即将崩溃（线程 ${thread.name}）", throwable = throwable)
                log.flush()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
