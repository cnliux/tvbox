package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAreaLayoutTest {

    @Test
    fun `默认值按列数取整并保持整行`() {
        // 手机：2 列，默认 2 行 -> 4 个槽位。
        assertEquals(4, resolveCardSlotCount(requested = 0, columns = 2, defaultSlots = 4))
        // 电视：5 列，默认 1 行 -> 5 个槽位。
        assertEquals(5, resolveCardSlotCount(requested = 0, columns = 5, defaultSlots = 5))
    }

    @Test
    fun `越界数量收敛到整行`() {
        // 3 个在 2 列布局中会留下半行，收敛为 2。
        assertEquals(2, resolveCardSlotCount(requested = 3, columns = 2, defaultSlots = 4))
        // 低于一行时按一行处理。
        assertEquals(2, resolveCardSlotCount(requested = 1, columns = 2, defaultSlots = 4))
        // 超过 3 行上限时收敛到最大整行槽位。
        assertEquals(6, resolveCardSlotCount(requested = 99, columns = 2, defaultSlots = 4))
        assertEquals(15, resolveCardSlotCount(requested = 99, columns = 5, defaultSlots = 5))
    }

    @Test
    fun `列数异常时按一列兜底`() {
        assertEquals(3, resolveCardSlotCount(requested = 3, columns = 0, defaultSlots = 0))
    }

    @Test
    fun `内容不超出槽位时全部展示`() {
        val layout = resolveCardAreaLayout(total = 3, requested = 4, columns = 2, defaultSlots = 4)
        assertEquals(3, layout.visibleCount)
        assertFalse(layout.showMore)
        assertEquals(3, layout.slotCount)
    }

    @Test
    fun `内容超出时用最后一个槽位显示更多`() {
        val layout = resolveCardAreaLayout(total = 8, requested = 4, columns = 2, defaultSlots = 4)
        assertEquals(3, layout.visibleCount)
        assertTrue(layout.showMore)
        // 内容 + 「更多」正好铺满 2 行，不出现半行或空位。
        assertEquals(4, layout.slotCount)
    }

    @Test
    fun `空内容不渲染更多入口`() {
        val layout = resolveCardAreaLayout(total = 0, requested = 4, columns = 2, defaultSlots = 4)
        assertEquals(0, layout.visibleCount)
        assertFalse(layout.showMore)
        assertEquals(0, layout.slotCount)
    }
}
