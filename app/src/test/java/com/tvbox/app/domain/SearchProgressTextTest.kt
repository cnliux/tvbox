package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchProgressTextTest {

    @Test
    fun completedKeepsOriginalWording() {
        assertEquals(
            "已完成 1/3 条线路 · 找到 7 个结果",
            completedSearchProgressText(1, 3, 7),
        )
    }

    @Test
    fun activeKeepsOriginalWording() {
        assertEquals(
            "正在搜索 3 个来源（已完成 1，找到 7 条结果）",
            activeSearchProgressText(1, 3, 7),
        )
    }

    @Test
    fun progressIsHiddenWhenNoSourceStarted() {
        assertEquals("", completedSearchProgressText(0, 0, 0))
        assertEquals("", activeSearchProgressText(0, 0, 0))
        assertEquals("", completedSearchProgressText(0, -1, 3))
    }

    @Test
    fun completedIsClampedToTotalAndResultCountIsNonNegative() {
        assertEquals(
            "已完成 3/3 条线路 · 找到 0 个结果",
            completedSearchProgressText(9, 3, -5),
        )
        assertEquals(
            "正在搜索 2 个来源（已完成 2，找到 0 条结果）",
            activeSearchProgressText(9, 2, -1),
        )
    }
}
