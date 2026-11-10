package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthGateTest {

    private val sources = listOf(
        ManagedSource("a", "源A", weight = 50),
        ManagedSource("b", "源B", weight = 100),
        ManagedSource("c", "源C", weight = 100, pinned = true),
    )

    @Test
    fun successClearsFailuresAndMarksFastSourceAvailable() {
        val previous = SourceHealthSnapshot("a", consecutiveFailures = 2)
        val next = nextSourceHealth(previous, SourceProbeResult("a", true, 120L, atMs = 1_000L))
        assertEquals(SourceAvailability.Available, next.availability)
        assertEquals(0, next.consecutiveFailures)
        assertEquals(120L, next.latencyMs)
        assertEquals(1_000L, next.lastCheckedAtMs)
    }

    @Test
    fun slowSuccessIsDegraded() {
        val next = nextSourceHealth(null, SourceProbeResult("a", true, 3_000L, atMs = 1L))
        assertEquals(SourceAvailability.Degraded, next.availability)
    }

    @Test
    fun thirdConsecutiveFailureMarksUnavailable() {
        var health: SourceHealthSnapshot? = null
        repeat(3) { index ->
            health = nextSourceHealth(health, SourceProbeResult("a", false, atMs = 100L + index))
        }
        val final = checkNotNull(health)
        assertEquals(3, final.consecutiveFailures)
        assertEquals(SourceAvailability.Unavailable, final.availability)
    }

    @Test
    fun unavailableSourceIsExcludedFromFanOut() {
        val bad = nextSourceHealth(
            nextSourceHealth(nextSourceHealth(null, SourceProbeResult("a", false, atMs = 10L)),
                SourceProbeResult("a", false, atMs = 20L)),
            SourceProbeResult("a", false, atMs = 30L),
        )
        val eligible = eligibleFanOutSources(sources, mapOf("a" to bad), nowMs = 40L)
        assertEquals(listOf("c", "b"), eligible.map { it.id })
    }

    @Test
    fun allSourcesUnavailableFallsBackToOriginalList() {
        val health = sources.associate { source ->
            source.id to SourceHealthSnapshot(
                sourceId = source.id,
                availability = SourceAvailability.Unavailable,
                consecutiveFailures = 5,
                lastCheckedAtMs = 100L,
            )
        }
        val eligible = eligibleFanOutSources(sources, health, nowMs = 101L)
        assertEquals(3, eligible.size)
        assertEquals("c", eligible.first().id)
    }

    @Test
    fun cooledDownSourceJoinsAgain() {
        val stale = SourceHealthSnapshot(
            sourceId = "a",
            availability = SourceAvailability.Unavailable,
            consecutiveFailures = 5,
            lastCheckedAtMs = 0L,
        )
        val eligible = eligibleFanOutSources(sources, mapOf("a" to stale), nowMs = 20 * 60 * 1000L)
        assertEquals(3, eligible.size)
    }

    @Test
    fun timeoutBudgetMatchesEligibleSourceCount() {
        val budget = sourceTimeoutBudget(sources, 4_000L)
        assertEquals(3, budget.size)
        assertTrue(budget.all { it > 0L })
    }
}
