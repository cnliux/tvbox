package com.tvbox.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.ui.MobileNavScreen
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.TvScreen
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.history.component.HistoryCardRow
import com.tvbox.app.ui.history.component.HistoryProgressBar
import com.tvbox.app.ui.history.component.PosterWithDecor
import com.tvbox.app.ui.history.component.SectionHeader
import com.tvbox.app.ui.history.component.StatusRow
import com.tvbox.app.ui.theme.TvColors
import kotlinx.coroutines.launch

/**
 * 手机模式「历史」Tab：针对竖屏触屏重新设计，不再复用电视端的网格 + 焦点体系。
 *
 * - 列表式单列布局：一行一条记录，信息密度与触控目标更适合手机；
 * - 按「今天 / 昨天 / 日期」分组，快速定位最近观看；
 * - 每条右侧提供删除按钮，支持单条清理，不必为了删一条而清空全部；
 * - 顶部不显示任何说明性提示，标题与「清空」按钮共用一行，节省竖屏空间；
 * - 空态动作用 [TvBoxViewModel.openHome] 跳转首页（历史是底部 Tab，没有"返回上一页"语义）。
 */
@Composable
internal fun MobileHistoryScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val scope = rememberCoroutineScope()
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
    // 「仅看更新」Chip：只保留有新集提示（newEpisodeHint != null）的记录。
    var onlyUpdates by remember { mutableStateOf(false) }
    val filteredHistoryItems = remember(state.historyItems, normalizedHistoryQuery, onlyUpdates) {
        val queried = if (normalizedHistoryQuery.isBlank()) {
            state.historyItems
        } else {
            val needle = normalizedHistoryQuery
            state.historyItems.filter { item ->
                item.movieName.contains(needle, ignoreCase = true) ||
                    item.sourceName.contains(needle, ignoreCase = true) ||
                    item.typeName.contains(needle, ignoreCase = true)
            }
        }
        if (onlyUpdates) queried.filter { it.newEpisodeHint != null } else queried
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
    // 分组数据上移到列表外计算：Header 作为 LazyColumn 的第一个 item，与分组共用同一次重组结果。
    val dayStartMs = startOfDayMs(System.currentTimeMillis())
    val sections = remember(sortedHistoryItems, dayStartMs, sortMode) {
        if (sortMode == HistorySortMode.ADDED) {
            listOf(HistorySection("按添加时间", sortedHistoryItems))
        } else {
            buildHistorySections(sortedHistoryItems, dayStartMs)
        }
    }

    // ---- 新增：封面装饰与脉冲 gate ----
    val newCount = filteredHistoryItems.count { it.newEpisodeHint != null }
    val pulseEnabled = remember(newCount) { newCount in 1..3 }
    val decorCache = remember(filteredHistoryItems, pulseEnabled) {
        filteredHistoryItems.associateWith { it.toCoverDecor(pulseEnabled) }
    }
    val firstNewIndex = remember(filteredHistoryItems) { filteredHistoryItems.firstNewIndex() }

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
            text = { Text("将清除 ${state.historyItems.size} 条 / ${formatStorageEstimate(state.historyItems)}，源与 UA 不受影响。") },
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

    val listState = rememberLazyListState()
    var showJumpBtn by remember { mutableStateOf(firstNewIndex >= 0) }
    LaunchedEffect(firstNewIndex) {
        showJumpBtn = firstNewIndex >= 0
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 修复顶部遮挡：仅使用 statusBars 吸收顶部状态栏 inset（不使用 systemBars，
            // 因为手机版底部已有 MobileNavigationBar 自行处理导航栏空间，再加 systemBars
            // 底部 padding 会压缩内容导致搜索框以上的区域被挤出视口）。
            .windowInsetsPadding(WindowInsets.statusBars),
        color = Color(0xFF121212),
    ) {
        if (state.historyItems.isEmpty()) {
            EmptyState(
                title = "还没有观看记录",
                subtitle = "在首页选择影片开始观看，进度会自动记录在这里",
                actionLabel = "去豆瓣热播",
                onAction = null,
            )
        } else {
            PullToRefreshBox(
                isRefreshing = state.historyUpdateChecking,
                onRefresh = { actions.refreshHistory() },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        // 沉浸式 Header：搜索框 + 筛选 Chip 作为第一个 item，随列表一起向上滚动。
                        // 顶部收紧：搜索框 40→36dp、行间距 10→8dp、底部留白 8→6dp，减少顶部空档。
                        item(key = "history_immersive_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // 第一行：搜索框
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF0D0D0D),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = Color(0xFF5C5C5C),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (historyQuery.isEmpty()) {
                                                Text(
                                                    text = "搜索剧名 / 演员 / 源名",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = Color(0xFF5C5C5C),
                                                    maxLines = 1,
                                                )
                                            }
                                            androidx.compose.foundation.text.BasicTextField(
                                                value = historyQuery,
                                                onValueChange = { historyQuery = it },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                    color = TvColors.TextPrimary,
                                                ),
                                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFFB020)),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        when {
                                            historyQuery.isNotEmpty() -> Text(
                                                text = "✕",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF5C5C5C),
                                                modifier = Modifier
                                                    .semantics { role = androidx.compose.ui.semantics.Role.Button }
                                                    .clickable { historyQuery = "" }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                            )
                                            state.historyUpdateChecking -> CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFFFFB020),
                                            )
                                        }
                                    }
                                }

                                // 第二行：横向滑动 Chip 组
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (multiSelectMode) {
                                        HistoryChip(
                                            label = "全选",
                                            onClick = { selectedKeys = filteredHistoryItems.map(::historyItemKey).toSet() },
                                        )
                                        HistoryChip(
                                            label = "删除(${selectedKeys.size})",
                                            danger = true,
                                            enabled = selectedItems.isNotEmpty(),
                                            onClick = { confirmBatchDelete = true },
                                        )
                                        HistoryChip(label = "取消", onClick = { exitMultiSelect() })
                                    } else {
                                        HistoryChip(
                                            label = sortMode.label,
                                            selected = sortMode == HistorySortMode.RECENT,
                                            onClick = {
                                                sortMode = if (sortMode == HistorySortMode.RECENT) {
                                                    HistorySortMode.ADDED
                                                } else {
                                                    HistorySortMode.RECENT
                                                }
                                            },
                                        )
                                        // 琥珀 "有更新" chip（已存在的 onlyUpdates 换皮）
                                        HistoryChip(
                                            label = "↺ 有更新 $newCount",
                                            selected = onlyUpdates,
                                            accent = true,
                                            onClick = { onlyUpdates = !onlyUpdates },
                                        )
                                        HistoryChip(
                                            label = "清理失效源",
                                            enabled = staleItems.isNotEmpty(),
                                            onClick = { confirmCleanStale = true },
                                        )
                                        HistoryChip(
                                            label = "清空历史",
                                            danger = true,
                                            onClick = { confirmClear = true },
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                        }

                        if (filteredHistoryItems.isEmpty()) {
                            item(key = "history_no_match") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 64.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "没有匹配「$normalizedHistoryQuery」",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TvColors.TextPrimary,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("已看完", "有更新", "直播").forEach { suggest ->
                                                Text(
                                                    text = suggest,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF888888),
                                                    modifier = Modifier
                                                        .semantics { role = androidx.compose.ui.semantics.Role.Button }
                                                        .clickable { historyQuery = suggest },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            sections.forEachIndexed { sectionIndex, section ->
                                item(key = "header_$sectionIndex") {
                                    SectionHeader(
                                        label = section.label,
                                        count = section.items.size,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                items(items = section.items, key = { historyItemKey(it) }) { entry ->
                                    val decor = decorCache[entry]
                                        ?: entry.toCoverDecor(pulseEnabled)

                                    HistoryCardRow(
                                        item = entry,
                                        multiSelectMode = multiSelectMode,
                                        selected = historyItemKey(entry) in selectedKeys,
                                        pulseEnabled = pulseEnabled,
                                        onClick = {
                                            if (multiSelectMode) toggleSelection(entry)
                                            else actions.resumeHistory(entry, TvScreen.History)
                                        },
                                        onLongClick = {
                                            if (!multiSelectMode) {
                                                multiSelectMode = true
                                                selectedKeys = setOf(historyItemKey(entry))
                                            } else {
                                                toggleSelection(entry)
                                            }
                                        },
                                        onDelete = { pendingDelete = entry },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                    // 扁平列表：细分隔线只贯穿右侧文字区域（与文字列左缘对齐）
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 78.dp),
                                        thickness = HistoryTokens.M.SegH,
                                        color = HistoryTokens.Divider,
                                    )
                                }
                            }
                        }
                    }

                    // 跳未读悬浮钮
                    if (showJumpBtn && firstNewIndex >= 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp)
                                .size(48.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFFFFB020), Color(0xFFFF8C00))),
                                    shape = CircleShape,
                                )
                                .semantics {
                                    role = androidx.compose.ui.semantics.Role.Button
                                    contentDescription = "跳到 ${newCount} 部有更新的记录"
                                }
                                .clickable {
                                    scope.launch { listState.animateScrollToItem(firstNewIndex) }
                                    showJumpBtn = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "↓ $newCount",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 工具函数（从 MobileHistoryScreen.kt 迁移过来，保持独立文件）
// ─────────────────────────────────────────────────────────────

private fun formatStorageEstimate(items: List<WatchHistoryItem>): String =
    "${(items.size * 4).toInt()}KB" // 近似估算

internal data class HistorySection(
    val label: String,
    val items: List<WatchHistoryItem>,
)

internal enum class HistorySortMode(val label: String) {
    RECENT("最近观看"),
    ADDED("添加时间"),
}

internal fun historyItemKey(item: WatchHistoryItem): String =
    "${item.apiLineId}:${item.movieId}:${item.episodeIndex}"

/**
 * 历史筛选/操作 Chip：横向滑动一排里的小胶囊。
 * - [selected]：当前生效（仅排序 / 有更新筛选用）
 * - [accent]：琥珀"有更新"强调
 * - [danger]：删除类危险操作
 * - [enabled]：灰态（失效源为空 / 无选中项时）
 */
@Composable
private fun HistoryChip(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    danger: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val (bg, fg) = when {
        !enabled -> HistoryTokens.CaughtUpBg to HistoryTokens.MetaDim
        danger   -> HistoryTokens.NewTextBg to HistoryTokens.NewText
        accent   -> HistoryTokens.NewTextBg to HistoryTokens.NewText
        selected -> HistoryTokens.NewTextBg to HistoryTokens.NewText
        else     -> Color(0xFF222222) to HistoryTokens.Meta
    }
    Surface(
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
