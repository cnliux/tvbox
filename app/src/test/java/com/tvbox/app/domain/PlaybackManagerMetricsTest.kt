package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放管家四联指标与证据纯函数的 JVM 单测（无 Android 依赖）。 */
class PlaybackManagerMetricsTest {

    @Test
    fun percentile50HandlesOddEvenAndEmpty() {
        assertNull(percentile50(emptyList()))
        assertEquals(20L, percentile50(listOf(10L, 20L, 30L)))
        assertEquals(25L, percentile50(listOf(10L, 20L, 30L, 40L)))
    }

    @Test
    fun appendSuccessLatencyKeepsOnlyNewestSamples() {
        var entry = PlaybackHealthEntry(key = "k")
        repeat(25) { index ->
            entry = appendSuccessLatency(entry, index.toLong(), maxSamples = 20)
        }
        assertEquals(20, entry.successLatencyMs.size)
        assertEquals(5L, entry.successLatencyMs.first())
        assertEquals(24L, entry.successLatencyMs.last())
    }

    @Test
    fun snapshotDerivesFourMetrics() {
        val snapshot = PlaybackHealthSnapshot(
            entries = mapOf(
                "a" to PlaybackHealthEntry(
                    key = "a",
                    successCount = 3,
                    failureCount = 1,
                    lastSuccessAtMs = 9_000L,
                    successLatencyMs = listOf(800L, 1_200L),
                    stutterSampleCount = 1,
                ),
            ),
        )

        val metrics = snapshot.metrics(nowMs = 69_000L)
        assertEquals(0.75f, metrics.successRate, 0.0001f)
        assertEquals(1_000L, metrics.p50LatencyMs)
        assertEquals(0.25f, metrics.stutterRate, 0.0001f)
        assertEquals("1 分钟前", metrics.lastSuccessAgoText)
        assertEquals(4, metrics.sampleCount)
    }

    @Test
    fun badgeStatesCoverRecommendedSlowFailureAndNoSample() {
        assertEquals(PlaybackHealthBadge.NoSample, derivePlaybackHealthBadge(null, 10_000L))

        assertEquals(
            PlaybackHealthBadge.Recommended,
            derivePlaybackHealthBadge(
                PlaybackHealthEntry(key = "ok", successCount = 3, lastSuccessAtMs = 9_000L),
                nowMs = 10_000L,
            ),
        )

        assertEquals(
            PlaybackHealthBadge.Slower,
            derivePlaybackHealthBadge(
                PlaybackHealthEntry(
                    key = "slow",
                    successCount = 3,
                    lastSuccessAtMs = 9_000L,
                    successLatencyMs = listOf(8_000L, 9_000L),
                ),
                nowMs = 10_000L,
            ),
        )

        assertEquals(
            PlaybackHealthBadge.RecentFailure,
            derivePlaybackHealthBadge(
                PlaybackHealthEntry(key = "bad", failureCount = 2, lastFailureAtMs = 9_000L),
                nowMs = 10_000L,
            ),
        )

        assertTrue(
            explainPlaybackBadge(PlaybackHealthBadge.NoSample, null, 0L).contains("未采样"),
        )
    }

    @Test
    fun evidenceLogIsNewestFirstAndBounded() {
        var log = emptyList<PlaybackEvidence>()
        repeat(60) { index ->
            log = appendEvidence(
                log,
                PlaybackEvidence(
                    atMs = index.toLong(),
                    source = "line",
                    event = PlaybackEvidenceEvent.BufferStall,
                ),
            )
        }

        assertEquals(50, log.size)
        assertEquals(59L, log.first().atMs)
        assertEquals(10L, recentEvidence(log, 50).last().atMs)
        assertEquals(3, recentEvidence(log, 3).size)
    }
}
