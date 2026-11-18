package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定播放器四项交互改动的纯逻辑：
 * 选集去编号、线路按延迟排序与三色阈值、倍速/画面顺序切换、详情页续播。
 */
class PlayerControlPolicyTest {

    @Test
    fun speedStepsCycleInOrderAndWrapAround() {
        assertEquals(0.75f, nextPlaybackSpeed(0.5f), 0.001f)
        assertEquals(1f, nextPlaybackSpeed(0.75f), 0.001f)
        assertEquals(1.25f, nextPlaybackSpeed(1f), 0.001f)
        assertEquals(1.5f, nextPlaybackSpeed(1.25f), 0.001f)
        assertEquals(2f, nextPlaybackSpeed(1.5f), 0.001f)
        // 最高档再按一次回到最低档 0.5x。
        assertEquals(0.5f, nextPlaybackSpeed(2f), 0.001f)
    }

    @Test
    fun nonStandardSpeedJumpsToNextHigherStep() {
        // 偏好里可能存着旧版本的 1.1x：应推进到 1.25x，而不是回到 0.5x。
        assertEquals(1.25f, nextPlaybackSpeed(1.1f), 0.001f)
        // 超出最高档的值回到最低档 0.5x。
        assertEquals(0.5f, nextPlaybackSpeed(3f), 0.001f)
    }

    @Test
    fun pictureModeCyclesThroughAllModesAndWraps() {
        val modes = VideoScaleMode.entries
        modes.forEach { mode ->
            val next = nextVideoScaleMode(mode)
            assertEquals(modes[(modes.indexOf(mode) + 1) % modes.size], next)
        }
        assertEquals(modes.first(), nextVideoScaleMode(modes.last()))
    }

    @Test
    fun latencyTiersUse300And800Thresholds() {
        assertEquals(LineLatencyTier.Fast, lineLatencyTier(120L, tested = true))
        assertEquals(LineLatencyTier.Fast, lineLatencyTier(300L, tested = true))
        assertEquals(LineLatencyTier.Medium, lineLatencyTier(301L, tested = true))
        assertEquals(LineLatencyTier.Medium, lineLatencyTier(800L, tested = true))
        assertEquals(LineLatencyTier.Slow, lineLatencyTier(801L, tested = true))
        // 超时与未测速必须区分：前者已证明不可用，后者只是还没测。
        assertEquals(LineLatencyTier.Timeout, lineLatencyTier(null, tested = true))
        assertEquals(LineLatencyTier.Untested, lineLatencyTier(null, tested = false))
    }

    @Test
    fun sourcesSortByLatencyKeepingStableOrderForUnmeasured() {
        val latencies = mapOf<String, Long?>(
            "b" to 500L,
            "c" to 80L,
            "d" to null,
        )
        val sorted = sortByLatency(listOf("a", "b", "c", "d", "e"), latencies) { it }
        // 已测出毫秒的在前（按快慢），测速失败的其次，未测速的按原顺序排最后。
        assertEquals(listOf("c", "b", "d", "a", "e"), sorted)
    }

    @Test
    fun unmeasuredSourcesAreNeverDropped() {
        val latencies = emptyMap<String, Long?>()
        val sources = listOf("a", "b", "c")
        // 首次进入播放页时所有线路都未测速：排序不能把列表变空。
        assertEquals(sources, sortByLatency(sources, latencies) { it })
    }

    @Test
    fun episodeTitlesLoseLeadingNumberPrefix() {
        // 播放器选集曾显示成「1. 1.」：位置编号与源标题编号叠加。
        assertEquals("上", displayEpisodeTitle("1. 上", 0))
        assertEquals("下", displayEpisodeTitle("第2集 下", 1))
        // 整条标题只是编号时保留编号，避免出现空标题。
        assertEquals("3", displayEpisodeTitle("3", 2))
        // 手机选集常见「1.第一集」「01.第01集」叠加编号。
        assertEquals("第一集", displayEpisodeTitle("1.第一集", 0))
        assertEquals("第01集", displayEpisodeTitle("01.第01集", 0))
        assertEquals("第1集", displayEpisodeTitle("1、第1集", 0))
    }

    @Test
    fun resumePointFoundByUrlNotStaleIndex() {
        val movie = movieOf(
            source("主线路", "https://a.test/1.m3u8", "https://a.test/2.m3u8"),
            source("备用线路", "https://b.test/1.m3u8", "https://b.test/2.m3u8"),
        )
        // 历史记录的 sourceIndex/episodeIndex 已过期，但地址仍在备用线路第 2 集。
        val point = findResumePoint(
            movie,
            listOf(
                history(
                    sourceIndex = 0,
                    episodeIndex = 0,
                    episodeUrl = "https://b.test/2.m3u8",
                    positionMs = 600_000L,
                    durationMs = 2_400_000L,
                ),
            ),
        )
        assertEquals(PlaybackResumePoint(1, 1, 600_000L), point)
    }

    @Test
    fun finishedOrUnwatchedHistoryHasNoResumePoint() {
        val movie = movieOf(source("主线路", "https://a.test/1.m3u8"))
        // 已看到 98%：续播会立刻触发自动下一集，应当从头播放。
        assertEquals(
            null,
            findResumePoint(movie, listOf(history(0, 0, "https://a.test/1.m3u8", 980L, 1_000L))),
        )
        // 进度为 0：没有可续播的位置。
        assertEquals(
            null,
            findResumePoint(movie, listOf(history(0, 0, "https://a.test/1.m3u8", 0L, 1_000L))),
        )
        // 历史里没有这部影片。
        assertEquals(null, findResumePoint(movie, emptyList()))
    }

    @Test
    fun playButtonOnlyResumesTheEpisodeThatWasWatched() {
        val movie = movieOf(
            source("主线路", "https://a.test/1.m3u8", "https://a.test/2.m3u8"),
        )
        val history = listOf(history(0, 1, "https://a.test/2.m3u8", 300_000L, 2_400_000L))

        // 选中的正是历史那一集：带进度续播。
        assertEquals(300_000L, resumePositionForEpisode(movie, history, 0, 1))
        // 用户主动点了另一集：从那一集开头播放。
        assertEquals(0L, resumePositionForEpisode(movie, history, 0, 0))
    }

    @Test
    fun resumeRatioBoundaryIsBelowFinished() {
        val movie = movieOf(source("主线路", "https://a.test/1.m3u8"))
        // 94% 仍算没看完，允许续播。
        assertTrue(findResumePoint(movie, listOf(history(0, 0, "https://a.test/1.m3u8", 940L, 1_000L))) != null)
    }
}

private fun movieOf(vararg sources: PlaySource) = Movie(
    id = 7,
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
    playSources = sources.toList(),
)

private fun source(name: String, vararg urls: String) = PlaySource(
    name = name,
    episodes = urls.mapIndexed { index, url -> PlayEpisode(title = "第${index + 1}集", url = url) },
)

private fun history(
    sourceIndex: Int,
    episodeIndex: Int,
    episodeUrl: String,
    positionMs: Long,
    durationMs: Long,
) = WatchHistoryItem(
    movieId = 7,
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
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtEpochMs = 0L,
)
