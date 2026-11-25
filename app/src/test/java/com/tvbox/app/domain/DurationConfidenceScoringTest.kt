package com.tvbox.app.domain

import com.tvbox.app.data.UNTRUSTED_DOMAIN_SCORE_FACTOR
import com.tvbox.app.data.UNTRUSTED_DURATION_WEIGHT
import com.tvbox.app.data.confidenceAdjustedScore
import com.tvbox.app.data.healthScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-04：验证 [contributesToScore] 已进入生产评分入参——
 * [com.tvbox.app.data.PlaybackHealthSnapshot.healthScore] 与
 * [com.tvbox.app.data.LiveDomainScoreStore.scoreFor] 共用的调分规则。
 */
class DurationConfidenceScoringTest {

    private fun snapshotWith(confidence: DurationConfidence): PlaybackHealthSnapshot =
        PlaybackHealthSnapshot(
            mapOf(
                "line" to PlaybackHealthEntry(
                    key = "line",
                    successCount = 2,
                    failureCount = 1,
                    stutterSampleCount = 2,
                    durationConfidence = confidence,
                ),
            ),
        )

    @Test
    fun `时长不可信时成功样本打折降低健康分`() {
        val high = snapshotWith(DurationConfidence.HIGH)
        val low = snapshotWith(DurationConfidence.LOW)
        val none = snapshotWith(DurationConfidence.NONE)

        assertEquals(high.healthScore(), low.healthScore(), 0.0001)
        assertTrue(none.healthScore() < high.healthScore())
        // 2 次成功 × 0.5 权重 - 1 次失败 × 1.5 - 2 次卡顿 × 0.3
        assertEquals(2 * UNTRUSTED_DURATION_WEIGHT - 1.5 - 0.6, none.healthScore(), 0.0001)
    }

    @Test
    fun `时长不可信不减免失败与卡顿扣分`() {
        val failures = PlaybackHealthSnapshot(
            mapOf(
                "line" to PlaybackHealthEntry(
                    key = "line",
                    failureCount = 3,
                    stutterSampleCount = 4,
                    durationConfidence = DurationConfidence.NONE,
                ),
            ),
        )
        assertEquals(-3 * 1.5 - 4 * 0.3, failures.healthScore(), 0.0001)
    }

    @Test
    fun `空快照健康分为零`() {
        assertEquals(0.0, PlaybackHealthSnapshot().healthScore(), 0.0001)
    }

    @Test
    fun `域名分仅对正向分打折负分保留`() {
        assertEquals(10.0, confidenceAdjustedScore(10.0, DurationConfidence.HIGH), 0.0001)
        assertEquals(10.0, confidenceAdjustedScore(10.0, DurationConfidence.LOW), 0.0001)
        assertEquals(
            10.0 * UNTRUSTED_DOMAIN_SCORE_FACTOR,
            confidenceAdjustedScore(10.0, DurationConfidence.NONE),
            0.0001,
        )
        assertEquals(-10.0, confidenceAdjustedScore(-10.0, DurationConfidence.NONE), 0.0001)
        assertEquals(0.0, confidenceAdjustedScore(0.0, DurationConfidence.NONE), 0.0001)
    }
}
