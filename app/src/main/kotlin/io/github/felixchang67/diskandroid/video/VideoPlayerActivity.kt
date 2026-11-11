package io.github.felixchang67.diskandroid.video

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.felixchang67.diskandroid.DiskApp
import io.github.felixchang67.diskandroid.core.player.DiscPlayer
import kotlinx.coroutines.launch

/**
 * 全屏视频播放界面（横屏）。播放器本身在 [VideoPlaybackController] 里（进程级），这里只负责画面、操作和按键。
 * 离开界面（回桌面、锁屏）时暂停；播放停止（关闭、盘片取出、光驱断开）时界面自动关闭。
 */
class VideoPlayerActivity : ComponentActivity() {
    private val video: VideoPlaybackController get() = (application as DiskApp).container.video

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            VideoPlayerScreen(video, onClose = { close("用户关闭播放界面") })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                video.screen.collect { if (it == null && !isFinishing) finish() }
            }
        }
    }

    private fun close(reason: String) {
        video.stop(reason)
        finish()
    }

    override fun onStop() {
        // 离开界面就暂停（回来后点播放继续）
        if (!isFinishing) video.pause()
        super.onStop()
    }

    override fun onDestroy() {
        // 从最近任务里划掉之类：界面没了，播放也停掉
        if (isFinishing && video.screen.value != null) video.stop("播放界面被关闭")
        super.onDestroy()
    }

    /** 遥控器 / 键盘：菜单里方向键移动高亮、确定键选择；播放时左右键快退快进。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = video.screen.value?.state ?: return super.onKeyDown(keyCode, event)
        val menu = s.inMenu
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (menu) video.command(DiscPlayer.CMD_UP) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (menu) video.command(DiscPlayer.CMD_DOWN) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (menu) video.command(DiscPlayer.CMD_LEFT) else seekBy(s, -10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (menu) video.command(DiscPlayer.CMD_RIGHT) else seekBy(s, 10_000)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (menu) video.command(DiscPlayer.CMD_ACTIVATE) else video.togglePause()
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> video.togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> video.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> video.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> video.command(DiscPlayer.CMD_NEXT_CHAPTER)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> video.command(DiscPlayer.CMD_PREV_CHAPTER)
            KeyEvent.KEYCODE_MEDIA_STOP -> close("停止键")
            KeyEvent.KEYCODE_MENU -> when {
                !s.isBluray -> video.command(DiscPlayer.CMD_MENU, DiscPlayer.MENU_ROOT)
                // 蓝光：播放中有弹出菜单就开关它，否则回顶层菜单
                s.popup -> video.command(DiscPlayer.CMD_MENU, DiscPlayer.BD_MENU_POPUP)
                s.bdMode == DiscPlayer.BD_MODE_MENU -> video.command(DiscPlayer.CMD_MENU, DiscPlayer.BD_MENU_TOP)
                else -> return super.onKeyDown(keyCode, event)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun seekBy(s: DiscPlayer.State, deltaMs: Long) {
        if (s.domain != DiscPlayer.Domain.TITLE || s.durationMs <= 0) return
        video.command(DiscPlayer.CMD_SEEK_MS, (s.positionMs + deltaMs).coerceIn(0, s.durationMs - 1000).toInt())
    }
}
