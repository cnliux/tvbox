package com.tvbox.app.domain

const val PLAYBACK_HEALTH_COOLDOWN_MS = 30 * 60 * 1000L
const val PLAYBACK_HEALTH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L
const val PLAYBACK_HEALTH_MAX_ENTRIES = 300
/** 健康表低水位（滞回）：超过高水位时一次性淘汰到该数量，避免高水位附近每次写入都全表排序。 */
const val PLAYBACK_HEALTH_LOW_WATER = 180
/** 每条线路保留的成功首帧延迟样本上限（环形截断，避免持久化体积膨胀）。 */
const val PLAYBACK_HEALTH_MAX_LATENCY_SAMPLES = 20
/** 首帧延迟达到该值即视为「较慢」，用于播放管家徽章判定。 */
const val PLAYBACK_HEALTH_SLOW_LATENCY_MS = 5_000L
/** 成功率/卡顿率参与判定的最小采样数，样本太少时不下结论。 */
const val PLAYBACK_HEALTH_MIN_RATE_SAMPLES = 3

enum class PlaybackIssueType {
    Error,
    SlowBuffer,
}

data class PlaybackHealthEntry(
    val key: String,
    val lastFailureAtMs: Long = 0L,
    val lastSlowBufferAtMs: Long = 0L,
    /** 最近一次成功播放的时间戳；即旧口径中的 lastSuccessAt。 */
    val lastSuccessAtMs: Long = 0L,
    val failureCount: Int = 0,
    val slowBufferCount: Int = 0,
    val successCount: Int = 0,
    /** 成功播放的首帧延迟样本（毫秒），只保留最近 [PLAYBACK_HEALTH_MAX_LATENCY_SAMPLES] 条。 */
    val successLatencyMs: List<Long> = emptyList(),
    /** 卡顿样本计数：每次记录缓冲/卡顿时累加，用于计算卡顿率。 */
    val stutterSampleCount: Int = 0,
    /**
     * T1-04：最新一条测速响应的时长可信度。
     * HIGH = Content-Range 含完整 TOTAL，可作时长参考；LOW = 有 Content-Length 但不可信；NONE = 无法判断。
     * NONE 时该样本不参与健康评分（见 [com.tvbox.app.domain.contributesToScore]）。
     */
    val durationConfidence: DurationConfidence = DurationConfidence.NONE,
) {
    fun isRecentlyUnhealthy(nowMs: Long, cooldownMs: Long = PLAYBACK_HEALTH_COOLDOWN_MS): Boolean {
        return recentIssueType(nowMs, cooldownMs) != null
    }

    fun recentIssueType(nowMs: Long, cooldownMs: Long = PLAYBACK_HEALTH_COOLDOWN_MS): PlaybackIssueType? {
        val lastIssueAtMs = maxOf(lastFailureAtMs, lastSlowBufferAtMs)
        if (lastIssueAtMs <= 0L) return null
        if (lastSuccessAtMs > lastIssueAtMs) return null
        if (nowMs - lastIssueAtMs >= cooldownMs) return null
        return if (lastSlowBufferAtMs > lastFailureAtMs) {
            PlaybackIssueType.SlowBuffer
        } else {
            PlaybackIssueType.Error
        }
    }

    val latestActivityAtMs: Long
        get() = maxOf(lastFailureAtMs, lastSlowBufferAtMs, lastSuccessAtMs)

    val issueCount: Int
        get() = failureCount + slowBufferCount

    /** 明确的成功/失败尝试次数（卡顿不单独算一次尝试）。 */
    val attemptCount: Int
        get() = successCount + failureCount

    val successRate: Float
        get() = if (attemptCount == 0) 0f else successCount.toFloat() / attemptCount

    val p50LatencyMs: Long?
        get() = percentile50(successLatencyMs)

    /** 卡顿样本占「成功 + 卡顿」样本的比例；没有任何样本时为 0。 */
    val stutterRate: Float
        get() {
            val denominator = successCount + stutterSampleCount
            return if (denominator == 0) 0f else stutterSampleCount.toFloat() / denominator
        }
}

