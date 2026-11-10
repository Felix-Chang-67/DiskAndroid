package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
            compose.setContent {
                Column {
                    VideoSection(session, info, container.files)
                    DiscFilesSection(session, info, container.files) {}
                }
            }
            waitText("主片（自动挑选）：标题 1")
            compose.onNodeWithText("DVD-Video · 3 个标题", substring = true).assertExists()
            compose.onNodeWithText("没有加密：播放在下一个测试节点", substring = true).assertExists()
            compose.onNodeWithText("音轨 1：日语 AC-3 单声道 48 kHz · 流 80h", substring = true).assertExists()
            compose.onNodeWithText("音轨 2：英语 MPEG-1 立体声 48 kHz · 流 C0h", substring = true).assertExists()
            // 测试盘的标题都不到 10 秒（真盘上是片头警告、菜单背景）：默认折叠
            compose.onNodeWithText("显示 2 个不到 10 秒的标题", substring = true).performClick()
            waitText("其他标题")
            // 按时长从长到短：标题 3（8 秒，和主片一样长）在标题 2（3 秒）前面
            compose.onNodeWithText("标题 3 · 0:08 · 2 章", substring = true).assertExists()
            compose.onNodeWithText("标题 2 · 0:03 · 1 章", substring = true).assertExists()

            // 手动选标题 3
            compose.onAllNodesWithText("选这个")[0].performClick()
            waitText("选中的标题：标题 3")
            compose.onNodeWithText("改回自动挑的主片（标题 1）").assertExists()
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
}
