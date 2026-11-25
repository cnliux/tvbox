package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3-24：本机健康页入口与汇总格式的 JVM 单测。 */
class HealthPageMetricsTest {

    @Test
    fun `health page opens after five menu presses`() {
        assertFalse(HealthPageMetrics.shouldOpen(4))
        assertTrue(HealthPageMetrics.shouldOpen(5))
        assertTrue(HealthPageMetrics.shouldOpen(9))
    }

    @Test
    fun `p95 uses nearest rank and handles empty input`() {
        assertEquals(0L, HealthPageMetrics.p95(emptyList()))
        assertEquals(90L, HealthPageMetrics.p95(listOf(90L)))
        val samples = (1..20).map { it.toLong() * 100L }
        assertEquals(1_900L, HealthPageMetrics.p95(samples))
    }

    @Test
    fun `format exposes the five numbers geek users screenshot`() {
        val text = HealthPageMetrics.format(
            HealthSnapshot(
                ttfpP95Ms = 1_400L,
                longFrames = 38,
                throttled = 2,
                tier = MemoryGovernor.Tier.TIGHT,
                redactedExport = true,
            ),
        )
        assertTrue(text, "首帧P95 1400ms" in text)
        assertTrue(text, "长帧 38" in text)
        assertTrue(text, "被限流 2 次" in text)
        assertTrue(text, "TIGHT" in text)
        assertTrue(text, "已打码导出 ✓" in text)
    }
}
