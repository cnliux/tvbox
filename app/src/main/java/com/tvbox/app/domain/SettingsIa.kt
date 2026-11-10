package com.tvbox.app.domain

/**
 * 设置页信息架构状态层（第 98/99/124/125/165/166 轮）。
 * 五组分组、条目当前值摘要、危险操作二次确认与 TV 方向键路径都是纯数据，
 * 手机页内管理与 TV 左右分栏复用同一套状态。
 */

enum class SettingGroup(val label: String) {
    Playback("播放与画面"),
    ContentSources("内容源"),
    Appearance("外观"),
    DeviceUpdate("设备与更新"),
    Advanced("高级"),
}

data class SettingEntry(
    val id: String,
    val group: SettingGroup,
    val label: String,
    /** 当前值摘要，例如「视频接口：XX（3 个）」，避免必须点进去才知道。 */
    val valueSummary: String = "",
)

data class SettingGroupState(
    val group: SettingGroup,
    val entries: List<SettingEntry>,
    val summary: String,
)

fun settingEntrySummary(entry: SettingEntry): String =
    entry.valueSummary.ifBlank { "未设置" }

fun buildSettingGroups(entries: List<SettingEntry>): List<SettingGroupState> =
    SettingGroup.entries.map { group ->
        val items = entries.filter { it.group == group }
        SettingGroupState(
            group = group,
            entries = items,
            summary = "${items.size} 项",
        )
    }

/** TV 左侧分类 → 右侧条目的可预测焦点路径；返回逐级返回时的落点顺序。 */
fun tvFocusPath(groups: List<SettingGroupState>, startIndex: Int = 0): List<String> {
    if (groups.isEmpty()) return emptyList()
    val safeStart = startIndex.coerceIn(0, groups.lastIndex)
    return buildList {
        add("分类:${groups[safeStart].group.label}")
        groups[safeStart].entries.forEach { add("条目:${it.label}") }
        add("返回:分类列表")
    }
}

enum class DangerousAction { ClearSources, ClearHistory }

/** 危险操作必须二次确认；文案写明不可撤销的具体后果。 */
fun dangerousActionConfirmation(action: DangerousAction): String = when (action) {
    DangerousAction.ClearSources -> "清空后所有已导入的视频/直播源都会被删除，且无法撤销，需要重新导入或扫码同步。确认继续？"
    DangerousAction.ClearHistory -> "清除后所有观看历史与续播进度都会被删除，且无法恢复。确认继续？"
}

fun requiresSecondConfirmation(action: DangerousAction): Boolean = true

fun dangerousActionLabel(action: DangerousAction): String = when (action) {
    DangerousAction.ClearSources -> "清空源配置"
    DangerousAction.ClearHistory -> "清除观看历史"
}
