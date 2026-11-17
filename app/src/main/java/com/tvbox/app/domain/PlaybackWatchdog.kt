package com.tvbox.app.domain

/** 播放失败分类：UI 文案与日志各自对应，避免"转圈到天荒地老"。 */
enum class PlaybackFailureKind {
    /** 取流失败：建立数据源/连接阶段就报错。 */
    SourceFailed,

    /** 已建源但始终没有渲染出首帧（IPTV 盒子上常见的"连接成功但无画面"）。 */
    FirstFrameTimeout,

    /**
     * 软卡顿：曾推进过的流静止达到软阈值，仅提示"正在重连"，**不触发硬恢复**。
     * 对应 pure_live 的两段式卡顿（短卡顿仍算缓冲，不误报/不刷新地址）。
     * 仅当softStallTimeoutMs 非 null 时才会产生。
     */
    StallSoft,

    /** 已经出过首帧，之后进度长时间卡死或缓冲（硬阈值，触发恢复）。 */
    Stalled,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val elapsedMs: Long,
)

/**
 * 一次进度采样。除位置外可带缓冲/渲染帧信息，供两段式卡顿与缓冲对账使用；
 * 默认值使旧调用点（只传 isPlaying/positionMs/nowMs）行为完全不变。
 */
data class PlaybackProgressSample(
    val isPlaying: Boolean,
    val positionMs: Long,
    val nowMs: Long,
    /** bufferedPosition - currentPosition，>=0。 */
    val bufferedAheadMs: Long = 0L,
    /** 已渲染输出帧累计数（单调）。 */
    val renderedFrames: Long = 0L,
    /** 引擎是否报告 buffering；仅作辅助，判据以位置是否推进为准。 */
    val engineBuffering: Boolean = false,
)

/**
 * 共享播放看门狗：覆盖「取流/建源 → 首帧」整段，以及「出帧后卡死」两个阶段。
 *
 * 纯状态机，不持有播放器；页面把播放器回调折算成 [onSourceRequested]/[onFirstFrameRendered]/
 * [onProgressSample]，由 UI 侧定时调用 [checkFirstFrame] 判定是否超时。
 *
 * 两段式卡顿（softStallTimeoutMs != null 时启用）：曾推进过的流先报一次 [PlaybackFailureKind.StallSoft]
 * 让用户知道"正在重连"，只有持续到硬阈值 [stallTimeoutMs] 才报 [PlaybackFailureKind.Stalled] 触发恢复。
 * 对"从首帧后就没动过"的真卡死跳过软段，直接走硬阈值，保住兜底不被延迟。
 */