/** 取延迟样本的中位数（P50）；空样本返回 null。纯函数，便于单测。 */
fun percentile50(samples: List<Long>): Long? {
    if (samples.isEmpty()) return null
    val sorted = samples.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2L
    }
}

/** 追加一条成功延迟样本，按上限截断最旧样本。纯函数，返回新对象。 */
fun appendSuccessLatency(
    entry: PlaybackHealthEntry,
    latencyMs: Long,
    maxSamples: Int = PLAYBACK_HEALTH_MAX_LATENCY_SAMPLES,
): PlaybackHealthEntry {
    val safeLatency = latencyMs.coerceAtLeast(0L)
    val limit = maxSamples.coerceAtLeast(0)
    if (limit == 0) return entry
    val updated = (entry.successLatencyMs + safeLatency).takeLast(limit)
    return entry.copy(successLatencyMs = updated)
}

/** 相对时间文案，纯函数（无 Android 依赖）。无样本或时间倒流时返回「未采样」。 */
fun formatLastSuccessAgo(lastSuccessAtMs: Long, nowMs: Long): String {
    if (lastSuccessAtMs <= 0L) return "未采样"
    val ageMs = nowMs - lastSuccessAtMs
    if (ageMs < 0L) return "刚刚"
    if (ageMs < 60_000L) return "刚刚"
    if (ageMs < 3_600_000L) return "${ageMs / 60_000L} 分钟前"
    if (ageMs < 86_400_000L) return "${ageMs / 3_600_000L} 小时前"
    return "${ageMs / 86_400_000L} 天前"
}

/** 播放管家四联指标：成功率 / P50 首帧延迟 / 卡顿率 / 最近成功距离现在。 */
data class PlaybackHealthMetrics(
    val successRate: Float,
    val p50LatencyMs: Long?,
    val stutterRate: Float,
    /** 最近成功距离现在的毫秒数；没有成功样本时为 null。 */
    val lastSuccessAgoMs: Long?,
    val lastSuccessAgoText: String,
    /** 参与统计的尝试次数。 */
    val sampleCount: Int,
)

fun PlaybackHealthEntry.metrics(nowMs: Long): PlaybackHealthMetrics {
    val agoMs = if (lastSuccessAtMs > 0L) (nowMs - lastSuccessAtMs).coerceAtLeast(0L) else null
    return PlaybackHealthMetrics(
        successRate = successRate,
        p50LatencyMs = p50LatencyMs,
        stutterRate = stutterRate,
        lastSuccessAgoMs = agoMs,
        lastSuccessAgoText = formatLastSuccessAgo(lastSuccessAtMs, nowMs),
        sampleCount = attemptCount,
    )
}

/**
 * 播放管家徽章：四态展示（推荐/较慢/近期失败/未采样）。
 * 颜色映射由 UI 层按主题 token 完成，避免 domain 依赖 Compose。
 */
enum class PlaybackHealthBadge(val label: String) {
    Recommended("推荐"),
    Slower("较慢"),
    RecentFailure("近期失败"),
    NoSample("未采样"),
    ;
}

/** 依据健康条目派生徽章状态。纯函数，规则集中在这里便于单测与复用。 */
fun derivePlaybackHealthBadge(
    entry: PlaybackHealthEntry?,
    nowMs: Long,
    cooldownMs: Long = PLAYBACK_HEALTH_COOLDOWN_MS,
    slowLatencyMs: Long = PLAYBACK_HEALTH_SLOW_LATENCY_MS,
): PlaybackHealthBadge {
    if (entry == null) return PlaybackHealthBadge.NoSample
    if (entry.attemptCount == 0 && entry.successLatencyMs.isEmpty() && entry.stutterSampleCount == 0) {
        return PlaybackHealthBadge.NoSample
    }
    when (entry.recentIssueType(nowMs, cooldownMs)) {
        PlaybackIssueType.Error -> return PlaybackHealthBadge.RecentFailure
        PlaybackIssueType.SlowBuffer -> return PlaybackHealthBadge.Slower
        null -> Unit
    }
    if (entry.successCount == 0) return PlaybackHealthBadge.RecentFailure
    val latency = entry.p50LatencyMs
    if (latency != null && latency >= slowLatencyMs) return PlaybackHealthBadge.Slower
    if (entry.attemptCount >= PLAYBACK_HEALTH_MIN_RATE_SAMPLES &&
        entry.successRate < 0.8f
    ) {
        return PlaybackHealthBadge.Slower
    }
    return PlaybackHealthBadge.Recommended
}

