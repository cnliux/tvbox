package com.tvbox.app.domain

/**
 * 平台直播音视频漂移自愈控制器（任务2/3 自适应修复）。
 *
 * 缺口背景：此前平台直播的音视频同步完全依赖 Go 侧 FLV 代理的「音频回拉」，Kotlin 侧
 * 对 drift 无感知；一旦代理没兜住（或直连路径），就会出现「声音画面不同步」且无法自愈。
 *
 * 设计：每秒采样 [audioPositionMs] - [videoPositionMs] 得到 drift。状态机三段：
 * - |drift| < [softDriftMs]：正常，累积连续正常计数以便从告警态平滑回落；
 * - soft ≤ |drift| < hardMs：告警但不动手（代理的音频回拉通常能在小 drift 内自行修好），
 *   避免和代理打架；
 * - |drift| ≥ [hardDriftMs] 且连续 [consecutiveHardSamples] 次：判定为代理没兜住的真漂移，
 *   触发一次强制重同步（UI 侧调用播放器的 seekTo(currentPosition) 走 CLOSEST_SYNC 重新对齐），
 *   同一轮漂移只触发一次，直到漂移回落后才允许再次触发。
 *
 * 纯状态机、不持播放器，便于单元测试覆盖各漂移曲线。
 */
class AvResyncController(
    /** 软阈值：低于该漂移视为正常。 */
    private val softDriftMs: Long = DEFAULT_SOFT_DRIFT_MS,
    /** 硬阈值：连续达到该漂移才触发重同步。 */
    private val hardDriftMs: Long = DEFAULT_HARD_DRIFT_MS,
    /** 连续多少次采样达到硬阈值才动作，避免瞬时抖动误触发。 */
    private val consecutiveHardSamples: Int = DEFAULT_CONSECUTIVE_HARD_SAMPLES,
) {
    private var hardReported = false
    private var hardStreak = 0

    /** 最近一次采样的漂移毫秒（音频 - 视频），用于 UI/日志观测。 */
    var lastDriftMs: Long = 0L
        private set

    /** 是否处于「已判定漂移并触发过重同步、等待回落」的状态。 */
    val isResyncing: Boolean get() = hardReported

    /**
     * 采一次音视频位置。返回 true 表示本采样应触发一次强制重同步。
     * 播放未开始（audio 或 video 位置为负）时忽略。
     */
    fun onSample(audioPositionMs: Long, videoPositionMs: Long): Boolean {
        if (audioPositionMs < 0 || videoPositionMs < 0) {
            lastDriftMs = 0L
            return false
        }
        val drift = (audioPositionMs - videoPositionMs)
        lastDriftMs = drift
        val abs = kotlin.math.abs(drift)
        if (abs < softDriftMs) {
            // 回到正常：完全复位，允许下一轮漂移再次触发。
            hardStreak = 0
            hardReported = false
            return false
        }
        if (abs >= hardDriftMs) {
            hardStreak++
            if (hardStreak >= consecutiveHardSamples && !hardReported) {
                hardReported = true
                return true
            }
        } else {
            // 介于软硬之间：告警带，不累积也不触发（交给代理的音频回拉自愈）。
            hardStreak = 0
        }
        return false
    }

    /** 换流/重连后调用：清空漂移基线，避免把换流瞬间的跳变算成漂移。 */
    fun reset() {
        hardStreak = 0
        hardReported = false
        lastDriftMs = 0L
    }

    companion object {
        /** 软阈值 500ms：低于此视为同步正常。 */
        const val DEFAULT_SOFT_DRIFT_MS = 500L
        /** 硬阈值 1.5s：连续达到才强制重对齐。 */
        const val DEFAULT_HARD_DRIFT_MS = 1_500L
        /** 连续采样次数：3 秒（配合 1s 采样周期）。 */
        const val DEFAULT_CONSECUTIVE_HARD_SAMPLES = 3
    }
}
