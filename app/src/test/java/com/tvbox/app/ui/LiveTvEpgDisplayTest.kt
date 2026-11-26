package com.tvbox.app.ui

import com.tvbox.app.domain.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「节目单越界处理」设置（epgOutOfRangePolicy）的行为锁定：
 * clip=旧口径（取未结束节目/退回尾部），next=只显示下一档，blank=越界留空。
 */
class LiveTvEpgDisplayTest {

    private val hour = 3_600_000L

    // 三段节目：08:00-09:00、09:00-10:00、10:00-11:00（以 base 为 08:00）。
    private val base = 1_767_240_000_000L
    private val programs = listOf(
        EpgProgram("A", base, base + hour),
        EpgProgram("B", base + hour, base + 2 * hour),
        EpgProgram("C", base + 2 * hour, base + 3 * hour),
    )

    @Test
    fun inRangeStartsFromCurrentProgram() {
        val rows = buildLiveEpgDisplay(programs, nowMs = base + 30 * 60_000L, rows = 2)
        assertEquals(listOf("A", "B"), rows.map { it.title })
    }

    @Test
    fun clipBetweenProgramsShowsNextUntilEnd() {
        // 越界（A 已结束、B 未开始）：clip 取第一条未结束的节目起。
        val rows = buildLiveEpgDisplay(programs, nowMs = base + hour, rows = 2, outOfRangePolicy = "clip")
        assertEquals(listOf("B", "C"), rows.map { it.title })
    }

    @Test
    fun clipAfterAllProgramsFallsBackToTail() {
        val rows = buildLiveEpgDisplay(programs, nowMs = base + 4 * hour, rows = 2)
        assertEquals(listOf("B", "C"), rows.map { it.title })
    }

    @Test
    fun nextShowsUpcomingAndBlanksWhenAllEnded() {
        val upcoming = buildLiveEpgDisplay(programs, nowMs = base - hour, rows = 2, outOfRangePolicy = "next")
        assertEquals(listOf("A", "B"), upcoming.map { it.title })
        val ended = buildLiveEpgDisplay(programs, nowMs = base + 4 * hour, rows = 2, outOfRangePolicy = "next")
        assertEquals(emptyList<String>(), ended.map { it.title })
    }

    @Test
    fun blankPolicyEmptiesOutOfRangeOnly() {
        assertEquals(emptyList<String>(), buildLiveEpgDisplay(programs, nowMs = base + 4 * hour, outOfRangePolicy = "blank").map { it.title })
        assertEquals(listOf("A", "B", "C"), buildLiveEpgDisplay(programs, nowMs = base + 10 * 60_000L, rows = 3, outOfRangePolicy = "blank").map { it.title })
    }
}
