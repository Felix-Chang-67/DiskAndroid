package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import io.github.felixchang67.diskandroid.core.testing.FakeTrack
import io.github.felixchang67.diskandroid.ui.DiscFilesSection
import io.github.felixchang67.diskandroid.ui.VideoSection
import io.github.felixchang67.diskandroid.video.VideoPlaybackController
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
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 视频内容：dvdauthor 做的三个标题的 DVD-Video（见 core/video 的 make_fixtures.sh）放进模拟光驱。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h2400dp")
class VideoUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitText(text: String) = compose.waitUntil(10_000) {
        idle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun listsDvdTitlesAndLetsTheUserPickAnother() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val img = GZIPInputStream(javaClass.getResourceAsStream("/fixtures/dvd_video.iso.gz")!!).use { it.readBytes() }
        val sectors = (img.size / 2048).toLong()
        val disc = FakeDisc(0x10, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors, image = img)
        val mmc = FakeMmc().apply {
            insertDisc(disc, spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
        }
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val session = DriveSession(FakeMmcTransport(mmc), container.log, "测试光驱", scope, executor, pollIntervalMs = 50, becomingReadyIntervalMs = 20)
        try {
            session.start()
            compose.waitUntil(10_000) {
                idle()
                session.disc.value != null
            }
            val info = session.disc.value!!
            // 电脑上没有 Android 的原生库：当作可用（播放按钮只回调，不真的打开播放器）
            container.video.nativeProblem = { null }
            val played = mutableListOf<Int>()
            compose.setContent {
                Column {
                    VideoSection(session, info, container.files, container.video) { menu, t -> played += if (menu) 0 else t }
                    DiscFilesSection(session, info, container.files) {}
                }
            }
            waitText("主片（自动挑选）：标题 1")
            compose.onNodeWithText("DVD-Video · 3 个标题", substring = true).assertExists()
            compose.onNodeWithText("没有加密", substring = true).assertExists()
            // 模拟光驱是地区 2（RPC-II），测试盘全区
            compose.onNodeWithText("地区码：盘片全区，光驱地区 2：播放器用地区 2", substring = true).assertExists()
            compose.onNodeWithText("▶ 从光盘菜单开始").performClick()
            compose.onNodeWithText("▶ 直接播放主片（标题 1）").performClick()
            idle()
            assertEquals(listOf(0, 1), played)
            compose.onNodeWithText("放入视频盘（DVD / 蓝光）后自动播放", substring = true).assertExists()
            compose.onNodeWithText("音轨 1：日语 AC-3 单声道 48 kHz · 流 80h", substring = true).assertExists()
            compose.onNodeWithText("音轨 2：英语 MPEG-1 立体声 48 kHz · 流 C0h", substring = true).assertExists()
            // 测试盘的标题都不到 10 秒（真盘上是片头警告、菜单背景）：默认折叠
            compose.onNodeWithText("显示 2 个不到 10 秒的标题", substring = true).performClick()
            waitText("其他标题")
            // 按时长从长到短：标题 3（8 秒，和主片一样长）在标题 2（3 秒）前面
            compose.onNodeWithText("标题 3 · 0:08 · 2 章", substring = true).assertExists()
            compose.onNodeWithText("标题 2 · 0:03 · 1 章", substring = true).assertExists()

            // 列表里每个标题都能直接播放
            compose.onAllNodesWithText("播放")[0].performClick()
            idle()
            assertEquals(listOf(0, 1, 3), played)

            // 手动选标题 3
            compose.onAllNodesWithText("选这个")[0].performClick()
            waitText("选中的标题：标题 3")
            compose.onNodeWithText("改回自动挑的主片（标题 1）").assertExists()
            compose.onNodeWithText("▶ 直接播放选中的标题 3").assertExists()
            val state = container.files.states.value[session]!!.video!!
            assertEquals("标题 3", state.chosen?.id)
            assertTrue(container.ring.snapshot().any { it.message.contains("视频结构：DVD-Video，3 个标题") })
            assertTrue(container.ring.snapshot().any { it.message.contains("手动选择标题：标题 3") })
        } finally {
            session.close()
            scope.cancel()
            executor.close()
        }
    }

    /** CSS 加密的 DVD：没有解密组件时明确提示，不给播放按钮；地区不匹配也要说清楚。 */
    @Test
    fun cssDiscAsksForTheDecryptionComponent() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val img = GZIPInputStream(javaClass.getResourceAsStream("/fixtures/dvd_video.iso.gz")!!).use { it.readBytes() }
        val sectors = (img.size / 2048).toLong()
        // CSS，只允许地区 1（光驱是地区 2）
        val disc = FakeDisc(0x10, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors, image = img, dvdProtection = 0x01, dvdRegionMask = 0xFE)
        val mmc = FakeMmc().apply {
            insertDisc(disc, spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
        }
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val session = DriveSession(FakeMmcTransport(mmc), container.log, "测试光驱", scope, executor, pollIntervalMs = 50, becomingReadyIntervalMs = 20)
        try {
            session.start()
            compose.waitUntil(10_000) {
                idle()
                session.disc.value != null
            }
            val info = session.disc.value!!
            container.video.nativeProblem = { null }
            val played = mutableListOf<Int>()
            compose.setContent {
                Column {
                    VideoSection(session, info, container.files, container.video) { menu, t -> played += if (menu) 0 else t }
                    DiscFilesSection(session, info, container.files) {}
                }
            }
            waitText("主片（自动挑选）：标题 1")
            compose.onNodeWithText("该盘有版权保护，需要启用解密组件").assertExists()
            compose.onAllNodesWithText("▶ 从光盘菜单开始").assertCountEquals(0)
            compose.onAllNodesWithText("播放").assertCountEquals(0)
            compose.onNodeWithText("地区不匹配：盘片地区 1，光驱地区 2", substring = true).assertExists()
            // 直接调用也会拒绝，并写进日志
            assertEquals(VideoPlaybackController.PROTECTED_MESSAGE, container.video.play(session, info, 0, null, "测试"))
            assertTrue(container.ring.snapshot().any { it.message.contains("不播放：该盘有版权保护") })
            assertTrue(played.isEmpty())
        } finally {
            session.close()
            scope.cancel()
            executor.close()
        }
    }
}
