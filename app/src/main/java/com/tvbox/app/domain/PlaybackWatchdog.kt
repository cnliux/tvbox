package com.tvbox.app.domain

/** 播放失败分类：UI 文案与日志各自对应，避免"转圈到天荒地老"。 */
enum class PlaybackFailureKind {
    /** 取流失败：建立数据源/连接阶段就报错。 */
    SourceFailed,

    /** 已建源但始终没有渲染出首帧（IPTV 盒子上常见的"连接成功但无画面"）。 */
    FirstFrameTimeout,

    /** 已经出过首帧，之后进度卡死或长时间缓冲。 */
    Stalled,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val elapsedMs: Long,
)

/**
 * 共享播放看门狗：覆盖「取流/建源 → 首帧」整段，以及「出帧后卡死」两个阶段。
 *
 * 纯状态机，不持有播放器；页面把播放器回调折算成 [onSourceRequested]/[onFirstFrameRendered]/
 * [onProgressSample]，由 UI 侧定时调用 [checkFirstFrame] 判定是否超时。
 */
class PlaybackWatchdog(
    private val firstFrameTimeoutMs: Long = DEFAULT_FIRST_FRAME_TIMEOUT_MS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {
    private var sourceRequestedAtMs: Long? = null
    private var firstFrameAtMs: Long? = null
    private var lastPositionMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var firstFrameIssueReported = false
    private var stallIssueReported = false

    /** 是否已经渲染过至少一帧。 */
    val hasFirstFrame: Boolean get() = firstFrameAtMs != null

    /** 最近一次「建源 → 首帧」耗时；未开始取流或尚未出帧时为 null。 */
    val firstFrameElapsedMs: Long?
        get() {
            val requestedAt = sourceRequestedAtMs ?: return null
            val firstFrameAt = firstFrameAtMs ?: return null
            return (firstFrameAt - requestedAt).coerceAtLeast(0L)
        }

    /** 新的取流/建源开始：重置整段计时，包括首帧与出帧后的卡死判定。 */
    fun onSourceRequested(nowMs: Long) {
        sourceRequestedAtMs = nowMs
        firstFrameAtMs = null
        lastPositionMs = null
        lastProgressAtMs = null
        firstFrameIssueReported = false
        stallIssueReported = false
    }

    /** 首帧真正渲染：此后改由 [onProgressSample] 负责卡死判定。 */
    fun onFirstFrameRendered(nowMs: Long) {
        firstFrameAtMs = nowMs
        lastProgressAtMs = nowMs
        lastPositionMs = null
        firstFrameIssueReported = false
        stallIssueReported = false
    }

    /** 暂停/退后台：清空进度基线，恢复后重新开始判定，避免把暂停时长算成卡死。 */
    fun onPaused() {
        lastPositionMs = null
        lastProgressAtMs = null
    }

    /**
     * 首帧超时判定：从 [onSourceRequested] 起算，只有真正播放意图为 true 时才计时。
     * 返回非空表示应进入失败/兜底状态；同一轮取流只报告一次。
     */
    fun checkFirstFrame(nowMs: Long, playWhenReady: Boolean): PlaybackFailure? {
        if (!playWhenReady || firstFrameAtMs != null || firstFrameIssueReported) return null
        val startedAt = sourceRequestedAtMs ?: return null
        val elapsed = nowMs - startedAt
        if (elapsed < firstFrameTimeoutMs) return null
        firstFrameIssueReported = true
        return PlaybackFailure(PlaybackFailureKind.FirstFrameTimeout, elapsed)
    }

    /**
     * 出帧后的进度采样：位置停滞超过 [stallTimeoutMs] 判定卡死；
     * 未出首帧时不做此事（交给 [checkFirstFrame]），避免和起播慢重复触发。
     */
    fun onProgressSample(isPlaying: Boolean, positionMs: Long, nowMs: Long): PlaybackFailure? {
        if (firstFrameAtMs == null) return null
        if (!isPlaying) {
            onPaused()
            return null
        }
        val previousPosition = lastPositionMs
        if (previousPosition == null || previousPosition != positionMs) {
            lastPositionMs = positionMs
            lastProgressAtMs = nowMs
            stallIssueReported = false
            return null
        }
        val lastProgressAt = lastProgressAtMs ?: nowMs.also { lastProgressAtMs = it }
        if (!stallIssueReported && nowMs - lastProgressAt >= stallTimeoutMs) {
            stallIssueReported = true
            return PlaybackFailure(PlaybackFailureKind.Stalled, nowMs - lastProgressAt)
        }
        return null
    }

    companion object {
        /** 与平台直播既有首帧超时保持一致：12 秒仍无画面即进入兜底。 */
        const val DEFAULT_FIRST_FRAME_TIMEOUT_MS = 12_000L

        /** 出帧后 4 秒位置未推进视为卡死（沿用原电视直播看门狗阈值）。 */
        const val DEFAULT_STALL_TIMEOUT_MS = 4_000L
    }
}
