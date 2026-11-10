package com.tvbox.app.domain

/**
 * 播放器增强的纯逻辑层：不依赖 Android / Media3，可在 JVM 单测中直接覆盖。
 *
 * 这里只提供判定与计算，不持有状态、不触发播放器动作；UI 接入前不改变任何既有行为。
 */

// ---------------------------------------------------------------- 长按快进/快退

data class SeekTier(val minHoldMs: Long, val stepSeconds: Int)

/** 按住越久步进越大；首档与现有遥控器单次 10 秒语义一致。 */
val DEFAULT_SEEK_TIERS: List<SeekTier> = listOf(
    SeekTier(0L, 10),
    SeekTier(1_500L, 30),
    SeekTier(3_500L, 60),
)

data class SeekStepState(
    val stepSeconds: Int,
    /** 距离进入下一档还剩多久；已在最高档时为 null。 */
    val nextTierInMs: Long?,
    val nextStepSeconds: Int?,
)

/** 按当前按住时长解析步进值与「下一档」提示，供操作栏时间轴显示。 */
fun resolveSeekStep(
    holdDurationMs: Long,
    tiers: List<SeekTier> = DEFAULT_SEEK_TIERS,
): SeekStepState {
    require(tiers.isNotEmpty()) { "tiers must not be empty" }
    require(tiers.first().minHoldMs == 0L) { "first tier must start at 0ms" }
    require(tiers.zipWithNext().all { (a, b) -> b.minHoldMs > a.minHoldMs }) {
        "tiers must be strictly sorted by minHoldMs"
    }
    val held = holdDurationMs.coerceAtLeast(0L)
    val index = tiers.indexOfLast { held >= it.minHoldMs }.coerceAtLeast(0)
    val current = tiers[index]
    val next = tiers.getOrNull(index + 1)
    return SeekStepState(
        stepSeconds = current.stepSeconds,
        nextTierInMs = next?.let { (it.minHoldMs - held).coerceAtLeast(0L) },
        nextStepSeconds = next?.stepSeconds,
    )
}

/** 遥控器长按连发时，把「按住时长」直接换算成毫秒步长，供 UI 层调用。 */
fun seekStepMsForHold(
    holdDurationMs: Long,
    tiers: List<SeekTier> = DEFAULT_SEEK_TIERS,
): Long = resolveSeekStep(holdDurationMs, tiers).stepSeconds * 1_000L

// ---------------------------------------------------------------- 跳过片头/片尾

/** 默认关闭，不改变既有用户体验；具体持久化由设置层负责。 */
data class SkipCreditsConfig(
    val enabled: Boolean = false,
    val introSeconds: Int = 0,
    val outroSeconds: Int = 0,
)

enum class SkipCreditsAction { SeekToIntro, PlayNextEpisode }

data class SkipCreditsDecision(val action: SkipCreditsAction, val targetPositionMs: Long)

/**
 * 根据播放位置与单集时长判断是否应跳过片头或片尾。
 * 返回 null 表示不动作（含配置关闭、时长未知、片头片尾区间重叠等边界）。
 */
fun resolveSkipCredits(
    positionMs: Long,
    durationMs: Long,
    config: SkipCreditsConfig,
): SkipCreditsDecision? {
    if (!config.enabled) return null
    if (durationMs <= 0L) return null
    val introMs = config.introSeconds.coerceAtLeast(0) * 1_000L
    val outroMs = config.outroSeconds.coerceAtLeast(0) * 1_000L
    if (introMs <= 0L && outroMs <= 0L) return null
    // 片头与片尾区间重叠时宁可不动作，避免在片头/片尾之间来回跳。
    if (introMs + outroMs >= durationMs) return null
    val position = positionMs.coerceAtLeast(0L).coerceAtMost(durationMs)
    if (introMs > 0L && position < introMs) {
        return SkipCreditsDecision(SkipCreditsAction.SeekToIntro, introMs)
    }
    if (outroMs > 0L && position >= durationMs - outroMs) {
        return SkipCreditsDecision(SkipCreditsAction.PlayNextEpisode, 0L)
    }
    return null
}

// ---------------------------------------------------------------- 错误分级与自愈

enum class PlaybackErrorKind { Network, Decoder, SourceInvalid, Unknown }

enum class PlaybackErrorAction { Retry, SwitchLine, SwitchSource, ShowMessage }

data class PlaybackErrorDecision(
    val kind: PlaybackErrorKind,
    val action: PlaybackErrorAction,
    val userMessage: String,
)

/**
 * 把播放器异常归类为可执行的自愈策略。
 * 只依赖错误信息文本与可选 HTTP 状态码，避免 domain 层耦合 Media3。
 */
fun classifyPlaybackError(message: String?, httpStatus: Int? = null): PlaybackErrorDecision {
    val text = message.orEmpty().lowercase()
    val sourceInvalid = (httpStatus != null && httpStatus in 400..499) ||
        listOf(
            "404", "403", "410", "source not found", "invalid source", "no playable",
            "解析失败", "无播放地址", "源失效", "播放地址失效",
        ).any { text.contains(it) }
    val decoder = listOf(
        "decoder", "codec", "mediacodec", "unsupported format", "unsupported container",
        "解码", "不支持的格式", "容器不支持",
    ).any { text.contains(it) }
    val network = listOf(
        "timeout", "timed out", "network", "dns", "socket", "connection", "ioexception",
        "unable to resolve host", "超时", "网络", "连接失败", "读取失败",
    ).any { text.contains(it) }
    return when {
        sourceInvalid -> PlaybackErrorDecision(
            PlaybackErrorKind.SourceInvalid,
            PlaybackErrorAction.SwitchSource,
            "当前播放源已失效，正在尝试其他来源",
        )
        decoder -> PlaybackErrorDecision(
            PlaybackErrorKind.Decoder,
            PlaybackErrorAction.SwitchLine,
            "当前视频格式无法解码，正在切换线路",
        )
        network -> PlaybackErrorDecision(
            PlaybackErrorKind.Network,
            PlaybackErrorAction.Retry,
            "网络异常，正在重试当前线路",
        )
        else -> PlaybackErrorDecision(
            PlaybackErrorKind.Unknown,
            PlaybackErrorAction.ShowMessage,
            "播放失败，可重试或手动切换线路",
        )
    }
}
