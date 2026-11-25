package com.tvbox.app.ui

import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7-36 G3：四套遥控器语料 → 动作序列断言。
 * 失败消息必须带设备名与语料说明（验收要求），不能只输出 `expected true`。
 */
class RemoteKeyMatrixTest {

    private val router = LiveRemoteKeyRouter()

    @Test
    fun confirmKeyAcrossAllDeviceProfiles() {
        RemoteKeyEventFixtures.profiles.forEach { profile ->
            val start = LiveRemoteState()
            val label = "${profile.device}（${profile.note}）"

            val down = router.handle(profile.confirmKey, KeyEventType.KeyDown, start)
            assertEquals("$label：确认键按下不应执行动作", start, down?.state)

            val up = router.handle(profile.confirmKey, KeyEventType.KeyUp, start)
            assertEquals("$label：确认键抬起应打开面板", UIState.CHANNEL_LIST, up?.state?.uiState)
            assertEquals("$label：动作序列错误", listOf(LiveRemoteEffect.OpenChannelList), up?.effects)
        }
    }

    @Test
    fun backUnwindsPanelAcrossAllDeviceProfiles() {
        RemoteKeyEventFixtures.profiles.forEach { profile ->
            val panel = LiveRemoteState(uiState = UIState.CHANNEL_LIST)
            val label = "${profile.device}（${profile.note}）"

            val down = router.handle(profile.backKey, KeyEventType.KeyDown, panel)
            assertEquals("$label：BACK 按下不应立即弹栈", panel, down?.state)

            val up = router.handle(profile.backKey, KeyEventType.KeyUp, panel)
            assertEquals("$label：BACK 抬起应回到全屏", UIState.FULLSCREEN, up?.state?.uiState)
            assertEquals("$label：动作序列错误", listOf(LiveRemoteEffect.ClosePanel), up?.effects)
        }
    }

    @Test
    fun keyRepeatIsSwallowedAcrossAllDeviceProfiles() {
        RemoteKeyEventFixtures.profiles.forEach { profile ->
            val label = "${profile.device}（${profile.note}）"
            val result = router.handle(
                profile.confirmKey, KeyEventType.KeyUp, LiveRemoteState(), repeatCount = 3
            )
            assertTrue("$label：长按连发应被吞掉而不是重复执行", result?.effects.isNullOrEmpty())
        }
    }

    @Test
    fun digitProfilesEnterNumberState() {
        RemoteKeyEventFixtures.profiles
            .filterNot { it.device.contains("开关控") }
            .forEach { profile ->
                val label = "${profile.device}（${profile.note}）"
                val result = router.handle(profile.digitOneKey, KeyEventType.KeyDown, LiveRemoteState())
                assertEquals("$label：数字键应进入选台态", "1", result?.state?.numberInput)
            }
    }
}
