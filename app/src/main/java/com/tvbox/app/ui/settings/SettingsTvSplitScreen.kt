package com.tvbox.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.BuildConfig
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvColors

/**
 * TV 端设置页。
 *
 * 与手机端 [SettingsMobileScreen] 采用**同一套信息架构**：入口页 = 状态 Hero 卡 + 分类卡片网格，
 * 点击进入分类详情（分组行 + 图标 + 右侧控件）。差异只在交互层：
 * 遥控器靠 [tvFocusScale] 焦点放大与描边，手机端靠触摸点击态。
 *
 * 分类与手机端完全一致（通用 / 播放 / 数据源 / 数据管理 / 关于）；
 * 远程管理在 TV 端仍可从「视频接口管理」页打开，不单独占一个分类。
 */
@Composable
fun SettingsTvSplitScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf<TvSettingCategory?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }

    // 分类详情是二级页面：遥控器返回键优先回到分类入口，与「返回」按钮一致。
    // 只在详情页启用，入口页仍交给根 BackHandler 返回首页。
    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空历史记录", color = TvColors.TextPrimary) },
            text = { Text("确定要清空所有观看历史记录吗？此操作不可撤销。", color = TvColors.TextSecondary) },
            confirmButton = {
                Button(onClick = { confirmClearHistory = false; actions.clearHistory() }) {
                    Text("清空", color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消", color = TvColors.TextSecondary) } },
        )
    }
    if (confirmClearPlaybackStats) {
        AlertDialog(
            onDismissRequest = { confirmClearPlaybackStats = false },
            title = { Text("清空线路统计", color = TvColors.TextPrimary) },
            text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。", color = TvColors.TextSecondary) },
            confirmButton = {
                Button(onClick = { confirmClearPlaybackStats = false; actions.clearPlaybackStats() }) {
                    Text("清空", color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearPlaybackStats = false }) { Text("取消", color = TvColors.TextSecondary) } },
        )
    }

    Box(modifier = modifier.fillMaxSize().background(TvColors.Background)) {
        Crossfade(targetState = selectedCategory, label = "tv-settings-crossfade") { category ->
            if (category == null) {
                TvSettingsLanding(
                    state = state,
                    onCategoryClick = { selectedCategory = it },
                )
            } else {
                TvSettingsDetail(
                    category = category,
                    state = state,
                    actions = actions,
                    onBack = { selectedCategory = null },
                    onClearHistory = { confirmClearHistory = true },
                    onClearPlaybackStats = { confirmClearPlaybackStats = true },
                )
            }
        }
    }
}

// ─────────────────────────── 入口页 ───────────────────────────

