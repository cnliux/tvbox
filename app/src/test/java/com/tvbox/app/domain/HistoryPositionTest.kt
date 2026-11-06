package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPositionTest {

    @Test
    fun locatesSourceAndEpisodeByUrlAfterLineOrderChanged() {
        val movie = movie(
            sources = listOf(
                source("主线路", "https://a.test/1.m3u8", "https://a.test/2.m3u8"),
                source("备用线路", "https://b.test/1.m3u8", "https://b.test/2.m3u8"),
            ),
        )
        // 历史记录保存在"备用线路第2集"，即使 sourceIndex 字段过期也能按地址定位。
        val item = history(sourceIndex = 0, episodeIndex = 0, episodeUrl = "https://b.test/2.m3u8")

        assertEquals(1 to 1, resolveHistoryPosition(movie, item))
    }

    @Test
    fun fallsBackToSavedIndexWhenUrlGone() {
        val movie = movie(
            sources = listOf(
                source("主线路", "https://a.test/1.m3u8", "https://a.test/2.m3u8"),
                source("备用线路", "https://b.test/1.m3u8"),
            ),
        )
        val item = history(sourceIndex = 1, episodeIndex = 0, episodeUrl = "https://dead.test/gone.m3u8")

        assertEquals(1 to 0, resolveHistoryPosition(movie, item))
    }

    @Test
    fun fallsBackToPreferredSourceWhenSavedIndexOutOfRange() {
        val movie = movie(
            sources = listOf(
                source("mp4线路", "https://a.test/1.mp4"),
                source("m3u8线路", "https://b.test/1.m3u8", "https://b.test/2.m3u8"),
            ),
        )
        val item = history(sourceIndex = 9, episodeIndex = 9, episodeUrl = "https://dead.test/gone.m3u8")

        val (sourceIndex, episodeIndex) = resolveHistoryPosition(movie, item)
        // preferredSourceIndex 优先含 m3u8 且非空的线路。
        assertEquals(1, sourceIndex)
        assertEquals(0, episodeIndex)
    }
}

private fun movie(sources: List<PlaySource>) = Movie(
    id = 1,
    apiLineId = "line",
    apiLineName = "line",
    name = "测试片",
    typeId = 1,
    typeName = "电影",
    posterUrl = "",
    remarks = "",
    year = "2026",
    area = "",
    language = "",
    actor = "",
    director = "",
    duration = "",
    description = "",
    playSources = sources,
)

private fun source(name: String, vararg urls: String) = PlaySource(
    name = name,
    episodes = urls.mapIndexed { i, url -> PlayEpisode(title = "第${i + 1}集", url = url) },
)

private fun history(sourceIndex: Int, episodeIndex: Int, episodeUrl: String) = WatchHistoryItem(
    movieId = 1,
    apiLineId = "line",
    apiLineName = "line",
    movieName = "测试片",
    posterUrl = "",
    typeName = "电影",
    remarks = "",
    sourceIndex = sourceIndex,
    sourceName = "s",
    episodeIndex = episodeIndex,
    episodeTitle = "第1集",
    episodeUrl = episodeUrl,
    positionMs = 0L,
    durationMs = 0L,
    updatedAtEpochMs = 0L,
)
