package com.tvbox.app.domain

import java.util.ArrayDeque

enum class SlowBufferReason {
    StartupTooLong,
    ContinuousBufferTooLong,
    FrequentBuffering,
    CumulativeBufferTooLong,
}

data class PlaybackBufferDecision(
    val reason: SlowBufferReason,
)

data class PlaybackBufferReadyResult(
    val decision: PlaybackBufferDecision? = null,
    val shouldRecordPlaybackSuccess: Boolean = false,
)

class PlaybackBufferMonitor(
    private val continuousBufferThresholdMs: Long = DEFAULT_CONTINUOUS_BUFFER_THRESHOLD_MS,
    private val seekGraceMs: Long = DEFAULT_SEEK_GRACE_MS,
    private val frequentBufferWindowMs: Long = DEFAULT_FREQUENT_BUFFER_WINDOW_MS,
    private val frequentBufferCount: Int = DEFAULT_FREQUENT_BUFFER_COUNT,
    private val cumulativeBufferThresholdMs: Long = DEFAULT_CUMULATIVE_BUFFER_THRESHOLD_MS,
) {
    private val completedBuffers = ArrayDeque<CompletedBuffer>()
    private var hasReachedReady = false
    private var currentBufferStartedAtMs: Long? = null
    private var currentBufferFromSeek = false
    private var currentBufferIsStartup = false
    private var currentBufferIssueReported = false
    private var lastSeekAtMs: Long? = null
    private var seekCooling = false
    /** seekCooling 的启动时间；若持续冷却超过阈值仍未 onReady/onMediaChanged/onPlaybackFailed，强制释放，避免永久屏蔽后续缓冲判定。 */
    private var seekCoolingSinceMs: Long? = null

    fun onMediaChanged() {
        completedBuffers.clear()
        hasReachedReady = false
        currentBufferStartedAtMs = null
        currentBufferFromSeek = false
        currentBufferIsStartup = false
        currentBufferIssueReported = false
        lastSeekAtMs = null
        resetSeekCoolingLocked()
    }

    fun onSeekStarted(nowMs: Long) {
        lastSeekAtMs = nowMs
        seekCooling = true
        seekCoolingSinceMs = nowMs
        currentBufferStartedAtMs = null
        currentBufferFromSeek = false
        currentBufferIsStartup = false
        currentBufferIssueReported = false
    }

    fun onPaused() {
        currentBufferStartedAtMs = null
        currentBufferFromSeek = false
        currentBufferIsStartup = false
        currentBufferIssueReported = false
        // 暂停期间若 seekCooling 持续过久，强制释放：用户恢复播放后不应继续受上一次 seek 冷却影响。
        if (seekCooling) resetSeekCoolingLocked()
    }

    /** 当前媒体播放失败（取流/解码报错）时调用：重置缓冲状态机，准备下一次源尝试。 */
    fun onPlaybackFailed() {
        currentBufferStartedAtMs = null
        currentBufferFromSeek = false
        currentBufferIsStartup = false
        currentBufferIssueReported = false
        resetSeekCoolingLocked()
    }

    fun onBuffering(playWhenReady: Boolean, nowMs: Long): PlaybackBufferDecision? {
        if (!playWhenReady) {
            onPaused()
            return null
        }

        if (currentBufferStartedAtMs == null) {
            currentBufferStartedAtMs = nowMs
            currentBufferFromSeek = seekCooling || isWithinSeekGrace(nowMs)
            currentBufferIsStartup = !hasReachedReady
            currentBufferIssueReported = false
        }

        if (seekCooling && nowMs - (seekCoolingSinceMs ?: nowMs) >= SEEK_COOLING_MAX_MS) {
            resetSeekCoolingLocked()
        }
        if (seekCooling) return null

        val startedAtMs = currentBufferStartedAtMs ?: return null
        val durationMs = nowMs - startedAtMs
        if (currentBufferIssueReported) return null
        pruneCompletedBuffers(nowMs)
        val reason = when {
            durationMs >= continuousBufferThresholdMs -> if (currentBufferIsStartup) {
                SlowBufferReason.StartupTooLong
            } else SlowBufferReason.ContinuousBufferTooLong
            !currentBufferFromSeek && completedBuffers.size >= frequentBufferCount -> SlowBufferReason.FrequentBuffering
            !currentBufferFromSeek && completedBuffers.sumOf { it.durationMs } >= cumulativeBufferThresholdMs ->
                SlowBufferReason.CumulativeBufferTooLong
            else -> null
        } ?: return null
        currentBufferIssueReported = true
        return PlaybackBufferDecision(reason)
    }

    fun onReady(playWhenReady: Boolean, nowMs: Long): PlaybackBufferReadyResult {
        val startedAtMs = currentBufferStartedAtMs
        val wasFromSeek = currentBufferFromSeek
        val wasStartup = currentBufferIsStartup
        val issueAlreadyReported = currentBufferIssueReported
        val wasSeekCooling = seekCooling
        currentBufferStartedAtMs = null
        currentBufferFromSeek = false
        currentBufferIsStartup = false
        currentBufferIssueReported = false
        hasReachedReady = true
        resetSeekCoolingLocked()

        if (!playWhenReady) return PlaybackBufferReadyResult()
        if (startedAtMs == null) {
            return PlaybackBufferReadyResult(shouldRecordPlaybackSuccess = true)
        }
        if (issueAlreadyReported) return PlaybackBufferReadyResult()

        if (wasSeekCooling || wasFromSeek) {
            return PlaybackBufferReadyResult(shouldRecordPlaybackSuccess = true)
        }

        val durationMs = nowMs - startedAtMs
        if (wasStartup) {
            return PlaybackBufferReadyResult(shouldRecordPlaybackSuccess = true)
        }

        recordCompletedBuffer(endedAtMs = nowMs, durationMs = durationMs.coerceAtLeast(0L))
        // Do not switch a source that has just recovered. Historical penalties are
        // evaluated only if another buffering interval actually begins.
        return PlaybackBufferReadyResult(shouldRecordPlaybackSuccess = true)
    }

    private fun recordCompletedBuffer(endedAtMs: Long, durationMs: Long) {
        completedBuffers.addLast(CompletedBuffer(endedAtMs = endedAtMs, durationMs = durationMs))
        pruneCompletedBuffers(endedAtMs)
    }

    private fun pruneCompletedBuffers(nowMs: Long) {
        val earliestKeptAtMs = nowMs - frequentBufferWindowMs
        while (completedBuffers.peekFirst()?.endedAtMs?.let { it < earliestKeptAtMs } == true) {
            completedBuffers.removeFirst()
        }
    }

    private fun isWithinSeekGrace(nowMs: Long): Boolean {
        val seekAtMs = lastSeekAtMs ?: return false
        return nowMs - seekAtMs in 0..seekGraceMs
    }

    /** 统一重置 seek 冷却状态（由 onReady / onMediaChanged / 超时兜底共用）。 */
    private fun resetSeekCoolingLocked() {
        seekCooling = false
        seekCoolingSinceMs = null
    }

    private data class CompletedBuffer(
        val endedAtMs: Long,
        val durationMs: Long,
    )

    companion object {
        const val DEFAULT_CONTINUOUS_BUFFER_THRESHOLD_MS = 5_000L
        /** seek 冷却最长持续时长：超出后强制解除冷却，避免流失败/未就绪时永久屏蔽缓冲检测。 */
        const val SEEK_COOLING_MAX_MS = 30_000L
        const val DEFAULT_SEEK_GRACE_MS = 3_000L
        const val DEFAULT_FREQUENT_BUFFER_WINDOW_MS = 60_000L
        const val DEFAULT_FREQUENT_BUFFER_COUNT = 3
        const val DEFAULT_CUMULATIVE_BUFFER_THRESHOLD_MS = 8_000L

        /** pure_live 式缓冲对账阈值：位置推进 >= 1s 且不超过墙钟 +2s，或 1 秒内渲染 >= 10 帧。 */
        const val BUFFER_RECONCILE_POSITION_MS = 1_000L
        const val BUFFER_RECONCILE_SLACK_MS = 2_000L
        const val BUFFER_RECONCILE_FRAMES = 10
        const val BUFFER_RECONCILE_MIN_AHEAD_MS = 1_000L

        /**
         * 缓冲对账（pure_live G02.2）：判断"引擎说不缓冲了但会话仍认为在缓冲"时是否应结束缓冲。
         *
         * @param positionAdvancedMs 位置推进量；>= [BUFFER_RECONCILE_POSITION_MS] 且 <= 墙钟 + slack 才算真实推进
         *   （超过墙钟说明时间戳跳变/seek，不能算）。
         * @param wallElapsedMs 同一窗口的墙钟经过时长。
         * @param renderedFrames 窗口内渲染帧数；>= [BUFFER_RECONCILE_FRAMES] 即视为在动。
         * @param bufferedAheadMs 缓冲前瞻；至少 [BUFFER_RECONCILE_MIN_AHEAD_MS] 才认为有数据支撑。
         */
        fun reconcileBuffering(
            positionAdvancedMs: Long,
            wallElapsedMs: Long,
            renderedFrames: Int,
            bufferedAheadMs: Long,
        ): Boolean {
            if (wallElapsedMs <= 0) return false
            val positionOk = positionAdvancedMs >= BUFFER_RECONCILE_POSITION_MS &&
                positionAdvancedMs <= wallElapsedMs + BUFFER_RECONCILE_SLACK_MS
            val framesOk = renderedFrames >= BUFFER_RECONCILE_FRAMES
            return bufferedAheadMs >= BUFFER_RECONCILE_MIN_AHEAD_MS && (positionOk || framesOk)
        }
    }
}