@Composable
private fun TvSettingsLanding(
    state: TvBoxUiState,
    onCategoryClick: (TvSettingCategory) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "设置",
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 40.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { TvSettingsHeroCard(state = state) }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(TvSettingCategory.entries) { category ->
                        TvCategoryCard(category = category, onClick = { onCategoryClick(category) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSettingsHeroCard(state: TvBoxUiState) {
    val detectedMode = DeviceMode.detectFromSystem(androidx.compose.ui.platform.LocalContext.current)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        TvColors.Accent.copy(alpha = 0.28f),
                        TvColors.AccentStrong.copy(alpha = 0.12f),
                    )
                )
            )
            .border(1.dp, TvColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(TvColors.Accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    "当前配置",
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvHeroStat("视频源", state.selectedApiLine?.name ?: "未配置", Modifier.weight(1f))
                TvHeroStat("直播源", "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条", Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvHeroStat("设备", state.appSettings.deviceMode.summaryLabel(detectedMode), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TvHeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, color = TvColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvCategoryCard(category: TvSettingCategory, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        category.gradientStart.copy(alpha = 0.22f),
                        category.gradientEnd.copy(alpha = 0.06f),
                    )
                )
            )
            .border(1.dp, category.gradientStart.copy(alpha = 0.25f), shape)
            .tvFocusScale(shape = shape)
            .clickable(role = Role.Button, onClick = onClick)
            // 图标、标题、副标题原本各自成节点，整卡合并成一条分类播报。
            .semantics(mergeDescendants = true) {
                contentDescription = "${category.title}，${category.subtitle}"
            }
            .focusable()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(category.gradientStart.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = category.gradientStart,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column {
                Text(
                    text = category.title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = category.subtitle,
                    color = TvColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────── 详情页 ───────────────────────────

@Composable
private fun TvSettingsDetail(
    category: TvSettingCategory,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onClearPlaybackStats: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvFocusButton(text = "返回", onClick = onBack)
            Text(
                text = category.title,
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = category.subtitle,
            color = TvColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                when (category) {
                    TvSettingCategory.General -> TvGeneralDetail(state, actions)
                    TvSettingCategory.Playback -> TvPlaybackDetail(state, actions, onClearPlaybackStats)
                    TvSettingCategory.Sources -> TvSourcesDetail(state, actions)
                    TvSettingCategory.Data -> TvDataDetail(state, actions, onClearHistory)
                    TvSettingCategory.About -> TvAboutDetail(state, actions)
                }
            }
        }
    }
}

@Composable
private fun TvGeneralDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val detectedMode = DeviceMode.detectFromSystem(androidx.compose.ui.platform.LocalContext.current)
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "设备模式",
            subtitle = state.appSettings.deviceMode.summaryLabel(detectedMode),
            trailing = {
                TvChoiceChips(
                    options = DeviceMode.entries,
                    selected = state.appSettings.deviceMode,
                    label = { it.displayName },
                    onSelect = actions::updateDeviceMode,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "界面主题",
            subtitle = state.appSettings.theme.displayName,
            trailing = {
                TvChoiceChips(
                    options = TvTheme.entries,
                    selected = state.appSettings.theme,
                    label = { it.displayName },
                    onSelect = actions::updateTheme,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "字体大小",
            subtitle = state.appSettings.fontScale.displayName,
            trailing = {
                TvChoiceChips(
                    options = TvFontScale.entries,
                    selected = state.appSettings.fontScale,
                    label = { it.displayName },
                    onSelect = actions::updateFontScale,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "继续观看",
            subtitle = if (state.appSettings.homeContinueWatchingEnabled) "首页显示最近观看记录" else "不显示继续观看",
            trailing = {
                Switch(
                    checked = state.appSettings.homeContinueWatchingEnabled,
                    onCheckedChange = actions::updateHomeContinueWatching,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "启动时检查更新",
            subtitle = if (state.appSettings.checkUpdatesOnStartup) "已开启" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.checkUpdatesOnStartup,
                    onCheckedChange = actions::updateStartupUpdateCheck,
                )
            },
        )
    }
}

@Composable
private fun TvPlaybackDetail(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onClearPlaybackStats: () -> Unit,
) {
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "自动换线",
            subtitle = if (state.appSettings.playbackAgentAutoSwitchEnabled) "播放失败或缓冲过久时自动切换" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                    onCheckedChange = actions::updatePlaybackAgentAutoSwitch,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "视频缩放",
            subtitle = state.appSettings.videoScaleMode.displayName,
            trailing = {
                TvChoiceChips(
                    options = VideoScaleMode.entries,
                    selected = state.appSettings.videoScaleMode,
                    label = { it.displayName },
                    onSelect = actions::updateVideoScaleMode,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "解码策略",
            subtitle = state.appSettings.decoderStrategy.description,
            trailing = {
                TvChoiceChips(
                    options = DecoderStrategy.entries,
                    selected = state.appSettings.decoderStrategy,
                    label = { it.displayName },
                    onSelect = actions::updateDecoderStrategy,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页每行卡片数",
            subtitle = if (state.appSettings.videoGridColumns > 0) "每行 ${state.appSettings.videoGridColumns} 个" else "自动（按屏幕宽度，每格最小 160dp）",
            trailing = {
                TvChoiceChips(
                    options = listOf(0, 3, 4, 5, 6),
                    selected = state.appSettings.videoGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updateVideoGridColumns,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页热播数量",
            subtitle = quantitySummary(
                state.appSettings.homeHotContentCount,
                QuantitySettings.HomeHotContentCount,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.HomeHotContentCount.choices,
                    selected = state.appSettings.homeHotContentCount,
                    label = {
                        if (it == QuantitySettings.HomeHotContentCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateHomeHotContentCount,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "直播卡片数量",
            subtitle = if (state.appSettings.platformLiveGridColumns > 0) "每行 ${state.appSettings.platformLiveGridColumns} 个" else "自动（按设备类型）",
            trailing = {
                TvChoiceChips(
                    options = listOf(0, 4, 5, 6, 7, 8),
                    selected = state.appSettings.platformLiveGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updatePlatformLiveGridColumns,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "继续观看数量",
            subtitle = quantitySummary(
                state.appSettings.continueWatchingCount,
                QuantitySettings.ContinueWatchingCount,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.ContinueWatchingCount.choices,
                    selected = state.appSettings.continueWatchingCount,
                    label = {
                        if (it == QuantitySettings.ContinueWatchingCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateContinueWatchingCount,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "EPG 节目条数",
            subtitle = quantitySummary(
                state.appSettings.epgCardRows,
                QuantitySettings.EpgCardRows,
                unit = " 条",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.EpgCardRows.choices,
                    selected = state.appSettings.epgCardRows,
                    label = { if (it == QuantitySettings.EpgCardRows.default) "默认" else "$it" },
                    onSelect = actions::updateEpgCardRows,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台候选行数",
            subtitle = quantitySummary(
                state.appSettings.liveNumberMatchRows,
                QuantitySettings.LiveNumberMatchRows,
                unit = " 行",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.LiveNumberMatchRows.choices,
                    selected = state.appSettings.liveNumberMatchRows,
                    label = { if (it == QuantitySettings.LiveNumberMatchRows.default) "默认" else "$it" },
                    onSelect = actions::updateLiveNumberMatchRows,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台匹配上限",
            subtitle = quantitySummary(
                state.appSettings.liveNumberSearchLimit,
                QuantitySettings.LiveNumberSearchLimit,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.LiveNumberSearchLimit.choices,
                    selected = state.appSettings.liveNumberSearchLimit,
                    label = {
                        if (it == QuantitySettings.LiveNumberSearchLimit.default) "默认" else "$it"
                    },
                    onSelect = actions::updateLiveNumberSearchLimit,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "相关频道数量",
            subtitle = quantitySummary(
                state.appSettings.relatedChannelCount,
                QuantitySettings.RelatedChannelCount,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.RelatedChannelCount.choices,
                    selected = state.appSettings.relatedChannelCount,
                    label = { if (it == QuantitySettings.RelatedChannelCount.default) "默认" else "$it" },
                    onSelect = actions::updateRelatedChannelCount,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "剧集排序",
            subtitle = if (state.appSettings.episodeSortReverse) "倒序（…,3,2,1）" else "正序（1,2,3,…）",
            trailing = {
                TvFocusButton(
                    text = if (state.appSettings.episodeSortReverse) "切正序" else "切倒序",
                    onClick = actions::toggleEpisodeSortReverse,
                )
            },
        )
        TvDividerInGroup()
        TvSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片头跳过",
            subtitle = if (state.appSettings.introSkipSeconds > 0) "自动跳过前 ${state.appSettings.introSkipSeconds}s" else "不跳过",
            seconds = state.appSettings.introSkipSeconds,
            onSecondsChange = actions::updateIntroSkipSeconds,
        )
        TvDividerInGroup()
        TvSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片尾跳过",
            subtitle = if (state.appSettings.outroSkipSeconds > 0) "提前 ${state.appSettings.outroSkipSeconds}s 进入下一集" else "不跳过",
            seconds = state.appSettings.outroSkipSeconds,
            onSecondsChange = actions::updateOutroSkipSeconds,
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "线路统计",
            subtitle = state.playbackHealth.qualitySummaryText(),
            trailing = {
                if (state.playbackHealth.entryCount > 0) {
                    TvFocusButton(text = "清空", onClick = onClearPlaybackStats)
                }
            },
        )
    }
}

@Composable
private fun TvSourcesDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "视频源",
            subtitle = state.selectedApiLine?.name ?: "未配置",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openVideoSourceManager) },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "IPTV 直播源",
            subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openLiveSourceManager) },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openPlatformLive) },
        )
    }
}

@Composable
private fun TvDataDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearHistory: () -> Unit) {
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "观看历史",
            subtitle = "管理本地播放记录（共 ${state.historyItems.size} 条）",
            trailing = {
                if (state.historyItems.isNotEmpty()) {
                    TvFocusButton(text = "清空", onClick = onClearHistory)
                }
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "历史保留条数",
            subtitle = quantitySummary(
                state.appSettings.historyMaxItems,
                QuantitySettings.HistoryMaxItems,
                unit = " 条",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.HistoryMaxItems.choices,
                    selected = state.appSettings.historyMaxItems,
                    label = { if (it == QuantitySettings.HistoryMaxItems.default) "默认" else "$it" },
                    onSelect = actions::updateHistoryMaxItems,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "搜索热词数量",
            subtitle = quantitySummary(
                state.appSettings.hotWordCount,
                QuantitySettings.HotWordCount,
                unit = " 条",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.HotWordCount.choices,
                    selected = state.appSettings.hotWordCount,
                    label = { if (it == QuantitySettings.HotWordCount.default) "默认" else "$it" },
                    onSelect = actions::updateHotWordCount,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "拼音候选数量",
            subtitle = quantitySummary(
                state.appSettings.searchSuggestionCount,
                QuantitySettings.SearchSuggestionCount,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.SearchSuggestionCount.choices,
                    selected = state.appSettings.searchSuggestionCount,
                    label = {
                        if (it == QuantitySettings.SearchSuggestionCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateSearchSuggestionCount,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "豆瓣热播每页条数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotPageSize,
                QuantitySettings.DoubanHotPageSize,
                unit = " 条",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.DoubanHotPageSize.choices,
                    selected = state.appSettings.doubanHotPageSize,
                    label = { if (it == QuantitySettings.DoubanHotPageSize.default) "默认" else "$it" },
                    onSelect = actions::updateDoubanHotPageSize,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "热播回退源数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotFallbackLines,
                QuantitySettings.DoubanHotFallbackLines,
                unit = " 路",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.DoubanHotFallbackLines.choices,
                    selected = state.appSettings.doubanHotFallbackLines,
                    label = {
                        if (it == QuantitySettings.DoubanHotFallbackLines.default) "默认" else "$it"
                    },
                    onSelect = actions::updateDoubanHotFallbackLines,
                )
            },
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "多源搜索并发数",
            subtitle = quantitySummary(
                state.appSettings.maxParallelSourceRequests,
                QuantitySettings.MaxParallelSourceRequests,
                unit = " 个",
            ),
            trailing = {
                TvChoiceChips(
                    options = QuantitySettings.MaxParallelSourceRequests.choices,
                    selected = state.appSettings.maxParallelSourceRequests,
                    label = {
                        if (it == QuantitySettings.MaxParallelSourceRequests.default) "默认" else "$it"
                    },
                    onSelect = actions::updateMaxParallelSourceRequests,
                )
            },
        )
    }
}

@Composable
private fun TvAboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "应用名称",
            subtitle = "TVBox",
            trailing = {},
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "当前版本",
            subtitle = "v${BuildConfig.VERSION_NAME}",
            trailing = {},
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "开源协议",
            subtitle = "GPLv3",
            trailing = {},
        )
        TvDividerInGroup()
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "检查更新",
            subtitle = state.updateError ?: "当前版本 v${BuildConfig.VERSION_NAME}",
            trailing = {
                if (!state.updateChecking) {
                    TvFocusButton(text = "检查", onClick = { actions.checkForAppUpdate(showError = true) })
                } else {
                    Text("检查中…", color = TvColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
    }
}

// ─────────────────────────── 通用组件 ───────────────────────────

@Composable
private fun TvSettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TvColors.Surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun TvDividerInGroup() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = TvColors.Border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun TvSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TvColors.AccentSoft.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TvColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TvColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, color = TvColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(16.dp))
        Box(contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

@Composable
private fun TvSecondsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TvColors.AccentSoft.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TvColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TvColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, color = TvColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        TvFocusButton(text = "−5s", onClick = { onSecondsChange((seconds - 5).coerceAtLeast(0)) })
        Text(
            text = "${seconds}s",
            color = TvColors.Accent,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp).padding(horizontal = 8.dp),
        )
        TvFocusButton(text = "+5s", onClick = { onSecondsChange((seconds + 5).coerceAtMost(300)) })
    }
}

@Composable
private fun <T> TvChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    // 选项多或 720p 窄屏时自动换行；focusGroup 保证遥控器左右/上下移动按视觉顺序走。
    FlowRow(
        modifier = Modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(shape)
                    .background(
                        if (isSelected) TvColors.Accent.copy(alpha = 0.18f)
                        else TvColors.SurfaceRaised.copy(alpha = 0.6f)
                    )
                    .tvFocusScale(shape = shape)
                    .clickable(role = Role.Button) { onSelect(option) }
                    .semantics { stateDescription = if (isSelected) "已选中" else "未选中" }
                    .focusable()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) TvColors.Accent else TvColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvFocusButton(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> TvColors.SurfaceRaised.copy(alpha = 0.5f)
                    selected -> TvColors.Accent.copy(alpha = 0.22f)
                    else -> TvColors.SurfaceRaised
                }
            )
            .tvFocusScale(shape = shape)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics {
                stateDescription = when {
                    !enabled -> "不可用"
                    selected -> "已选中"
                    else -> "未选中"
                }
            }
            .focusable(enabled = enabled)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> TvColors.TextTertiary
                selected -> TvColors.Accent
                else -> TvColors.TextPrimary
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ─────────────────────────── 分类定义（与手机端一致） ───────────────────────────

private enum class TvSettingCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    General("通用", "设备·外观·启动", Icons.Default.Settings, Color(0xFF4DA3FF), Color(0xFF1E3A5F)),
    Playback("播放", "播放管家·缩放·跳过", Icons.Default.PlayArrow, Color(0xFF1ED760), Color(0xFF0E4A23)),
    Sources("数据源", "视频源·直播源", Icons.AutoMirrored.Filled.List, Color(0xFFFFA42B), Color(0xFF5C3A0E)),
    Data("数据管理", "历史·搜索·分页", Icons.Default.Delete, Color(0xFFFF7171), Color(0xFF5C1E25)),
    About("关于", "版本·协议", Icons.Default.Info, Color(0xFF55F0A1), Color(0xFF14532D)),
}
