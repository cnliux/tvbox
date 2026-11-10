package com.tvbox.app.domain

/**
 * 线路级的起播质量统计（第 91 轮）。
 *
 * 与 [LiveDomainScore] 互补：后者按**域名**跨频道累积成败，用于把差 CDN 整体压低；
 * 这里按「频道 + 线路下标」记录**最近一次起播耗时**与成功/失败次数，用于
 * 「该频道哪条线路最快」以及自动换线时优先选历史最佳。
 *
 * 只影响排序，不硬性剔除线路：全部线路都失败时仍要能播。
 */
private const val QUALITY_HALF_LIFE_MS = 6L * 60 * 60 * 1000L
private const val QUALITY_MAX_ENTRIES = 300

data class LineQualityRecord(
    val lineKey: String,
    val lastStartDurationMs: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastAttemptAtMs: Long = 0L,
) {
    /** 分数越高越优先；从未记录的线路得 0 分，介于「一直失败」与「有过成功」之间。 */
    fun score(nowMs: Long): Double {
        val total = successCount + failureCount
        if (total <= 0) return 0.0
        val successRate = successCount.toDouble() / total
        // 起播越快分越高：3 秒以上开始明显衰减，最快与最慢相差约 3 倍。
        val speed = lastStartDurationMs?.takeIf { it > 0L }
            ?.let { (3_000.0 / (it + 1_000.0)).coerceIn(0.2, 3.0) }
            ?: 1.0
        val ageMs = (nowMs - lastAttemptAtMs).coerceAtLeast(0L)
        val decay = Math.pow(0.5, ageMs.toDouble() / QUALITY_HALF_LIFE_MS.toDouble())
        // 起播速度只在有成功记录时加分；纯失败线路必须为负分，
        // 才会排在「从未试过」的线路之后，符合只影响排序、不硬剔除的设计。
        return (successRate * 2.0 + speed * successRate - failureCount * 0.25) * decay
    }
}

fun lineQualityKey(channelKey: String, lineIndex: Int): String = "$channelKey#$lineIndex"

/** 记录一次起播结果；超时 / 失败传 null 时长。超出容量时淘汰最久未活动的记录。 */
fun recordLineStart(
    records: Map<String, LineQualityRecord>,
    lineKey: String,
    startDurationMs: Long?,
    success: Boolean,
    nowMs: Long,
    maxEntries: Int = QUALITY_MAX_ENTRIES,
): Map<String, LineQualityRecord> {
    if (lineKey.isBlank()) return records
    val old = records[lineKey] ?: LineQualityRecord(lineKey = lineKey)
    val updated = old.copy(
        lastStartDurationMs = if (success) startDurationMs?.coerceAtLeast(0L) else old.lastStartDurationMs,
        successCount = old.successCount + if (success) 1 else 0,
        failureCount = old.failureCount + if (success) 0 else 1,
        lastAttemptAtMs = nowMs,
    )
    val merged = records + (lineKey to updated)
    if (merged.size <= maxEntries) return merged
    return merged.entries
        .sortedByDescending { (_, entry) -> entry.lastAttemptAtMs }
        .take(maxEntries)
        .associate { (key, value) -> key to value }
}

/**
 * 按历史质量对线路下标排序：分数高的在前，同分保持源内原顺序（稳定排序）。
 * 未记录过的线路得 0 分，会排在「明确失败过」的线路之前、「成功过」的线路之后。
 */
fun rankLinesByQuality(
    lines: List<LiveChannelLine>,
    records: Map<String, LineQualityRecord>,
    channelKey: String,
    nowMs: Long,
): List<Int> {
    if (lines.size <= 1) return lines.indices.toList()
    return lines.indices.sortedWith(
        compareByDescending<Int> { index ->
            records[lineQualityKey(channelKey, index)]?.score(nowMs) ?: 0.0
        }.thenBy { it },
    )
}
