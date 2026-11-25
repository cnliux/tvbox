package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.tvbox.app.domain.LiveRemoteDirectionMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定直播遥控状态机的四条关键语义：
 * 确认键两段式、面板状态隔离、数字输入拦截、BACK 逐级弹栈。
 */
class LiveRemoteKeyRouterTest {

    private val router = LiveRemoteKeyRouter()

    @Test
    fun confirmKeyDoesNothingUntilKeyUp() {
        val start = LiveRemoteState()

        val down = router.handle(AndroidKeyEvent.KEYCODE_DPAD_CENTER, KeyEventType.KeyDown, start)
        assertEquals(start, down?.state)
        assertTrue(down?.effects.isNullOrEmpty())

        val up = router.handle(AndroidKeyEvent.KEYCODE_DPAD_CENTER, KeyEventType.KeyUp, start)
        assertEquals(UIState.CHANNEL_LIST, up?.state?.uiState)
        assertEquals(listOf(LiveRemoteEffect.OpenChannelList), up?.effects)
    }

    @Test
    fun panelStatePassesEverythingExceptBackToChildren() {
        val panel = LiveRemoteState(uiState = UIState.CHANNEL_LIST)

        assertNull(router.handle(AndroidKeyEvent.KEYCODE_DPAD_UP, KeyEventType.KeyDown, panel))
        assertNull(router.handle(AndroidKeyEvent.KEYCODE_DPAD_CENTER, KeyEventType.KeyUp, panel))
        assertNull(router.handle(AndroidKeyEvent.KEYCODE_DPAD_LEFT, KeyEventType.KeyUp, panel))

        val backDown = router.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyDown, panel)
        assertEquals(panel, backDown?.state)

