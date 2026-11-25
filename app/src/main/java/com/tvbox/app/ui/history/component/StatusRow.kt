package com.tvbox.app.ui.history.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.displayEpisodeTitle
import com.tvbox.app.ui.history.HistoryTokens

/**
 * 状态行：显示"上次看到 第N集"。
 *
 * 更新/追平状态由海报底部 CoverTag 统一标识，本行只负责观看进度，避免与海报标签重复。
 * 单行显示，超长省略；占满文字列宽度（父级 HistoryCardRow 的 weight(1f) Column）。
 */
@Composable
fun StatusRow(item: WatchHistoryItem) {
    val epDisplay = remember(item.episodeTitle, item.episodeIndex) {
        val t = item.episodeTitle.takeIf { it.isNotBlank() }
            ?.let { displayEpisodeTitle(it, item.episodeIndex) }
            ?: "第${item.episodeIndex + 1}集"
        t
    }

    // 简化：海报底部 CoverTag 已负责"有更新/今日开播/已追平"状态标识，
    // 右侧只显示观看进度，避免信息重复且节省横向空间（防止"上次看到..."被裁）。
    Text(
        text = "上次看到 $epDisplay",
        fontSize = HistoryTokens.M.MetaSz,
        color = HistoryTokens.Meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}
