package io.github.felixchang67.diskandroid.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.felixchang67.diskandroid.DiskApp
import io.github.felixchang67.diskandroid.MainActivity
import io.github.felixchang67.diskandroid.R
import io.github.felixchang67.diskandroid.core.cdda.PlayerState
import io.github.felixchang67.diskandroid.core.log.DiskLog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 播放期间的前台服务（类型 mediaPlayback）：保持进程（和 USB 连接）在息屏后继续工作，
 * 提供通知栏 / 锁屏 / 蓝牙耳机按键控制（MediaSession）。播放结束或停止后自动移除通知并退出。
 */
class CdPlaybackService : Service() {
    private lateinit var controller: CdPlaybackController
    private lateinit var log: DiskLog
    private lateinit var session: MediaSession
    private val scope = MainScope()
    private var foreground = false
    private var lastKey: String? = null
    private var lastPositionPushMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as DiskApp).container
        controller = container.player
        log = container.log
        createChannel()
        // 注意：MediaSession 自己也有 controller 属性，回调里用局部变量避免混淆
        val player = controller
        session = MediaSession(this, "DiskAndroid-CD").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onSkipToNext() {
                    player.next()
                }

                override fun onSkipToPrevious() {
                    player.previous()
                }

                override fun onSeekTo(pos: Long) {
                    player.seekTo(pos)
                }

                override fun onStop() {
                    player.stop()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        scope.launch {
            combine(controller.current, controller.state) { c, s -> c to s }.collect { (c, s) -> render(c, s) }
        }
        log.i(TAG, "播放服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.togglePause()
            ACTION_NEXT -> controller.next()
            ACTION_PREVIOUS -> controller.previous()
            ACTION_STOP -> controller.stop()
        }
        // startForegroundService() 之后必须尽快 startForeground()，即使播放已经结束（随后马上移除）
        goForeground(buildNotification(controller.current.value, controller.state.value))
        if (controller.state.value?.active != true) stopAndRemove()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        log.i(TAG, "播放服务已退出")
        super.onDestroy()
    }

    private fun render(c: CdPlaybackController.Current?, s: PlayerState?) {
        if (c == null || s == null || !s.active) {
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
            if (foreground) stopAndRemove()
            return
        }
        val track = s.track
        val key = "${s.status}|${track?.number}"
        val now = System.currentTimeMillis()
        val changed = key != lastKey
        if (changed) {
            lastKey = key
            track?.let { t ->
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, t.performer ?: c.album.performer ?: "")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, c.album.title ?: "音乐 CD")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                        .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, t.number.toLong())
                        .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, c.album.tracks.size.toLong())
                        .build(),
                )
            }
            if (foreground) notificationManager().notify(NOTIFICATION_ID, buildNotification(c, s))
        }
        // 播放进度：系统按 1 倍速自己推算，状态变化或每 5 秒校正一次即可
        if (changed || now - lastPositionPushMs > 5_000) {
            lastPositionPushMs = now
            val st = when (s.status) {
                PlayerState.Status.PLAYING -> PlaybackState.STATE_PLAYING
                PlayerState.Status.PAUSED -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_BUFFERING
            }
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                    )
                    .setState(st, s.positionMs, if (st == PlaybackState.STATE_PLAYING) 1f else 0f)
                    .build(),
            )
        }
    }

    private fun goForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (e: Exception) {
            log.e(TAG, "无法进入前台服务（息屏后可能被系统暂停）", throwable = e)
        }
    }

    private fun stopAndRemove() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        lastKey = null
        stopSelf()
    }

    private fun buildNotification(c: CdPlaybackController.Current?, s: PlayerState?): Notification {
        val track = s?.track
        val playing = s?.status != PlayerState.Status.PAUSED
        val title = track?.let { "${it.number}. ${it.title}" } ?: "音乐 CD"
        val text = listOfNotNull(track?.performer ?: c?.album?.performer, c?.album?.title).distinct().joinToString(" · ").ifEmpty { "DiskAndroid" }
        fun action(icon: Int, label: String, action: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, serviceIntent(action)).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_disc)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREVIOUS))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "暂停", ACTION_TOGGLE) else action(android.R.drawable.ic_media_play, "播放", ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "停止", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, CdPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "音乐 CD 播放", NotificationManager.IMPORTANCE_LOW).apply {
            description = "播放音乐 CD 时显示的控制通知"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PLAYER"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "io.github.felixchang67.diskandroid.player.TOGGLE"
        private const val ACTION_NEXT = "io.github.felixchang67.diskandroid.player.NEXT"
        private const val ACTION_PREVIOUS = "io.github.felixchang67.diskandroid.player.PREVIOUS"
        private const val ACTION_STOP = "io.github.felixchang67.diskandroid.player.STOP"

        fun start(context: Context, log: DiskLog) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CdPlaybackService::class.java))
            } catch (e: Exception) {
                // 例如 App 在后台时由蓝牙按键恢复播放：Android 12+ 不允许从后台启动前台服务
                log.w(TAG, "无法启动播放服务：${e.javaClass.simpleName}（${e.message}）")
            }
        }
    }
}
