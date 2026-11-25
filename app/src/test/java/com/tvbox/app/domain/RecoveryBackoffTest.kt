package com.tvbox.app.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务2/3 自适应退避策略测试：验证按失败原因差异化基线、指数递增、封顶与抖动边界。
 */
class RecoveryBackoffTest {

    private val noJitter = RecoveryBackoff(jitterRatio = 0.0)

    @Test
    fun `Ended 首次重试用 1_5s 基线`() {
        assertEquals(1_500L, noJitter.nextDelayMs(LiveLineFallbackReason.Ended, retry = 1))
    }

    @Test
    fun `Stall 首次重试用 3s 基线`() {
        assertEquals(3_000L, noJitter.nextDelayMs(LiveLineFallbackReason.Stall, retry = 1))
    }

    @Test
    fun `Error 首次重试立即执行`() {
        assertEquals(0L, noJitter.nextDelayMs(LiveLineFallbackReason.Error, retry = 1))
    }

    @Test
    fun `指数递增并封顶`() {
        assertEquals(3_000L, noJitter.nextDelayMs(LiveLineFallbackReason.Ended, retry = 2))
        assertEquals(6_000L, noJitter.nextDelayMs(LiveLineFallbackReason.Ended, retry = 3))
        // retry 很大时封顶在 8s。
        assertEquals(8_000L, noJitter.nextDelayMs(LiveLineFallbackReason.Ended, retry = 20))
    }

    @Test
    fun `Error 零基线无论第几次都为 0`() {
        assertEquals(0L, noJitter.nextDelayMs(LiveLineFallbackReason.Error, retry = 5))
    }

    @Test
    fun `抖动不会产生负数或超出上限`() {
        val jittered = RecoveryBackoff(
            jitterRatio = 0.3,
            random = Random(42),
        )
        repeat(200) { i ->
            val d = jittered.nextDelayMs(LiveLineFallbackReason.Stall, retry = (i % 5) + 1)
            assertTrue("延迟必须非负: $d", d >= 0L)
            assertTrue("延迟必须不超上限: $d", d <= RecoveryBackoff.DEFAULT_MAX_MS)
        }
    }
}
