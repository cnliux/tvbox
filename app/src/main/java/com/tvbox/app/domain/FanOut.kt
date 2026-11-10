package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 一条来源的取数任务。由调用方提供，domain 层不关心网络细节。 */
fun interface FanOutTask<T : Any> {
    suspend fun fetch(): T
}

/**
 * 一条来源结束时立即产出的增量结果。
 *
 * [value] 为 null 表示该来源超时或失败；即使失败也会发出一条记录，
 * 让 UI 的「已完成 N/M 条线路」进度能走到终点。
 */
data class FanOutUpdate<T : Any>(
    val sourceIndex: Int,
    val value: T?,
    val completedSources: Int,
    val totalSources: Int,
) {
    val isSuccess: Boolean get() = value != null
}

/**
 * 多源并行 fan-out / fan-in 的统一入口。
 *
 * 语义：
 * - **先到先显示**：每条来源一结束就 emit，不等待最慢的一条；
 * - **per-source timeout**：单条来源超时只让该条失败，不影响其他来源；
 * - **失败隔离**：单条来源抛异常只 emit 一条失败记录；[CancellationException] 原样上抛，
 *   不误判成接口失败，也不继续尝试备用线路；
 * - **限流**：[maxParallel] 控制在途请求数，避免低端盒子同时打满多条线路；
 * - **取消传播**：flow 被取消（页面退出、发起新搜索）时，所有子任务随父作用域一起取消。
 *
 * 所有来源结束后 flow 正常完成，不抛异常；调用方只需按 [FanOutUpdate.isSuccess] 合并结果。
 */
fun <T : Any> fanOutIncrementally(
    tasks: List<FanOutTask<T>>,
    maxParallel: Int = 3,
    perSourceTimeoutMs: Long = 4_000L,
    /**
     * 可选的按来源索引分配超时预算（第 100/141 轮：高权重来源给更长预算）。
     * 不传时退化为统一 [perSourceTimeoutMs]，既有调用点语义不变。
     */
    timeoutForSource: ((Int) -> Long)? = null,
): Flow<FanOutUpdate<T>> = channelFlow {
    require(maxParallel > 0) { "maxParallel must be positive" }
    require(perSourceTimeoutMs > 0) { "perSourceTimeoutMs must be positive" }
    val total = tasks.size
    if (total == 0) return@channelFlow

    val completed = AtomicInteger(0)
    val semaphore = Semaphore(maxParallel)
    tasks.forEachIndexed { index, task ->
        launch {
            val sourceTimeoutMs = (timeoutForSource?.invoke(index) ?: perSourceTimeoutMs).coerceAtLeast(1L)
            val value: T? = semaphore.withPermit {
                try {
                    withTimeoutOrNull(sourceTimeoutMs) { task.fetch() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val done = completed.incrementAndGet()
            send(FanOutUpdate(index, value, done, total))
        }
    }
}
