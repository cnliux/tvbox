package com.tvbox.app.domain

/**
 * 「两状态模型」：把收藏（用户主动保存）与追更（关注新集）统一成一条 [FollowEntry]。
 *
 * 方案 P23 落地：收藏表达「我想留着这部片」，追更表达「我想第一时间知道有新集」。
 * [FollowState.Favorite] 只保存不打扰；[FollowState.Following] 额外参与刷新调度与通知。
 * 本文件是纯 Kotlin 数据层，不依赖 Android，便于单测（P1 只统一模型与手动刷新；
 * P2 才在系统通知授权后按省电窗口检查已追更项）。
 */

/** 追更/收藏状态：None=无、Favorite=收藏、Following=追更。 */
enum class FollowState {
    /** 未收藏也未追更。 */
    None,
    /** 仅收藏：保留入口，但不做新集检查与通知。 */
    Favorite,
    /** 追更：关注新集，参与刷新调度，集数上涨时允许通知。 */
    Following,
}

/** 一部片的追更/收藏条目，键为 [mediaKey]。 */
data class FollowEntry(
    /** 媒体唯一键（如 `apiLineId:movieId`）。 */
    val mediaKey: String,
    /** 当前状态。 */
    val state: FollowState,
    /** 已知的最大总集数；0 表示未知或尚未检查。 */
    val latestKnownEpisode: Int = 0,
    /** 最近一次跨源新集检查时间（epoch ms）；0 表示从未检查。 */
    val lastCheckedAtMs: Long = 0L,
    /** 条目最近一次本地变更时间（epoch ms），用于多端合并冲突裁决。 */
    val updatedAtMs: Long = 0L,
)

/**
 * 追更/收藏的纯函数策略集合：合并、刷新调度与通知判定。
 * 全部为无副作用的纯函数，便于单测覆盖冲突边界。
 */
object FollowPolicy {

    /**
     * 合并本地与远端条目。冲突规则：
     * 1. [FollowEntry.updatedAtMs] 大者胜（后改的覆盖先改的）；
     * 2. updatedAtMs 相同时 [FollowState] 高者优先：Following > Favorite > None；
     * 3. 无论谁胜出，[FollowEntry.latestKnownEpisode] 始终取两侧较大值（集数只增不减）。
     */
    fun merge(local: FollowEntry, remote: FollowEntry): FollowEntry {
        val winner = when {
            local.updatedAtMs > remote.updatedAtMs -> local
            remote.updatedAtMs > local.updatedAtMs -> remote
            // 时间戳相同：按状态优先级裁决，避免互相覆盖造成状态回退。
            else -> if (stateRank(local.state) >= stateRank(remote.state)) local else remote
        }
        return winner.copy(
            latestKnownEpisode = maxOf(local.latestKnownEpisode, remote.latestKnownEpisode),
        )
    }

    /**
     * 是否需要刷新该条目的新集信息：距上次检查超过 [intervalMs]（默认 6 小时）才刷新。
     * 仅追更条目由调用方调度；本函数只回答「时间到没到」，不区分状态。
     * 从未检查（lastCheckedAtMs=0）视为需要刷新。
     */
    fun needsRefresh(entry: FollowEntry, nowMs: Long, intervalMs: Long = 6 * 60 * 60 * 1000L): Boolean {
        if (entry.lastCheckedAtMs <= 0L) return true
        return nowMs - entry.lastCheckedAtMs > intervalMs
    }

    /**
     * 是否应该通知新集：只有 state=Following 且新集数确实上涨才通知。
     * Favorite / None 一律不打扰；集数未变或回落不通知。
     */
    fun shouldNotify(old: FollowEntry, new: FollowEntry): Boolean =
        new.state == FollowState.Following && new.latestKnownEpisode > old.latestKnownEpisode

    /** 状态优先级：Following > Favorite > None。 */
    private fun stateRank(state: FollowState): Int = when (state) {
        FollowState.Following -> 2
        FollowState.Favorite -> 1
        FollowState.None -> 0
    }
}
