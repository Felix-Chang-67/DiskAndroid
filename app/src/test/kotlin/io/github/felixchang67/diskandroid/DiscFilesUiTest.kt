package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
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
import io.github.felixchang67.diskandroid.ui.RipSection
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

/** 盘上的文件：用 genisoimage 做的 Joliet 镜像（日文文件名、VIDEO_TS）当作 DVD+RW 放进模拟光驱。 */
@RunWith(RobolectricTestRunner::class)
// 屏幕要足够高：没有滚动容器，列表在屏幕外时点击不到
@Config(sdk = [35], qualifiers = "w600dp-h2400dp")
class DiscFilesUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun image(): ByteArray = GZIPInputStream(javaClass.getResourceAsStream("/fixtures/joliet_rr.iso.gz")!!).use { it.readBytes() }

    @Test
    fun browsesJapaneseFoldersAndTrimsRewritableRip() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val img = image()
        // DVD+RW：整张盘格式化成 5000 扇区，ISO 只有 464 扇区
        val disc = FakeDisc(0x1A, listOf(FakeTrack(1, 1, 0, 5_000, audio = false)), 5_000, erasable = true, image = img)
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
                androidx.compose.foundation.layout.Column {
                    DiscFilesSection(session, info, container.files) {}
                    RipSection(session, info, container.rip, container.files) {}
                }
            }
            compose.waitUntil(10_000) {
                idle()
                compose.onAllNodesWithText("日本語フォルダ").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("内容：DVD-Video", substring = true).assertExists()
            compose.onNodeWithText("「ライブ_LIVE_AT_TEST」", substring = true).assertExists()
            compose.onNodeWithText("README.TXT").assertExists()
            compose.onNodeWithText("日本語フォルダ").performClick()
            compose.waitUntil(10_000) {
                idle()
                compose.onAllNodesWithText("歌詞（さくら）.txt").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("サブ").assertExists()
            // 回到根目录（路径第一级显示卷标）
            compose.onNodeWithText("ライブ_LIVE_AT_TEST").performClick()
            compose.waitUntil(10_000) {
                idle()
                compose.onAllNodesWithText("README.TXT").fetchSemanticsNodes().isNotEmpty()
            }
            // 读成 ISO：可重写盘只读文件系统部分（464 扇区 = 950 272 字节）
            compose.onNodeWithText("只读文件系统部分").assertExists()
            compose.onNodeWithText("950272 字节（464 扇区）；", substring = true).assertExists()
            compose.onNodeWithText("文件系统只用到 950272 字节", substring = true).assertExists()
            val state = container.files.states.value[session]!!
            assertEquals(464L, state.info!!.fileSystemEnd)
            assertTrue(container.ring.snapshot().any { it.message.contains("盘上的文件系统：ISO 9660 + Joliet") })
        } finally {
            session.close()
            scope.cancel()
            executor.close()
        }
    }
}
