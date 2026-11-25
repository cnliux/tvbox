package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2-16：探活冷却 + 「DEAD 由用户投票」的 JVM 单测。 */
class ProbeCooldownTest {

    @Test
    fun `probe failure only downgrades to slow and never marks dead`() {
        val state = ProbeCooldown.onProbeFailure(null, 1_000L)
        assertEquals(ProbeCooldown.Grade.SLOW, state.grade)
        assertFalse(ProbeCooldown.shouldProbe(state, 1_000L))
        assertTrue(ProbeCooldown.shouldProbe(state, 1_000L + ProbeCooldown.SLOW_COOLDOWN_MS))
        assertEquals(0, state.userFailures)
    }

    @Test
    fun `dead cooldown is fifteen minutes and clock rewind stays cooling`() {
        val state = ProbeCooldown.State(ProbeCooldown.Grade.DEAD, 10_000L)
        assertTrue(ProbeCooldown.isCoolingDown(state, 10_000L + ProbeCooldown.DEAD_COOLDOWN_MS - 1L))
        assertFalse(ProbeCooldown.isCoolingDown(state, 10_000L + ProbeCooldown.DEAD_COOLDOWN_MS))
        assertTrue(ProbeCooldown.isCoolingDown(state, 1L)) // 时钟回拨 fail closed：视为仍在冷却
    }

    @Test
    fun `two real user playback failures are required to mark dead`() {
        val first = ProbeCooldown.onUserPlaybackFailure(null, 1_000L)
        assertEquals(ProbeCooldown.Grade.ALIVE, first.grade)
        assertEquals(1, first.userFailures)

        val second = ProbeCooldown.onUserPlaybackFailure(first, 2_000L)
        assertEquals(ProbeCooldown.Grade.DEAD, second.grade)
        assertEquals(2, second.userFailures)
        assertFalse(ProbeCooldown.shouldProbe(second, 2_000L))
    }

    @Test
    fun `probe success does not erase user votes`() {
        val afterFailure = ProbeCooldown.onUserPlaybackFailure(null, 1_000L)
        val recovered = ProbeCooldown.onProbeSuccess(afterFailure, 2_000L)
        assertEquals(ProbeCooldown.Grade.ALIVE, recovered.grade)
        assertEquals(1, recovered.userFailures)
    }

    @Test
    fun `cold start probes at most three and counts suppressed requests`() {
        assertEquals(3, ProbeCooldown.coldStartBudget(8))
        assertEquals(2, ProbeCooldown.coldStartBudget(2))
        assertEquals(0, ProbeCooldown.coldStartBudget(0))
        assertEquals(5, ProbeCooldown.suppressedRequests(8, 3))
        assertEquals(0, ProbeCooldown.suppressedRequests(2, 3))
    }
}
