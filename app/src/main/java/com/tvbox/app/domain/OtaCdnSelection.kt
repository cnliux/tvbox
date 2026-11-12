package com.tvbox.app.domain

/**
 * OTA CDN 选择与探活状态层（第 136/138/139/140/167 轮）。
 * 默认 Auto（保持健康度排序），用户可在设置页高级项指定某条线路；
 * 状态标签只用本机计数与耗时，不保存也不展示完整 URL。
 */

enum class CdnSelectionMode { Auto, Manual }

data class CdnPreference(
    val mode: CdnSelectionMode = CdnSelectionMode.Auto,
    val candidateId: String = "",
)

private const val CDN_PREFERENCE_VERSION = "v1"

fun encodeCdnPreference(preference: CdnPreference): String = listOf(
    CDN_PREFERENCE_VERSION,
    preference.mode.name,
    preference.candidateId,
).joinToString("|")

fun decodeCdnPreference(raw: String?): CdnPreference {
    if (raw.isNullOrBlank()) return CdnPreference()
    val parts = raw.split('|')
    if (parts.size < 3 || parts[0] != CDN_PREFERENCE_VERSION) return CdnPreference()
    val mode = runCatching { CdnSelectionMode.valueOf(parts[1]) }.getOrDefault(CdnSelectionMode.Auto)
    return CdnPreference(mode, parts[2])
}

/** 手动选择只把指定线路提到首位，其余仍按健康度顺序兜底；指定线路不存在时退回 Auto。 */
fun applyCdnPreference(
    candidates: List<OtaCdnCandidate>,
    preference: CdnPreference,
): List<OtaCdnCandidate> {
    if (preference.mode != CdnSelectionMode.Manual || preference.candidateId.isBlank()) return candidates
    val chosen = candidates.firstOrNull { it.id == preference.candidateId } ?: return candidates
    return listOf(chosen) + candidates.filterNot { it.id == chosen.id }
}

fun cdnStatusLabel(health: OtaCdnHealth, nowMs: Long): String {
    if (health.consecutiveFailures > 0) return "连续失败 ${health.consecutiveFailures} 次"
    if (health.successCount == 0) return "未使用"
    val ageMinutes = ((nowMs - health.lastSuccessAtMs).coerceAtLeast(0L)) / 60_000L
    return "最近成功 ${ageMinutes} 分钟前 · 最快 ${health.bestSuccessMs}ms"
}

/**
 * 探活计划：只对前 [maxProbes] 条线路做轻量 HEAD/Range 探测，
 * 单条预算按剩余总额均分且设下限，避免在弱网上把更新时间全耗在探活上。
 */
fun buildCdnProbePlan(
    candidates: List<OtaCdnCandidate>,
    budgetMs: Long = 3_000L,
    maxProbes: Int = 5,
    minProbeMs: Long = 300L,
): List<Pair<String, Long>> {
    val targets = candidates.take(maxProbes.coerceAtLeast(0))
    if (targets.isEmpty()) return emptyList()
    val perProbe = (budgetMs / targets.size).coerceAtLeast(minProbeMs)
    return targets.map { it.id to perProbe }
}