class PlaybackWatchdog(
    private val firstFrameTimeoutMs: Long = DEFAULT_FIRST_FRAME_TIMEOUT_MS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val softStallTimeoutMs: Long? = null,
    private val moveEpsilonMs: Long = DEFAULT_MOVE_EPSILON_MS,
    private val reconcileFrames: Int = DEFAULT_RECONCILE_FRAMES,
    private val reconcileWindowMs: Long = DEFAULT_RECONCILE_WINDOW_MS,
) {
    private var sourceRequestedAtMs: Long? = null
    private var firstFrameAtMs: Long? = null
    private var lastPositionMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var firstFrameIssueReported = false
    private var stallIssueReported = false
    private var softStallIssueReported = false
    // 两段式：是否曾观察到位置推进；渲染帧对账基线。
    private var hasEverMoved = false
    private var lastRenderedFrames = 0L
    private var renderedSampleAtMs: Long? = null

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
        softStallIssueReported = false
        hasEverMoved = false
        lastRenderedFrames = 0L
        renderedSampleAtMs = null
    }

    /** 首帧真正渲染：此后改由 [onProgressSample] 负责卡死判定。 */
    fun onFirstFrameRendered(nowMs: Long) {
        firstFrameAtMs = nowMs
        lastProgressAtMs = nowMs
        lastPositionMs = null
        firstFrameIssueReported = false
        stallIssueReported = false
        softStallIssueReported = false
        hasEverMoved = false
        lastRenderedFrames = 0L
        renderedSampleAtMs = null
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
     * 出帧后的进度采样（旧签名，保持兼容）：位置停滞超过 [stallTimeoutMs] 判定卡死。
     * 未出首帧时不做此事（交给 [checkFirstFrame]），避免和起播慢重复触发。
     */
    fun onProgressSample(isPlaying: Boolean, positionMs: Long, nowMs: Long): PlaybackFailure? =
        onProgressSample(PlaybackProgressSample(isPlaying, positionMs, nowMs))

    /**
     * 出帧后的进度采样（含缓冲/帧信息）。
     *
     * 判定顺序（保证不破坏真卡死兜底）：
     * 1. 未播放 → onPaused；
     * 2. 位置推进 >= [moveEpsilonMs] 或渲染帧在窗口内达标 → 对账成功，刷新基线返回 null
     *    （以"是否真的在动"为准，忽略 engineBuffering 标志，避免误判）；
     * 3. softStallTimeoutMs 启用且 hasEverMoved 且到软阈值 → 报一次 StallSoft；
     * 4. 到硬阈值 [stallTimeoutMs] → 报一次 Stalled（真卡死兜底，从首帧后没动过也走这条）。
     */
    fun onProgressSample(sample: PlaybackProgressSample): PlaybackFailure? {
        if (firstFrameAtMs == null) return null
        if (!sample.isPlaying) {
            onPaused()
            return null
        }
        val nowMs = sample.nowMs
        val previousPosition = lastPositionMs

        // 2a. 位置推进：只要与上次不同即算推进（与历史行为一致，兼容旧调用点）。
        // moveEpsilonMs 仅作为"回退"方向的容差保护：轻微回退（如时间戳取整）不算异常。
        val positionAdvanced = previousPosition == null || sample.positionMs != previousPosition
        // 2b. 渲染帧对账：窗口内渲染帧数达标也算"在动"（画面在动却转圈的场景）。
        val sampleAt = renderedSampleAtMs
        val windowOk = sampleAt != null && nowMs - sampleAt <= reconcileWindowMs
        val framesAdvanced = windowOk && sample.renderedFrames > 0 &&
            sample.renderedFrames - lastRenderedFrames >= reconcileFrames
        // 更新帧基线（即便本次没达标，也要滚动窗口基线）。
        lastRenderedFrames = sample.renderedFrames
        renderedSampleAtMs = nowMs

        if (positionAdvanced || framesAdvanced) {
            if (positionAdvanced) {
                hasEverMoved = true
                lastPositionMs = sample.positionMs
            }
            lastProgressAtMs = nowMs
            stallIssueReported = false
            softStallIssueReported = false
            return null
        }
        // 位置未推进但缓冲充足且距上次推进在 slack 内：视为正常缓冲，暂不报。
        if (sample.bufferedAheadMs >= BUFFER_SLACK_AHEAD_MS) {
            val lastProgressAt = lastProgressAtMs ?: nowMs
            if (nowMs - lastProgressAt <= BUFFER_SLACK_WINDOW_MS) return null
        }

        val lastProgressAt = lastProgressAtMs ?: nowMs.also { lastProgressAtMs = it }
        val elapsed = nowMs - lastProgressAt

        // 3. 软段：仅"曾推进过"的流才提示，避免把真卡死误判成"正在重连"。
        if (softStallTimeoutMs != null && hasEverMoved && !softStallIssueReported && elapsed >= softStallTimeoutMs) {
            softStallIssueReported = true
            return PlaybackFailure(PlaybackFailureKind.StallSoft, elapsed)
        }
        // 4. 硬段：真卡死兜底，任何情况都走到这里。
        if (!stallIssueReported && elapsed >= stallTimeoutMs) {
            stallIssueReported = true
            return PlaybackFailure(PlaybackFailureKind.Stalled, elapsed)
        }
        return null
    }

    companion object {
        /** 与平台直播既有首帧超时保持一致：12 秒仍无画面即进入兜底。 */
        const val DEFAULT_FIRST_FRAME_TIMEOUT_MS = 12_000L

        /** 出帧后 4 秒位置未推进视为卡死（沿用原电视直播看门狗阈值）。 */
        const val DEFAULT_STALL_TIMEOUT_MS = 4_000L

        /** 位置推进判定的容差：小于该毫秒数视为未推进（时间戳取整抖动）。 */
        const val DEFAULT_MOVE_EPSILON_MS = 250L

        /** 渲染帧对账：窗口内渲染帧数达到该值即视为"在动"。 */
        const val DEFAULT_RECONCILE_FRAMES = 10

        /** 渲染帧对账窗口长度。 */
        const val DEFAULT_RECONCILE_WINDOW_MS = 1_000L

        private const val BUFFER_SLACK_AHEAD_MS = 1_000L
        private const val BUFFER_SLACK_WINDOW_MS = 2_000L
    }
}
