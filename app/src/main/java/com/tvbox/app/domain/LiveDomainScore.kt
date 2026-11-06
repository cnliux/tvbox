package com.tvbox.app.domain

/**
 * 直播线路的"按域名打分"记忆。
 *
 * 现有 [LiveLinePreference] 只记"这个频道上次用第几号线"，换源或频道排版变化后即失效；
 * [playbackHealthKey] 记的是影片/集次维度。这里补一层**域名维度**的评分：
 * 同一域名下所有频道的成败会互相累积，源里通道很多时，能直接把"这家的 CDN 最近不行"先排掉。
 *
 * 设计要点：
 * - 只用于**排序**，不用于硬性剔除：全部线路都黑历史时仍要能播（否则等于把用户锁死）。
 * - 半衰期衰减：按"上次活动距现在多久"折减，避免一次网络波动永久压制某域名。
 * - 上限 [LIVE_DOMAIN_MAX_ENTRIES] 条，按最近活动时间淘汰，避免持久化无限增长。
 */
data class LiveDomainScore(
    val domain: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastSuccessAtMs: Long = 0L,
    val lastFailureAtMs: Long = 0L,
    val lastActivityAtMs: Long = 0L,
) {
    /**
     * 评分越高越优先。成功加权、失败加罚，并按衰减后的时间远近折算。
     * 从未记录过的域名得 0 分，介于"一直失败"与"偶尔成功"之间——首播时仍然是源内顺序。
     */
    fun score(nowMs: Long): Double {
        val success = decayedWeight(lastSuccessAtMs, nowMs) * successCount
        val failure = decayedWeight(lastFailureAtMs, nowMs) * failureCount
        return success * 1.0 - failure * 1.5
    }

    private fun decayedWeight(atMs: Long, nowMs: Long): Double {
        if (atMs <= 0L) return 0.0
        val ageMs = (nowMs - atMs).coerceAtLeast(0L)
        return Math.pow(0.5, ageMs.toDouble() / LIVE_DOMAIN_HALF_LIFE_MS.toDouble())
    }

    companion object {
        /** 半衰期 12 小时：半天内失败的域名仍被明显压低，一天后基本恢复。 */
        const val LIVE_DOMAIN_HALF_LIFE_MS = 12L * 60 * 60 * 1000L
    }
}

/** 评分表容量上限：超出后按最近活动时间淘汰最久未用的域名。 */
const val LIVE_DOMAIN_MAX_ENTRIES = 200

/** 从线路地址取域名作为评分键；解析失败返回 null（该线路不参与域名优选）。 */
fun liveLineDomain(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        java.net.URI(trimmed).host?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * 按域名评分对线路下标排序：分数高的在前，同分保持源内原顺序（稳定排序），
 * 未记录过的线路得 0 分，会排在"明确失败过"的线路之前、排在"明确成功过"的之后。
 */
fun rankLiveLineIndicesByDomain(
    lines: List<LiveChannelLine>,
    scores: Map<String, LiveDomainScore>,
    nowMs: Long,
): List<Int> {
    if (lines.size <= 1) return lines.indices.toList()
    return lines.indices.sortedWith(
        compareByDescending<Int> { index ->
            val domain = liveLineDomain(lines[index].url) ?: return@compareByDescending 0.0
            scores[domain]?.score(nowMs) ?: 0.0
        }.thenBy { it },
    )
}

/**
 * 记忆合并：成功/失败各 +1，并刷新对应时间戳。
 * 超过容量上限时按最近活动时间淘汰，保留活跃域名。
 */
fun updateLiveDomainScores(
    current: Map<String, LiveDomainScore>,
    domain: String,
    success: Boolean,
    nowMs: Long,
    maxEntries: Int = LIVE_DOMAIN_MAX_ENTRIES,
): Map<String, LiveDomainScore> {
    if (domain.isBlank()) return current
    val key = domain.lowercase()
    val old = current[key] ?: LiveDomainScore(domain = key)
    val updated = if (success) {
        old.copy(
            successCount = old.successCount + 1,
            lastSuccessAtMs = nowMs,
            lastActivityAtMs = nowMs,
        )
    } else {
        old.copy(
            failureCount = old.failureCount + 1,
            lastFailureAtMs = nowMs,
            lastActivityAtMs = nowMs,
        )
    }
    val merged = current + (key to updated)
    if (merged.size <= maxEntries) return merged
    return merged.entries
        .sortedByDescending { (_, entry) -> entry.lastActivityAtMs }
        .take(maxEntries)
        .associate { (k, v) -> k to v }
}
