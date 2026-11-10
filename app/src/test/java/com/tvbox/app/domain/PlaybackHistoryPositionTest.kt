package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第 309 轮 · 历史续播定位单测
 * 锁定 [resolveHistoryPosition]：优先按集地址反查线路/集下标，地址找不到退回保存下标，
 * 最后回退到主线路首集；越界下标与空地址都不应抛出。
 */
class PlaybackHistoryPositionTest {

    private fun ep(title: String, url: String) = PlayEpisode(title, url)

    private fun movie(vararg sources: PlaySource) = Movie(
        id = 1,
        apiLineId = "ruyi",
        apiLineName = "如意",
        name = "测试剧",
        typeId = 1,
        typeName = "电视剧",
        posterUrl = "",
        remarks = "",
        year = "2026",
        area = "",
        language = "",
        actor = "",
        director = "",
        duration = "",
        description = "",
        playSources = sources.toList(),
    )

    @Test
    fun urlMatchResolvesSourceAndEpisode() {
        val s0 = PlaySource("默认", listOf(ep("第1集", "a1"), ep("第2集", "a2")))
        val s1 = PlaySource("备用", listOf(ep("第1集", "b1"), ep("第2集", "b2")))
        val m = movie(s0, s1)
        val item = WatchHistoryItem(
            movieId = 1, apiLineId = "ruyi", apiLineName = "如意", movieName = "测试剧",
            posterUrl = "", typeName = "", remarks = "", sourceIndex = 0, sourceName = "默认",
            episodeIndex = 0, episodeTitle = "第2集", episodeUrl = "b2", positionMs = 0,
            durationMs = 0, updatedAtEpochMs = 0,
        )
        val (src, epIdx) = resolveHistoryPosition(m, item)
        assertEquals(1, src)
        assertEquals(1, epIdx)
    }

    @Test
    fun urlMissingFallsBackToSavedIndices() {
        val s0 = PlaySource("默认", listOf(ep("第1集", "a1"), ep("第2集", "a2")))
        val m = movie(s0)
        val item = WatchHistoryItem(
            movieId = 1, apiLineId = "ruyi", apiLineName = "如意", movieName = "测试剧",
            posterUrl = "", typeName = "", remarks = "", sourceIndex = 0, sourceName = "默认",
            episodeIndex = 1, episodeTitle = "第2集", episodeUrl = "unknown", positionMs = 0,
            durationMs = 0, updatedAtEpochMs = 0,
        )
        val (src, epIdx) = resolveHistoryPosition(m, item)
        assertEquals(0, src)
        assertEquals(1, epIdx)
    }

    @Test
    fun outOfRangeFallsBackToPreferredSourceFirstEpisode() {
        val s0 = PlaySource("默认", listOf(ep("第1集", "a1")))
        val s1 = PlaySource("m3u8线", listOf(ep("第1集", "b1"), ep("第2集", "b2")))
        val m = movie(s0, s1)
        val item = WatchHistoryItem(
            movieId = 1, apiLineId = "ruyi", apiLineName = "如意", movieName = "测试剧",
            posterUrl = "", typeName = "", remarks = "", sourceIndex = 99, sourceName = "x",
            episodeIndex = 99, episodeTitle = "y", episodeUrl = "z", positionMs = 0,
            durationMs = 0, updatedAtEpochMs = 0,
        )
        val (src, epIdx) = resolveHistoryPosition(m, item)
        // 越界保存到主线路（m3u8 优先），集下标回退到 0
        assertEquals(1, src)
        assertEquals(0, epIdx)
    }
}
