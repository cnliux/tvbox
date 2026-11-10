package com.tvbox.app.domain

/**
 * 设置页摘要与危险操作的运行层（第 261–270 轮的领域部分）。
 *
 * `SettingsIa` 给出分组与单条摘要，这里补「整页摘要、条目行文案、二次确认状态」，
 * 让 TV 左右分栏与手机页内管理复用同一份渲染数据，避免两处各写一套文案。
 */

data class SettingsPageSummary(
    val groups: List<SettingGroupState>,
    val totalEntries: Int,
    val emptyGroups: List<SettingGroup>,
)

fun buildSettingsPageSummary(entries: List<SettingEntry>): SettingsPageSummary {
    val groups = buildSettingGroups(entries)
    return SettingsPageSummary(
        groups = groups,
        totalEntries = entries.size,
        emptyGroups = groups.filter { it.entries.isEmpty() }.map { it.group },
    )
}

/** 条目行统一为「标签：当前值摘要」，未设置时显示「未设置」。 */
fun settingRowLabel(entry: SettingEntry): String =
    "${entry.label}：${settingEntrySummary(entry)}"

/** 分组摘要在「N 项」之外补上未配置条目数，提醒用户还有多少项没设值。 */
fun settingGroupSummary(state: SettingGroupState): String {
    val missing = state.entries.count { it.valueSummary.isBlank() }
    return if (missing > 0) "${state.summary} · $missing 项未设置" else state.summary
}

/**
 * 二次确认状态：未确认时返回确认文案（调用方展示对话框），确认后返回 null 表示可以执行。
 * 所有危险操作都必须走这里，不允许 UI 直接调用清空函数。
 */
fun pendingDangerousConfirmation(action: DangerousAction, confirmed: Boolean): String? =
    if (requiresSecondConfirmation(action) && !confirmed) dangerousActionConfirmation(action) else null
