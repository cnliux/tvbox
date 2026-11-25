package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T3-21：内存水位三档与策略收紧的 JVM 单测。 */
class MemoryGovernorTest {

    @Test
    fun `available memory maps to three tiers`() {
        assertEquals(MemoryGovernor.Tier.RICH, MemoryGovernor.tierFor(600L))
        assertEquals(MemoryGovernor.Tier.TIGHT, MemoryGovernor.tierFor(300L))
        assertEquals(MemoryGovernor.Tier.CRITICAL, MemoryGovernor.tierFor(100L))
        assertEquals(MemoryGovernor.Tier.CRITICAL, MemoryGovernor.tierFor(4_096L, lowMemory = true))
    }

    @Test
    fun `trim level maps to tightening only`() {
        assertEquals(MemoryGovernor.Tier.CRITICAL, MemoryGovernor.tierFromTrimLevel(15))
        assertEquals(MemoryGovernor.Tier.CRITICAL, MemoryGovernor.tierFromTrimLevel(20))
        assertEquals(MemoryGovernor.Tier.TIGHT, MemoryGovernor.tierFromTrimLevel(10))
        assertEquals(MemoryGovernor.Tier.TIGHT, MemoryGovernor.tierFromTrimLevel(5))
        assertNull(MemoryGovernor.tierFromTrimLevel(0))
    }

    @Test
    fun `each tier has a distinct cache budget`() {
        val rich = MemoryGovernor.prefs(MemoryGovernor.Tier.RICH)
        val tight = MemoryGovernor.prefs(MemoryGovernor.Tier.TIGHT)
        val critical = MemoryGovernor.prefs(MemoryGovernor.Tier.CRITICAL)
        assertEquals(240, rich.posters)
        assertEquals(96, tight.posters)
        assertEquals(32, critical.posters)
        assertEquals(2, rich.prefetchPages)
        assertEquals(0, critical.prefetchPages)
        assertEquals(24, rich.epgHours)
        assertEquals(0, critical.epgHours)
        assertEquals(true, critical.minimizeGoWindow)
        assertEquals(false, rich.minimizeGoWindow)
    }

    @Test
    fun `poster prefetch and epg settings are clamped by tier budget`() {
        val rich = MemoryGovernor.Tier.RICH
        val tight = MemoryGovernor.Tier.TIGHT
        val critical = MemoryGovernor.Tier.CRITICAL
        // 海报：用户设置只能更严，不能超过档位预算。
        assertEquals(20, MemoryGovernor.effectivePosterLimit(20, rich))
        assertEquals(240, MemoryGovernor.effectivePosterLimit(1_000, rich))
        assertEquals(96, MemoryGovernor.effectivePosterLimit(1_000, tight))
        assertEquals(32, MemoryGovernor.effectivePosterLimit(1_000, critical))
        assertEquals(0, MemoryGovernor.effectivePosterLimit(-1, critical))
        // 自动预取页数：档位预算是硬上限，CRITICAL 关闭自动预取。
        assertEquals(2, MemoryGovernor.effectivePrefetchPages(5, rich))
        assertEquals(1, MemoryGovernor.effectivePrefetchPages(5, tight))
        assertEquals(0, MemoryGovernor.effectivePrefetchPages(5, critical))
        assertEquals(0, MemoryGovernor.effectivePrefetchPages(-3, rich))
        // EPG 时长：按档位截断，CRITICAL 的 0 小时表示不拉取。
        assertEquals(12, MemoryGovernor.effectiveEpgHours(12, rich))
        assertEquals(6, MemoryGovernor.effectiveEpgHours(24, tight))
        assertEquals(0, MemoryGovernor.effectiveEpgHours(24, critical))
        assertEquals(0, MemoryGovernor.effectiveEpgHours(-1, rich))
    }

    @Test
    fun `tighten immediately but do not auto restore`() {
        var tier = MemoryGovernor.Tier.RICH
        tier = MemoryGovernor.nextTier(tier, MemoryGovernor.Tier.CRITICAL)
        assertEquals(MemoryGovernor.Tier.CRITICAL, tier)
        assertEquals(
            MemoryGovernor.Tier.CRITICAL,
            MemoryGovernor.nextTier(tier, MemoryGovernor.Tier.RICH),
        )
    }
}
