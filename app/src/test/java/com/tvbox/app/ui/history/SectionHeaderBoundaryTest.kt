package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 扩展现有的 [com.tvbox.app.ui.MobileHistorySectionsTest]，钉住三个关键跨天时刻：
 * 23:59 / 00:00 / 00:01 —— 保证归属不变（已修复的 bug 不回归）。
 *
 * ⚠️ 必须用 startOfDayMs(nowMs) 路径，不能用 LocalDate.now()，否则凌晨时点会错跑分组。
 */
class SectionHeaderBoundaryTest {

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, second)
        return c.timeInMillis
    }

    private fun item(movieId: Int, updatedAt: Long) = WatchHistoryItem(
        movieId = movieId, apiLineId = "ruyi", apiLineName = "如意",
        movieName = "m$movieId", posterUrl = "", typeName = "", remarks = "",
        sourceIndex = 0, sourceName = "默认", episodeIndex = 0,
        episodeTitle = "第1集", episodeUrl = "u$movieId",
        positionMs = 0, durationMs = 0, updatedAtEpochMs = updatedAt,
    )

    /** 23:59 观看的记录，在 00:00 之后仍应归属于「今天」（同一天内）。 */
    @Test
    fun lateNightViewBeforeMidnightStaysTodayAtSameDayNow() {
        val viewedAt = localTime(2026, 11, 18, 23, 59, 0)
        val now = localTime(2026, 11, 18, 23, 59, 30) // 仍属于同一天
        val sections = buildHistorySections(listOf(item(1, viewedAt)), now)
        assertEquals("今天", sections[0].label)
        assertEquals(1, sections[0].items.size)
    }

    /** 00:00 观看的记录，在 00:01 仍属于「今天」。 */
    @Test
    fun midnightViewStaysTodayJustAfterMidnight() {
        val viewedAt = localTime(2026, 11, 18, 0, 0, 0)
        val now = localTime(2026, 11, 18, 0, 0, 1) // 同一天的极 early 时段
        val sections = buildHistorySections(listOf(item(2, viewedAt)), now)
        assertEquals("今天", sections[0].label)
    }

    /** 00:01 观看的记录，在次日 23:59 时应归入「昨天」。 */
    @Test
    fun earlyMorningViewBecomesYesterdayNextDayLateNight() {
        val viewedAt = localTime(2026, 11, 18, 0, 1, 0)
        val now = localTime(2026, 11, 18, 23, 59, 0)
        // 此时仍在当天，应为"今天"
        val sameDay = buildHistorySections(listOf(item(3, viewedAt)), now)
        assertEquals("今天", sameDay[0].label)

        // 下一天 00:01，该记录应变成"昨天"
        val nextDayNow = localTime(2026, 11, 19, 0, 1, 0)
        val nextDaySections = buildHistorySections(listOf(item(3, viewedAt)), nextDayNow)
        assertEquals("昨天", nextDaySections[0].label)
    }

    /** 零点整前后各一条，应分属不同组。 */
    @Test
    fun oneBeforeAndOneAfterMidnightSplitIntoTwoGroups() {
        val before = localTime(2026, 11, 18, 23, 59, 59)
        val after = localTime(2026, 11, 19, 0, 0, 1)
        val now = localTime(2026, 11, 19, 12, 0, 0)

        val sections = buildHistorySections(
            // 实现契约：调用方按 updatedAtEpochMs 倒序传入（最近在前），分组保持该顺序。
            listOf(item(2, after), item(1, before)),
            now,
        )
        assertEquals(listOf("今天", "昨天"), sections.map { it.label })
        assertEquals(1, sections[0].items.size) // 今天：1 条（after）
        assertEquals(2, sections[0].items[0].movieId) // 今天那条是 after
        assertEquals(1, sections[1].items.size) // 昨天：1 条（before）
        assertEquals(1, sections[1].items[0].movieId) // 昨天那条是 before
    }
}
