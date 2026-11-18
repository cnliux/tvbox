package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.displayEpisodeTitle
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.components.rememberTvImageLoader
import com.tvbox.app.ui.theme.TvColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * 手机模式「历史」Tab：针对竖屏触屏重新设计，不再复用电视端的网格 + 焦点体系。
 *
 * - 列表式单列布局：一行一条记录，信息密度与触控目标更适合手机；
 * - 按「今天 / 昨天 / 日期」分组，快速定位最近观看；
 * - 每条右侧提供删除按钮，支持单条清理，不必为了删一条而清空全部；
 * - 顶部不显示任何说明性提示，标题与「清空」按钮共用一行，节省竖屏空间；
 * - 空态动作用 [TvBoxViewModel.openHome] 跳转首页（历史是底部 Tab，没有“返回上一页”语义）。
 */
@Composable
internal fun MobileHistoryScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WatchHistoryItem?>(null) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史") },
            text = { Text("将删除全部观看记录，且无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    actions.clearHistory()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除记录") },
            text = { Text("删除「${item.movieName}」的观看记录？") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    actions.removeHistoryItem(item)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TvColors.Background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部只有标题与清空按钮，不再显示“观看记录：...”这类说明提示。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "历史",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.historyUpdateChecking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "正在检查各源更新…",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvColors.TextSecondary,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = state.historyItems.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("清空") }
            }

            if (state.historyItems.isEmpty()) {
                EmptyState(
                    title = "暂无观看历史",
                    subtitle = "在首页选择影片开始观看，进度会自动记录在这里",
                    actionLabel = "去首页",
                    onAction = actions::openHome,
                )
            } else {
                // 分组计算依赖「本地自然日」：跨过午夜（或跨年）后若列表未变，首次计算时冻结的
                // 「今天/昨天」标签会错误地停留在旧日期。这里把本地日零点时间戳纳入 remember key，
                // 使页面在午夜之后任意一次重组（回到 Tab / 数据变化）时都按新日期重新分组。
                val dayStartMs = startOfDayMs(System.currentTimeMillis())
                val sections = remember(state.historyItems, dayStartMs) {
                    buildHistorySections(state.historyItems, dayStartMs)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEachIndexed { sectionIndex, section ->
                        // 使用独立 sticky key：同一影片在同源的多个观看记录（不同集数）必须各自拥有
                        // 稳定标识，否则 LazyColumn 在重组时可能把 scroll 状态、焦点与动画错乱到错误条目上。
                        // 分组头不用 label 作 key：日期标签（如「M月d日」）跨年会重复，改用分组序号保证唯一。
                        item(key = "header_$sectionIndex") {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TvColors.AccentStrong.copy(alpha = 0.22f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    text = section.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TvColors.AccentStrong,
                                    modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
                                )
                            }
                        }
                        // 使用 apiLineId:movieId:episodeIndex 作为稳定 key：同一影片在同源的多个观看记录（不同集数）必须
                        // 各自拥有独立 key，否则 LazyColumn 在重组时可能把 scroll 状态、焦点与 anim 错乱到错误条目上。
                        items(items = section.items, key = { "${it.apiLineId}:${it.movieId}:${it.episodeIndex}" }) { entry ->
                            MobileHistoryRow(
                                item = entry,
                                onClick = { actions.resumeHistory(entry, TvScreen.History) },
                                onDelete = { pendingDelete = entry },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileHistoryRow(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var posterFailed by remember(item.posterUrl) { mutableStateOf(false) }
    val timeText = remember(item.updatedAtEpochMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.updatedAtEpochMs))
    }
    val progressText = if (item.durationMs > 0) {
        "${formatPlayerTime(item.positionMs)} / ${item.progressPercent}%"
    } else {
        "已看到 ${formatPlayerTime(item.positionMs)}"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = TvColors.SurfaceRaised.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 82.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TvColors.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (item.posterUrl.isNotBlank() && !posterFailed) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.movieName,
                        contentScale = ContentScale.Crop,
                        onError = { posterFailed = true },
                        imageLoader = rememberTvImageLoader(),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "无图",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.movieName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (item.sourceName.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = TvColors.Accent.copy(alpha = 0.18f),
                        ) {
                            Text(
                                text = item.sourceName,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TvColors.Accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.episodeTitle.isNotBlank()) {
                        // 使用 displayEpisodeTitle 统一剥离源站叠加的编号前缀（如「1.第一集」→「第一集」），
                        // 与详情页选集显示保持一致。
                        val displayTitle = remember(item.episodeTitle, item.episodeIndex) {
                            displayEpisodeTitle(item.episodeTitle, item.episodeIndex)
                        }
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TvColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$timeText 观看",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvColors.TextSecondary,
                    )
                    // 更新提示紧跟在观看时间后面，不单独占一行：
                    // 历史行本来就不高，再加一行会把列表压得很松散。
                    item.updateHint?.let { hint ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = TvColors.Accent.copy(alpha = 0.16f),
                        ) {
                            Text(
                                text = hint,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TvColors.Accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除记录",
                    tint = TvColors.Danger,
                )
            }
        }
    }
}

internal data class HistorySection(
    val label: String,
    val items: List<WatchHistoryItem>,
)

/** 返回 [epochMs] 所在本地自然日的零点时间戳（时/分/秒/毫秒清零）。 */
internal fun startOfDayMs(epochMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** 按日历日分组（今天 / 昨天 / M月d日），保持仓库返回的倒序（最近在前）。 */
internal fun buildHistorySections(
    items: List<WatchHistoryItem>,
    nowMs: Long,
): List<HistorySection> {
    val todayStart = startOfDayMs(nowMs)
    val yesterdayStart = todayStart - 24L * 60L * 60L * 1000L
    val dayFormat = SimpleDateFormat("M月d日", Locale.getDefault())

    fun labelOf(epochMs: Long): String = when {
        epochMs >= todayStart -> "今天"
        epochMs >= yesterdayStart -> "昨天"
        else -> dayFormat.format(Date(epochMs))
    }

    val grouped = LinkedHashMap<String, MutableList<WatchHistoryItem>>()
    items.forEach { item ->
        grouped.getOrPut(labelOf(item.updatedAtEpochMs)) { mutableListOf() }.add(item)
    }
    return grouped.map { (label, group) -> HistorySection(label, group) }
}
