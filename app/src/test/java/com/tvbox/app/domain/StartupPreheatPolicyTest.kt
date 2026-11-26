package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5-30：启动预热目标决策纯函数。 */
class StartupPreheatPolicyTest {

    @Test
    fun `tv with normal memory preheats all three targets`() {
        assertEquals(
            listOf(StartupPreheatPolicy.PREFS, StartupPreheatPolicy.DOUBAN_HOT, StartupPreheatPolicy.UPDATE_REPO),
            StartupPreheatPolicy.shouldPreheat(isTv = true, isLowMemory = false),
        )
    }

    @Test
    fun `phone preheats home-path targets but defers update repo`() {
        assertEquals(
            listOf(StartupPreheatPolicy.PREFS, StartupPreheatPolicy.DOUBAN_HOT),
            StartupPreheatPolicy.shouldPreheat(isTv = false, isLowMemory = false),
        )
    }

    @Test
    fun `low memory preheats prefs only regardless of device form`() {
        assertEquals(listOf(StartupPreheatPolicy.PREFS), StartupPreheatPolicy.shouldPreheat(isTv = true, isLowMemory = true))
        assertEquals(listOf(StartupPreheatPolicy.PREFS), StartupPreheatPolicy.shouldPreheat(isTv = false, isLowMemory = true))
    }

    @Test
    fun `prefs is always preheated as the startup-critical target`() {
        // 设置 prefs 是启动关键路径，任何形态/内存条件下都必须预热。
        for (isTv in listOf(true, false)) {
            for (isLowMemory in listOf(true, false)) {
                assertTrue(
                    "低内存/手机/电视组合下都必须预热 PREFS",
                    StartupPreheatPolicy.PREFS in StartupPreheatPolicy.shouldPreheat(isTv, isLowMemory),
                )
            }
        }
    }
}
