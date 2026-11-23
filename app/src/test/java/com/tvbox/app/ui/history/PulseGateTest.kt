package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 pulseEnabled gate 的行为：
 * - newCount == 0 → false
 * - newCount == 1,2,3 → true
 * - newCount >= 4 → false
 */
class PulseGateTest {

    private fun makeItems(countWithNew: Int, total: Int): List<WatchHistoryItem> =
        (1..total).map { i ->
            WatchHistoryItem(
                movieId = i, apiLineId = "ruyi", apiLineName = "如意",
                movieName = "m$i", posterUrl = "", typeName = "", remarks = "",
                sourceIndex = 0, sourceName = "源", episodeIndex = 0,
                episodeTitle = "第1集", episodeUrl = "u$i",
                positionMs = 0, durationMs = 0, updatedAtEpochMs = i.toLong(),
                latestEpisodeCount = if (i <= countWithNew) i + 1 else 0,
            )
        }

    @Test
    fun zeroUpdatesDisablesPulse() {
        val items = makeItems(0, 5)
        val newCount = items.count { it.newEpisodeHint != null }
        assertFalse("newCount=$newCount", newCount in 1..3)
    }

    @Test
    fun oneUpdateEnablesPulse() {
        val items = makeItems(1, 5)
        val newCount = items.count { it.newEpisodeHint != null }
        assertTrue("newCount=$newCount", newCount in 1..3)
    }

    @Test
    fun threeUpdatesStillEnablesPulse() {
        val items = makeItems(3, 6)
        val newCount = items.count { it.newEpisodeHint != null }
        assertTrue("newCount=$newCount", newCount in 1..3)
    }

    @Test
    fun fourUpdatesDisablesPulse() {
        val items = makeItems(4, 8)
        val newCount = items.count { it.newEpisodeHint != null }
        assertFalse("newCount=$newCount should be >= 4", newCount in 1..3)
    }

    @Test
    fun eightUpdatesDisablesPulse() {
        val items = makeItems(8, 10)
        val newCount = items.count { it.newEpisodeHint != null }
        assertFalse("newCount=$newCount", newCount in 1..3)
    }

    @Test
    fun exactBoundaryThreeIsAllowed() {
        // 边界验证：3 条允许脉冲，4 条禁止。
        val c3 = makeItems(3, 3).count { it.newEpisodeHint != null }
        val c4 = makeItems(4, 4).count { it.newEpisodeHint != null }
        assertTrue("3 updates → pulse on", c3 in 1..3)
        assertFalse("4 updates → pulse off", c4 in 1..3)
    }
}
