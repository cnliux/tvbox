package com.tvbox.app.domain

/**
 * 搜索进度文案（第 201–210 轮）。
 *
 * 原先两处文案直接写在 `SearchScreen.kt` 里，且「已完成数」可能大于「总数」
 * （多源并发结束时的进度帧顺序不同）。这里把文案收敛成纯函数并做上下界钳制，
 * UI 只负责展示，文案格式保持不变。
 */

fun completedSearchProgressText(completedSources: Int, totalSources: Int, resultCount: Int): String =
    if (totalSources <= 0) {
        ""
    } else {
        val completed = completedSources.coerceIn(0, totalSources)
        "已完成 $completed/$totalSources 条线路 · 找到 ${resultCount.coerceAtLeast(0)} 个结果"
    }

fun activeSearchProgressText(completedSources: Int, totalSources: Int, resultCount: Int): String =
    if (totalSources <= 0) {
        ""
    } else {
        val completed = completedSources.coerceIn(0, totalSources)
        "正在搜索 $totalSources 个来源（已完成 $completed，找到 ${resultCount.coerceAtLeast(0)} 条结果）"
    }
