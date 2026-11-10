package com.tvbox.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/** 带权重的来源任务：权重越高，分到的单源超时预算越长。 */
data class WeightedSourceTask<T : Any>(
    val task: FanOutTask<T>,
    val weight: Int = 100,
)

/** 按权重把基准超时拆成单源超时；权重为 0 的来源仍保留最小预算。 */
fun weightedTimeouts(
    weights: List<Int>,
    baseTimeoutMs: Long,
    minTimeoutMs: Long = 500L,
): List<Long> {
    require(baseTimeoutMs > 0L) { "baseTimeoutMs must be positive" }
    if (weights.isEmpty()) return emptyList()
    val normalized = weights.map { it.coerceAtLeast(0) }
    val max = normalized.maxOrNull()?.takeIf { it > 0 } ?: 1
    return normalized.map { weight -> maxOf(minTimeoutMs, baseTimeoutMs * weight / max) }
}

/**
 * 多源影片并行搜索：按权重分配单源超时、先到先发、按 (来源, 影片 id) 增量去重。
 *
 * 首页与搜索页共用这一条 fan-out/fan-in 路径；页面退出时 flow 取消会一并传播到每条来源。
 */
fun searchMoviesIncrementally(
    sources: List<WeightedSourceTask<List<Movie>>>,
    baseTimeoutMs: Long = 4_000L,
    maxParallel: Int = 3,
): Flow<MultiSourceSearchUpdate> = channelFlow {
    if (sources.isEmpty()) return@channelFlow
    val timeouts = weightedTimeouts(sources.map { it.weight }, baseTimeoutMs)
    val tasks: List<FanOutTask<List<Movie>>> = sources.mapIndexed { index, source ->
        FanOutTask<List<Movie>> {
            withTimeoutOrNull(timeouts[index]) { source.task.fetch() }.orEmpty()
        }
    }
    var merged: List<Movie> = emptyList()
    fanOutIncrementally(
        tasks = tasks,
        maxParallel = maxParallel,
        perSourceTimeoutMs = timeouts.maxOrNull() ?: baseTimeoutMs,
    ).collect { update ->
        merged = mergeMoviesBySourceAndId(merged, update.value.orEmpty())
        send(MultiSourceSearchUpdate(merged, update.completedSources, update.totalSources))
    }
}
