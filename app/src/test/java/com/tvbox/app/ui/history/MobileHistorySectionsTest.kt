package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 锁定 [buildHistorySections] / [startOfDayMs] 的按日分组语义：
 * 「今天 / 昨天 / 更早日期」归类、保持仓库倒序、跨日零点刷新、分组头 key 唯一性。
 */
class MobileHistorySectionsTest {

    /** 用本地时区构造指定「年/月/日/时/分/秒」对应的 epoch ms。 */
    private fun localTime(
        year: Int,
        month: Int, // 1-based
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, second)
        return c.timeInMillis
    }

    private fun item(movieId: Int, updatedAt: Long) = WatchHistoryItem(
        movieId = movieId, apiLineId = "ruyi", apiLineName = "如意", movieName = "m$movieId",
        posterUrl = "", typeName = "", remarks = "", sourceIndex = 0, sourceName = "默认",
        episodeIndex = 0, episodeTitle = "第1集", episodeUrl = "u$movieId",
        positionMs = 0, durationMs = 0, updatedAtEpochMs = updatedAt,
    )

    @Test
    fun startOfDayZeroesTimeComponents() {
        val now = localTime(2026, 11, 18, 14, 30, 45)
        val start = startOfDayMs(now)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun groupsIntoTodayYesterdayAndOlder() {
        val now = localTime(2026, 11, 18, 12, 0, 0) // 2026-11-18 中午
        val today = localTime(2026, 11, 18, 8, 0, 0)
        val yesterday = localTime(2026, 11, 17, 22, 0, 0)
        val older = localTime(2026, 11, 15, 10, 0, 0)

        // buildHistorySections 用 LinkedHashMap 保持输入顺序；调用方（仓库）已是「最近在前」。
        val sections = buildHistorySections(
            listOf(item(3, today), item(2, yesterday), item(1, older)),
            now,
        )

        // 保持仓库倒序：最近在前。
        assertEquals(listOf("今天", "昨天", "11月15日"), sections.map { it.label })
        assertEquals(listOf(3), sections[0].items.map { it.movieId })
        assertEquals(listOf(2), sections[1].items.map { it.movieId })
        assertEquals(listOf(1), sections[2].items.map { it.movieId })
    }

    @Test
    fun sameDayItemsCollapseIntoOneGroup() {
        val now = localTime(2026, 11, 18, 12, 0, 0)
        val sections = buildHistorySections(
            listOf(
                item(1, localTime(2026, 11, 18, 9, 0, 0)),
                item(2, localTime(2026, 11, 18, 10, 0, 0)),
                item(3, localTime(2026, 11, 18, 11, 0, 0)),
            ),
            now,
        )
        assertEquals(1, sections.size)
        assertEquals("今天", sections[0].label)
        assertEquals(3, sections[0].items.size)
    }

    @Test
    fun emptyListYieldsNoSections() {
        assertTrue(buildHistorySections(emptyList(), System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun labelsRefreshAcrossMidnight() {
        // 观看时间落在 23:00，其「今天/昨天」归属随 now 所处的自然日变化。
        val viewedAt = localTime(2026, 11, 18, 23, 0, 0)

        // 同一时刻仍属于「今天」
        val sameDayNow = localTime(2026, 11, 18, 23, 30, 0)
        assertEquals("今天", buildHistorySections(listOf(item(1, viewedAt)), sameDayNow)[0].label)

        // 过午夜后同一观看记录变为「昨天」——证明跨天分组会随日期刷新。
        val nextDayNow = localTime(2026, 11, 19, 0, 30, 0)
        assertEquals("昨天", buildHistorySections(listOf(item(1, viewedAt)), nextDayNow)[0].label)
    }

    @Test
    fun crossYearOlderDateIncludesYear() {
        // 更早记录与 now 不在同一自然年：日期标签应带年份，避免「1月5日」跨年歧义。
        val now = localTime(2026, 1, 8, 12, 0, 0)
        val sameYear = localTime(2026, 1, 5, 9, 0, 0)
        val crossYear = localTime(2025, 12, 20, 9, 0, 0)
        val sections = buildHistorySections(
            listOf(item(1, crossYear), item(2, sameYear)),
            now,
        )
        // 输入顺序即输出顺序（LinkedHashMap）：跨年的在前、同年的在后。
        assertEquals(listOf("2025年12月20日", "1月5日"), sections.map { it.label })
    }
}
