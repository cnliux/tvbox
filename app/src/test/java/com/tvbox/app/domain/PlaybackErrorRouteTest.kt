package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorRouteTest {

    @Test
    fun networkErrorRetriesThenSwitchesLine() {
        val plan = buildPlaybackRecoveryPlan("网络超时，请稍后重试", maxAttempts = 3)
        assertEquals(PlaybackErrorKind.Network, plan.presentation.kind)
        assertEquals(PlaybackErrorAction.Retry, plan.steps.first())
        assertEquals(PlaybackErrorAction.SwitchLine, plan.steps.last())
        assertEquals(4, plan.steps.size)
        assertTrue(plan.userHint.contains("建议：重试"))
    }

    @Test
    fun decoderErrorSwitchesLineThenSource() {
        val plan = buildPlaybackRecoveryPlan("解码失败 codec")
        assertEquals(
            listOf(PlaybackErrorAction.SwitchLine, PlaybackErrorAction.SwitchSource),
            plan.steps,
        )
        assertEquals("切换线路", plan.presentation.actionLabel)
    }

    @Test
    fun invalidSourceSwitchesSourceOnly() {
        val plan = buildPlaybackRecoveryPlan("404", httpStatus = 404)
        assertEquals(listOf(PlaybackErrorAction.SwitchSource), plan.steps)
        assertEquals(PlaybackErrorKind.SourceInvalid, plan.presentation.kind)
    }

    @Test
    fun unknownErrorHasNoStepsAndNoActionLabel() {
        val plan = buildPlaybackRecoveryPlan(null)
        assertEquals(PlaybackErrorKind.Unknown, plan.presentation.kind)
        assertTrue(plan.steps.isEmpty())
        assertEquals(null, plan.presentation.actionLabel)
    }

    @Test
    fun attemptsAreCapped() {
        val short = buildPlaybackRecoveryPlan("timeout", maxAttempts = 0).steps
        val long = buildPlaybackRecoveryPlan("timeout", maxAttempts = 99).steps
        assertEquals(2, short.size)
        assertEquals(6, long.size)
    }

    @Test
    fun appendKeepsBaseMessageForUnknownErrors() {
        val base = "播放失败，正在尝试其他线路"
        assertEquals(base, appendPlaybackErrorHint(base, null))
        assertEquals(base, appendPlaybackErrorHint(base, "some opaque error"))
    }

    @Test
    fun appendAddsActionableHintWithoutDroppingBaseMessage() {
        val base = "网络超时，请稍后重试"
        val result = appendPlaybackErrorHint(base, "socket timeout")
        assertTrue(result.startsWith(base))
        assertTrue(result.contains("建议：重试"))
        assertFalse(result.contains(baseMessageTwice(base)))
    }

    private fun baseMessageTwice(base: String): String = "$base（$base"
}
