package com.tvbox.app.domain

/**
 * S3-17：订阅 / 源导入的 dry-run 预览与备份旋转（纯函数，供 UI 与单测共用）。
 *
 * 现状导入路径是「解析 → 立即替换 → Toast」，用户点完才知道少了 7 条源。
 * 这里先把差异算清楚：预览数字必须与落地数字逐项相等，避免「导入后源少了一半」。
 */
data class ImportSourceRef(
    val id: String,
    val name: String,
    val url: String,
)

data class SubscriptionDiff(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val renamed: Int = 0,
) {
    val totalChanges: Int get() = added + updated + removed + renamed
}

object SubscriptionImportPlanner {
    /**
     * 按 [ImportSourceRef.id] 对齐新旧集合：
     * - 新出现的 id = added
     * - 消失的 id = removed
     * - 同 id 但 name 变 = renamed，url 变 = updated（两者都变各计一次）
     */
    fun diff(
        current: List<ImportSourceRef>,
        incoming: List<ImportSourceRef>,
    ): SubscriptionDiff {
        val oldById = current.associateBy { it.id }
        val newById = incoming.associateBy { it.id }
        var updated = 0
        var renamed = 0
        newById.forEach { (id, next) ->
            val previous = oldById[id] ?: return@forEach
            if (previous.url != next.url) updated++
            if (previous.name != next.name) renamed++
        }
        return SubscriptionDiff(
            added = newById.keys.count { it !in oldById },
            removed = oldById.keys.count { it !in newById },
            updated = updated,
            renamed = renamed,
        )
    }

    /** 旋转备份槽：输入按时间从旧到新排列，只留最新 [maxSlots] 份，其余淘汰。 */
    fun <T> rotateBackups(backups: List<T>, maxSlots: Int = 5): List<T> {
        val limit = maxSlots.coerceAtLeast(1)
        return if (backups.size <= limit) backups else backups.takeLast(limit)
    }
}
