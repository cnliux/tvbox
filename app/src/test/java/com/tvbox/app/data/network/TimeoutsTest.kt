package com.tvbox.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-09：分层超时预算的 JVM 单测；不发起任何网络请求。 */
class TimeoutsTest {

    @Test
    fun `each scenario has its documented connect read and total budget`() {
        assertEquals(1_500L to 2_500L, Timeouts.PROBE.let { it.connectMs to it.readMs })
        assertEquals(4_000L, Timeouts.PROBE.totalMs)
        assertEquals(7_500L, Timeouts.DETAIL.totalMs)
        assertEquals(6_000L, Timeouts.POSTER.totalMs)
        assertEquals(9_000L, Timeouts.FIRST_FRAME.totalMs)
        assertEquals(15_000L, Timeouts.OTA.readMs)
        assertEquals(12_000L, Timeouts.BUFFER_STALL_MS)
    }

    @Test
    fun `probe is the tightest budget and ota is the most generous`() {
        val budgets = Timeouts.Scenario.entries.map(Timeouts::forScenario)
        assertEquals(Timeouts.PROBE, budgets.minBy { it.totalMs })
        assertEquals(Timeouts.OTA, budgets.maxBy { it.totalMs })
        assertTrue(Timeouts.DETAIL.totalMs < Timeouts.FIRST_FRAME.totalMs)
    }

    @Test
    fun `all scenarios are distinct and exhaustive`() {
        val scenarios = Timeouts.Scenario.entries
        assertEquals(scenarios.size, scenarios.map(Timeouts::forScenario).distinct().size)
        assertEquals(scenarios.size, scenarios.map(Timeouts::failureSemantic).distinct().size)
        scenarios.forEach { assertNotEquals(0L, Timeouts.forScenario(it).connectMs) }
    }

    @Test
    fun `probe timeout never degrades into slow semantic`() {
        assertEquals(Timeouts.FailureSemantic.TIMEOUT, Timeouts.failureSemantic(Timeouts.Scenario.PROBE))
        assertEquals(Timeouts.FailureSemantic.CACHE, Timeouts.failureSemantic(Timeouts.Scenario.DETAIL))
        assertEquals(Timeouts.FailureSemantic.PLACEHOLDER, Timeouts.failureSemantic(Timeouts.Scenario.POSTER))
        assertEquals(Timeouts.FailureSemantic.SWITCH_LINE, Timeouts.failureSemantic(Timeouts.Scenario.FIRST_FRAME))
        assertEquals(Timeouts.FailureSemantic.RESUME, Timeouts.failureSemantic(Timeouts.Scenario.OTA))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `budget rejects non positive values`() {
        Timeouts.Budget(0L, 100L)
    }
}
