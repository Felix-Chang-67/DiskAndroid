package io.github.felixchang67.diskandroid.rip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.felixchang67.diskandroid.DiskApp
import io.github.felixchang67.diskandroid.MainActivity
import io.github.felixchang67.diskandroid.R
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.drive.DiscInfo
import io.github.felixchang67.diskandroid.core.rip.RipEngine
import io.github.felixchang67.diskandroid.files.DiscBrowser
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 读盘 / 复制文件期间的前台服务（类型 connectedDevice：与 USB 光驱持续通信）。
 * 读一张 BD 可能要一个小时，息屏后进程和 USB 连接都要保持；通知栏显示进度，可以取消。做完自动退出。
 */
class RipService : Service() {
    private lateinit var controller: RipController
    private lateinit var browser: DiscBrowser
    private lateinit var log: DiskLog
    private val scope = MainScope()
    private var foreground = false
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as DiskApp).container
        controller = container.rip
        browser = container.files
        log = container.log
        val channel = NotificationChannel(CHANNEL_ID, "读盘", NotificationManager.IMPORTANCE_LOW).apply {
            description = "读成 ISO、从盘上复制文件时显示进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        scope.launch {
            combine(controller.state, browser.copy) { r, c -> r to c }.collect { (r, c) ->
                val ripRunning = r?.running == true
                val copyRunning = c?.running == true
                if (!ripRunning && !copyRunning) {
                    if (foreground) stopAndRemove()
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (foreground && now - lastNotifyMs >= 1_000) {
                    lastNotifyMs = now
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, current())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            browser.cancelCopy()
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, current(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } catch (e: Exception) {
            log.e(RipController.TAG, "无法进入前台服务（读盘时请保持屏幕常亮、不要切走 App）", throwable = e)
        }
        if (controller.state.value?.running != true && !browser.copying) stopAndRemove()
        return START_NOT_STICKY
    }

    private fun current(): Notification {
        val c = browser.copy.value
        return if (controller.state.value?.running != true && c?.running == true) buildCopy(c) else build(controller.state.value)
    }

    private fun buildCopy(c: DiscBrowser.CopyState): Notification {
        val percent = if (c.totalBytes > 0) (c.doneBytes * 100 / c.totalBytes).toInt() else 0
        val title = if (c.cancelling) "正在取消…" else c.title
        val text = "${c.doneFiles}/${c.totalFiles} 个文件 · ${DiscInfo.formatBytes(c.doneBytes)} / ${DiscInfo.formatBytes(c.totalBytes)} · " +
            "%.1f MB/s".format(c.bytesPerSecond / 1e6)
        return notification(title, text, percent, c.totalBytes == 0L)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopAndRemove() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun build(s: RipController.State?): Notification {
        val p = s?.progress
        val percent = p?.let { (it.doneSectors * 100 / it.totalSectors.coerceAtLeast(1)).toInt() } ?: 0
        val speed = p?.let { "%.1f MB/s（约 %.1fx）".format(it.bytesPerSecond / 1e6, RipEngine.speedFactor(it.bytesPerSecond, s.family)) } ?: ""
        val title = if (s?.cancelling == true) "正在取消…" else "正在读成 ISO：${s?.imageName ?: "准备中"}"
        val text = p?.let { "第 ${it.pass} 遍 $percent% · $speed" + (if (it.badSectors > 0) " · 坏扇区 ${it.badSectors}" else "") } ?: (s?.phase ?: "准备中…")
        return notification(title, text, percent, p == null)
    }

    private fun notification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RipService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_disc)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "取消", cancel).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rip"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL = "io.github.felixchang67.diskandroid.rip.CANCEL"

        fun start(context: Context, log: DiskLog) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RipService::class.java))
            } catch (e: Exception) {
                log.w(RipController.TAG, "无法启动读盘服务：${e.javaClass.simpleName}（${e.message}）")
            }
        }
    }
}
