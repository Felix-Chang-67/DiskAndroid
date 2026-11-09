package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.felixchang67.diskandroid.core.cdda.PlayerState
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.testing.CdTextBuilder
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import io.github.felixchang67.diskandroid.player.CdPlaybackService
import io.github.felixchang67.diskandroid.ui.CdPlayerSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 在 JVM 上（Robolectric）走一遍音乐 CD 的完整链路：模拟光驱（带日文 CD-TEXT 的盘）→ 光驱会话读取盘片信息 →
 * 播放界面显示日文曲名 → 点"从头播放" → 引擎经会话读 CD-DA、写进 AudioTrack → 播完。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CdPlayerUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsJapaneseTitlesAndPlaysThroughAudioTrack() {
        val app = ApplicationProvider.getApplicationContext<DiskApp>()
        val container = app.container
        val mmc = FakeMmc().apply {
            insertDisc(FakeDisc.audioCd(trackCount = 3, secondsPerTrack = 20, cdText = CdTextBuilder.japaneseAlbum()), spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
        }
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val session = DriveSession(FakeMmcTransport(mmc), container.log, "测试光驱", scope, executor, pollIntervalMs = 50, becomingReadyIntervalMs = 20)
        try {
            session.start()
            val t0 = System.currentTimeMillis()
            while (session.disc.value == null && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
            val disc = requireNotNull(session.disc.value) { "盘片信息没有读出来" }

            compose.setContent {
                CdPlayerSection(session, disc, container.player) { track -> container.player.play(session, disc, track) }
            }
            compose.onNodeWithText("テスト・アルバム").assertExists()
            compose.onNodeWithText("夏の歌～ライブ").assertExists()
            compose.onNodeWithText("秋の歌①").assertExists()

            compose.onNodeWithText("▶ 从头播放").performClick()
            // Robolectric 的主线程消息要手动推进；它的 AudioTrack 也不按真实时间消耗数据，整张专辑几百毫秒就"播完"
            fun idle() = shadowOf(Looper.getMainLooper()).idle()
            compose.waitUntil(20_000) {
                idle()
                container.player.state.value?.status == PlayerState.Status.ENDED
            }
            compose.waitForIdle()
            assertEquals(4500L, mmc.cdSectorsRead, "3 轨 × 20 秒，每个扇区正好读一次")
            assertEquals(listOf(706, 0xFFFF), mmc.speedRequests, "播放时降到约 4 倍速，播完恢复")
            assertTrue(container.ring.snapshot().any { it.message == "专辑播放完毕" })
            assertTrue(container.ring.snapshot().any { it.message.startsWith("CD-DA 字节序检测结果：小端") })
            // 播放期间请求了前台服务（息屏后继续播放）
            val started = shadowOf(app).nextStartedService
            assertEquals(CdPlaybackService::class.java.name, started?.component?.className)
            compose.onNodeWithText("▶ 播放").assertExists()
            compose.onNodeWithText("播放完毕", substring = true).assertExists()
            // 阶段 1：仍然只有读类命令
            val opcodes = mmc.received.map { it[0].toInt() and 0xFF }.toSet()
            assertTrue(opcodes.all { it in setOf(0x00, 0x03, 0x12, 0x1B, 0x25, 0x43, 0x46, 0x4A, 0x51, 0x52, 0x5A, 0xA4, 0xAC, 0xAD, 0xBB, 0xBD, 0xBE) }, opcodes.toString())
        } finally {
            container.player.shutdown()
            session.close()
            scope.cancel()
            executor.close()
        }
    }
}
