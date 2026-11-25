package com.tvbox.app.data.network

import kotlin.random.Random

/**
 * 重试的种类。三类共用同一个仲裁器，避免「OkHttp 重试 × 源探测 × 播放换源 × OTA」叠加成一次点击 6~11 倍流量。
 */
enum class RetryKind {
    /** 源探活/测速。 */
    PROBE,
    /** 播放失败换源。 */
    PLAYBACK,
    /** OTA 下载：最严，见 P2-17。 */
    OTA,
}

/**
 * 单一重试仲裁器（T1-07）：全仓「再试一次」的唯一决策点。
 *
 * 以归一化 host 为键，在滚动窗口内限制每类重试次数；同 host 的不同种类分别计数。
 * [backoffMs] 提供带全抖动的指数退避，禁止固定 sleep（固定间隔等于自证脚本）。
 *
 * 时间源默认取单调时钟（[System.nanoTime]），避免用户改系统时间导致窗口永不滚动或立即滚动。
 * 纯 Kotlin 实现，不依赖 Android，便于 JVM 单测。
 */
class RetryArbiter(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: Random = Random.Default,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val limits: Map<RetryKind, Int> = DEFAULT_LIMITS,
) {
    private data class Attempt(
        var windowStart: Long,
        val used: MutableMap<RetryKind, Int> = mutableMapOf(),
    )

    private val budget = mutableMapOf<String, Attempt>()

    /**
     * 本次重试是否获准。获准时立即记账；未获准不记账（调用方必须直接失败，不得自行重试）。
     * 空白/非法 host 一律拒绝，避免绕过预算。
     */
    @Synchronized
    fun permit(host: String, kind: RetryKind): Boolean {
        val key = normalizeHost(host)
        if (key.isEmpty()) return false
        val now = nowMs()
        val attempt = budget.getOrPut(key) { Attempt(now) }
        if (now - attempt.windowStart > windowMs || now < attempt.windowStart) {
            attempt.windowStart = now
            attempt.used.clear()
        }
        val limit = limits[kind] ?: return false
        val used = attempt.used[kind] ?: 0
        if (used >= limit) return false
        attempt.used[kind] = used + 1
        return true
    }

    /** 指数退避 + 0..399ms 全抖动；n 被夹在 0..4，避免溢出。 */
    fun backoffMs(n: Int): Long {
        val shift = n.coerceIn(0, MAX_BACKOFF_SHIFT)
        return BASE_BACKOFF_MS * (1L shl shift) + random.nextLong(0, JITTER_MS)
    }

    /** 当前窗口内某 host 已用次数（供诊断页/单测读取）。 */
    @Synchronized
    fun used(host: String, kind: RetryKind): Int =
        budget[normalizeHost(host)]?.used?.get(kind) ?: 0

    /** 清空预算（切换订阅、FactoryReset、单测隔离时调用）。 */
    @Synchronized
    fun reset() {
        budget.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        private const val BASE_BACKOFF_MS = 250L
        private const val JITTER_MS = 400L
        private const val MAX_BACKOFF_SHIFT = 4

        val DEFAULT_LIMITS: Map<RetryKind, Int> = mapOf(
            RetryKind.PROBE to 2,
            RetryKind.PLAYBACK to 3,
            RetryKind.OTA to 2,
        )

        /**
         * 把 URL 或 `host:port` 收敛为小写 host：去 scheme、userinfo、port、路径与结尾点，
         * IPv6 字面量去方括号。返回空串表示非法输入，调用方应拒绝重试。
         */
        fun normalizeHost(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            val scheme = value.indexOf("://")
            if (scheme >= 0) value = value.substring(scheme + 3)
            val at = value.lastIndexOf('@')
            if (at >= 0) value = value.substring(at + 1)
            val slash = value.indexOfAny(charArrayOf('/', '?', '#'))
            if (slash >= 0) value = value.substring(0, slash)
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end < 0) return ""
                return value.substring(1, end).lowercase()
            }
            val colon = value.lastIndexOf(':')
            // 仅当冒号后全是数字时视为端口；IPv6 未加括号的情况原样保留（少见且不合法）。
            if (colon > 0 && value.substring(colon + 1).all { it.isDigit() }) {
                value = value.substring(0, colon)
            }
            return value.trimEnd('.').lowercase()
        }
    }
}
