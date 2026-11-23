package com.tvbox.app.ui.history.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.displayEpisodeTitle
import com.tvbox.app.ui.history.HistoryTokens

/**
 * 状态行：[● +1集] [○ 直播] · 上次看到第N集
 *
 * 空间不足时按**固定顺序**降级：先丢来源 badge → 再丢前缀只留"91集" → 最后才动标题。
 * 标题是最后一道防线，永远不为 badge 让路。
 */
@Composable
fun StatusRow(item: WatchHistoryItem) {
    val updateHint = item.newEpisodeHint
    val epDisplay = remember(item.episodeTitle, item.episodeIndex) {
        val t = item.episodeTitle.takeIf { it.isNotBlank() }
            ?.let { displayEpisodeTitle(it, item.episodeIndex) }
            ?: "第${item.episodeIndex + 1}集"
        t
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 更新提示 badge（有新增集数才显示）
        updateHint?.let { hint ->
            val color = if (hint.confirmed) HistoryTokens.NewFrom else HistoryTokens.LiveGreen
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.15f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "${if (hint.confirmed) "●" else "○"} ${hint.label}",
                    fontSize = 11.sp,
                    color = color,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 元数据："上次看到 第N集"
        Text(
            text = "上次看到 $epDisplay",
            fontSize = HistoryTokens.M.MetaSz,
            color = HistoryTokens.Meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
