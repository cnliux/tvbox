package com.tvbox.app.domain

/**
 * 播放器控件的顺序切换与线路延迟分级（纯函数，便于单元测试）。
 *
 * 「快进（倍速）」与「画面」按钮不再弹层选择，而是每按一次切到下一档；
 * 档位顺序必须在 TV 与手机两个布局里完全一致，否则同一台设备换个方向
 * 就会出现「按一次跳两档」的错觉。
 */

/** 倍速档位：从慢到快排列，方便长按快进/循环切换时自然递增。含最低档 0.5x。 */
val PLAYER_SPEED_STEPS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)

/** 音频输出档位（任务1 电视直播无声修复）。顺序与设置页 chips 一致。 */
val AUDIO_OUTPUT_MODE_OPTIONS: List<String> = listOf("auto", "stereo", "passthrough")

/** 音频输出档位的展示名。 */
fun audioOutputModeLabel(mode: String): String = when (mode) {
    "stereo" -> "立体声"
    "passthrough" -> "直通"
    else -> "自动"
}

/**
 * 取下一档倍速。
 *
 * 偏好里可能存着非标准值（旧版本/外部写入的 1.1x），此时按「第一个更大档位」
 * 推进，没有更大档位就回到最低档，保证每次点击都会变化，不会卡在某个不存在的档位上。
 */
fun nextPlaybackSpeed(current: Float, steps: List<Float> = PLAYER_SPEED_STEPS): Float {
    if (steps.isEmpty()) return current
    val index = steps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    if (index >= 0) return steps[(index + 1) % steps.size]
    return steps.firstOrNull { it > current } ?: steps.first()
}

/** 取下一个画面比例：按 [VideoScaleMode.entries] 声明顺序循环。 */
fun nextVideoScaleMode(current: VideoScaleMode): VideoScaleMode {
    val modes = VideoScaleMode.entries
    val index = modes.indexOf(current)
    return modes[(index + 1) % modes.size]
}

/** 线路延迟分级阈值：绿 ≤300ms、黄 ≤800ms、红 >800ms。 */
const val LATENCY_FAST_MAX_MS = 300L
const val LATENCY_MEDIUM_MAX_MS = 800L

enum class LineLatencyTier {
    /** 未测速：既不证明快也不证明慢，排在已测线路之后。 */
    Untested,

    /** 测速超时/不可达。 */
    Timeout,
    Fast,
    Medium,
    Slow,
}

/**
 * 延迟分级。[latencyMs] 为 null 且 [tested] 为 true 表示测速失败（超时），
 * 与「还没测过」要区分开：前者已经证明这条线路当前不可用。
 */
fun lineLatencyTier(latencyMs: Long?, tested: Boolean): LineLatencyTier = when {
    !tested -> LineLatencyTier.Untested
    latencyMs == null -> LineLatencyTier.Timeout
    latencyMs <= LATENCY_FAST_MAX_MS -> LineLatencyTier.Fast
    latencyMs <= LATENCY_MEDIUM_MAX_MS -> LineLatencyTier.Medium
    else -> LineLatencyTier.Slow
}

/** 分级对应的短标签，用于线路按钮副标题与读屏播报。 */
fun lineLatencyTierLabel(tier: LineLatencyTier): String = when (tier) {
    LineLatencyTier.Fast -> "≤300ms"
    LineLatencyTier.Medium -> "≤800ms"
    LineLatencyTier.Slow -> ">800ms"
    LineLatencyTier.Timeout -> "测速失败"
    LineLatencyTier.Untested -> "未测速"
}

/**
 * 按实测延迟升序排列线路。
 *
 * 排序只用于展示：已测出具体毫秒数的线路在前（快的更前），测速失败的其次，
 * 未测速的保持原顺序排最后。不能把未测速线路直接剔除——首次打开播放页时
 * 所有线路都还没测过，剔除会让线路列表变空。
 */
fun <T> sortByLatency(
    items: List<T>,
    latencies: Map<String, Long?>,
    key: (T) -> String,
): List<T> {
    // 先算一次 key：线路数很少，但排序比较器会被调用多次，
    // 每次重新取 lineId 既啰嗦又容易在某次改动里漏掉取值来源。
    val keyed = items.mapIndexed { index, item -> Triple(index, item, key(item)) }
    return keyed.sortedWith(
        compareBy<Triple<Int, T, String>> { (_, _, id) ->
            when {
                latencies[id] != null -> 0
                id in latencies -> 1
                else -> 2
            }
        }.thenBy { (_, _, id) -> latencies[id] ?: Long.MAX_VALUE }
            .thenBy { (index, _, _) -> index },
    ).map { (_, item, _) -> item }
}
