package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 311 轮 · 观看历史纯函数单测
 * 锁定 [WatchHistoryItem.progressPercent] 的边界保护与 [List.continueWatching]
 * 的排序、去重、过滤（已看完不进续看）与数量上限。
 */
class WatchHistoryTest {

    private fun item(
        movieId: Int,
        updatedAt: Long,
        positionMs: Long,
        durationMs: Long,
        apiLineId: String = "ruyi",
    ) = WatchHistoryItem(
        movieId = movieId, apiLineId = apiLineId, apiLineName = "如意", movieName = "m$movieId",
        posterUrl = "", typeName = "", remarks = "", sourceIndex = 0, sourceName = "默认",
        episodeIndex = 0, episodeTitle = "第1集", episodeUrl = "u$movieId",
        positionMs = positionMs, durationMs = durationMs, updatedAtEpochMs = updatedAt,
    )

    @Test
    fun progressPercentGuardsInvalidInputs() {
        assertEquals(0, item(1, 0, 0L, 1000L).progressPercent)
        assertEquals(0, item(1, 0, 500L, 0L).progressPercent)
        assertEquals(50, item(1, 0, 500L, 1000L).progressPercent)
    }

    @Test
    fun continueWatchingSortsByRecencyAndDedups() {
        val list = listOf(
            item(1, updatedAt = 100, positionMs = 500, durationMs = 1000),
            item(2, updatedAt = 300, positionMs = 500, durationMs = 1000),
            item(1, updatedAt = 200, positionMs = 500, durationMs = 1000), // 同 apiLine:movieId 去重，取更晚
        )
        val result = list.continueWatching()
        assertEquals(2, result.size)
        // 最新在前面
        assertEquals(2, result[0].movieId)
        assertEquals(1, result[1].movieId)
    }

    @Test
    fun finishedItemsExcludedFromContinueWatching() {
        val list = listOf(
            item(1, updatedAt = 100, positionMs = 1000, durationMs = 1000), // 100% 看完
            item(2, updatedAt = 200, positionMs = 950, durationMs = 1000), // >=95% 视为看完
            item(3, updatedAt = 300, positionMs = 400, durationMs = 1000), // 续看
        )
        val result = list.continueWatching()
        assertEquals(1, result.size)
        assertEquals(3, result[0].movieId)
    }

    @Test
    fun continueWatchingRespectsLimit() {
        val list = (1..10).map { item(it, updatedAt = it.toLong(), positionMs = 100, durationMs = 1000) }
        val result = list.continueWatching(limit = 4)
        assertEquals(4, result.size)
        assertTrue(result.all { it.updatedAtEpochMs <= 10L })
    }

    @Test
    fun zeroPositionExcluded() {
        val list = listOf(item(1, updatedAt = 100, positionMs = 0, durationMs = 1000))
        assertTrue(list.continueWatching().isEmpty())
    }
}
