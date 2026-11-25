package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.tvbox.app.data.network.GlobalUserAgent

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

/**
 * T2-16：探针期间临时覆盖全局 UA 的租约。
 *
 * 测速与业务接口共用 [GlobalUserAgent]，而一轮体检有 3 条并发探针：
 * 若每条各自「读旧值 → 写探针 UA → 写完还原」，先完成的一条会把仍在进行的
 * 探针 UA 还原成播放 UA。这里用引用计数保证只有最后一条离开时才恢复，
 * 不改变并发上限、冷却判定与结果回调语义。
 */
internal object ProbeUaLease {
    private val lock = Any()
    private var depth = 0
    private var baseline: String? = null

    /** 进入探针：首次进入记住原 UA 并切到探针 UA。 */
    fun acquire(probeUa: String) = synchronized(lock) {
        if (depth == 0) {
            baseline = GlobalUserAgent.value
            GlobalUserAgent.value = probeUa
        }
        depth++
    }

    /** 离开探针：最后一次离开才恢复原 UA；取消异常也必须调用。 */
    fun release() = synchronized(lock) {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) {
            baseline?.let { GlobalUserAgent.value = it }
            baseline = null
        }
    }
}
