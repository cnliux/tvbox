package com.tvbox.app.ui

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定四页控件状态归并后的显隐与返回层级，防止把点播/直播的旧分叉带回来。
 */
class PlayerChromeStateTest {

    // ── 问题 8：面板触发控件回焦（panelTriggerFocus）状态机 ──

    @Test
    fun openPanelWithTriggerStoresItForCloseTimeRestore() {
        val state = PlayerChromeState()
        val trigger = FocusRequester()
        state.openPanel(PlayerPanel.Episodes, trigger)
        assertEquals(PlayerPanel.Episodes, state.panel)
        assertSame(trigger, state.panelTriggerFocus)
    }

    @Test
    fun openPanelWithoutTriggerLeavesItNull() {
        val state = PlayerChromeState()
        state.openPanel(PlayerPanel.Info)
        assertNull(state.panelTriggerFocus)

        // 按键入口（LiveRemoteKeyRouter 等）不带触发件；关闭时回焦回落播放键/根节点。
        state.openPanel(PlayerPanel.Settings, null)
        assertNull(state.panelTriggerFocus)
    }

    @Test
    fun keyInitiatedOpenClearsStaleTriggerFromEarlierButtonOpen() {
        val state = PlayerChromeState()
        state.openPanel(PlayerPanel.Episodes, FocusRequester())
        state.openPanel(PlayerPanel.Audio) // 按键再开一个面板：旧触发件必须失效
        assertNull(state.panelTriggerFocus)
    }

    @Test
    fun closePanelKeepsTriggerPendingForDeclarativeRestore() {
        // 契约：closePanel() 只复位面板；触发件由组合层 LaunchedEffect 声明式消费，
        // 状态层不能提前清掉，否则关闭当帧读不到回焦目标。
        val state = PlayerChromeState()
        val trigger = FocusRequester()
        state.openPanel(PlayerPanel.Subtitle, trigger)
        state.closePanel()
        assertEquals(PlayerPanel.None, state.panel)
        assertSame(trigger, state.panelTriggerFocus)
    }

    @Test
    fun restoreFocusIsNoOpInTouchModeAndDoesNotConsumeTrigger() {
        val state = PlayerChromeState(touchMode = true)
        val trigger = FocusRequester()
        state.openPanel(PlayerPanel.Info, trigger)
        state.restoreFocus() // 手机端没有 D-pad：不抢焦、不清触发件
        assertSame(trigger, state.panelTriggerFocus)
    }

    @Test
    fun autoHideRestoresRootFocusInTvModeWithoutThrowing() {
        val state = PlayerChromeState(initialVisible = true)
        state.hide()
        assertFalse(state.visible)
        // 自动隐藏统一回根节点（JVM 无组合时 requestFocus 返回 false，runCatching 兜底不抛）。
        state.hide()
        assertFalse(state.visible)
    }

    @Test
    fun autoHideOnlyWhilePlayingWithoutOverlays() {
        assertTrue(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.None,
                locked = false,
                scrubbing = false,
                playing = true,
                holdVisible = false,
            ),
        )
    }

    @Test
    fun pausedControlsNeverAutoHide() {
        assertFalse(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.None,
                locked = false,
                scrubbing = false,
                playing = false,
                holdVisible = false,
            ),
        )
    }

    @Test
    fun panelLockScrubAndPageOverlayAllHoldControls() {
        assertFalse(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.Episodes,
                locked = false,
                scrubbing = false,
                playing = true,
                holdVisible = false,
            ),
        )
        assertFalse(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.None,
                locked = true,
                scrubbing = false,
                playing = true,
                holdVisible = false,
            ),
        )
        assertFalse(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.None,
                locked = false,
                scrubbing = true,
                playing = true,
                holdVisible = false,
            ),
        )
        assertFalse(
            playerChromeShouldAutoHide(
                visible = true,
                panel = PlayerPanel.None,
                locked = false,
                scrubbing = false,
                playing = true,
                holdVisible = true,
            ),
        )
    }

    @Test
    fun hiddenControlsStayHidden() {
        assertFalse(
            playerChromeShouldAutoHide(
                visible = false,
                panel = PlayerPanel.None,
                locked = false,
                scrubbing = false,
                playing = true,
                holdVisible = false,
            ),
        )
    }

    @Test
    fun backDismissesOneLayerAtATime() {
        assertEquals(
            PlayerChromeBackAction.DismissExtra,
            playerChromeBackAction(
                locked = false, panel = PlayerPanel.None, extraLayerOpen = true,
                visible = true, dismissControls = true,
            ),
        )
        assertEquals(
            PlayerChromeBackAction.LockedHint,
            playerChromeBackAction(
                locked = true, panel = PlayerPanel.None, extraLayerOpen = false,
                visible = true, dismissControls = true,
            ),
        )
        assertEquals(
            PlayerChromeBackAction.ClosePanel,
            playerChromeBackAction(
                locked = false, panel = PlayerPanel.Sources, extraLayerOpen = false,
                visible = true, dismissControls = true,
            ),
        )
        assertEquals(
            PlayerChromeBackAction.HideControls,
            playerChromeBackAction(
                locked = false, panel = PlayerPanel.None, extraLayerOpen = false,
                visible = true, dismissControls = true,
            ),
        )
        assertEquals(
            PlayerChromeBackAction.Exit,
            playerChromeBackAction(
                locked = false, panel = PlayerPanel.None, extraLayerOpen = false,
                visible = false, dismissControls = true,
            ),
        )
    }

    @Test
    fun livePagesDoNotAddAControlsHideLayerOnBack() {
        assertEquals(
            PlayerChromeBackAction.Exit,
            playerChromeBackAction(
                locked = false, panel = PlayerPanel.None, extraLayerOpen = false,
                visible = true, dismissControls = false,
            ),
        )
    }

    @Test
    fun portraitLiveKeepsOnlyFullscreen() {
        val live = PlayerChromeCapabilities(
            seek = false,
            resize = true,
            favorite = true,
            orientation = true,
            mute = true,
            settings = true,
            channel = true,
            info = true,
            url = true,
        )
        val portrait = live.forOrientation(portrait = true)
        assertTrue(portrait.orientation)
        assertTrue(portrait.portraitMinimal)
        assertFalse(portrait.seek)
        assertFalse(portrait.resize)
        assertFalse(portrait.favorite)
        assertFalse(portrait.mute)
        assertFalse(portrait.settings)
        assertFalse(portrait.channel)
        assertFalse(portrait.info)
        assertFalse(portrait.url)
    }

    @Test
    fun landscapeAndPagesWithoutFullscreenKeepCapabilities() {
        val live = PlayerChromeCapabilities(seek = false, orientation = true, channel = true)
        assertEquals(live, live.forOrientation(portrait = false))

        // 竖版但本页没有全屏入口（如手机点播）不裁剪，避免只剩返回按钮。
        val vod = PlayerChromeCapabilities(seek = true, episodes = true, speed = true)
        assertEquals(vod, vod.forOrientation(portrait = true))
    }
}
