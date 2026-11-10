package com.tvbox.app.domain

/**
 * 断点续播的进度取值与回写策略（纯函数，便于单测）。
 *
 * 解决三个边界：
 * - 切来源 / 备用线路补齐时，新播放器尚未 prepare 会报告 position=0，不能把进度清零；
 * - 新来源时长比旧来源短时，直接按旧进度 seek 会落到片尾并触发自动下一集；
 * - 进度回写过于频繁会反复写 DataStore，需要按位移与时间双重节流。
 */

/** 保留片尾 2% 余量，避免恢复位置紧贴结尾触发「自动跳下一集」。 */
private const val TAIL_GUARD_NUMERATOR = 98L
private const val TAIL_GUARD_DENOMINATOR = 100L

data class ResumePosition(
    val positionMs: Long,
    val clampedToDuration: Boolean,
)

/**
 * @param savedPositionMs 历史记录里的进度
 * @param playerPositionMs 当前播放器报告的进度（未 prepare 时通常为 0）
 * @param durationMs 新来源的时长，<=0 表示未知
 * @param isPrepared 播放器是否已 prepare（未 prepare 时 playerPositionMs 不可信）
 * @param sourceChanged 是否刚切换过来源 / 线路
 */
fun resolveResumePosition(
    savedPositionMs: Long,
    playerPositionMs: Long,
    durationMs: Long,
    isPrepared: Boolean,
    sourceChanged: Boolean,
): ResumePosition {
    val saved = savedPositionMs.coerceAtLeast(0L)
    val player = if (isPrepared) playerPositionMs.coerceAtLeast(0L) else 0L
    // 已 prepare 且播放器明确给出进度时优先用播放器的值；否则退回历史记录。
    var candidate = if (player > 0L) player else saved
    if (durationMs <= 0L) return ResumePosition(candidate, clampedToDuration = false)

    val tailGuard = durationMs * TAIL_GUARD_NUMERATOR / TAIL_GUARD_DENOMINATOR
    // 切来源时新片长可能更短：必须 clamp，否则 seek 到片尾。
    val upperBound = if (sourceChanged) tailGuard else durationMs.coerceAtLeast(0L)
    val clamped = candidate > upperBound
    return ResumePosition(candidate.coerceAtMost(upperBound.coerceAtLeast(0L)), clamped)
}

/**
 * 进度回写节流：位移超过 [minDeltaMs] 立即回写；
 * 位移不大但距上次回写超过 [minIntervalMs] 也回写一次（保证退出时不会丢太多进度）。
 */
fun shouldPersistProgress(
    lastPersistedMs: Long,
    candidateMs: Long,
    nowMs: Long,
    lastPersistAtMs: Long,
    minDeltaMs: Long = 5_000L,
    minIntervalMs: Long = 10_000L,
): Boolean {
    if (candidateMs < 0L) return false
    val delta = kotlin.math.abs(candidateMs - lastPersistedMs.coerceAtLeast(0L))
    if (delta >= minDeltaMs) return true
    if (candidateMs == lastPersistedMs) return false
    return nowMs - lastPersistAtMs >= minIntervalMs
}

/** 失败回退时保留两者中更大且有效的位置，避免重试把进度冲掉。 */
fun positionForRetry(savedPositionMs: Long, playerPositionMs: Long): Long =
    maxOf(savedPositionMs.coerceAtLeast(0L), playerPositionMs.coerceAtLeast(0L))
