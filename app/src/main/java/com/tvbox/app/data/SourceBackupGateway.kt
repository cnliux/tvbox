package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.backupExportState

/**
 * 源配置备份的 UI 网关（第 231–240 轮）。
 *
 * `SourceConfigBackup` 只做编解码，这里补上「导出前敏感字段自检 → 可导出状态」
 * 与「导入结果 → 可直接展示的提示 + 按备份顺序恢复」，让设置页不需要理解 JSON 结构。
 */

object SourceBackupGateway {
    data class ExportResult(
        val payload: String?,
        val message: String,
        val allowed: Boolean,
    )

    data class ImportResult(
        val payload: SourceConfigBackup.Payload?,
        val message: String,
    )

    fun export(
        lines: List<ApiLine>,
        liveUrls: List<String>,
        order: List<String> = emptyList(),
        weights: Map<String, Int> = emptyMap(),
    ): ExportResult {
        val raw = SourceConfigBackup.export(lines, liveUrls, order, weights)
        val state = backupExportState(SourceConfigBackup.hasSensitiveKeys(raw))
        return ExportResult(
            payload = raw.takeIf { state.canExport },
            message = state.message ?: "备份不含密钥",
            allowed = state.canExport,
        )
    }

    fun parse(raw: String): ImportResult {
        val result = SourceConfigBackup.parse(raw)
        val payload = result.payload
        return when {
            payload == null -> ImportResult(null, result.error ?: "备份解析失败")
            payload.lines.isEmpty() && payload.liveUrls.isEmpty() ->
                ImportResult(null, "备份中没有可恢复的源")
            else -> ImportResult(
                payload,
                "预览：${payload.lines.size} 个视频源、${payload.liveUrls.size} 个直播订阅",
            )
        }
    }

    /** 按备份里的顺序恢复，未出现在 order 中的源保持原有相对顺序。 */
    fun restoreOrder(lines: List<ApiLine>, order: List<String>): List<ApiLine> {
        if (order.isEmpty()) return lines
        val rank = order.withIndex().associate { (index, id) -> id to index }
        return lines.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }
}
