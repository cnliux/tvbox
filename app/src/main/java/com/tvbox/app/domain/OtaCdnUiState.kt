package com.tvbox.app.domain

/**
 * OTA 多 CDN 的设置页状态（第 281–290 轮的领域部分）。
 *
 * `OtaCdnSelection` 处理偏好与探活计划；这里把健康度、手动偏好、探活结果
 * 汇总成可直接渲染的行数据。只展示线路名与计数，不展示完整 URL。
 */

data class CdnStatusRow(
    val id: String,
    val label: String,
    val status: String,
    val selected: Boolean,
)

fun buildCdnStatusRows(
    candidates: List<OtaCdnCandidate>,
    health: Map<String, OtaCdnHealth>,
    preference: CdnPreference = CdnPreference(),
    nowMs: Long = 0L,
): List<CdnStatusRow> {
    val ordered = applyCdnPreference(candidates, preference)
    val selectedId = if (preference.mode == CdnSelectionMode.Manual) preference.candidateId else ""
    return ordered.map { candidate ->
        CdnStatusRow(
            id = candidate.id,
            label = candidate.label,
            status = cdnStatusLabel(health[candidate.id] ?: OtaCdnHealth(), nowMs),
            selected = candidate.id == selectedId,
        )
    }
}

/** 探活结果汇总：只报告成功条数与总条数，不把线路地址写进 UI 或日志。 */
fun summarizeCdnProbeResults(results: Map<String, Boolean>): String {
    if (results.isEmpty()) return "尚未探活"
    val ok = results.values.count { it }
    return "探活完成：$ok/${results.size} 条线路可用"
}

/** 探活计划与结果不一致时（例如中途取消）给出可解释的提示。 */
fun cdnProbeCoverage(plan: List<Pair<String, Long>>, results: Map<String, Boolean>): Int =
    if (plan.isEmpty()) 0
    else (results.size * 100 / plan.size).coerceIn(0, 100)