/** 徽章点击后展示的「为什么这样评」说明，纯函数。 */
fun explainPlaybackBadge(
    badge: PlaybackHealthBadge,
    entry: PlaybackHealthEntry?,
    nowMs: Long,
): String {
    if (entry == null) return "尚无该线路的播放记录，因此显示为未采样。"
    val metrics = entry.metrics(nowMs)
    val latencyText = metrics.p50LatencyMs?.let { "$it ms" } ?: "无样本"
    val basis = "依据最近 ${metrics.sampleCount} 次采样：成功率 ${(metrics.successRate * 100).toInt()}%，" +
        "首帧中位数 $latencyText，卡顿率 ${(metrics.stutterRate * 100).toInt()}%，最近成功 ${metrics.lastSuccessAgoText}。"
    return when (badge) {
        PlaybackHealthBadge.Recommended -> "近期没有失败或卡顿记录，首帧延迟处于正常区间。$basis"
        PlaybackHealthBadge.Slower -> "近期出现卡顿或首帧延迟偏高，建议换线或观察。$basis"
        PlaybackHealthBadge.RecentFailure -> "最近一次播放失败，且之后没有成功记录。$basis"
        PlaybackHealthBadge.NoSample -> "该线路没有可用于评估的播放记录。$basis"
    }
}

data class PlaybackHealthSnapshot(
    val entries: Map<String, PlaybackHealthEntry> = emptyMap(),
) {
    fun entryFor(key: String): PlaybackHealthEntry? = entries[key]

    val entryCount: Int
        get() = entries.size

    val successCount: Int
        get() = entries.values.sumOf { it.successCount }

    val failureCount: Int
        get() = entries.values.sumOf { it.failureCount }

    val slowBufferCount: Int
        get() = entries.values.sumOf { it.slowBufferCount }

    /** 所有线路聚合后的四联指标。 */
    fun metrics(nowMs: Long): PlaybackHealthMetrics {
        val allEntries = entries.values
        val attempts = allEntries.sumOf { it.attemptCount }
        val successes = successCount
        val stutters = allEntries.sumOf { it.stutterSampleCount }
        val stutterDenominator = successes + stutters
        val latencySamples = allEntries.flatMap { it.successLatencyMs }
        val lastSuccessAtMs = allEntries.maxOfOrNull { it.lastSuccessAtMs }?.takeIf { it > 0L } ?: 0L
        val agoMs = if (lastSuccessAtMs > 0L) (nowMs - lastSuccessAtMs).coerceAtLeast(0L) else null
        return PlaybackHealthMetrics(
            successRate = if (attempts == 0) 0f else successes.toFloat() / attempts,
            p50LatencyMs = percentile50(latencySamples),
            stutterRate = if (stutterDenominator == 0) 0f else stutters.toFloat() / stutterDenominator,
            lastSuccessAgoMs = agoMs,
            lastSuccessAgoText = formatLastSuccessAgo(lastSuccessAtMs, nowMs),
            sampleCount = attempts,
        )
    }

    /** 格式化显示线路质量统计摘要，供 UI 层直接使用。 */
    fun qualitySummaryText(): String {
        if (entryCount <= 0) return "暂无线路质量记录"
        return "已记录 $entryCount 条线路表现｜成功 $successCount｜失败 $failureCount｜卡顿 $slowBufferCount｜保留最近 30 天"
    }
}

