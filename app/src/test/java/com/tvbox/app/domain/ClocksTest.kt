package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T7-46：三时钟语义回归。测试显式传值，避免 android.jar stub。 */
class ClocksTest {
    @Test
    fun `mono is independent of wall clock jumps`() {
        assertEquals(1_000L, Clocks.mono(1_000L))
        assertEquals(9_000L, Clocks.mono(9_000L))
    }

    @Test
    fun `wall uses ntp delta when synced`() {
        Clocks.setNtpOffset(5_000L)
        assertEquals(1_705_000_000_000L + 5_000L, Clocks.wall(1_705_000_000_000L, 100L, 1_700_000_000_000L))
        Clocks.clearNtpOffset()
    }

    @Test
    fun `wall falls back to boot base plus elapsed when rtc is bogus`() {
        Clocks.clearNtpOffset()
        assertEquals(1_700_000_060_000L, Clocks.wall(0L, 60_000L, 1_700_000_000_000L))
    }

    @Test
    fun `untrusted wall clock is flagged`() {
        assertFalse(Clocks.isWallTrusted(0L))
        assertFalse(Clocks.isWallTrusted(1_000L))
        assertTrue(Clocks.isWallTrusted(1_700_000_000_000L))
    }

    @Test
    fun `logical ordering resolves same-millisecond writes`() {
        val first = Clocks.logical()
        val second = Clocks.logical()
        assertTrue(second > first)
        // 裁决键取 max(wall, logical)：同毫秒写入靠逻辑序号打破平局。
        assertEquals(second, Clocks.orderKey(1L, second))
        assertEquals(100L, Clocks.orderKey(100L, 1L))
    }
}
