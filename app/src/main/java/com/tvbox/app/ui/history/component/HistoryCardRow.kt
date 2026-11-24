package com.tvbox.app.ui.history.component

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.ui.history.CoverBadgeState
import com.tvbox.app.ui.history.CoverDecor
import com.tvbox.app.ui.history.firstNewIndex
import com.tvbox.app.ui.history.toCoverDecor
import com.tvbox.app.ui.history.HistoryTokens

/**
 * 手机历史行卡片：定高 86dp，标题单行截断，海报四态角标。
 *
 * 硬约束：
 * - 行高固定 86dp；标题永不缩放、永不折行、始终 maxLines=1。
 * - 状态行按三级顺序降级：先丢来源 badge → 再丢前缀 → 最后动标题。
 * - delete IconButton 触摸目标 ≥48dp（spec 要求）。
 */
@Composable
fun HistoryCardRow(
    item: WatchHistoryItem,
    multiSelectMode: Boolean,
    selected: Boolean,
    pulseEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decor = remember(item) { item.toCoverDecor(pulseEnabled) }
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(HistoryTokens.M.RowH)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左 2dp 琥珀竖条：仅 hasNew 时出现，列表唯一不被滚动糊掉的锚点。
        // ❌ 别用 drawBehind —— LazyColumn clipToBounds 会啃掉 1px。
        if (decor.badge != CoverBadgeState.None) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(HistoryTokens.DirtyAmber, Color.Transparent))),
            )
        } else {
            Spacer(modifier = Modifier.width(2.dp))
        }

        // 海报 + 装饰（64×86dp 全出血，无内边距 → 与基准稿 64×86 严格一致）
        PosterWithDecor(
            itemUrl = item.posterUrl,
            decor = decor,
            modifier = Modifier
                .size(HistoryTokens.M.PW, HistoryTokens.M.PH)
                .padding(
                    start = HistoryTokens.M.PosterPadStart,
                    end = HistoryTokens.M.PosterPadEnd,
                ),
        )

        Spacer(modifier = Modifier.width(HistoryTokens.M.Gap))

        // 右侧内容区：weight(1f) 占满剩余宽度，fillMaxHeight 保证对齐。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            // 主标题：永远 maxLines=1、16sp，超长省略。
            Text(
                text = item.movieName,
                fontSize = HistoryTokens.M.TitleSz,
                fontWeight = FontWeight.Medium,
                color = HistoryTokens.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(2.dp))

            // 状态行：badge + 集数信息
            StatusRow(item = item)

            Spacer(modifier = Modifier.height(4.dp))

            // 进度条（<2% 且非直播时不画）
            val prog = item.progressPercent / 100f
            if (prog >= 0.02f || item.typeName.contains("直播", ignoreCase = true)) {
                HistoryProgressBar(prog)
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 删除按钮（多选态切 Checkbox）
        if (multiSelectMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.size(HistoryTokens.M.DelBtn),
            )
        } else {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(HistoryTokens.M.DelBtn),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除记录",
                    tint = HistoryTokens.IconIdle,
                )
            }
        }
    }
}
