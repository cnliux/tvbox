package com.tvbox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定四页控件状态归并后的显隐与返回层级，防止把点播/直播的旧分叉带回来。
 */
class PlayerChromeStateTest {

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
