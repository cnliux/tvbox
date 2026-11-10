package com.tvbox.app.domain

/**
 * 播放错误分级 → 自愈动作序列（第 211–220 轮）。
 *
 * `classifyPlaybackError` / `presentPlaybackError` 只给出单次分类与一句提示；
 * 这里补上「按什么顺序重试、最多几次、给用户的可操作建议」，供播放页把
 * 错误信息从「播放失败」升级成可执行的提示，且不改变现有播放管家自动换线流程。
 */

data class PlaybackRecoveryPlan(
    val presentation: PlaybackErrorPresentation,
    val steps: List<PlaybackErrorAction>,
    val userHint: String,
)

fun buildPlaybackRecoveryPlan(
    message: String?,
    httpStatus: Int? = null,
    maxAttempts: Int = 3,
): PlaybackRecoveryPlan {
    val presentation = presentPlaybackError(message, httpStatus)
    val cap = maxAttempts.coerceIn(1, 5)
    val steps: List<PlaybackErrorAction> = when (presentation.action) {
        PlaybackErrorAction.Retry -> List(cap) { PlaybackErrorAction.Retry } + PlaybackErrorAction.SwitchLine
        PlaybackErrorAction.SwitchLine -> listOf(
            PlaybackErrorAction.SwitchLine,
            PlaybackErrorAction.SwitchSource,
        )
        PlaybackErrorAction.SwitchSource -> listOf(PlaybackErrorAction.SwitchSource)
        PlaybackErrorAction.ShowMessage -> emptyList()
    }
    val hint = buildString {
        append(presentation.title)
        append("：")
        append(presentation.message)
        presentation.actionLabel?.let { append(" · 建议：$it") }
    }
    return PlaybackRecoveryPlan(presentation, steps, hint)
}

/**
 * 把分级提示追加到播放器已有错误文案后面；原有文案（错误码映射）保持在前，
 * 避免替换掉「网络超时 / 播放地址无效」等用户已经熟悉的说明。
 */
fun appendPlaybackErrorHint(baseMessage: String, message: String?, httpStatus: Int? = null): String {
    val plan = buildPlaybackRecoveryPlan(message, httpStatus)
    // 无法归类时不追加重复的「播放失败」，避免把已有错误码文案变成长句。
    if (plan.presentation.kind == PlaybackErrorKind.Unknown) return baseMessage
    return if (plan.userHint.isBlank() || baseMessage.contains(plan.userHint)) {
        baseMessage
    } else {
        "$baseMessage（${plan.userHint}）"
    }
}
