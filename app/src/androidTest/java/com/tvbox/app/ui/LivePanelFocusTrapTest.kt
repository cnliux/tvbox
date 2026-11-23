package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tvbox.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 面板焦点陷阱的 instrumentation 验证。
 *
 * 纯 JVM 单测只能验证状态机，无法验证真实的 Compose 焦点分发；
 * 这里在真机/模拟器上锁定三条语义：弹出即聚焦、面板内移动不被抢回、关闭后焦点回播放器。
 */
class LivePanelFocusTrapTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val panelVisible = mutableStateOf(false)

    @Composable
    private fun FocusTrapFixture() {
        val rootFocus = rememberLiveRootFocusRequester(restoreWhen = !panelVisible.value)
        val firstFocus = rememberLivePanelFocusRequester(active = panelVisible.value)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .livePanelAnchor(rootFocus)
                .focusable(),
        ) {
            // 背景播放器：面板打开时禁止获焦，避免焦点穿透。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag(PLAYER_TAG)
                    .focusable(enabled = !panelVisible.value),
            )
            if (panelVisible.value) {
                Column(
                    modifier = Modifier
                        .livePanelFocusTrap(panelVisible.value, firstFocus),
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .testTag(FIRST_BUTTON_TAG)
                            .focusRequester(firstFocus),
                    ) { Text("第一个按钮") }
                    Button(onClick = {}, modifier = Modifier.testTag(SECOND_BUTTON_TAG)) { Text("第二个按钮") }
                }
            }
        }
    }

    @Test
    fun openingPanelFocusesFirstButton() {
        composeRule.setContent { FocusTrapFixture() }
        composeRule.runOnUiThread { panelVisible.value = true }
        composeRule.waitForIdle()
        assertFocused(FIRST_BUTTON_TAG)
        composeRule.onNodeWithText("第一个按钮").assertExists()
    }

    @Test
    fun closingPanelRestoresFocusToPlayer() {
        composeRule.setContent { FocusTrapFixture() }
        composeRule.runOnUiThread { panelVisible.value = true }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { panelVisible.value = false }
        composeRule.waitForIdle()
        assertFocused(PLAYER_TAG)
    }

    @Test
    fun backgroundPlayerCannotTakeFocusWhilePanelOpen() {
        composeRule.setContent { FocusTrapFixture() }
        composeRule.runOnUiThread { panelVisible.value = true }
        composeRule.waitForIdle()
        assertFocused(FIRST_BUTTON_TAG)
        // 面板内的焦点状态由 onFocusChanged 的 hasFocus 兜底；背景此时 focusable=false。
        composeRule.onNodeWithTag(SECOND_BUTTON_TAG).assertExists()
    }

    /** 用语义属性判断焦点，避免依赖 assertHasFocus 这类可能随版本迁移的扩展函数。 */
    private fun assertFocused(tag: String) {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(
            "$tag 应持有焦点，实际 config=${node.config}",
            node.config[SemanticsProperties.Focused],
        )
    }

    private companion object {
        const val PLAYER_TAG = "player-root"
        const val FIRST_BUTTON_TAG = "panel-first-button"
        const val SECOND_BUTTON_TAG = "panel-second-button"
    }
}
