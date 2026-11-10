package com.tvbox.app.domain

/**
 * 源管理状态层（第 87/88/89/113/114/115/116/154/155/156/157 轮）。
 * 与第 73 轮 `SubscriptionDiagnostics`、第 90 轮 `SourceConfigBackup` 联动：
 * 探活结果决定状态标签与是否参与 fan-out，排序/权重与备份共用同一份顺序。
 */

enum class SourceAvailability { Available, Degraded, Unavailable, Unknown }

data class SourceHealthSnapshot(
    val sourceId: String,
    val availability: SourceAvailability = SourceAvailability.Unknown,
    val consecutiveFailures: Int = 0,
    val lastCheckedAtMs: Long = 0L,
    val latencyMs: Long = 0L,
)

data class ManagedSource(
    val id: String,
    val name: String,
    val weight: Int = 100,
    val pinned: Boolean = false,
)

/** 置顶优先，其次权重，最后保持原始顺序，保证遥控器焦点路径稳定。 */
fun orderManagedSources(
    sources: List<ManagedSource>,
    health: Map<String, SourceHealthSnapshot> = emptyMap(),
): List<ManagedSource> = sources
    .mapIndexed { index, source -> index to source }
    .sortedWith(
        compareByDescending<Pair<Int, ManagedSource>> { (_, s) -> s.pinned }
            .thenByDescending { (_, s) -> s.weight }
            .thenBy { (_, s) -> if (health[s.id]?.availability == SourceAvailability.Unavailable) 1 else 0 }
            .thenBy { (index, _) -> index }
    )
    .map { it.second }

/** 连续失败达到阈值且仍在冷却窗口内的源不参与首页/搜索并行 fan-out。 */
fun shouldJoinFanOut(
    snapshot: SourceHealthSnapshot?,
    nowMs: Long,
    cooldownMs: Long = 10L * 60L * 1000L,
    maxFailures: Int = 3,
): Boolean {
    if (snapshot == null) return true
    if (snapshot.availability == SourceAvailability.Available) return true
    if (snapshot.consecutiveFailures < maxFailures) return true
    val cooledDown = snapshot.lastCheckedAtMs <= 0L || nowMs - snapshot.lastCheckedAtMs >= cooldownMs
    return cooledDown
}

fun sourceStatusLabel(snapshot: SourceHealthSnapshot?): String = when (snapshot?.availability) {
    SourceAvailability.Available -> "可用 ${snapshot.latencyMs}ms"
    SourceAvailability.Degraded -> "较慢"
    SourceAvailability.Unavailable -> "不可用"
    null, SourceAvailability.Unknown -> "未检测"
}

data class ImportUiState(
    val errorKind: SubscriptionErrorKind? = null,
    val skippedCount: Int = 0,
    val previewCount: Int = 0,
    val lastConfigPreserved: Boolean = true,
)

fun importStatusMessage(state: ImportUiState): String {
    if (state.errorKind != null) {
        val reason = when (state.errorKind) {
            SubscriptionErrorKind.Dns -> "域名解析失败"
            SubscriptionErrorKind.Timeout -> "请求超时"
            SubscriptionErrorKind.Connection -> "无法连接"
            SubscriptionErrorKind.Http -> "服务返回错误"
            SubscriptionErrorKind.NotJson -> "返回内容不是 JSON"
            SubscriptionErrorKind.InvalidSchema -> "配置格式不合法"
            SubscriptionErrorKind.Unknown -> "未知错误"
        }
        return "$reason，已保留上次成功配置"
    }
    return "预览 ${state.previewCount} 条，跳过 ${state.skippedCount} 条"
}

data class BackupUiState(
    val canExport: Boolean = true,
    val sanitized: Boolean = true,
    val message: String? = null,
)

/** 导出前自检：不通过时禁止导出，避免把凭据写进可分享的备份。 */
fun backupExportState(hasSensitiveKeys: Boolean): BackupUiState = if (hasSensitiveKeys) {
    BackupUiState(canExport = false, sanitized = false, message = "检测到敏感字段，已阻止导出")
} else {
    BackupUiState(canExport = true, sanitized = true, message = "备份不含密钥")
}
