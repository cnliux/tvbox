package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 P23 轮 · 两状态模型（收藏/追更）纯函数单测。
 * 锁定 [FollowPolicy.merge] 的冲突裁决（时间戳大者胜、同时间戳状态优先、集数取 max）、
 * [FollowPolicy.needsRefresh] 的 6 小时节流窗口与 [FollowPolicy.shouldNotify] 的通知边界。
 */
class FollowPolicyTest {

    private val key = "ruyi:42"

    private fun entry(
        state: FollowState = FollowState.None,
        episodes: Int = 0,
        updatedAtMs: Long = 0L,
        lastCheckedAtMs: Long = 0L,
    ) = FollowEntry(
        mediaKey = key,
        state = state,
        latestKnownEpisode = episodes,
        lastCheckedAtMs = lastCheckedAtMs,
        updatedAtMs = updatedAtMs,
    )

    // ---------- merge：updatedAtMs 大者胜（三个方向） ----------

    @Test
    fun merge_localNewerTimestampWins() {
        val local = entry(state = FollowState.Following, episodes = 10, updatedAtMs = 200L)
        val remote = entry(state = FollowState.Favorite, episodes = 5, updatedAtMs = 100L)
        assertEquals(FollowState.Following, FollowPolicy.merge(local, remote).state)
    }

    @Test
    fun merge_remoteNewerTimestampWins() {
        val local = entry(state = FollowState.Favorite, episodes = 5, updatedAtMs = 100L)
        val remote = entry(state = FollowState.Following, episodes = 10, updatedAtMs = 300L)
        assertEquals(FollowState.Following, FollowPolicy.merge(local, remote).state)
    }

    @Test
    fun merge_equalTimestampFallsBackToStatePriority() {
        // 时间戳相同：Following 优先于 Favorite，即使 local 是 Favorite。
        val local = entry(state = FollowState.Favorite, episodes = 5, updatedAtMs = 100L)
        val remote = entry(state = FollowState.Following, episodes = 5, updatedAtMs = 100L)
        assertEquals(FollowState.Following, FollowPolicy.merge(local, remote).state)
    }

    // ---------- merge：latestKnownEpisode 取 max ----------

    @Test
    fun merge_takesMaxKnownEpisodeAcrossBothSides() {
        // 胜出方集数较低时，仍取两侧较大值，保证集数只增不减。
        val local = entry(state = FollowState.Following, episodes = 12, updatedAtMs = 200L)
        val remote = entry(state = FollowState.Favorite, episodes = 20, updatedAtMs = 100L)
        val merged = FollowPolicy.merge(local, remote)
        assertEquals(20, merged.latestKnownEpisode)
        assertEquals(FollowState.Following, merged.state)
    }

    // ---------- needsRefresh：6 小时节流窗口 ----------

    @Test
    fun needsRefresh_withinSixHoursIsFalse() {
        val checked = entry(state = FollowState.Following, lastCheckedAtMs = 1_000_000L)
        val sixHours = 6 * 60 * 60 * 1000L
        assertFalse(FollowPolicy.needsRefresh(checked, nowMs = 1_000_000L + sixHours))
    }

    @Test
    fun needsRefresh_beyondSixHoursIsTrue() {
        val checked = entry(state = FollowState.Following, lastCheckedAtMs = 1_000_000L)
        val sixHoursPlus = 6 * 60 * 60 * 1000L + 1L
        assertTrue(FollowPolicy.needsRefresh(checked, nowMs = 1_000_000L + sixHoursPlus))
    }

    @Test
    fun needsRefresh_neverCheckedIsTrue() {
        assertTrue(FollowPolicy.needsRefresh(entry(state = FollowState.Following), nowMs = 0L))
    }

    // ---------- shouldNotify：通知边界 ----------

    @Test
    fun shouldNotify_favoriteNeverNotifies() {
        val old = entry(state = FollowState.Favorite, episodes = 10)
        val new = entry(state = FollowState.Favorite, episodes = 12)
        assertFalse(FollowPolicy.shouldNotify(old, new))
    }

    @Test
    fun shouldNotify_followingWithNewEpisodeNotifies() {
        val old = entry(state = FollowState.Following, episodes = 10)
        val new = entry(state = FollowState.Following, episodes = 11)
        assertTrue(FollowPolicy.shouldNotify(old, new))
    }

    @Test
    fun shouldNotify_followingUnchangedEpisodeDoesNotNotify() {
        val old = entry(state = FollowState.Following, episodes = 10)
        val new = entry(state = FollowState.Following, episodes = 10)
        assertFalse(FollowPolicy.shouldNotify(old, new))
    }

    @Test
    fun shouldNotify_noneNeverNotifies() {
        val old = entry(state = FollowState.None, episodes = 10)
        val new = entry(state = FollowState.None, episodes = 15)
        assertFalse(FollowPolicy.shouldNotify(old, new))
    }
}
