package com.tvbox.app.ui.history

import com.tvbox.app.domain.WatchHistoryItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/** 按日分组生成历史分组列表，保持仓库倒序（最近在前），跨天零点准确归属。 */
internal fun buildHistorySections(
    items: List<WatchHistoryItem>,
    nowMs: Long,
): List<HistorySection> {
    val todayStart = startOfDayMs(nowMs)
    val yesterdayStart = todayStart - 24L * 60L * 60L * 1000L
    val currentYear = yearOf(nowMs)

    fun labelOf(epochMs: Long): String = when {
        epochMs >= todayStart -> "今天"
        epochMs >= yesterdayStart -> "昨天"
        else -> if (yearOf(epochMs) == currentYear) {
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(epochMs))
        } else {
            SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(epochMs))
        }
    }

    // LinkedHashMap 保持插入顺序（调用方已按 updatedAtEpochMs 降序传入）
    val grouped = LinkedHashMap<String, MutableList<WatchHistoryItem>>()
    items.forEach { item ->
        grouped.getOrPut(labelOf(item.updatedAtEpochMs)) { mutableListOf() }.add(item)
    }
    return grouped.map { (label, group) -> HistorySection(label, group) }
}

internal fun startOfDayMs(epochMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun yearOf(epochMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    return calendar.get(Calendar.YEAR)
}
