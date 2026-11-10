package com.tvbox.app.domain

/**
 * 自动换线的抖动抑制。
 *
 * 现状：播放器在首帧失败时可能连续多次回调「线路异常」，每次都立刻切下一条线路，
 * 结果是几秒内把所有线路轮换一遍，用户看到连续黑屏；而「用户主动切线路」不应被抑制。
 *
 * 这里只做**时间窗判定**，不改变换线策略（仍是逐条递增直到用尽），
 * 第 72 轮的 `decideLineSwitch` 负责三态语义与在途去重，两者可叠加使用。
 */
data class AutoSwitchThrottleState(
    val lastSwitchAtMs: Long = 0L,
    val consecutiveSwitches: Int = 0,
)

sealed interface AutoSwitchGate {
    /** 允许本次自动换线，并推进节流状态。 */
    data class Allow(val nextState: AutoSwitchThrottleState) : AutoSwitchGate

    /** 落在退避窗口内：本次不切线路，由调用方原地重连，避免连续黑屏。 */
    data class Defer(val retryAfterMs: Long) : AutoSwitchGate
}

fun gateAutoSwitch(
    state: AutoSwitchThrottleState,
    nowMs: Long,
    baseBackoffMs: Long = 800L,
    maxBackoffMs: Long = 6_400L,
): AutoSwitchGate {
    require(baseBackoffMs > 0) { "baseBackoffMs must be positive" }
    require(maxBackoffMs >= baseBackoffMs) { "maxBackoffMs must be >= baseBackoffMs" }
    // 首次自动切，或系统时钟回拨 / 测试注入的早期时间：直接放行。
    if (state.lastSwitchAtMs <= 0L || nowMs <= state.lastSwitchAtMs) {
        return AutoSwitchGate.Allow(AutoSwitchThrottleState(lastSwitchAtMs = nowMs, consecutiveSwitches = 1))
    }
    val shift = (state.consecutiveSwitches - 1).coerceIn(0, 6)
    val backoff = (baseBackoffMs shl shift).coerceAtMost(maxBackoffMs)
    val elapsed = nowMs - state.lastSwitchAtMs
    if (elapsed < backoff) {
        return AutoSwitchGate.Defer(retryAfterMs = backoff - elapsed)
    }
    return AutoSwitchGate.Allow(
        AutoSwitchThrottleState(lastSwitchAtMs = nowMs, consecutiveSwitches = state.consecutiveSwitches + 1),
    )
}

/** 用户主动切线路 / 切换频道后清零：手动意图不占用自动换线次数，也不受退避窗口影响。 */
fun resetAutoSwitchThrottle(): AutoSwitchThrottleState = AutoSwitchThrottleState()