fun prunePlaybackHealthEntries(
    entries: Collection<PlaybackHealthEntry>,
    nowMs: Long,
    retentionMs: Long = PLAYBACK_HEALTH_RETENTION_MS,
    maxEntries: Int = PLAYBACK_HEALTH_MAX_ENTRIES,
    lowWater: Int = PLAYBACK_HEALTH_LOW_WATER,
): List<PlaybackHealthEntry> {
    val cutoffMs = nowMs - retentionMs
    val retained = entries
        .filter { entry ->
            entry.key.isNotBlank() &&
                entry.latestActivityAtMs > 0L &&
                entry.latestActivityAtMs >= cutoffMs
        }
        .sortedByDescending { it.latestActivityAtMs }
    // P2-18 滞回：仅当超过高水位才一次性淘汰到低水位，避免在高水位附近每次写入都全表排序。
    if (retained.size <= maxEntries) return retained
    val target = lowWater.coerceAtLeast(1).coerceAtMost(maxEntries)
    return retained.take(target)
}

data class PlaybackAgentDecision(
    val nextSourceIndex: Int?,
    val nextSourceName: String? = null,
    val skippedRecentlyUnhealthyCount: Int = 0,
) {
    val switched: Boolean
        get() = nextSourceIndex != null
}

data class PlaybackAgentSourceSelection(
    val sourceIndex: Int,
    val sourceName: String? = null,
    val requestedSourceIndex: Int,
    val skippedRecentlyUnhealthyCount: Int = 0,
) {
    val switchedFromRequested: Boolean
        get() = sourceIndex != requestedSourceIndex
}

