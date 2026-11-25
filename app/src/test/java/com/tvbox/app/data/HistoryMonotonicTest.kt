package com.tvbox.app.data

import com.tvbox.app.domain.MAX_HISTORY_BYTES
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.estimateBytes
import com.tvbox.app.domain.monotonicPosition
import com.tvbox.app.domain.trimByVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5-31「进度单调闸」与 S3-15「体积上限」的纯逻辑单测。
 *
 * HistoryRepository 依赖 Android SharedPreferences，单测环境无法直接构造，
 * 因此把两个判定/裁剪逻辑抽成 [monotonicPosition] / [trimByVolume] 纯函数后，
 * 这里针对它们做行为验证（见任务要求 3）。
 */
class HistoryMonotonicTest {

    private fun item(
        movieId: Int,
        positionMs: Long,
        name: String = "影片$movieId",
        posterUrl: String = "https://example.com/poster$movieId.jpg",
        episodeUrl: String = "https://example.com/episode$movieId.m3u8",
        apiLineId: String = "ruyi",
        episodeIndex: Int = 0,
    ) = WatchHistoryItem(
        movieId = movieId,
        apiLineId = apiLineId,
        apiLineName = "如意",
        movieName = name,
        posterUrl = posterUrl,
        typeName = "电视剧",
        remarks = "",
        sourceIndex = 0,
        sourceName = "如意",
        episodeIndex = episodeIndex,
        episodeTitle = "第${episodeIndex + 1}集",
        episodeUrl = episodeUrl,
        positionMs = positionMs,
        durationMs = 180_000L,
        updatedAtEpochMs = positionMs,
    )

    @Test
    fun olderProgressNeverOverwritesNewer() {
        val saved = item(movieId = 1, positionMs = 22_000L)
        val stale = saved.copy(positionMs = 4_000L)

        // 第一次保存 22000 后，旧协程/旧记录回写 4000：不得把进度回退到 4000。
        val guarded = monotonicPosition(prev = saved, incoming = stale)
        assertEquals(22_000L, guarded)
    }

    @Test
    fun newerProgressStillWins() {
        val saved = item(movieId = 1, positionMs = 22_000L)
        val newer = saved.copy(positionMs = 30_000L)
        assertEquals(30_000L, monotonicPosition(prev = saved, incoming = newer))
    }

    @Test
    fun missingPreviousKeepsIncoming() {
        val incoming = item(movieId = 1, positionMs = 4_000L)
        assertEquals(4_000L, monotonicPosition(prev = null, incoming = incoming))
    }

    @Test
    fun volumeCapDropsOldest() {
        // 用超长字符串放大每条体积，确保少量条目即可越过 512KB 上限。
        val longPayload = "x".repeat(80_000)
        val items = listOf(
            item(movieId = 1, positionMs = 100L, name = longPayload, posterUrl = longPayload, episodeUrl = longPayload),
            item(movieId = 2, positionMs = 90L, name = longPayload, posterUrl = longPayload, episodeUrl = longPayload),
            item(movieId = 3, positionMs = 80L, name = longPayload, posterUrl = longPayload, episodeUrl = longPayload),
            item(movieId = 4, positionMs = 70L, name = longPayload, posterUrl = longPayload, episodeUrl = longPayload),
        )
        assertTrue(items.sumOf(::estimateBytes) > MAX_HISTORY_BYTES)

        val trimmed = trimByVolume(items)
        assertTrue(trimmed.sumOf(::estimateBytes) <= MAX_HISTORY_BYTES || trimmed.size == 1)
        // 最新条目（movieId=1）仍在首位。
        assertEquals(1, trimmed.first().movieId)
    }

    @Test
    fun volumeCapKeepsLatestWhenOnlyOneRemains() {
        val longPayload = "y".repeat(80_000)
        val single = listOf(
            item(movieId = 1, positionMs = 100L, name = longPayload, posterUrl = longPayload, episodeUrl = longPayload),
        )
        // 单条即使超限也保留（不产生空历史）。
        val trimmed = trimByVolume(single)
        assertEquals(1, trimmed.size)
        assertEquals(1, trimmed.first().movieId)
    }

    @Test
    fun estimateBytesIsPositive() {
        val long = item(
            movieId = 9,
            positionMs = 1_000L,
            name = "很长的影片名称".repeat(20),
            posterUrl = "https://example.com/poster-very-long.jpg",
            episodeUrl = "https://example.com/episode-very-long.m3u8",
        )
        assertTrue(estimateBytes(long) > 0)
    }
}
