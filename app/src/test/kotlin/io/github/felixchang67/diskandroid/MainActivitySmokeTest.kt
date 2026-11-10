package io.github.felixchang67.diskandroid

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 冒烟测试：在 JVM 上（Robolectric）真正启动 Application + MainActivity 并渲染 Compose 界面，
 * 确认没有启动崩溃、主要按钮能用。云端没有模拟器/真机，这是发 APK 前的最后一道检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun narrowLayoutShowsGuideCopiesDiagnosticsAndShowsLogs() {
        compose.onNodeWithText("：识别视频盘的主片", substring = true).assertIsDisplayed()
        // 小屏上后面的卡片在可视区外，LazyColumn 不会组合它们，先滚过去
        val deviceList = compose.onNode(hasScrollToKeyAction())
        deviceList.performScrollToKey("empty")
        compose.onNodeWithText("未检测到 USB 设备").assertIsDisplayed()

        deviceList.performScrollToKey("actions")
        compose.onNodeWithText("复制诊断信息").performClick()
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(text.startsWith("===== DiskAndroid 诊断信息 ====="), text.take(300))
        assertTrue(text.contains("没有检测到 USB 设备"), text.take(2000))
        assertTrue(text.contains("I/APP: DiskAndroid 启动"), text.takeLast(2000))

        compose.onNodeWithText("日志").performClick()
        compose.onNodeWithText("自动滚动").assertExists()
        assertTrue(compose.onAllNodesWithText("/USB:", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land")
class MainActivityWideLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabletLandscapeShowsDeviceAndLogPanesSideBySide() {
        compose.onNodeWithText("：识别视频盘的主片", substring = true).assertIsDisplayed()
        compose.onNodeWithText("自动滚动").assertIsDisplayed()
    }
}
