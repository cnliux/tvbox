package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipCreditsRuntimeTest {

    private val config = SkipCreditsConfig(enabled = true, introSeconds = 30, outroSeconds = 20)
    private val durationMs = 600_000L

    @Test
    fun disabledConfigNeverActs() {
        val (decision, state) = advanceSkipCredits(
            resetSkipCreditsRuntime(), 0L, durationMs, SkipCreditsConfig(),
        )
        assertNull(decision)
        assertEquals(resetSkipCreditsRuntime(), state)
    }

    @Test
    fun introSkipFiresOnlyOncePerEpisode() {
        val first = advanceSkipCredits(resetSkipCreditsRuntime(), 0L, durationMs, config)
        assertEquals(SkipCreditsAction.SeekToIntro, first.first!!.action)
        assertEquals(30_000L, first.first!!.targetPositionMs)
        assertTrue(first.second.introSkipped)

        val second = advanceSkipCredits(first.second, 5_000L, durationMs, config)
        assertNull(second.first)
        assertEquals(first.second, second.second)
    }

    @Test
    fun outroTriggersNextEpisodeOnlyOnce() {
        val (decision, state) = advanceSkipCredits(
            resetSkipCreditsRuntime(), 590_000L, durationMs, config,
        )
        assertEquals(SkipCreditsAction.PlayNextEpisode, decision!!.action)
        assertTrue(state.outroTriggered)
        assertNull(advanceSkipCredits(state, 595_000L, durationMs, config).first)
    }

    @Test
    fun resetAllowsNextEpisodeToSkipIntroAgain() {
        val (_, state) = advanceSkipCredits(resetSkipCreditsRuntime(), 0L, durationMs, config)
        assertTrue(state.introSkipped)
        val nextEpisode = advanceSkipCredits(resetSkipCreditsRuntime(), 0L, durationMs, config)
        assertEquals(SkipCreditsAction.SeekToIntro, nextEpisode.first!!.action)
    }

    @Test
    fun overlappingIntroAndOutroNeverActs() {
        val short = SkipCreditsConfig(enabled = true, introSeconds = 40, outroSeconds = 40)
        assertNull(advanceSkipCredits(resetSkipCreditsRuntime(), 0L, 60_000L, short).first)
    }

    @Test
    fun hintReflectsConfig() {
        assertEquals("跳过片头/片尾：关闭", skipCreditsHint(SkipCreditsConfig()))
        assertEquals("已开启 · 片头 30 秒 · 片尾 20 秒", skipCreditsHint(config))
        assertEquals(
            "已开启 · 未设置秒数",
            skipCreditsHint(SkipCreditsConfig(enabled = true)),
        )
    }
}
