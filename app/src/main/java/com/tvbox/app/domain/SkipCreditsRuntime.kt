package com.tvbox.app.domain

/**
 * 跳过片头/片尾的运行层（第 221–230 轮）。
 *
 * `resolveSkipCredits` 是单次判定纯函数；播放器每 250ms 采样一次，若直接执行会
 * 在片头区间内反复 seek、在片尾区间内反复触发下一集。这里补一层「每集只触发一次」
 * 的运行状态，切集时由调用方显式 reset。默认配置仍是关闭，不改变既有体验。
 */

data class SkipCreditsRuntimeState(
    val introSkipped: Boolean = false,
    val outroTriggered: Boolean = false,
)

fun resetSkipCreditsRuntime(): SkipCreditsRuntimeState = SkipCreditsRuntimeState()

/**
 * @return 本次应执行的动作与新状态；null 动作表示本帧不干预播放器。
 */
fun advanceSkipCredits(
    state: SkipCreditsRuntimeState,
    positionMs: Long,
    durationMs: Long,
    config: SkipCreditsConfig,
): Pair<SkipCreditsDecision?, SkipCreditsRuntimeState> {
    val decision = resolveSkipCredits(positionMs, durationMs, config) ?: return null to state
    return when (decision.action) {
        SkipCreditsAction.SeekToIntro -> if (state.introSkipped) {
            null to state
        } else {
            decision to state.copy(introSkipped = true)
        }
        SkipCreditsAction.PlayNextEpisode -> if (state.outroTriggered) {
            null to state
        } else {
            decision to state.copy(outroTriggered = true)
        }
    }
}

/** 操作栏开关下方的说明文案，明确两条规则与「默认关闭」。 */
fun skipCreditsHint(config: SkipCreditsConfig): String = if (!config.enabled) {
    "跳过片头/片尾：关闭"
} else {
    listOfNotNull(
        config.introSeconds.takeIf { it > 0 }?.let { "片头 $it 秒" },
        config.outroSeconds.takeIf { it > 0 }?.let { "片尾 $it 秒" },
    ).ifEmpty { listOf("未设置秒数") }.joinToString(" · ", prefix = "已开启 · ")
}
