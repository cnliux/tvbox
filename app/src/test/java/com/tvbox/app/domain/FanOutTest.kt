package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多源并行工具的 JVM 单测。
 *
 * 工程目前只有 junit，没有 kotlinx-coroutines-test，因此这里用 runBlocking +
 * 真实短延迟 + AtomicInteger 手写收集，不引入任何新依赖。
 */
class FanOutTest {

    private fun <T : Any> tasksOf(vararg blocks: suspend () -> T): List<FanOutTask<T>> =
        blocks.map { block -> FanOutTask<T> { block() } }

    @Test
    fun emitsInCompletionOrderWithMonotonicProgress() = runBlocking {
        // 进度单调性是 fanOutIncrementally 的纯逻辑保证（完成即 emit、completedSources 递增），
        // 不应依赖墙钟完成顺序——真实 delay 的先后在 CI 受限 CPU 上会被调度抖动打乱
        // （10ms/30ms 间隔可倒置），导致断言 flaky 失败。
        // maxParallel=1 强制串行：完成顺序 == 提交顺序，由调度器确定性保证，
        // 只用 1ms 间隔保持测试快速，且不再受 CPU 争抢影响。
        val updates = fanOutIncrementally(
            tasks = tasksOf(
                { delay(2L); "slow" },
                { delay(1L); "fast" },
                { "mid" },
            ),
            maxParallel = 1,
            perSourceTimeoutMs = 1_000L,
        ).toList()

        assertEquals(listOf("slow", "fast", "mid"), updates.mapNotNull { it.value })
        assertEquals(listOf(0, 1, 2), updates.map { it.sourceIndex })
        assertEquals(listOf(1, 2, 3), updates.map { it.completedSources })
        assertTrue(updates.all { it.totalSources == 3 })
    }

    @Test
    fun perSourceTimeoutSkipsOnlyTheSlowSource() = runBlocking {
        val updates = fanOutIncrementally(
            tasks = tasksOf(
                { delay(500L); "never" },
                { "ok" },
            ),
            maxParallel = 2,
            perSourceTimeoutMs = 40L,
        ).toList()

        assertEquals(2, updates.size)
        assertEquals(listOf("ok"), updates.mapNotNull { it.value })
        assertEquals(1, updates.count { !it.isSuccess })
        assertEquals(2, updates.maxOf { it.completedSources })
    }

    @Test
    fun singleFailureIsIsolatedFromOtherSources() = runBlocking {
        val updates = fanOutIncrementally(
            tasks = tasksOf<String>(
                { throw IllegalStateException("boom") },
                { "good" },
            ),
            maxParallel = 2,
            perSourceTimeoutMs = 1_000L,
        ).toList()

        assertEquals(2, updates.size)
        assertEquals(listOf("good"), updates.mapNotNull { it.value })
        assertEquals(1, updates.count { !it.isSuccess })
    }

    @Test
    fun respectsMaxParallelLimit() = runBlocking {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val tasks: List<FanOutTask<Int>> = (1..5).map { index ->
            FanOutTask<Int> {
                val now = running.incrementAndGet()
                peak.getAndUpdate { old -> maxOf(old, now) }
                delay(20L)
                running.decrementAndGet()
                index
            }
        }

        val updates = fanOutIncrementally(
            tasks = tasks,
            maxParallel = 2,
            perSourceTimeoutMs = 1_000L,
        ).toList()

        assertEquals(5, updates.size)
        assertTrue("observed peak=${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun cancellationPropagatesToEverySource() = runBlocking {
        val started = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val tasks: List<FanOutTask<Int>> = (1..3).map { index ->
            FanOutTask<Int> {
                started.incrementAndGet()
                try {
                    delay(5_000L)
                } catch (e: CancellationException) {
                    cancelled.incrementAndGet()
                    throw e
                }
                index
            }
        }

        val job = launch {
            fanOutIncrementally(tasks, maxParallel = 3, perSourceTimeoutMs = 10_000L).toList()
        }
        withTimeoutOrNull(500L) {
            while (started.get() < 3) delay(5L)
        }
        assertEquals(3, started.get())

        job.cancel()
        job.join()
        assertEquals(3, cancelled.get())
    }

    @Test
    fun emptyTaskListProducesNothing() = runBlocking {
        assertTrue(fanOutIncrementally(emptyList<FanOutTask<String>>()).toList().isEmpty())
    }

    @Test
    fun invalidArgumentsAreRejected() {
        val result = runCatching {
            runBlocking {
                fanOutIncrementally(listOf(FanOutTask { "x" }), maxParallel = 0).toList()
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
