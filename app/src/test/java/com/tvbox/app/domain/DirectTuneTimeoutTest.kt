package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-08 数字选台超时四档的纯逻辑单测：
 * - 默认 2000ms 与既有行为逐字节一致；
 * - clamp 收敛到合法档位（未知值回退默认，手动=0 保留）；
 * - displayName 对「手动确认」档与秒数档分别呈现。
 */
class DirectTuneTimeoutTest {

    @Test
    fun defaultMatchesLegacyBehavior() {
        // 既有硬编码 CHANNEL_NUMBER_COMMIT_DELAY_MS = 2000，默认值必须保持一致。
        assertEquals(2_000L, DirectTuneTimeout.DefaultMs)
        assertTrue(2_000L in DirectTuneTimeout.choices)
    }

    @Test
    fun choicesAreFourLevels() {
        assertEquals(listOf(1_500L, 2_000L, 3_000L, 0L), DirectTuneTimeout.choices)
    }

    @Test
    fun clampKeepsValidChoices() {
        assertEquals(1_500L, DirectTuneTimeout.clamp(1_500L))
        assertEquals(2_000L, DirectTuneTimeout.clamp(2_000L))
        assertEquals(3_000L, DirectTuneTimeout.clamp(3_000L))
        assertEquals(0L, DirectTuneTimeout.clamp(0L))
    }

    @Test
    fun clampFallsBackToDefaultForUnknown() {
        // 旧数据或异常输入（如 500 / 5000）收敛回默认 2000。
        assertEquals(2_000L, DirectTuneTimeout.clamp(500L))
        assertEquals(2_000L, DirectTuneTimeout.clamp(5_000L))
    }

    @Test
    fun displayNameDistinguishesManual() {
        assertEquals("手动确认", DirectTuneTimeout.displayName(0L))
        assertEquals("2 秒", DirectTuneTimeout.displayName(2_000L))
        assertEquals("1 秒", DirectTuneTimeout.displayName(1_500L))
    }
}
