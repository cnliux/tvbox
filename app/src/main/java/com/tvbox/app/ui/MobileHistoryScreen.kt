package com.tvbox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.displayEpisodeTitle
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.components.NewEpisodeBadge
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
    // #309：长按条目进入多选模式，选中项用稳定标识（apiLineId:movieId:episodeIndex）记录。
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    // #311：apiLineId 不在当前 apiLines 中的历史条目即「失效源记录」；线路未加载时不做判断，
    // 避免刚进页面 apiLines 还为空时把全部历史误判成失效。
    val staleItems = remember(state.historyItems, state.apiLines) {
        if (state.apiLines.isEmpty()) {
            emptyList()
        } else {
            val validIds = state.apiLines.map { it.id }.toSet()
            state.historyItems.filter { it.apiLineId !in validIds }
        }
    }
    var confirmCleanStale by remember { mutableStateOf(false) }
    // #317：最近观看 / 添加时间排序切换（纯本地排序，不改动历史数据）。
    var sortMode by remember { mutableStateOf(HistorySortMode.RECENT) }
    // #308：历史搜索——按片名 / 来源 / 分类本地过滤，不改动历史数据本身。
    var historyQuery by remember { mutableStateOf("") }
    val normalizedHistoryQuery = historyQuery.trim()
    val filteredHistoryItems = remember(state.historyItems, normalizedHistoryQuery) {
        if (normalizedHistoryQuery.isBlank()) {
            state.historyItems
        } else {
            val needle = normalizedHistoryQuery
            state.historyItems.filter { item ->
                item.movieName.contains(needle, ignoreCase = true) ||
                    item.sourceName.contains(needle, ignoreCase = true) ||
                    item.typeName.contains(needle, ignoreCase = true)
            }
        }
    }
    // #317：旧数据 addedAtEpochMs=0，回退到 updatedAtEpochMs 参与排序。
    val sortedHistoryItems = remember(filteredHistoryItems, sortMode) {
        when (sortMode) {
            HistorySortMode.RECENT -> filteredHistoryItems.sortedByDescending { it.updatedAtEpochMs }
            HistorySortMode.ADDED -> filteredHistoryItems.sortedByDescending {
                it.addedAtEpochMs.takeIf { ts -> ts > 0L } ?: it.updatedAtEpochMs
            }
        }
    }
    val selectedItems = remember(state.historyItems, selectedKeys) {
        state.historyItems.filter { historyItemKey(it) in selectedKeys }
    }

    fun toggleSelection(item: WatchHistoryItem) {
        val key = historyItemKey(item)
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedKeys = emptySet()
    }

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
    // #309：批量删除确认。保持上面的单条删除 Dialog 不变。
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("批量删除") },
            text = { Text("删除选中的 ${selectedItems.size} 条观看记录？") },
            confirmButton = {
                Button(onClick = {
                    confirmBatchDelete = false
                    actions.removeHistoryItems(selectedItems)
                    exitMultiSelect()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
            },
        )
    }
    // #311：失效源清理确认。
    if (confirmCleanStale) {
        AlertDialog(
            onDismissRequest = { confirmCleanStale = false },
            title = { Text("清理失效源记录") },
            text = { Text("删除 ${staleItems.size} 条引用已删除源的观看记录？") },
            confirmButton = {
                Button(onClick = {
                    confirmCleanStale = false
                    actions.removeStaleHistoryItems()
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanStale = false }) { Text("取消") }
            },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 修复顶部遮挡：仅使用 statusBars 吸收顶部状态栏 inset（不使用 systemBars，
            // 因为手机版底部已有 MobileNavigationBar 自行处理导航栏空间，再加 systemBars
            // 底部 padding 会压缩内容导致搜索框以上的区域被挤出视口）。
            .windowInsetsPadding(WindowInsets.statusBars),
        color = TvColors.Background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题 + 操作按钮：多选模式下切换为「全选 / 删除 / 取消」，其余情况保持「清空」。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (multiSelectMode) "已选 ${selectedKeys.size} 条" else "历史",
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
                if (multiSelectMode) {
                    // #309：多选操作。全选只作用于当前（搜索 + 排序后的）可见列表。
                    TextButton(onClick = { selectedKeys = filteredHistoryItems.map(::historyItemKey).toSet() }) {
                        Text("全选")
                    }
                    TextButton(
                        onClick = { confirmBatchDelete = true },
                        enabled = selectedItems.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("删除", color = TvColors.Danger) }
                    TextButton(onClick = { exitMultiSelect() }) { Text("取消") }
                } else {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = state.historyItems.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("清空") }
                }
            }

            // #309/#311/#317：辅助操作行——排序切换、失效源清理入口。
            // 简约设计：弱化为小号次级文字按钮，去掉「长按多选」提示文案（手势可自然发现）。
            if (state.historyItems.isNotEmpty() && !multiSelectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        sortMode = if (sortMode == HistorySortMode.RECENT) {
                            HistorySortMode.ADDED
                        } else {
                            HistorySortMode.RECENT
                        }
                    }) {
                        Text(
                            text = "排序：${sortMode.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.TextSecondary,
                        )
                    }
                    TextButton(
                        onClick = { confirmCleanStale = true },
                        enabled = staleItems.isNotEmpty(),
                    ) {
                        Text(
                            text = "清理失效源${if (staleItems.isEmpty()) "" else "(${staleItems.size})"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvColors.TextSecondary,
                        )
                    }
                }
            }

            // #308：历史搜索框。仅在有历史时显示。
            // 简约设计：去外框 label，改为浅色圆角胶囊 + 内嵌放大镜图标，与整体风格统一。
            if (state.historyItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvColors.SurfaceRaised.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TvColors.TextTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (historyQuery.isEmpty()) {
                                Text(
                                    text = "搜索片名 / 来源 / 分类",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TvColors.TextTertiary,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = historyQuery,
                                onValueChange = { historyQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = TvColors.TextPrimary,
                                ),
                                cursorBrush = SolidColor(TvColors.Accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (historyQuery.isNotEmpty()) {
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvColors.TextTertiary,
                                modifier = Modifier
                                    .clickable { historyQuery = "" }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.historyItems.isEmpty()) {
                EmptyState(
                    title = "暂无观看历史",
                    subtitle = "在首页选择影片开始观看，进度会自动记录在这里",
                    actionLabel = "去首页",
                    onAction = actions::openHome,
                )
            } else if (filteredHistoryItems.isEmpty()) {
                EmptyState(
                    title = "没有匹配的历史",
                    subtitle = "试试其他关键词，或清空搜索框查看全部记录",
                )
            } else {
                // 分组计算依赖「本地自然日」：跨过午夜（或跨年）后若列表未变，首次计算时冻结的
                // 「今天/昨天」标签会错误地停留在旧日期。这里把本地日零点时间戳纳入 remember key，
                // 使页面在午夜之后任意一次重组（回到 Tab / 数据变化）时都按新日期重新分组。
                val dayStartMs = startOfDayMs(System.currentTimeMillis())
                // #317：按日期分组只适用于「最近观看」；「添加时间」排序下按 updatedAt 分组会打乱
                // 排序结果，因此直接展示单一分组。
                val sections = remember(sortedHistoryItems, dayStartMs, sortMode) {
                    if (sortMode == HistorySortMode.ADDED) {
                        listOf(HistorySection("按添加时间", sortedHistoryItems))
                    } else {
                        buildHistorySections(sortedHistoryItems, dayStartMs)
                    }
                }
                PullToRefreshBox(
                    isRefreshing = state.historyUpdateChecking,
                    onRefresh = { actions.refreshHistory() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                    sections.forEachIndexed { sectionIndex, section ->
                        // 使用独立 sticky key：同一影片在同源的多个观看记录（不同集数）必须各自拥有
                        // 稳定标识，否则 LazyColumn 在重组时可能把 scroll 状态、焦点与动画错乱到错误条目上。
                        // 分组头不用 label 作 key：日期标签（如「M月d日」）跨年会重复，改用分组序号保证唯一。
                        // 简约设计：分组头不再使用强调色胶囊底，改为弱化次级文字。
                        item(key = "header_$sectionIndex") {
                            Text(
                                text = section.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TvColors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
                            )
                        }
                        // 使用与多选选中集合相同的稳定 key（apiLineId:movieId:episodeIndex），
                        // 保证同一影片不同集数各自独立，且选中状态在重组后不丢失。
                        items(items = section.items, key = { historyItemKey(it) }) { entry ->
                            MobileHistoryRow(
                                item = entry,
                                multiSelectMode = multiSelectMode,
                                selected = historyItemKey(entry) in selectedKeys,
                                onClick = {
                                    if (multiSelectMode) toggleSelection(entry)
                                    else actions.resumeHistory(entry, TvScreen.History)
                                },
                                // #309：长按进入多选并选中当前条目；已进入多选则继续切换选中。
                                onLongClick = {
                                    if (!multiSelectMode) {
                                        multiSelectMode = true
                                        selectedKeys = setOf(historyItemKey(entry))
                                    } else {
                                        toggleSelection(entry)
                                    }
                                },
                                onDelete = { pendingDelete = entry },
                            )
                            // 扁平列表：用细分隔线代替卡片底色，视觉更轻。
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 76.dp),
                                thickness = 0.5.dp,
                                color = TvColors.Border.copy(alpha = 0.6f),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MobileHistoryRow(
    item: WatchHistoryItem,
    multiSelectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var posterFailed by remember(item.posterUrl) { mutableStateOf(false) }
    // 方案5（渐变进度 + 来源延续）：
    // - 标题「剧名·第N集」+ 新集 badge；
    // - 元信息「上次看到 NN% · 继续观看」（无时长数据时回退到具体时间点）；
    // - 蓝→紫渐变进度条；来源以海报底部小标签延续呈现。
    val title = remember(item.movieName, item.episodeTitle, item.episodeIndex) {
        val episode = item.episodeTitle.takeIf { it.isNotBlank() }
            ?.let { displayEpisodeTitle(it, item.episodeIndex) }
        if (episode.isNullOrBlank()) item.movieName else "${item.movieName}·$episode"
    }
    val progressText = if (item.durationMs > 0) {
        "上次看到 ${item.progressPercent}% · 继续观看"
    } else {
        "上次看到 ${formatPlayerTime(item.positionMs)} · 继续观看"
    }
    val hasSourceLabel = item.sourceName.isNotBlank()

    // 扁平行：无卡片底色，仅保留海报圆角与点击涟漪；分隔线由外层列表统一绘制。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // #309：长按进入多选；单选/多选手势都在这一层处理，避免与子控件争抢点击。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(8.dp))
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
            // 来源小标签：压在海报底部，延续「从哪个源继续看」的语境。
            if (hasSourceLabel) {
                Text(
                    text = item.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC0B0F14))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 新剧集提示统一走 NewEpisodeBadge（labelMedium、橙/绿高对比），
                // 与 TV 历史卡片、详情页同一套文案与颜色，保证竖屏列表可扫读。
                item.newEpisodeHint?.let { hint ->
                    Spacer(modifier = Modifier.width(8.dp))
                    NewEpisodeBadge(hint = hint)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 蓝→紫渐变进度条：LinearProgressIndicator 不支持 Brush，改为双层 Box 实现。
            val fraction = (item.progressPercent.coerceIn(0, 100)) / 100f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TvColors.SurfaceRaised),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(TvColors.Accent, Color(0xFF8B5CF6)),
                            ),
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        if (multiSelectMode) {
            // #309：多选态用只读勾选框展示选中状态，点击交由外层 combinedClickable 处理，
            // 避免 Checkbox 自身点击与整行点击重复触发导致「点一次反而取消选中」。
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除记录",
                    tint = TvColors.TextSecondary,
                )
            }
        }
    }
}

internal data class HistorySection(
    val label: String,
    val items: List<WatchHistoryItem>,
)

/** #317：历史排序模式。RECENT 按最近观看时间，ADDED 按首次加入时间。 */
internal enum class HistorySortMode(val label: String) {
    RECENT("最近观看"),
    ADDED("添加时间"),
}

/**
 * #309：某源某影片某集的稳定标识，用作多选选中集合与 LazyColumn 的 key，
 * 避免同一影片不同集数互相串位。
 */
internal fun historyItemKey(item: WatchHistoryItem): String =
    "${item.apiLineId}:${item.movieId}:${item.episodeIndex}"

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
    val currentYear = yearOf(nowMs)

    fun labelOf(epochMs: Long): String = when {
        epochMs >= todayStart -> "今天"
        epochMs >= yesterdayStart -> "昨天"
        else -> if (yearOf(epochMs) == currentYear) {
            SAME_YEAR_DAY_FORMAT.get().format(Date(epochMs))
        } else {
            CROSS_YEAR_DAY_FORMAT.get().format(Date(epochMs))
        }
    }

    val grouped = LinkedHashMap<String, MutableList<WatchHistoryItem>>()
    items.forEach { item ->
        grouped.getOrPut(labelOf(item.updatedAtEpochMs)) { mutableListOf() }.add(item)
    }
    return grouped.map { (label, group) -> HistorySection(label, group) }
}

/** 取 [epochMs] 所属自然年份。 */
private fun yearOf(epochMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    return calendar.get(Calendar.YEAR)
}

// ThreadLocal 避免跨线程共享 SimpleDateFormat（不线程安全）；且避免每次 build 重建。
// Java 的 ThreadLocal.get() 在 Kotlin 中是平台类型（判为可空），这里覆写为非空返回，
// 既保留线程本地复用语义，也无需在调用处加 ?. / !!。
private fun dayFormatThreadLocal(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat(pattern, Locale.getDefault())
    override fun get(): SimpleDateFormat = super.get() ?: initialValue().also { set(it) }
}
private val SAME_YEAR_DAY_FORMAT = dayFormatThreadLocal("M月d日")
private val CROSS_YEAR_DAY_FORMAT = dayFormatThreadLocal("yyyy年M月d日")
