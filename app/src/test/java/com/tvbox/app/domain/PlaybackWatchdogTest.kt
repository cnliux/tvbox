package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWatchdogTest {
    private fun watchdog(): PlaybackWatchdog = PlaybackWatchdog(
        firstFrameTimeoutMs = 12_000L,
        stallTimeoutMs = 4_000L,
    )

    @Test
    fun firstFrameTimeoutIsReportedOnceOnly() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)

        assertNull(watchdog.checkFirstFrame(nowMs = 11_999L, playWhenReady = true))
        val failure = watchdog.checkFirstFrame(nowMs = 12_000L, playWhenReady = true)
        assertEquals(PlaybackFailureKind.FirstFrameTimeout, failure?.kind)
        assertNull(watchdog.checkFirstFrame(nowMs = 30_000L, playWhenReady = true))
    }

    @Test
    fun pausedPlaybackDoesNotTriggerFirstFrameTimeout() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)

        assertNull(watchdog.checkFirstFrame(nowMs = 20_000L, playWhenReady = false))
    }

    @Test
    fun firstFrameStopsFirstFrameTimeoutAndEnablesStallDetection() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)
        watchdog.onFirstFrameRendered(nowMs = 3_000L)

        assertNull(watchdog.checkFirstFrame(nowMs = 20_000L, playWhenReady = true))
        assertTrue(watchdog.hasFirstFrame)

        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 3_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 6_999L))
        val failure = watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 7_000L)
        assertEquals(PlaybackFailureKind.Stalled, failure?.kind)
    }

    @Test
    fun newSourceResetsBothStages() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)
        watchdog.onFirstFrameRendered(nowMs = 1_000L)
        assertTrue(watchdog.hasFirstFrame)

        watchdog.onSourceRequested(nowMs = 5_000L)
        assertFalse(watchdog.hasFirstFrame)
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 0L, nowMs = 6_000L))
        assertEquals(
            PlaybackFailureKind.FirstFrameTimeout,
            watchdog.checkFirstFrame(nowMs = 17_000L, playWhenReady = true)?.kind,
        )
    }

    @Test
    fun pauseClearsStallBaseline() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)
        watchdog.onFirstFrameRendered(nowMs = 1_000L)

        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 1_000L))
        assertNull(watchdog.onProgressSample(isPlaying = false, positionMs = 100L, nowMs = 10_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 10_001L))
        assertEquals(
            PlaybackFailureKind.Stalled,
            watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 14_001L)?.kind,
        )
    }

    @Test
    fun firstFrameElapsedMeasuresSourceToFrame() {
        val watchdog = watchdog()
        assertNull(watchdog.firstFrameElapsedMs)
        watchdog.onSourceRequested(nowMs = 1_000L)
        assertNull(watchdog.firstFrameElapsedMs)

        watchdog.onFirstFrameRendered(nowMs = 4_500L)
        assertEquals(3_500L, watchdog.firstFrameElapsedMs)

        // 新的取流重新计时，旧的首帧耗时不再对外提供。
        watchdog.onSourceRequested(nowMs = 10_000L)
        assertNull(watchdog.firstFrameElapsedMs)
    }

    @Test
    fun advancingPositionKeepsPlaybackHealthy() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)
        watchdog.onFirstFrameRendered(nowMs = 1_000L)

        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 1_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 101L, nowMs = 5_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 102L, nowMs = 20_000L))
    }
}