        val backUp = router.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyUp, panel)
        assertEquals(UIState.FULLSCREEN, backUp?.state?.uiState)
        assertEquals(listOf(LiveRemoteEffect.ClosePanel), backUp?.effects)
    }

    @Test
    fun numberEntryBlocksChannelSwitching() {
        val digit = router.handle(AndroidKeyEvent.KEYCODE_1, KeyEventType.KeyDown, LiveRemoteState())
        assertEquals("1", digit?.state?.numberInput)
        assertEquals(listOf(LiveRemoteEffect.NumberChanged("1")), digit?.effects)

        val blocked = router.handle(AndroidKeyEvent.KEYCODE_DPAD_DOWN, KeyEventType.KeyDown, digit!!.state)
        assertEquals(digit.state, blocked?.state)
        assertTrue(blocked?.effects.isNullOrEmpty())

        val commit = router.handle(AndroidKeyEvent.KEYCODE_ENTER, KeyEventType.KeyUp, digit.state)
        assertEquals(listOf(LiveRemoteEffect.CommitNumber), commit?.effects)
    }

    @Test
    fun backUnwindsOneLayerAtATime() {
        val typing = LiveRemoteState(numberInput = "12", numberInputNonce = 2)
        val cleared = router.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyUp, typing)
        assertEquals("", cleared?.state?.numberInput)
        assertEquals(listOf(LiveRemoteEffect.NumberChanged("")), cleared?.effects)

        val controller = LiveRemoteState(uiState = UIState.CONTROLLER)
        val closed = router.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyUp, controller)
        assertEquals(UIState.FULLSCREEN, closed?.state?.uiState)

        val exit = router.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyUp, LiveRemoteState())
        assertEquals(listOf(LiveRemoteEffect.ExitLive), exit?.effects)
    }

    @Test
    fun sourceSwitchCarriesContinuityHintForHuyaDouyu() {
        val huya = LiveRemoteKeyRouter(isContinuitySource = { true })
            .handle(AndroidKeyEvent.KEYCODE_DPAD_RIGHT, KeyEventType.KeyDown, LiveRemoteState())
        assertTrue(
            huya?.effects?.contains(LiveRemoteEffect.SourceSwitched(true)) == true,
        )
        assertTrue(huya?.effects?.contains(LiveRemoteEffect.NextSource) == true)

        val other = LiveRemoteKeyRouter(isContinuitySource = { false })
            .handle(AndroidKeyEvent.KEYCODE_DPAD_LEFT, KeyEventType.KeyDown, LiveRemoteState())
        assertTrue(
            other?.effects?.contains(LiveRemoteEffect.SourceSwitched(false)) == true,
        )
    }

    @Test
    fun repeatedKeyIsSwallowedWithoutEffect() {
        val result = router.handle(
            AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            KeyEventType.KeyDown,
            LiveRemoteState(),
            repeatCount = 3,
        )
        assertTrue(result?.effects.isNullOrEmpty())
    }

    @Test
    fun backCanBeDelegatedWhenNoOverlayIsOpen() {
        val delegating = LiveRemoteKeyRouter(exitLiveOnBackInFullscreen = false)
        assertNull(
            delegating.handle(AndroidKeyEvent.KEYCODE_BACK, KeyEventType.KeyUp, LiveRemoteState()),
        )
    }

    @Test
    fun failureCardKeepsConfirmForFocusedButton() {
        val withFailureCard = LiveRemoteKeyRouter(hasBlockingFailureCard = { true })
        assertNull(
            withFailureCard.handle(
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                KeyEventType.KeyUp,
                LiveRemoteState(),
            ),
        )
    }

    @Test
    fun numberEntryArrowMovesCandidateHighlightWithoutSwitchingChannel() {
        val typing = LiveRemoteState(numberInput = "1")
        val result = router.handle(AndroidKeyEvent.KEYCODE_DPAD_DOWN, KeyEventType.KeyUp, typing)
        assertEquals(listOf(LiveRemoteEffect.NumberMove(1)), result?.effects)
    }

    @Test
    fun menuKeyOpensChannelListOnlyOnKeyUp() {
        val start = LiveRemoteState()
        val down = router.handle(AndroidKeyEvent.KEYCODE_MENU, KeyEventType.KeyDown, start)
        assertTrue(down?.effects.isNullOrEmpty())
        assertEquals(start, down?.state)

        val up = router.handle(AndroidKeyEvent.KEYCODE_MENU, KeyEventType.KeyUp, start)
        assertEquals(UIState.CHANNEL_LIST, up?.state?.uiState)
        assertEquals(listOf(LiveRemoteEffect.OpenChannelList), up?.effects)
    }

    @Test
    fun deleteRemovesLastDigitAndBumpsNonce() {
        val typing = LiveRemoteState(numberInput = "123", numberInputNonce = 3)
        val result = router.handle(AndroidKeyEvent.KEYCODE_DEL, KeyEventType.KeyDown, typing)
        assertEquals("12", result?.state?.numberInput)
        assertEquals(4, result?.state?.numberInputNonce)
        assertEquals(listOf(LiveRemoteEffect.NumberChanged("12")), result?.effects)
    }

    @Test
    fun numberInputTruncatesToLastFourDigits() {
        var state = LiveRemoteState()
        repeat(6) { index ->
            state = router.handle(
                AndroidKeyEvent.KEYCODE_0 + index + 1,
                KeyEventType.KeyDown,
                state,
            )!!.state
        }
        assertEquals("3456", state.numberInput)
    }

    @Test
    fun repeatedKeyIsConsumedWithoutChangingState() {
        val start = LiveRemoteState(numberInput = "12")
        val result = router.handle(
            AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            KeyEventType.KeyDown,
            start,
            repeatCount = 5,
        )
        // 非 null 表示事件被消费（不能穿透到背景换台），但状态与 effect 都不变。
        assertEquals(start, result?.state)
        assertTrue(result?.effects.isNullOrEmpty())
    }

    @Test
    fun enterKeySharesConfirmBranchWithDpadCenter() {
        val result = router.handle(AndroidKeyEvent.KEYCODE_ENTER, KeyEventType.KeyUp, LiveRemoteState())
        assertEquals(UIState.CHANNEL_LIST, result?.state?.uiState)
        assertEquals(listOf(LiveRemoteEffect.OpenChannelList), result?.effects)
    }

    @Test
    fun legacyDirectionMappingKeepsLeftRightChannelSwitching() {
        val legacy = LiveRemoteKeyRouter(
            directionMapping = LiveRemoteDirectionMapping.LEFT_RIGHT_CHANNEL,
        )
        assertEquals(
            listOf(LiveRemoteEffect.PreviousChannel),
            legacy.handle(AndroidKeyEvent.KEYCODE_DPAD_LEFT, KeyEventType.KeyDown, LiveRemoteState())?.effects,
        )
        assertEquals(
            listOf(LiveRemoteEffect.PreviousSource, LiveRemoteEffect.SourceSwitched(false)),
            legacy.handle(AndroidKeyEvent.KEYCODE_DPAD_UP, KeyEventType.KeyDown, LiveRemoteState())?.effects,
        )
    }
}
