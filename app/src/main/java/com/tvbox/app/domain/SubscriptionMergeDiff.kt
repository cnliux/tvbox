package com.tvbox.app.domain

/**
 * 订阅合并的 schema 版本。当前解码产物（SubscriptionDecoder 的输出，再由调用方
 * 映射为 [SourceEntry]）视为 v2：v1 是旧版无 liveUrls/enabled 的极简结构。
 * 预留 v3+ 用于后续扩展（例如源内多线路、UA/超时覆盖等）。
 */
const val SUBSCRIPTION_SCHEMA_VERSION = 2

/**
 * 订阅里的单个源，独立轻量结构，不与 [com.tvbox.app.domain.Movie] / 直播频道模型耦合。
 * @param id       稳定标识，作为 diff 的匹配键。
 * @param name     源显示名。
 * @param apiUrl   TVBox 配置 JSON 地址（接口地址）。
 * @param liveUrls 直播配置地址列表，可空。
 * @param enabled  是否启用，默认 true。
 */
data class SourceEntry(
    val id: String,
    val name: String,
    val apiUrl: String,
    val liveUrls: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/**
 * 已存在的源被 incoming 更新：[changedFields] 列出发生变化的字段名
 * （name / apiUrl / liveUrls / enabled）。
 */
data class SourceUpdate(val entry: SourceEntry, val changedFields: List<String>)

/** incoming 内部的重复冲突。 */
enum class ConflictKind { DuplicateId, DuplicateName, DuplicateUrl }

/** 订阅合并冲突，独立于搜索域的 [com.tvbox.app.domain.MergeConflict]。 */
data class SubscriptionMergeConflict(val kind: ConflictKind, val message: String)

/** diff 结果：四类归属 + incoming 内部冲突，均按 stable 顺序返回，保证幂等。 */
data class MergeDiff(
    val added: List<SourceEntry>,
    val updated: List<SourceUpdate>,
    val removed: List<SourceEntry>,
    val unchanged: List<SourceEntry>,
    val conflicts: List<SubscriptionMergeConflict>,
)

/**
 * 订阅合并 diff 层：只读比较，不写状态。
 * 按 id 匹配 current/incoming，同时检测 incoming 内部的重复 id/name/url。
 * 输出顺序由入参顺序决定（current 顺序优先），因此同输入两次 diff 结果相等（幂等）。
 */
object SubscriptionMerger {

    /** 参与比较的字段名集合，[diff] 变化检测按此顺序输出，保证 changedFields 稳定。 */
    private val COMPARED_FIELDS = listOf("name", "apiUrl", "liveUrls", "enabled")

    fun diff(current: List<SourceEntry>, incoming: List<SourceEntry>): MergeDiff {
        val currentById = current.associateBy { it.id }
        val added = mutableListOf<SourceEntry>()
        val updated = mutableListOf<SourceUpdate>()
        val unchanged = mutableListOf<SourceEntry>()

        for (entry in incoming) {
            val existing = currentById[entry.id]
            if (existing == null) {
                added += entry
                continue
            }
            val changes = changedFields(existing, entry)
            if (changes.isEmpty()) unchanged += entry else updated += SourceUpdate(entry, changes)
        }

        val incomingIds = incoming.map { it.id }.toSet()
        val removed = current.filter { it.id !in incomingIds }

        return MergeDiff(added, updated, removed, unchanged, detectConflicts(incoming))
    }

    fun summarize(diff: MergeDiff): String {
        val base = "新增 ${diff.added.size} 个源，更新 ${diff.updated.size} 个，" +
            "删除 ${diff.removed.size} 个，未变 ${diff.unchanged.size} 个"
        return if (diff.conflicts.isEmpty()) "$base，无冲突" else "$base，冲突 ${diff.conflicts.size} 项"
    }

    private fun changedFields(current: SourceEntry, incoming: SourceEntry): List<String> =
        COMPARED_FIELDS.filter { fieldHasChanged(current, incoming, it) }

    private fun fieldHasChanged(current: SourceEntry, incoming: SourceEntry, field: String): Boolean =
        when (field) {
            "name" -> current.name != incoming.name
            "apiUrl" -> current.apiUrl != incoming.apiUrl
            "liveUrls" -> current.liveUrls != incoming.liveUrls
            "enabled" -> current.enabled != incoming.enabled
            else -> false
        }

    /**
     * incoming 内部冲突检测：id 重复按 id 分组，name / url 重复均按字段值分组。
     * 只在重复值出现的第二条起报一条冲突，避免同一处重复刷屏。
     */
    private fun detectConflicts(incoming: List<SourceEntry>): List<SubscriptionMergeConflict> {
        val conflicts = mutableListOf<SubscriptionMergeConflict>()
        val seenIds = mutableSetOf<String>()
        val seenNames = mutableMapOf<String, String>()
        val seenUrls = mutableMapOf<String, String>()

        for (entry in incoming) {
            if (!seenIds.add(entry.id)) {
                conflicts += SubscriptionMergeConflict(
                    ConflictKind.DuplicateId,
                    "源 id 重复：${entry.id}",
                )
            }
            val name = entry.name
            if (name.isNotBlank()) {
                val prevId = seenNames[name]
                if (prevId != null && prevId != entry.id) {
                    conflicts += SubscriptionMergeConflict(
                        ConflictKind.DuplicateName,
                        "源 name 重复：$name（${prevId} / ${entry.id}）",
                    )
                } else if (prevId == null) {
                    seenNames[name] = entry.id
                }
            }
            for (url in entry.liveUrls) {
                if (url.isBlank()) continue
                val prevId = seenUrls[url]
                if (prevId != null && prevId != entry.id) {
                    conflicts += SubscriptionMergeConflict(
                        ConflictKind.DuplicateUrl,
                        "直播地址重复：$url（${prevId} / ${entry.id}）",
                    )
                } else if (prevId == null) {
                    seenUrls[url] = entry.id
                }
            }
        }
        return conflicts
    }
}
