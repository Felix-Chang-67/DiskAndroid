package io.github.felixchang67.diskandroid

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.felixchang67.diskandroid.core.drive.DriveSession
import io.github.felixchang67.diskandroid.core.drive.MediaStatus
import io.github.felixchang67.diskandroid.core.testing.FakeDisc
import io.github.felixchang67.diskandroid.core.testing.FakeMmc
import io.github.felixchang67.diskandroid.core.testing.FakeMmcTransport
import io.github.felixchang67.diskandroid.core.testing.FakeTrack
import io.github.felixchang67.diskandroid.ui.CdPlayerSection
import io.github.felixchang67.diskandroid.ui.RipSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoplayAndRipUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T> withSession(mmc: FakeMmc, block: (DriveSession) -> T): T {
        val app = ApplicationProvider.getApplicationContext<DiskApp>()
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val session = DriveSession(FakeMmcTransport(mmc), app.container.log, "测试光驱", scope, executor, pollIntervalMs = 50, becomingReadyIntervalMs = 20)
        try {
            session.start()
            return block(session)
        } finally {
            session.close()
            scope.cancel()
            executor.close()
        }
    }

    /** 会话开始后才放进去的音乐 CD：先显示"2 秒后播放"，到时自动从第 1 首开始。 */
    @Test
    fun newlyInsertedAudioCdAutoplaysAfterCountdown() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val mmc = FakeMmc()
        withSession(mmc) { session ->
            val player = container.player
            player.setAutoplay(true)
            player.watchForAutoplay(MutableStateFlow(listOf(session)), MutableStateFlow(true)) { false }
            compose.waitUntil(10_000) {
                idle()
                session.media.value is MediaStatus.NoMedium
            }
            mmc.insertDisc(FakeDisc.audioCd(trackCount = 2, secondsPerTrack = 10), spinUpPolls = 2)
            compose.waitUntil(10_000) {
                idle()
                player.autoplay.value?.session === session
            }
            val disc = session.disc.value!!
            compose.setContent { CdPlayerSection(session, disc, player, container.metadata) { player.play(session, disc, it) } }
            compose.onNodeWithText("立即播放").assertExists()
            compose.onNodeWithText("秒后播放", substring = true).assertExists()
            // 不点任何按钮：2 秒后自动开始
            compose.waitUntil(10_000) {
                idle()
                player.current.value?.session === session
            }
            assertTrue(player.autoplay.value == null)
            assertTrue(container.ring.snapshot().any { it.message.contains("自动播放第 1 轨") })
            player.stop()
        }
    }

    /** App 启动时盘已经在光驱里：不自动播放。数据盘显示"读成 ISO"。 */
    @Test
    fun discAlreadyInsideDoesNotAutoplayAndDataDiscShowsRipCard() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val mmc = FakeMmc().apply {
            insertDisc(FakeDisc.cdRom(sectors = 20_000, volumeId = "BONUS_DISC"), spinUpPolls = 0)
            pendingUnitAttention.clear()
            pendingMediaEvents.clear()
        }
        withSession(mmc) { session ->
            container.player.watchForAutoplay(MutableStateFlow(listOf(session)), MutableStateFlow(true)) { false }
            compose.waitUntil(10_000) {
                idle()
                session.disc.value != null
            }
            Thread.sleep(300)
            idle()
            assertTrue(container.player.autoplay.value == null)
            val disc = session.disc.value!!
            compose.setContent { RipSection(session, disc, container.rip, container.files) {} }
            compose.onNodeWithText("读成 ISO").assertExists()
            compose.onNodeWithText("选择文件夹").assertExists()
            compose.onNodeWithText("41.0 MB（20000 扇区）", substring = true).assertExists()
            compose.onNodeWithText("64 扇区").assertExists()
        }
    }

    /**
     * 会话开始后放进去的 DVD 视频盘：读出文件系统确认有 VIDEO_TS 后显示"2 秒后播放"，到时从光盘菜单开始播放。
     * 电脑上没有 Android 的原生库，打开播放器会报错（界面显示错误），这里验证的是整条自动播放的路径。
     */
    @Test
    fun newlyInsertedDvdVideoAutoplaysFromTheDiscMenu() {
        val container = ApplicationProvider.getApplicationContext<DiskApp>().container
        val video = container.video
        video.nativeProblem = { null }
        video.setAutoplay(true)
        val mmc = FakeMmc()
        withSession(mmc) { session ->
            video.watchForAutoplay(MutableStateFlow(listOf(session)), MutableStateFlow(true))
            compose.waitUntil(10_000) {
                idle()
                session.media.value is MediaStatus.NoMedium
            }
            val img = GZIPInputStream(javaClass.getResourceAsStream("/fixtures/dvd_video.iso.gz")!!).use { it.readBytes() }
            val sectors = (img.size / 2048).toLong()
            mmc.insertDisc(FakeDisc(0x10, listOf(FakeTrack(1, 1, 0, sectors, audio = false)), sectors, image = img), spinUpPolls = 2)
            compose.waitUntil(10_000) {
                idle()
                video.autoplay.value?.session === session
            }
            assertTrue(container.ring.snapshot().any { it.message.contains("放入了 DVD 视频盘：2 秒后自动播放") })
            // 不点任何按钮：2 秒后开始播放（Robolectric 的时钟不会自己走，手动拨过 2 秒）
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))
            compose.waitUntil(10_000) {
                idle()
                video.screen.value?.error != null
            }
            assertTrue(video.autoplay.value == null)
            assertTrue(container.ring.snapshot().any { it.message.contains("播放 DVD：从光盘菜单开始（自动播放）") })
            assertTrue(video.screen.value!!.error!!.contains("原生库"), video.screen.value!!.error)
            video.stop("测试结束")
            idle()
            assertTrue(video.screen.value == null)
        }
    }
}
