package com.tvbox.app.domain

/**
 * T2-16：探活冷却与「只读不改判」闸门。
 *
 * 三处调用方各自探测会把活源自己判死；这里把冷却窗口、冷启动探测上限、
 * 以及「DEAD 必须由用户真实播放失败 ≥2 次才能落章」收敛为纯状态机。
 * 探测失败只降级到 SLOW 并降低 confidence，绝不直接落 DEAD。
 */
object ProbeCooldown {

    const val DEAD_COOLDOWN_MS: Long = 15L * 60 * 1_000L
    const val SLOW_COOLDOWN_MS: Long = 5L * 60 * 1_000L
    const val MAX_COLD_START_PROBES: Int = 3
    const val DEAD_USER_FAILURE_THRESHOLD: Int = 2

    enum class Grade { ALIVE, SLOW, DEAD }

    data class State(
        val grade: Grade = Grade.ALIVE,
        val gradedAtMs: Long = 0L,
        val userFailures: Int = 0,
    )

    fun cooldownMs(grade: Grade): Long = when (grade) {
        Grade.ALIVE -> 0L
        Grade.SLOW -> SLOW_COOLDOWN_MS
        Grade.DEAD -> DEAD_COOLDOWN_MS
    }

    /** 单调时钟（Clocks.mono）差值；时钟回拨时视为仍在冷却，fail closed。 */
    fun isCoolingDown(state: State?, nowMs: Long): Boolean {
        if (state == null) return false
        val elapsed = nowMs - state.gradedAtMs
        return elapsed < cooldownMs(state.grade)
    }

    fun shouldProbe(state: State?, nowMs: Long): Boolean = !isCoolingDown(state, nowMs)

    /** 探测失败：最多降到 SLOW，并把用户失败计数清零重来。 */
    fun onProbeFailure(state: State?, nowMs: Long): State = State(
        grade = Grade.SLOW,
        gradedAtMs = nowMs,
        userFailures = 0,
    )

    /** 探测成功：回到 ALIVE，但保留用户失败计数（用户投票不会因一次探活成功抹掉）。 */
    fun onProbeSuccess(state: State?, nowMs: Long): State = State(
        grade = Grade.ALIVE,
        gradedAtMs = nowMs,
        userFailures = state?.userFailures ?: 0,
    )

    /** 用户真实播放失败累计到阈值才落 DEAD；未到阈值只降 confidence，不改级。 */
    fun onUserPlaybackFailure(state: State?, nowMs: Long): State {
        val failures = (state?.userFailures ?: 0) + 1
        return if (failures >= DEAD_USER_FAILURE_THRESHOLD) {
            State(Grade.DEAD, nowMs, failures)
        } else {
            State(state?.grade ?: Grade.ALIVE, state?.gradedAtMs ?: nowMs, failures)
        }
    }

    /** 首页冷启动最多探 3 个，其余懒探（滚到才探）。 */
    fun coldStartBudget(totalSources: Int): Int = minOf(totalSources.coerceAtLeast(0), MAX_COLD_START_PROBES)

    /** 可观测计数：本周主动少打了多少次请求。 */
    fun suppressedRequests(requested: Int, allowed: Int): Int =
        (requested - allowed).coerceAtLeast(0)
}
