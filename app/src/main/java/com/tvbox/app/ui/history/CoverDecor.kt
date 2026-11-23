package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem

/** 右上角数字/圆点徽章状态。 */
enum class CoverBadgeState { None, Dot, Count, NinePlus }

/** 海报底部通栏标签状态（互斥）。 */
enum class CoverTagState { None, TodayPremiere, UpdatedTo, CaughtUp }

/**
 * 单条历史项的封面叠加装饰快照，由 ViewModel 一次计算后注入，避免在重组里反复推导。
 *
 * @param badge       四态之一：None / Dot / Count (1-9) / NinePlus (≥10)。
 *                    当前项目无 unreadCount 列，实际只出 None/Dot；枚举保留以便未来扩展。
 * @param unread      未读数；≤0 时 badge=None。
 * @param tag         底部标签：有更新→UpdatedTo；今日开播→TodayPremiere；已追平→CaughtUp；其他 None。
 * @param latestEp    已知最新集数（可空，降级文案时用 null）。
 * @param pulse       列表级开关：newCount ∈ 1..3 才许呼吸，防止多数条目齐闪。
 */
data class CoverDecor(
    val badge: CoverBadgeState,
    val unread: Int,
    val tag: CoverTagState,
    val latestEp: Int?,
    val pulse: Boolean,
)

/**
 * 把 [WatchHistoryItem] 映射到封面装饰模型。
 *
 * - `hasNew` = 本机记录有后续集数待看，等价于 `newEpisodeHint != null`（无 unreadCount 列时的代理指标）。
 * - `pulseEnabled` 在 VM 层通过 `combine(...)` 算完暴露成 state；调用方传进来而不是在此处计数。
 * - 项目无 `todayPremiere` / `latestEpisode` 字段：今日开播/具体集数文案均降级为 `有更新`。
 */
fun WatchHistoryItem.toCoverDecor(pulseEnabled: Boolean): CoverDecor {
    val hasNew = newEpisodeHint != null
    // 无 unreadCount 列，只有"有/没有"两态。
    val unreadCount = if (hasNew) 1 else 0
    val badge = when {
        !hasNew       -> CoverBadgeState.None
        unreadCount >= 10 -> CoverBadgeState.NinePlus
        unreadCount >= 3  -> CoverBadgeState.Count
        else            -> CoverBadgeState.Dot
    }
    // 今日开播字段不存在，降级为空态 Tag。
    val tag = when {
        hasNew     -> CoverTagState.UpdatedTo
        else       -> CoverTagState.CaughtUp  // 无更新 = 追平提示（降低点击焦虑）
    }
    return CoverDecor(badge, unreadCount, tag, latestEpisodeCount.takeIf { it > 0 }, pulseEnabled)
}

/** 返回列表中第一条带更新记录的索引；没有则 -1。 */
fun List<WatchHistoryItem>.firstNewIndex(): Int = indexOfFirst { it.newEpisodeHint != null }.coerceAtLeast(-1)
