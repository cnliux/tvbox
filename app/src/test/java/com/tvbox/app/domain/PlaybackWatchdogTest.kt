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
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 400L, nowMs = 5_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 700L, nowMs = 20_000L))
    }

    @Test
    fun subEpsilonJitterDoesNotResetStallBaseline() {
        val watchdog = watchdog()
        watchdog.onSourceRequested(nowMs = 0L)
        watchdog.onFirstFrameRendered(nowMs = 1_000L)

        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 1_000L))
        // 1ms 抖动与轻微回退都不算推进，不能把卡死计时清零。
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 101L, nowMs = 2_000L))
        assertNull(watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 3_000L))
        assertEquals(
            PlaybackFailureKind.Stalled,
            watchdog.onProgressSample(isPlaying = true, positionMs = 100L, nowMs = 5_000L)?.kind,
        )
    }

    // ── 两段式卡顿（softStallTimeoutMs 启用）────────────────────

    private fun twoStageWatchdog(): PlaybackWatchdog = PlaybackWatchdog(
        firstFrameTimeoutMs = 20_000L,
        stallTimeoutMs = 12_000L,
        softStallTimeoutMs = 4_000L,
    )

    @Test
    fun softStallThenHardStallAfterEverMoved() {
        val watchdog = twoStageWatchdog()
        watchdog.onSourceRequested(0L)
        watchdog.onFirstFrameRendered(1_000L)
        // 位置推进过 300ms（>= 默认 250ms epsilon）：hasEverMoved=true，基线 at 2_000。
        assertNull(watchdog.onProgressSample(true, 100L, 1_000L))
        assertNull(watchdog.onProgressSample(true, 400L, 2_000L))
        // 静止到软阈值 4s（距上次推进 2_000 => 6_000）：只报一次 StallSoft。
        assertEquals(
            PlaybackFailureKind.StallSoft,
            watchdog.onProgressSample(true, 400L, 6_000L)?.kind,
        )
        assertNull(watchdog.onProgressSample(true, 400L, 7_000L)) // 不重复报
        // 到硬阈值（距上次推进 2_000 => 14_000）：报 Stalled。
        assertEquals(
            PlaybackFailureKind.Stalled,
            watchdog.onProgressSample(true, 400L, 14_000L)?.kind,
        )
    }

    @Test
    fun hardStallStillFiresAfterSoftSoRealFreezeIsNotDelayed() {
        val watchdog = twoStageWatchdog()
        watchdog.onSourceRequested(0L)
        watchdog.onFirstFrameRendered(1_000L)
        assertNull(watchdog.onProgressSample(true, 100L, 1_000L))
        assertNull(watchdog.onProgressSample(true, 400L, 2_000L))
        // 到软阈值先报 StallSoft（只提示一次）。
        assertEquals(
            PlaybackFailureKind.StallSoft,
            watchdog.onProgressSample(true, 400L, 6_000L)?.kind,
        )
        // 关键不变量：软段只提示一次，硬阈值到仍必报 Stalled 触发恢复——
        // 真卡死不会被两段式无限延迟。
        var hard: PlaybackFailureKind? = null
        for (t in 7_000L..14_000L step 1_000L) {
            watchdog.onProgressSample(true, 400L, t)?.let { hard = it.kind }
        }
        assertEquals(PlaybackFailureKind.Stalled, hard)
    }

    @Test
    fun renderedFramesReconcilePreventsFalseStall() {
        val watchdog = twoStageWatchdog()
        watchdog.onSourceRequested(0L)
        watchdog.onFirstFrameRendered(1_000L)
        assertNull(watchdog.onProgressSample(true, 100L, 1_000L))
        // 位置不动，但每个 250ms 采样点渲染帧 +10：在 1s 滚动窗口内达标，不报软/硬卡顿。
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 100L, 1_250L, renderedFrames = 10)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 100L, 1_500L, renderedFrames = 20)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 100L, 1_750L, renderedFrames = 30)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 100L, 2_000L, renderedFrames = 40)))
    }

    @Test
    fun slowlyAccumulatedFramesWithinWindowStillCountAsProgress() {
        val watchdog = twoStageWatchdog()
        watchdog.onSourceRequested(0L)
        watchdog.onFirstFrameRendered(1_000L)
        assertNull(watchdog.onProgressSample(true, 100L, 1_000L))
        // 先让位置真正推进一次，打开软卡顿判定（hasEverMoved=true），基线落在 1_100L。
        assertNull(watchdog.onProgressSample(true, 400L, 1_100L))
        // 每 200ms 仅 +3 帧：旧实现每次采样都滚动帧基线，窗口内永远凑不够 10 帧，
        // 会在 5_100L（距 1_100L 满 4s）误报 StallSoft；新实现在 2_000L 完成对账。
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 1_200L, renderedFrames = 3)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 1_400L, renderedFrames = 6)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 1_600L, renderedFrames = 9)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 1_800L, renderedFrames = 12)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 2_000L, renderedFrames = 15)))
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 5_100L, renderedFrames = 15)))
        // 对账后的 4s 才是真正的软卡顿起点：6_000L 应准时报一次。
        assertEquals(
            PlaybackFailureKind.StallSoft,
            watchdog.onProgressSample(PlaybackProgressSample(true, 400L, 6_000L, renderedFrames = 15))?.kind,
        )
    }

    @Test
    fun bufferedSlackDefersStallReport() {
        val watchdog = twoStageWatchdog()
        watchdog.onSourceRequested(0L)
        watchdog.onFirstFrameRendered(1_000L)
        assertNull(watchdog.onProgressSample(true, 100L, 1_000L))
        // 位置未推进但缓冲充足，且距上次推进 <=2s：视为正常缓冲，暂不报。
        assertNull(watchdog.onProgressSample(PlaybackProgressSample(true, 100L, 2_500L, bufferedAheadMs = 3_000L)))
    }
}
