package com.tvbox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
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
        if (state.historyItems.isEmpty()) {
            EmptyState(
                title = "暂无观看历史",
                subtitle = "在首页选择影片开始观看，进度会自动记录在这里",
                actionLabel = "去首页",
                onAction = actions::openHome,
            )
        } else {
            PullToRefreshBox(
                isRefreshing = state.historyUpdateChecking,
                onRefresh = { actions.refreshHistory() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // 沉浸式 Header：搜索框 + 筛选 Chip 作为第一个 item，随列表一起向上滚动。
                    item(key = "history_immersive_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 第一行：极简搜索框（44dp / #1A1D26 / 极细边框）。
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ImmersiveHistoryTokens.Surface,
                                border = BorderStroke(0.5.dp, ImmersiveHistoryTokens.Border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = ImmersiveHistoryTokens.TextMuted,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (historyQuery.isEmpty()) {
                                            Text(
                                                text = "搜索片名 / 来源",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = ImmersiveHistoryTokens.TextMuted,
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
                                            cursorBrush = SolidColor(ImmersiveHistoryTokens.SelectedText),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    when {
                                        historyQuery.isNotEmpty() -> Text(
                                            text = "✕",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ImmersiveHistoryTokens.TextMuted,
                                            modifier = Modifier
                                                .clickable { historyQuery = "" }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                        state.historyUpdateChecking -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = ImmersiveHistoryTokens.SelectedText,
                                        )
                                    }
                                }
                            }

                            // 第二行：横向滑动 Chip 组；多选态复用同一行位置放操作 Chip。
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
                                    // 「最近观看」Chip 兼作排序入口：RECENT 高亮，点击切换为「添加时间」。
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
                                    HistoryChip(
                                        label = "清理失效源",
                                        enabled = staleItems.isNotEmpty(),
                                        onClick = { confirmCleanStale = true },
                                    )
                                    HistoryChip(
                                        label = "仅看更新",
                                        selected = onlyUpdates,
                                        onClick = { onlyUpdates = !onlyUpdates },
                                    )
                                    HistoryChip(
                                        label = "清空历史",
                                        danger = true,
                                        onClick = { confirmClear = true },
                                    )
                                }
                                // 末尾留白，保证最后一个 Chip 能完整滑入视口。
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                    }

                    if (filteredHistoryItems.isEmpty()) {
                        item(key = "history_no_match") {
                            // 不用 EmptyState：其内部 fillMaxSize() 在 LazyColumn 的无限高约束下会失控。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "没有匹配的历史",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TvColors.TextPrimary,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "试试其他关键词，或清空搜索框查看全部记录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    } else {
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
                                // 缩进 88dp = 行内边距 4dp + 海报 72dp + 间距 12dp，
                                // 分割线只贯穿右侧文字区域，不穿过左侧海报。
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 88.dp),
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

/**
 * 「现代沉浸式 Chip」历史头部专用设计令牌，集中在此便于统一换肤，不污染全局 [TvColors] 主题。
 * 颜色严格对应设计稿：#1A1D26 / #94A3B8 / #34D399 / #EF4444 / #888888。
 */
private object ImmersiveHistoryTokens {
    val Surface = Color(0xFF1A1D26)
    val Border = Color(0xFF2A2F3A)
    val TextMuted = Color(0xFF94A3B8)
    val SelectedBackground = Color(0x1A34D399)
    val SelectedText = Color(0xFF34D399)
    val SelectedBorder = Color(0x4D34D399)
    val Danger = Color(0xFFEF4444)
}

/**
 * 历史头部筛选胶囊：默认深灰+细边框，选中半透明绿，警告态红字。
 * 16dp 圆角配 32dp 高度即标准胶囊；禁用态整体降透明度但仍保留轮廓。
 */
@Composable
private fun HistoryChip(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        ImmersiveHistoryTokens.SelectedBackground
    } else {
        ImmersiveHistoryTokens.Surface
    }
    val outline = if (selected) {
        ImmersiveHistoryTokens.SelectedBorder
    } else {
        ImmersiveHistoryTokens.Border
    }
    val foreground = when {
        danger -> ImmersiveHistoryTokens.Danger
        selected -> ImmersiveHistoryTokens.SelectedText
        else -> ImmersiveHistoryTokens.TextMuted
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier
            .height(32.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
            )
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
    // 沉浸式列表项（降噪）：
    // - 左侧海报固定 72x102dp、8dp 圆角、Crop 缩放；
    // - 标题「剧名·第N集」maxLines=1 省略，新集 badge 固定在标题右侧不被压缩；
    // - 元信息只保留「上次看到 NN% · 继续观看」，不再显示来源名（来源在详情页可见）；
    // - 蓝→紫渐变进度条；海报不再叠加任何来源文字，画面保持干净。
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
                .size(width = 72.dp, height = 102.dp)
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
            // 海报上不再叠加来源文字：来源已移至详情页，避免遮挡画面。
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            // weight(1f) 占满海报与删除图标之间的剩余宽度，高度与海报对齐（Row 默认 CenterVertically）。
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 标题吃掉剩余宽度并省略；badge 是非 weighted 子项、先测量，
                    // 因此永远保持完整尺寸，不会被长标题压缩。
                    modifier = Modifier.weight(1f),
                )
                // 新剧集提示统一走 NewEpisodeBadge（labelMedium、橙/绿高对比），
                // 与 TV 历史卡片、详情页同一套文案与颜色，保证竖屏列表可扫读。
                item.newEpisodeHint?.let { hint ->
                    Spacer(modifier = Modifier.width(8.dp))
                    NewEpisodeBadge(hint = hint)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 「上次看到 NN% · 继续观看」：不显示源名称，详情页已展示来源。
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
