package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 视频源批量测速的公共并发骨架。
 *
 * 之前 ViewModel 里三处手写同一套样板（用户一键测速 / 详情页线路测速 / 后台健康管理）：
 * `Semaphore(3)` + `Mutex` + completed 计数 + 单条完成即回调。差异只在单源测速函数
 * 与每条结果的消费方式，现收敛为一个可注入两者的纯协程函数。
 *
 * 语义与原实现保持一致：
 * - 并发上限 [parallelism]（原三处均为 3）；
 * - 单源测速抛 [CancellationException] 时立即透传，不被吞成 null；
 * - 每条结果在锁内回调 [onResult]，保证「结果写入 + 进度计数」对外原子可见，
 *   调用方可在回调里做一次状态合并（如 `latencies + (id to latency)` 与进度一起 update）；
 * - 返回完整结果表（含失败的 null 值），调用方无需再自行汇总。
 */
suspend fun measureSourceLatencies(
    lineIds: List<String>,
    parallelism: Int = 3,
    test: suspend (String) -> Long?,
    onResult: suspend (lineId: String, latency: Long?, completed: Int, total: Int) -> Unit =
        { _, _, _, _ -> },
): Map<String, Long?> {
    if (lineIds.isEmpty()) return emptyMap()
    val results = mutableMapOf<String, Long?>()
    val mutex = Mutex()
    var completed = 0
    coroutineScope {
        val semaphore = Semaphore(parallelism.coerceAtLeast(1))
        for (id in lineIds) {
            launch {
                semaphore.withPermit {
                    val latency = runCatching { test(id) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    mutex.withLock {
                        completed++
                        results[id] = latency
                        onResult(id, latency, completed, lineIds.size)
                    }
                }
            }
        }
    }
    return results.toMap()
}
