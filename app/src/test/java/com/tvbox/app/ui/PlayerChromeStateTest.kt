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
}
