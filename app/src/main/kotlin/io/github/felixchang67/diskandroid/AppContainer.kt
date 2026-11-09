package io.github.felixchang67.diskandroid

import android.app.Application
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.FileLogSink
import io.github.felixchang67.diskandroid.core.log.LogTags
import io.github.felixchang67.diskandroid.core.log.RingBufferSink
import io.github.felixchang67.diskandroid.core.scsi.LoggingScsiTransport
import io.github.felixchang67.diskandroid.core.usb.UsbDeviceMonitor
import io.github.felixchang67.diskandroid.core.usb.UsbDriveConnector
import io.github.felixchang67.diskandroid.diag.ExitReasons
import io.github.felixchang67.diskandroid.diag.PreviousSession
import io.github.felixchang67.diskandroid.diag.SystemInfo
import io.github.felixchang67.diskandroid.drive.DriveManager
import io.github.felixchang67.diskandroid.log.LogcatSink
import io.github.felixchang67.diskandroid.player.CdPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 手工依赖注入：进程级单例都放在这里。 */
class AppContainer(val app: Application) {
    val sessionId: String = "session-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())

    val logDir: File = (app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")).apply { mkdirs() }

    val log = DiskLog()
    val ring = RingBufferSink(capacity = 8000) { it.tag != LoggingScsiTransport.STREAM_TAG }
    val fileSink = FileLogSink(logDir, sessionId)

    /** 主线程作用域：光驱会话管理的状态都在这里修改。 */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val usbMonitor: UsbDeviceMonitor
    val usbConnector: UsbDriveConnector
    val driveManager: DriveManager
    val player: CdPlaybackController

    /** 上一个会话的警告 / 错误（启动时读取一次）。 */
    var previousSessionIssues: PreviousSession.Issues? = null
        private set

    init {
        log.addSink(ring)
        log.addSink(LogcatSink())
        log.addSink(fileSink)
        installCrashHandler()
        usbMonitor = UsbDeviceMonitor(app, log)
        usbConnector = UsbDriveConnector(usbMonitor.usbManager, log)
        driveManager = DriveManager(usbMonitor, usbConnector, log, mainScope)
        player = CdPlaybackController(app, log, mainScope)
    }

    fun start() {
        log.i(LogTags.APP, "DiskAndroid 启动", "会话" to sessionId, "日志目录" to logDir.absolutePath)
        SystemInfo.lines(app).forEach { log.i(LogTags.APP, it) }
        ExitReasons.logRecent(app, log)
        previousSessionIssues = runCatching { PreviousSession.extract(logDir, sessionId) }.getOrNull()
        usbMonitor.start()
        driveManager.start()
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
