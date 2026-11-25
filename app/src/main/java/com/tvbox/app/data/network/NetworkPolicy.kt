package com.tvbox.app.data.network

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * 网络出口统一口径（对应《聚合视频TVBox全项目优化方案》P36）：
 * 集中收敛 connect/read/write/call 超时、连接池空闲连接数与 keepAlive、
 * 响应体大小上限以及 DNS 缓存 TTL。各仓库通过 [applyPolicy] 套用，
 * 避免各处再手写一套 OkHttpClient.Builder 超时。
 *
 * 说明：body cap 与 DNS 缓存需在拦截器/Dns 层消费，本类只承载口径数值；
 * [PolicyEventListener] 提供调用次数/失败/平均耗时的运行时观测。
 */
data class NetworkPolicy(
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val writeTimeoutMs: Long = 15_000,
    val callTimeoutMs: Long = 30_000,
    val maxIdleConnections: Int = 5,
    val keepAliveSeconds: Long = 300,
    /** 响应体大小上限，默认 20 MiB；超过应由调用方按流式/分片策略处理。 */
    val maxBodyBytes: Long = 20L * 1024 * 1024,
    /** DNS 解析结果缓存 TTL，供 Dns 实现参考。 */
    val dnsCacheTtlMs: Long = 60_000,
) {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs 必须为正" }
        require(readTimeoutMs > 0) { "readTimeoutMs 必须为正" }
        require(writeTimeoutMs > 0) { "writeTimeoutMs 必须为正" }
        require(callTimeoutMs > 0) { "callTimeoutMs 必须为正" }
        require(maxIdleConnections >= 0) { "maxIdleConnections 不能为负" }
        require(keepAliveSeconds >= 0) { "keepAliveSeconds 不能为负" }
        require(maxBodyBytes > 0) { "maxBodyBytes 必须为正" }
        require(dnsCacheTtlMs >= 0) { "dnsCacheTtlMs 不能为负" }
    }
}

/** 项目内预设口径：默认业务、直播探测（短超时）、大文件下载（长读 + 大 body cap）。 */
object NetworkPolicies {
    val default = NetworkPolicy()
    val probe = NetworkPolicy(callTimeoutMs = 10_000, maxBodyBytes = 1L * 1024 * 1024)
    val largeDownload = NetworkPolicy(readTimeoutMs = 60_000, maxBodyBytes = 200L * 1024 * 1024)
}

/**
 * 将 [NetworkPolicy] 统一套用到 OkHttpClient.Builder：覆盖连接/读/写/整次调用
 * 超时，并替换为按 policy 构建的 [ConnectionPool]，保证全项目连接复用口径一致。
 */
fun OkHttpClient.Builder.applyPolicy(policy: NetworkPolicy): OkHttpClient.Builder =
    connectTimeout(policy.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(policy.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(policy.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(policy.callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectionPool(
            ConnectionPool(policy.maxIdleConnections, policy.keepAliveSeconds, TimeUnit.SECONDS)
        )

/** 调用统计快照：总调用数、失败数、平均耗时（毫秒）。 */
data class PolicyStats(
    val totalCalls: Int,
    val failedCalls: Int,
    val avgDurationMs: Long,
)

/**
 * 基于策略口径的 [EventListener]：在 callEnd/callFailed 记录耗时与失败，
 * 通过 [snapshot] 输出可观测指标，供验证连接复用、慢速攻击与取消场景。
 */
class PolicyEventListener(private val policy: NetworkPolicy) : EventListener() {
    private val totalCalls = AtomicInteger(0)
    private val failedCalls = AtomicInteger(0)
    private val durationSumMs = AtomicLong(0L)
    private val startNanos = AtomicLong(0L)

    override fun callStart(call: okhttp3.Call) {
        startNanos.set(System.nanoTime())
    }

    override fun callEnd(call: okhttp3.Call) {
        val elapsedMs = elapsedSinceStartMs()
        totalCalls.incrementAndGet()
        durationSumMs.addAndGet(elapsedMs)
    }

    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
        val elapsedMs = elapsedSinceStartMs()
        totalCalls.incrementAndGet()
        failedCalls.incrementAndGet()
        durationSumMs.addAndGet(elapsedMs)
    }

    /** 当前累计统计；无成功耗时样本时 avgDurationMs 为 0。 */
    fun snapshot(): PolicyStats {
        val total = totalCalls.get()
        val avg = if (total == 0) 0L else durationSumMs.get() / total
        return PolicyStats(
            totalCalls = total,
            failedCalls = failedCalls.get(),
            avgDurationMs = avg,
        )
    }

    private fun elapsedSinceStartMs(): Long {
        val start = startNanos.getAndSet(0L)
        if (start == 0L) return 0L
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }
}
