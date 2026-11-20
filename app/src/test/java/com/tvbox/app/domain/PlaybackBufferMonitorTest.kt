package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferMonitorTest {
    private fun monitor(): PlaybackBufferMonitor {
        return PlaybackBufferMonitor(
            continuousBufferThresholdMs = 5_000L,
            seekGraceMs = 3_000L,
            frequentBufferWindowMs = 60_000L,
            frequentBufferCount = 3,
            cumulativeBufferThresholdMs = 8_000L,
        )
    }

    @Test
    fun startupBufferUnderThresholdRecordsSuccessWithoutPenalty() {
        val monitor = monitor()

        assertNull(monitor.onBuffering(playWhenReady = true, nowMs = 0L))
        val result = monitor.onReady(playWhenReady = true, nowMs = 3_000L)

        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun startupBufferOverThresholdReportsSlowBuffer() {
        val monitor = monitor()

        assertNull(monitor.onBuffering(playWhenReady = true, nowMs = 0L))
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 5_000L)

        assertEquals(SlowBufferReason.StartupTooLong, decision?.reason)
    }

    @Test
    fun continuousBufferThatHasRecoveredDoesNotTriggerLateSwitch() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 1_000L)

        monitor.onBuffering(playWhenReady = true, nowMs = 10_000L)
        val result = monitor.onReady(playWhenReady = true, nowMs = 15_100L)

        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun frequentShortBuffersSwitchOnlyWhenBufferingAgain() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        repeat(2) { index ->
            val startAt = 10_000L + index * 10_000L
            monitor.onBuffering(playWhenReady = true, nowMs = startAt)
            val result = monitor.onReady(playWhenReady = true, nowMs = startAt + 1_000L)
            assertNull(result.decision)
            assertTrue(result.shouldRecordPlaybackSuccess)
        }
        monitor.onBuffering(playWhenReady = true, nowMs = 30_000L)
        val result = monitor.onReady(playWhenReady = true, nowMs = 31_000L)

        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
        assertEquals(SlowBufferReason.FrequentBuffering,
            monitor.onBuffering(playWhenReady = true, nowMs = 40_000L)?.reason)
    }

    @Test
    fun cumulativeShortBuffersSwitchOnlyWhenBufferingAgain() {
        val monitor = PlaybackBufferMonitor(
            continuousBufferThresholdMs = 5_000L,
            seekGraceMs = 3_000L,
            frequentBufferWindowMs = 60_000L,
            frequentBufferCount = 10,
            cumulativeBufferThresholdMs = 8_000L,
        )
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        monitor.onBuffering(playWhenReady = true, nowMs = 10_000L)
        monitor.onReady(playWhenReady = true, nowMs = 14_000L)
        monitor.onBuffering(playWhenReady = true, nowMs = 20_000L)
        val result = monitor.onReady(playWhenReady = true, nowMs = 24_100L)

        assertNull(result.decision)
        assertEquals(SlowBufferReason.CumulativeBufferTooLong,
            monitor.onBuffering(playWhenReady = true, nowMs = 30_000L)?.reason)
    }

    @Test
    fun shortBufferAfterSeekDoesNotCountAsFrequentBuffering() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        repeat(5) { index ->
            val seekAt = 10_000L + index * 10_000L
            monitor.onSeekStarted(nowMs = seekAt)
            monitor.onBuffering(playWhenReady = true, nowMs = seekAt + 100L)
            val result = monitor.onReady(playWhenReady = true, nowMs = seekAt + 1_000L)
            assertNull(result.decision)
            assertTrue(result.shouldRecordPlaybackSuccess)
        }
    }

    @Test
    fun longBufferAfterSeekIsProtectedUntilReady() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        monitor.onSeekStarted(nowMs = 10_000L)
        monitor.onBuffering(playWhenReady = true, nowMs = 10_100L)
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 15_100L)
        val result = monitor.onReady(playWhenReady = true, nowMs = 18_000L)

        assertNull(decision)
        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun bufferStartedAfterSeekGraceIsStillProtectedUntilReady() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        monitor.onSeekStarted(nowMs = 10_000L)
        monitor.onBuffering(playWhenReady = true, nowMs = 14_000L)
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 20_000L)
        val result = monitor.onReady(playWhenReady = true, nowMs = 21_000L)

        assertNull(decision)
        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun readyAfterSeekEndsCoolingForFutureBuffers() {
        val monitor = monitor()
        monitor.onBuffering(playWhenReady = true, nowMs = 0L)
        monitor.onReady(playWhenReady = true, nowMs = 500L)

        monitor.onSeekStarted(nowMs = 10_000L)
        monitor.onBuffering(playWhenReady = true, nowMs = 10_100L)
        monitor.onReady(playWhenReady = true, nowMs = 11_000L)

        monitor.onBuffering(playWhenReady = true, nowMs = 20_000L)
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 25_000L)

        assertEquals(SlowBufferReason.ContinuousBufferTooLong, decision?.reason)
    }

    @Test
    fun pausedBufferingIsIgnored() {
        val monitor = monitor()

        assertNull(monitor.onBuffering(playWhenReady = false, nowMs = 0L))
        val result = monitor.onReady(playWhenReady = false, nowMs = 10_000L)

        assertNull(result.decision)
        assertFalse(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun startupRecoveredAfterThresholdDoesNotTriggerLateSwitch() {
        val monitor = monitor()
        monitor.onBuffering(true, 0)
        val result = monitor.onReady(true, 6_000)
        assertNull(result.decision)
        assertTrue(result.shouldRecordPlaybackSuccess)
    }

    @Test
    fun alreadyReportedTimeoutIsNotReportedAgainOnRecovery() {
        val monitor = monitor()
        monitor.onBuffering(true, 0)
        assertEquals(SlowBufferReason.StartupTooLong, monitor.onBuffering(true, 5_000)?.reason)
        assertNull(monitor.onBuffering(true, 5_100))
        assertNull(monitor.onReady(true, 6_000).decision)
    }

    @Test
    fun previousShortBuffersExpireBeforeTheNextBuffer() {
        val monitor = monitor()
        monitor.onReady(true, 0)
        repeat(3) { index ->
            val time = (index + 1) * 10_000L
            monitor.onBuffering(true, time)
            monitor.onReady(true, time + 1_000)
        }
        assertNull(monitor.onBuffering(true, 100_000))
    }

    @Test
    fun seekAndPauseDoNotTriggerDeferredFrequentBufferSwitch() {
        val monitor = monitor()
        monitor.onReady(true, 0)
        repeat(3) { index ->
            val time = (index + 1) * 10_000L
            monitor.onBuffering(true, time)
            monitor.onReady(true, time + 1_000)
        }
        assertNull(monitor.onBuffering(false, 32_000))
        monitor.onSeekStarted(33_000)
        assertNull(monitor.onBuffering(true, 34_000))
        assertNull(monitor.onReady(true, 40_000).decision)
    }

    // ── 缓冲对账（pure_live G02.2）──────────────────────────────

    @Test
    fun reconcileBufferingByPositionWhenWithinWallClock() {
        // 位置推进 1.2s，墙钟 1.2s，缓冲充足 → 对账成功。
        assertTrue(PlaybackBufferMonitor.reconcileBuffering(1_200L, 1_200L, 0, 2_000L))
    }

    @Test
    fun reconcileBufferingRejectsTimestampJumpBeyondSlack() {
        // 位置推进 10s 但墙钟只过 1s（seek/时间戳跳变）→ 不算对账成功。
        assertFalse(PlaybackBufferMonitor.reconcileBuffering(10_000L, 1_000L, 0, 3_000L))
    }

    @Test
    fun reconcileBufferingByRenderedFrames() {
        // 位置不动但 1 秒内渲染 10 帧，缓冲充足 → 对账成功。
        assertTrue(PlaybackBufferMonitor.reconcileBuffering(0L, 1_000L, 10, 1_500L))
        // 帧数不足或缓冲不足 → 不成功。
        assertFalse(PlaybackBufferMonitor.reconcileBuffering(0L, 1_000L, 9, 1_500L))
        assertFalse(PlaybackBufferMonitor.reconcileBuffering(0L, 1_000L, 10, 500L))
    }

    @Test
    fun seekCoolingTimesOutWhenNoReadyArrives() {
        // seek 后流失败（无 onReady/onMediaChanged）超过 SEEK_COOLING_MAX_MS，应强制释放冷却状态。
        val monitor = PlaybackBufferMonitor(seekGraceMs = 3_000L)
        monitor.onSeekStarted(nowMs = 0L)
        // 5s 时仍在冷却窗口内，onBuffering 应返回 null（继续屏蔽）。
        assertNull(monitor.onBuffering(playWhenReady = true, nowMs = 5_000L))
        // 35s 时已超过 SEEK_COOLING_MAX_MS（30s），冷却释放，后续缓冲可被检测。
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 35_000L)
        // 此时 buffering 时长为 35_000 - 35_000 = 0（首次进入缓冲段），不会触发连续超时；
        // 但关键是 seekCooling 已被释放，下一次真正超过阈值的缓冲应能报告问题。
        assertNull(decision) // 刚进入缓冲，时长 0
        val secondDecision = monitor.onBuffering(playWhenReady = true, nowMs = 40_000L)
        assertEquals(SlowBufferReason.ContinuousBufferTooLong, secondDecision?.reason)
    }

    @Test
    fun onPlaybackFailedResetsSeekCooling() {
        val monitor = PlaybackBufferMonitor(seekGraceMs = 3_000L)
        monitor.onSeekStarted(nowMs = 0L)
        // 在冷却窗口内调用 onPlaybackFailed 应重置所有缓冲状态。
        monitor.onPlaybackFailed()
        // 重新进缓冲应立即开始计时（不再受冷却影响）。
        monitor.onBuffering(playWhenReady = true, nowMs = 6_000L)
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 11_000L)
        assertEquals(SlowBufferReason.StartupTooLong, decision?.reason)
    }

    @Test
    fun onPausedReleasesStaleSeekCooling() {
        val monitor = PlaybackBufferMonitor(seekGraceMs = 3_000L)
        monitor.onSeekStarted(nowMs = 0L)
        // 用户暂停（seekCooling=true）时，onPaused 会释放冷却。
        monitor.onPaused()
        // 恢复播放后进缓冲不应再受冷却约束。
        monitor.onBuffering(playWhenReady = true, nowMs = 6_000L)
        val decision = monitor.onBuffering(playWhenReady = true, nowMs = 11_000L)
        assertEquals(SlowBufferReason.StartupTooLong, decision?.reason)
    }
}
