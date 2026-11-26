package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-29：ANR 保守降级模式的纯函数判定（弹幕 / 搜索并发 / 探针超时 / 琥珀条）。 */
class AnrConservativePolicyTest {

    @Test
    fun `normal mode keeps full feature set`() {
        assertTrue(AnrConservativePolicy.danmakuEnabled(false))
        assertEquals(6, AnrConservativePolicy.searchParallelism(false))
        assertEquals(8_000L, AnrConservativePolicy.probeTimeoutMs(false))
        assertFalse(AnrConservativePolicy.showAmberBanner(false))
    }

    @Test
    fun `conservative mode degrades resources and shows amber banner`() {
        assertFalse(AnrConservativePolicy.danmakuEnabled(true))
        assertEquals(3, AnrConservativePolicy.searchParallelism(true))
        assertEquals(3_000L, AnrConservativePolicy.probeTimeoutMs(true))
        assertTrue(AnrConservativePolicy.showAmberBanner(true))
    }

    @Test
    fun `conservative bounds are strictly below normal values`() {
        // 并发与超时都必须收紧，否则降级模式没有资源收益。
        assertTrue(AnrConservativePolicy.searchParallelism(true) < AnrConservativePolicy.searchParallelism(false))
        assertTrue(AnrConservativePolicy.probeTimeoutMs(true) < AnrConservativePolicy.probeTimeoutMs(false))
        assertFalse(AnrConservativePolicy.danmakuEnabled(true))
        assertTrue(AnrConservativePolicy.showAmberBanner(true))
    }
}
