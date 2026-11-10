package com.tvbox.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 100/141 轮：验证 fan-out 工具支持按来源索引分配超时预算，且不改变缺省语义。 */
class FanOutWeightedTimeoutTest {

    @Test
    fun perSourceBudgetTimesOutOnlyTheSlowSource() = runBlocking {
        val tasks = listOf(
            FanOutTask<String> { delay(50L); "slow" },
            FanOutTask<String> { "fast" },
        )
        val updates = fanOutIncrementally(
            tasks = tasks,
            maxParallel = 2,
            perSourceTimeoutMs = 1_000L,
            timeoutForSource = { index -> if (index == 0) 1L else 500L },
        ).toList()
        assertEquals(2, updates.size)
        assertNull(updates.first { it.sourceIndex == 0 }.value)
        assertEquals("fast", updates.first { it.sourceIndex == 1 }.value)
        assertEquals(2, updates.maxOf { it.completedSources })
    }

    @Test
    fun withoutBudgetAllSourcesStillUseUniformTimeout() = runBlocking {
        val tasks = listOf(
            FanOutTask<String> { delay(50L); "a" },
            FanOutTask<String> { delay(50L); "b" },
        )
        val updates = fanOutIncrementally(
            tasks = tasks,
            maxParallel = 2,
            perSourceTimeoutMs = 1L,
        ).toList()
        assertEquals(2, updates.size)
        assertTrue(updates.all { it.value == null })
    }

    @Test
    fun splitBudgetMatchesWeightedTimeouts() {
        assertEquals(listOf(4_000L, 2_000L, 800L), splitSearchTimeoutBudget(listOf(100, 50, 20), 4_000L))
        assertTrue(splitSearchTimeoutBudget(emptyList(), 4_000L).isEmpty())
    }
}