class PlaybackAgent(
    private val unhealthyCooldownMs: Long = PLAYBACK_HEALTH_COOLDOWN_MS,
) {
    fun selectBestSource(
        movie: Movie,
        requestedSourceIndex: Int,
        episodeIndex: Int,
        healthSnapshot: PlaybackHealthSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): PlaybackAgentSourceSelection? {
        val sources = movie.playSources
        if (sources.isEmpty()) return null

        val safeRequestedSourceIndex = requestedSourceIndex.coerceIn(0, sources.lastIndex)
        val candidates = sources.indices.filter { sourceIndex ->
            sources[sourceIndex].episodes.getOrNull(episodeIndex)?.url?.isNotBlank() == true
        }
        if (candidates.isEmpty()) return null

        val skippedRecentlyUnhealthyCount = candidates.count { sourceIndex ->
            val key = playbackHealthKey(movie.id, episodeIndex, sources[sourceIndex])
            healthSnapshot.entryFor(key)?.isRecentlyUnhealthy(nowMs, unhealthyCooldownMs) == true
        }
        val selectedIndex = candidates
            .sortedWith(
                compareByDescending<Int> { sourceIndex ->
                    sourceHealthScore(
                        movie = movie,
                        sourceIndex = sourceIndex,
                        episodeIndex = episodeIndex,
                        requestedSourceIndex = safeRequestedSourceIndex,
                        healthSnapshot = healthSnapshot,
                        nowMs = nowMs,
                        cooldownMs = unhealthyCooldownMs,
                    )
                }.thenBy { sourceIndex ->
                    sourceDistance(sourceIndex, safeRequestedSourceIndex, sources.size)
                }.thenBy { sourceIndex ->
                    sourceIndex
                },
            )
            .first()

        return PlaybackAgentSourceSelection(
            sourceIndex = selectedIndex,
            sourceName = sources[selectedIndex].name,
            requestedSourceIndex = safeRequestedSourceIndex,
            skippedRecentlyUnhealthyCount = skippedRecentlyUnhealthyCount,
        )
    }

    fun selectNextSource(
        movie: Movie,
        currentSourceIndex: Int,
        episodeIndex: Int,
        blockedSourceIndexes: Set<Int>,
        healthSnapshot: PlaybackHealthSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): PlaybackAgentDecision {
        val sources = movie.playSources
        if (sources.size <= 1) return PlaybackAgentDecision(nextSourceIndex = null)
        // 收敛下标：负数会让取模产生负索引，进而在 episodeIndexes[sourceIndex] 越界。
        val safeCurrentIndex = currentSourceIndex.coerceIn(0, sources.lastIndex)
        val episode = sources.getOrNull(safeCurrentIndex)?.episodes?.getOrNull(episodeIndex)
            ?: return PlaybackAgentDecision(nextSourceIndex = null)
        val episodeIndexes = sources.map { correspondingEpisodeIndex(it, episode.title, episodeIndex) }

        // 1 until size（而非 1..size）：offset 取到 size 时会绕回当前正在失败的线路，
        // 导致 selectNextSource 又切回同一条死链触发无意义重播。
        val candidates = (1 until sources.size)
            .map { offset -> (safeCurrentIndex + offset) % sources.size }
            .mapNotNull { sourceIndex ->
                if (sourceIndex !in blockedSourceIndexes) {
                    episodeIndexes[sourceIndex]?.let { episodeIndex ->
                        if (sources[sourceIndex].episodes[episodeIndex].url.isNotBlank()) {
                            sourceIndex to episodeIndex
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            }

        if (candidates.isEmpty()) return PlaybackAgentDecision(nextSourceIndex = null)

        val healthyCandidates = candidates.filterNot { candidate ->
            val key = playbackHealthKey(movie.id, candidate.second, sources[candidate.first])
            healthSnapshot.entryFor(key)?.isRecentlyUnhealthy(nowMs, unhealthyCooldownMs) == true
        }
        val selectedIndex = candidates
            .sortedWith(
                compareByDescending<Pair<Int, Int>> { candidate ->
                    sourceHealthScore(
                        movie = movie,
                        sourceIndex = candidate.first,
                        episodeIndex = candidate.second,
                        requestedSourceIndex = currentSourceIndex,
                        healthSnapshot = healthSnapshot,
                        nowMs = nowMs,
                        cooldownMs = unhealthyCooldownMs,
                    )
                }.thenBy { candidate ->
                    sourceDistance(candidate.first, currentSourceIndex, sources.size)
                }.thenBy { candidate ->
                    candidate.first
                },
            )
            .first()
            .first

        return PlaybackAgentDecision(
            nextSourceIndex = selectedIndex,
            nextSourceName = sources[selectedIndex].name,
            skippedRecentlyUnhealthyCount = candidates.size - healthyCandidates.size,
        )
    }
}

private fun sourceHealthScore(
    movie: Movie,
    sourceIndex: Int,
    episodeIndex: Int,
    requestedSourceIndex: Int,
    healthSnapshot: PlaybackHealthSnapshot,
    nowMs: Long,
    cooldownMs: Long,
): Int {
    val source = movie.playSources[sourceIndex]
    val key = playbackHealthKey(movie.id, episodeIndex, source)
    val entry = healthSnapshot.entryFor(key)
    val recentIssue = entry?.recentIssueType(nowMs, cooldownMs)
    var score = if (recentIssue == null) 10_000 else -10_000
    val lastSuccessAtMs = entry?.lastSuccessAtMs ?: 0L
    if (recentIssue == null && lastSuccessAtMs > 0L) {
        val successAgeMs = nowMs - lastSuccessAtMs
        score += if (successAgeMs in 0 until cooldownMs) 2_000 else 500
    }
    score += ((entry?.successCount ?: 0) * 60).coerceAtMost(1_000)
    score -= ((entry?.failureCount ?: 0) * 80).coerceAtMost(1_000)
    score -= ((entry?.slowBufferCount ?: 0) * 120).coerceAtMost(1_500)
    if (sourceIndex == requestedSourceIndex) {
        score += 100
    }
    return score
}

private fun sourceDistance(sourceIndex: Int, requestedSourceIndex: Int, sourceCount: Int): Int {
    if (sourceCount <= 1) return 0
    val forward = if (sourceIndex >= requestedSourceIndex) {
        sourceIndex - requestedSourceIndex
    } else {
        sourceCount - requestedSourceIndex + sourceIndex
    }
    val backward = if (requestedSourceIndex >= sourceIndex) {
        requestedSourceIndex - sourceIndex
    } else {
        sourceCount - sourceIndex + requestedSourceIndex
    }
    return minOf(forward, backward)
}

fun playbackHealthKey(movieId: Int, episodeIndex: Int, source: PlaySource): String {
    val sourceId = source.lineId
        .ifBlank { source.lineName }
        .ifBlank { source.name }
    return listOf(movieId.toString(), episodeIndex.toString(), sourceId).joinToString("|")
}
