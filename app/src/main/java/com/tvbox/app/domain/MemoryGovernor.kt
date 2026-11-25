package com.tvbox.app.domain

/**
 * T3-21：LMK / 内存水位的单一闸门。
 *
 * 用 ActivityManager.MemoryInfo.availableMem + onTrimMemory(level) 决定三档策略，
 * 避免 2GB 盒子仍按 RICH 档缓存 240 张海报 / 预取 2 页 / 24 小时 EPG。
 * 这里只做纯判定；订阅 trim 回调与每 5s 采样的胶水由 Application 层负责。
 */
object MemoryGovernor {

    enum class Tier { RICH, TIGHT, CRITICAL }

    const val RICH_THRESHOLD_MB: Long = 512L
    const val TIGHT_THRESHOLD_MB: Long = 256L

    // ComponentCallbacks2 的数值常量（JVM 单测不依赖 android.jar）。
    const val TRIM_MEMORY_RUNNING_MODERATE: Int = 5
    const val TRIM_MEMORY_RUNNING_LOW: Int = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL: Int = 15
    const val TRIM_MEMORY_UI_HIDDEN: Int = 20

    data class Prefs(
        val posters: Int,
        val prefetchPages: Int,
        val epgHours: Int,
        val minimizeGoWindow: Boolean,
    )

    fun tierFor(availableMb: Long, lowMemory: Boolean = false): Tier = when {
        lowMemory || availableMb < TIGHT_THRESHOLD_MB -> Tier.CRITICAL
        availableMb < RICH_THRESHOLD_MB -> Tier.TIGHT
        else -> Tier.RICH
    }

    /** 只认「内存紧张」方向的 level；宽松回调返回 null，避免把 RICH 误降级。 */
    fun tierFromTrimLevel(level: Int): Tier? = when {
        level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_CRITICAL -> Tier.CRITICAL
        level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_RUNNING_MODERATE -> Tier.TIGHT
        else -> null
    }

    fun prefs(tier: Tier): Prefs = when (tier) {
        Tier.RICH -> Prefs(posters = 240, prefetchPages = 2, epgHours = 24, minimizeGoWindow = false)
        Tier.TIGHT -> Prefs(posters = 96, prefetchPages = 1, epgHours = 6, minimizeGoWindow = false)
        Tier.CRITICAL -> Prefs(posters = 32, prefetchPages = 0, epgHours = 0, minimizeGoWindow = true)
    }

    /** 观测到更差档位立即收紧；变好时不自动恢复，避免抖动导致缓存反复重建。 */
    fun nextTier(current: Tier, observed: Tier?): Tier =
        if (observed != null && observed.ordinal > current.ordinal) observed else current

    // T3-21：以下三个纯函数把档位预算接到真实消费点。用户设置只能更严，不能超过档位上限。

    /** 海报/历史列表容量：设置条数与档位预算取较小值。 */
    fun effectivePosterLimit(settingsLimit: Int, tier: Tier): Int =
        minOf(settingsLimit.coerceAtLeast(0), prefs(tier).posters)

    /** 自动预取页数：档位预算即硬上限，CRITICAL 为 0 表示关闭自动预取。 */
    fun effectivePrefetchPages(requested: Int, tier: Tier): Int =
        minOf(requested.coerceAtLeast(0), prefs(tier).prefetchPages)

    /** EPG 缓存时长（小时）：档位收紧时截断；0 表示本次不拉取节目单。 */
    fun effectiveEpgHours(settingsHours: Int, tier: Tier): Int =
        minOf(settingsHours.coerceAtLeast(0), prefs(tier).epgHours)
}
