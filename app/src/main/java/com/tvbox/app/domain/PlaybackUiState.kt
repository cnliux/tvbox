package com.tvbox.app.domain

/**
 * 播放页状态层（第 84/86/110/111/112/151/152/153 轮）。
 * 跳过片头片尾配置、长按时间轴提示、播放错误的可操作提示都抽成纯 Kotlin，
 * 配置默认关闭，不改变既有遥控器与手势语义；UI 只消费这里的状态。
 */

private const val SKIP_CREDITS_PREFS_VERSION = "v1"
private const val MAX_SKIP_SECONDS = 600

data class SkipCreditsPreferences(
    val enabled: Boolean = false,
    val introSeconds: Int = 0,
    val outroSeconds: Int = 0,
) {
    fun toConfig(): SkipCreditsConfig = SkipCreditsConfig(
        enabled = enabled,
        introSeconds = introSeconds.coerceIn(0, MAX_SKIP_SECONDS),
        outroSeconds = outroSeconds.coerceIn(0, MAX_SKIP_SECONDS),
    )
}

/** SharedPreferences 可存的单行编码；版本不匹配或字段损坏时回到「默认关闭」。 */
fun encodeSkipCreditsPreferences(preferences: SkipCreditsPreferences): String = listOf(
    SKIP_CREDITS_PREFS_VERSION,
    if (preferences.enabled) "1" else "0",
    preferences.introSeconds.coerceIn(0, MAX_SKIP_SECONDS),
    preferences.outroSeconds.coerceIn(0, MAX_SKIP_SECONDS),
).joinToString("|")

fun decodeSkipCreditsPreferences(raw: String?): SkipCreditsPreferences {
    if (raw.isNullOrBlank()) return SkipCreditsPreferences()
    val parts = raw.split('|')
    if (parts.size < 4 || parts[0] != SKIP_CREDITS_PREFS_VERSION) return SkipCreditsPreferences()
    val intro = parts[2].toIntOrNull() ?: return SkipCreditsPreferences()
    val outro = parts[3].toIntOrNull() ?: return SkipCreditsPreferences()
    return SkipCreditsPreferences(
        enabled = parts[1] == "1",
        introSeconds = intro.coerceIn(0, MAX_SKIP_SECONDS),
        outroSeconds = outro.coerceIn(0, MAX_SKIP_SECONDS),
    )
}

/** 长按加速度的时间轴提示：显示当前步进档位与预计落点，不引入额外动画库。 */
data class SeekTimelineHint(
    val stepSeconds: Int,
    val targetSeconds: Int,
    val accelerated: Boolean,
)

fun seekTimelineHint(
    currentPositionMs: Long,
    durationMs: Long,
    heldMs: Long,
    forward: Boolean,
): SeekTimelineHint {
    val firstStep = DEFAULT_SEEK_TIERS.first().stepSeconds
    val deltaMs = seekStepMsForHold(heldMs) * if (forward) 1L else -1L
    val stepSeconds = (kotlin.math.abs(deltaMs) / 1_000L).toInt()
    val upperBoundMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    val targetMs = (currentPositionMs.coerceAtLeast(0L) + deltaMs).coerceIn(0L, upperBoundMs)
    return SeekTimelineHint(
        stepSeconds = stepSeconds,
        targetSeconds = (targetMs / 1_000L).toInt(),
        accelerated = stepSeconds > firstStep,
    )
}

/** 错误分级后的可操作提示；action 交由播放页映射到「重试 / 换线路 / 换来源」。 */
data class PlaybackErrorPresentation(
    val kind: PlaybackErrorKind,
    val title: String,
    val message: String,
    val actionLabel: String?,
    val action: PlaybackErrorAction,
)

fun presentPlaybackError(message: String?, httpStatus: Int? = null): PlaybackErrorPresentation {
    val decision = classifyPlaybackError(message, httpStatus)
    val (title, actionLabel) = when (decision.kind) {
        PlaybackErrorKind.Network -> "网络异常" to "重试"
        PlaybackErrorKind.Decoder -> "解码失败" to "切换线路"
        PlaybackErrorKind.SourceInvalid -> "播放源失效" to "切换来源"
        PlaybackErrorKind.Unknown -> "播放失败" to null
    }
    return PlaybackErrorPresentation(
        kind = decision.kind,
        title = title,
        message = decision.userMessage,
        actionLabel = if (decision.action == PlaybackErrorAction.ShowMessage) null else actionLabel,
        action = decision.action,
    )
}
