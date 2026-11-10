package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMetricsTest {

    @Test
    fun phaseDurationsSkipMissingMarks() {
        val timeline = StartupTimeline()
            .record(StartupPhase.ProcessStart, 100L)
            .record(StartupPhase.ApplicationInit, 150L)
            .record(StartupPhase.FirstFrame, 400L)
        val durations = timeline.phaseDurations()
        assertEquals(50L, durations[StartupPhase.ApplicationInit])
        assertEquals(250L, durations[StartupPhase.FirstFrame])
        assertEquals(2, durations.size)
    }

    @Test
    fun negativeDurationIsDiscarded() {
        val timeline = StartupTimeline()
            .record(StartupPhase.ProcessStart, 500L)
            .record(StartupPhase.ApplicationInit, 100L)
        assertTrue(timeline.phaseDurations().isEmpty())
    }

    @Test
    fun totalRequiresProcessStartAndInteractive() {
        assertNull(startupTotalMs(StartupTimeline()))
        assertNull(startupTotalMs(StartupTimeline().record(StartupPhase.ProcessStart, 0L)))
        val timeline = StartupTimeline()
            .record(StartupPhase.ProcessStart, 0L)
            .record(StartupPhase.Interactive, 820L)
        assertEquals(820L, startupTotalMs(timeline))
    }

    @Test
    fun jankRatioHandlesEmptyAndClampedSamples() {
        assertEquals(0.0, jankRatio(FrameTimingSample(0, 0)), 0.0001)
        assertEquals(0.25, jankRatio(FrameTimingSample(100, 25)), 0.0001)
        assertEquals(0.0, jankRatio(FrameTimingSample(100, -5)), 0.0001)
    }

    @Test
    fun guardrailChecklistsAreNotEmpty() {
        assertTrue(COMPOSE_STABILITY_CHECKLIST.isNotEmpty())
        assertTrue(PROGUARD_TIGHTENING_STEPS.size >= 4)
        assertTrue(STARTUP_MEASURE_COMMANDS.any { it.contains("am start -W") })
    }
}
