package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 [toCoverDecor] 的四态映射：
 * - None / Dot（无 unreadCount，只有"有/没有"两态）
 * - CaughtUp vs UpdatedTo tag 互斥
 * - firstNewIndex 边界（空表、全部有更新、部分有更新）
 */
class CoverDecorMappingTest {

    private fun item(
        movieId: Int = 1,
        episodeIndex: Int = 0,
        // null = 由 hasNew 推导总集数；显式传值（含 0）用于锁定"已知/未知集数"的降级行为。
        latestEp: Int? = null,
        hasNew: Boolean = false,
        updatedAt: Long = 1000L,
    ) = WatchHistoryItem(
        movieId = movieId, apiLineId = "ruyi", apiLineName = "如意",
        movieName = "m$movieId", posterUrl = "", typeName = "", remarks = "",
        sourceIndex = 0, sourceName = "默认", episodeIndex = episodeIndex,
        episodeTitle = "第${episodeIndex + 1}集", episodeUrl = "u$movieId",
        positionMs = 0, durationMs = 0, updatedAtEpochMs = updatedAt,
        // 实现从 newEpisodeHint 派生"有新集"：总集数 > 已看集数才有提示。
        latestEpisodeCount = latestEp ?: (episodeIndex + if (hasNew) 2 else 1),
    )

    /** 已看到总集数最后一集 → badge=None, tag=CaughtUp。 */
    @Test
    fun noUpdateYieldsNoneBadgeAndCaughtUp() {
        val item = item(hasNew = false)
        val decor = item.toCoverDecor(pulseEnabled = true)
        assertEquals(CoverBadgeState.None, decor.badge)
        assertEquals(0, decor.unread)
        assertEquals(CoverTagState.CaughtUp, decor.tag)
    }

    /** hasNew=true → badge=Dot（降级为只有两态），tag=UpdatedTo。 */
    @Test
    fun hasUpdateYieldsDotAndUpdatedTo() {
        val item = item(hasNew = true, latestEp = 5)
        val decor = item.toCoverDecor(pulseEnabled = false)
        assertEquals(CoverBadgeState.Dot, decor.badge)
        assertEquals(1, decor.unread)
        assertEquals(CoverTagState.UpdatedTo, decor.tag)
    }

    /** earliest/latest episode count 0 时的降级：latestEp=null。 */
    @Test
    fun zeroLatestEpMeansNullInDecor() {
        val item = item(hasNew = false, latestEp = 0)
        val decor = item.toCoverDecor(false)
        assertEquals(null, decor.latestEp)
    }

    /** latestEp > 0 时应透传。 */
    @Test
    fun nonZeroLatestEpPassedThrough() {
        val item = item(hasNew = true, latestEp = 92)
        val decor = item.toCoverDecor(true)
        assertEquals(92, decor.latestEp!!)
    }

    @Test
    fun firstNewIndexEmptyList() {
        assertEquals(-1, emptyList<WatchHistoryItem>().firstNewIndex())
    }

    @Test
    fun firstNewIndexAllNoUpdate() {
        val list = listOf(item(hasNew = false), item(hasNew = false))
        assertEquals(-1, list.firstNewIndex())
    }

    @Test
    fun firstNewIndexFirstHasUpdate() {
        val list = listOf(item(hasNew = true), item(hasNew = false))
        assertEquals(0, list.firstNewIndex())
    }

    @Test
    fun firstNewIndexLastHasUpdate() {
        val list = listOf(item(hasNew = false), item(hasNew = true))
        assertEquals(1, list.firstNewIndex())
    }

    @Test
    fun pulseFlagPropagatedCorrectly() {
        val item = item(hasNew = true)
        assertEquals(true, item.toCoverDecor(pulseEnabled = true).pulse)
        assertEquals(false, item.toCoverDecor(pulseEnabled = false).pulse)
    }
}
