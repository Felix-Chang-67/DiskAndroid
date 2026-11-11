package io.github.felixchang67.diskandroid.video

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏视频播放界面（横屏）。播放器本身在 [VideoPlaybackController] 里（进程级），这里只负责画面、操作和按键。
 * 离开界面（回桌面、锁屏）时暂停；播放停止（关闭、盘片取出、光驱断开）时界面自动关闭。
 */
class VideoPlayerActivity : ComponentActivity() {
    private val video: VideoPlaybackController get() = (application as DiskApp).container.video

    /** 已经按哪个视频帧率挑过屏幕模式（避免重复设置）。 */
    private var matchedFps = 0.0
    private var loggedRefresh = 0f
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val d = window.decorView.display ?: return
            if (d.displayId == displayId) logRefresh("变了")
        }
    }

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
                video.screen.collect {
                    if (it == null && !isFinishing) finish()
                    it?.state?.videoFps?.let { fps -> matchRefreshRate(fps) }
                }
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    /**
     * 屏幕刷新率配合视频帧率：在和现在分辨率相同的模式里，挑刷新率是视频帧率整数倍（差 0.5% 以内）、又不低于 60 Hz 的最低一个
     * （软下拉的电影 23.976 → 120 Hz，每帧正好 5 次刷新，不会 2 / 3 次交替；59.94 → 60 Hz）。找不到合适的就交给系统。
     * 原生层也用 setFrameRate 告诉了系统帧率，HyperOS 不一定照办，这里再用窗口的 preferredDisplayModeId 明确要一次。
     */
    private fun matchRefreshRate(fps: Double) {
        if (fps <= 0 || abs(fps - matchedFps) < 0.01) return
        val display = window.decorView.display ?: return // 界面还没显示出来：下次状态更新再来
        matchedFps = fps
        val current = display.mode
        val modes = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val best = modes.filter { m ->
            val ratio = m.refreshRate / fps
            val k = ratio.roundToInt()
            k >= 1 && abs(ratio - k) / k < 0.005 && m.refreshRate >= 59f
        }.minByOrNull { it.refreshRate }
        val lp = window.attributes
        val id = best?.modeId ?: 0
        if (lp.preferredDisplayModeId != id) {
            lp.preferredDisplayModeId = id
            window.attributes = lp
        }
        video.note(
            "视频帧率 %.3f：请求屏幕 %s（这块屏能用的刷新率：%s Hz）".format(
                fps, best?.let { "%.0f Hz".format(it.refreshRate) } ?: "由系统决定（没有整数倍的刷新率）",
                modes.map { "%.0f".format(it.refreshRate) }.distinct().joinToString(" / "),
            ),
        )
        logRefresh("请求之后")
    }

    private fun logRefresh(why: String) {
        val rate = window.decorView.display?.refreshRate ?: return
        if (abs(rate - loggedRefresh) < 0.5f) return
        loggedRefresh = rate
        video.note("屏幕刷新率（$why）：%.1f Hz".format(rate))
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        // 从最近任务里划掉之类：界面没了，播放也停掉
        if (isFinishing && video.screen.value != null) video.stop("播放界面被关闭")
        super.onDestroy()
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
