package com.tvbox.app.domain

/**
 * 多源探活状态机与 fan-out 门控（第 211–220 轮）。
 *
 * 与 `SourceManagerState.shouldJoinFanOut` / `orderManagedSources` 联动：探活结果先转成
 * 健康度快照，再用同一套规则决定「哪些源参与本次并行搜索」，并按权重拆单源超时预算。
 * 全部为纯函数，页面退出时不持有任何在途状态。
 */

private const val SLOW_SOURCE_LATENCY_MS = 2_000L
private const val SOURCE_FAILURE_THRESHOLD = 3

data class SourceProbeResult(
    val sourceId: String,
    val success: Boolean,
    val latencyMs: Long = 0L,
    val atMs: Long,
)

/** 一次探活后的健康度转移：成功清零连续失败，失败累计到阈值才标为不可用。 */
fun nextSourceHealth(
    previous: SourceHealthSnapshot?,
    result: SourceProbeResult,
): SourceHealthSnapshot {
    if (result.success) {
        val latency = result.latencyMs.coerceAtLeast(0L)
        return SourceHealthSnapshot(
            sourceId = result.sourceId,
            availability = if (latency >= SLOW_SOURCE_LATENCY_MS) {
                SourceAvailability.Degraded
            } else {
                SourceAvailability.Available
            },
            consecutiveFailures = 0,
            lastCheckedAtMs = result.atMs,
            latencyMs = latency,
        )
    }
    val failures = (previous?.consecutiveFailures ?: 0) + 1
    return SourceHealthSnapshot(
        sourceId = result.sourceId,
        availability = if (failures >= SOURCE_FAILURE_THRESHOLD) {
            SourceAvailability.Unavailable
        } else {
            SourceAvailability.Degraded
        },
        consecutiveFailures = failures,
        lastCheckedAtMs = result.atMs,
        latencyMs = previous?.latencyMs ?: 0L,
    )
}

/**
 * 参与本次 fan-out 的源：冷却窗口内连续失败的源被剔除；全部被剔除时退回原列表，
 * 避免「体检全失败」导致首页与搜索彻底空白（可用性优先于严格门控）。
 */
fun eligibleFanOutSources(
    sources: List<ManagedSource>,
    health: Map<String, SourceHealthSnapshot>,
    nowMs: Long,
): List<ManagedSource> {
    val eligible = sources.filter { shouldJoinFanOut(health[it.id], nowMs) }
    val finalSources = eligible.ifEmpty { sources }
    return orderManagedSources(finalSources, health)
}

/** 与门控后的源顺序一一对应的单源超时预算，供 `fanOutIncrementally.timeoutForSource` 使用。 */
fun sourceTimeoutBudget(sources: List<ManagedSource>, baseTimeoutMs: Long): List<Long> =
    splitSearchTimeoutBudget(sources.map { it.weight }, baseTimeoutMs)
