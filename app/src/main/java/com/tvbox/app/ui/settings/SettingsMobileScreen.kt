package com.tvbox.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.BuildConfig
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.RemoteManageDialog
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.theme.TvColors

/**
 * 手机端设置页 —— 全新两级结构：分类入口网格 → 分类详情。
 * 入口页含状态 Hero 与 6 张渐变分类卡；点击卡片 Crossfade 到详情页。
 */
@Composable
fun SettingsMobileScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var selectedCategory by remember { mutableStateOf<MobileSettingCategory?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }

    // 分类详情是二级页面：系统返回键优先回到分类入口，与顶部返回按钮一致。
    // 只在详情页启用，入口页仍交给根 BackHandler 返回首页。
    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    if (state.remoteManageDialogVisible) {
        RemoteManageDialog(state = state, actions = actions)
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空历史记录", color = TvColors.TextPrimary) },
            text = { Text("确定要清空所有观看历史记录吗？此操作不可撤销。", color = TvColors.TextSecondary) },
            confirmButton = {
                Button(onClick = { confirmClearHistory = false; actions.clearHistory() }) { Text("清空", color = MaterialTheme.colorScheme.onPrimary) }
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
                Button(onClick = { confirmClearPlaybackStats = false; actions.clearPlaybackStats() }) { Text("清空", color = MaterialTheme.colorScheme.onPrimary) }
            },
            dismissButton = { TextButton(onClick = { confirmClearPlaybackStats = false }) { Text("取消", color = TvColors.TextSecondary) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
    ) {
        Crossfade(targetState = selectedCategory, label = "settings-mobile-crossfade") { category ->
            if (category == null) {
                SettingsMobileLanding(
                    state = state,
                    onCategoryClick = { selectedCategory = it },
                )
            } else {
                SettingsMobileDetail(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMobileLanding(
    state: TvBoxUiState,
    onCategoryClick: (MobileSettingCategory) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏：大标题，透明背景
        TopAppBar(
            title = {
                Text(
                    "设置",
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = TvColors.TextPrimary,
            ),
            windowInsets = WindowInsets.statusBars,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { SettingsHeroCard(state = state) }
            item { SettingsCategoryGrid(onCategoryClick = onCategoryClick) }
            item {
                Text(
                    text = "TVBox v${BuildConfig.VERSION_NAME} · GPLv3 · 仅供技术研究使用",
                    color = TvColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeroCard(state: TvBoxUiState) {
    val detectedMode = DeviceMode.detectFromSystem(LocalContext.current)
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(TvColors.Accent)
                )
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
                HeroStat(label = "视频源", value = state.selectedApiLine?.name ?: "未配置", modifier = Modifier.weight(1f))
                HeroStat(label = "直播源", value = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条", modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeroStat(label = "设备", value = state.appSettings.deviceMode.summaryLabel(detectedMode), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
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
private fun SettingsCategoryGrid(onCategoryClick: (MobileSettingCategory) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        items(MobileSettingCategory.entries) { category ->
            CategoryCard(category = category, onClick = { onCategoryClick(category) })
        }
    }
}

@Composable
private fun CategoryCard(category: MobileSettingCategory, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            // 卡片内图标、标题、说明原本是三个语义节点，合并成一条分类条目播报。
            .semantics(mergeDescendants = true) {
                contentDescription = "${category.title}，${category.subtitle}"
            },
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            category.gradientStart.copy(alpha = 0.22f),
                            category.gradientEnd.copy(alpha = 0.06f),
                        )
                    )
                )
                .border(1.dp, category.gradientStart.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
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
                        contentDescription = category.title,
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
}

// ─────────────────────────── 详情页 ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMobileDetail(
    category: MobileSettingCategory,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onClearPlaybackStats: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    category.title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TvColors.TextPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = TvColors.TextPrimary,
            ),
            windowInsets = WindowInsets.statusBars,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = category.subtitle,
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
            when (category) {
                MobileSettingCategory.General -> {
                    item { GeneralDetail(state, actions) }
                }
                MobileSettingCategory.Playback -> {
                    item { PlaybackDetail(state, actions, onClearPlaybackStats) }
                }
                MobileSettingCategory.Sources -> {
                    item { SourcesDetail(state, actions) }
                }
                MobileSettingCategory.Data -> {
                    item { DataDetail(state, actions, onClearHistory) }
                }
                MobileSettingCategory.About -> {
                    item { AboutDetail(state, actions) }
                }
            }
        }
    }
}

@Composable
private fun GeneralDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val detectedMode = DeviceMode.detectFromSystem(LocalContext.current)
    SettingsGroup {
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "设备模式",
            subtitle = state.appSettings.deviceMode.summaryLabel(detectedMode),
            trailing = {
                ChoiceChips(
                    options = DeviceMode.entries,
                    selected = state.appSettings.deviceMode,
                    label = { it.displayName },
                    onSelect = actions::updateDeviceMode,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Build,
            title = "界面主题",
            subtitle = state.appSettings.theme.displayName,
            trailing = {
                ChoiceChips(
                    options = TvTheme.entries,
                    selected = state.appSettings.theme,
                    label = { it.displayName },
                    onSelect = actions::updateTheme,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Build,
            title = "字体大小",
            subtitle = state.appSettings.fontScale.displayName,
            trailing = {
                ChoiceChips(
                    options = TvFontScale.entries,
                    selected = state.appSettings.fontScale,
                    label = { it.displayName },
                    onSelect = actions::updateFontScale,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
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
        DividerInGroup()
        SettingsRow(
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
private fun PlaybackDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearPlaybackStats: () -> Unit) {
    SettingsGroup {
        SettingsRow(
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
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "视频缩放",
            subtitle = state.appSettings.videoScaleMode.displayName,
            trailing = {
                ChoiceChips(
                    options = VideoScaleMode.entries,
                    selected = state.appSettings.videoScaleMode,
                    label = { it.displayName },
                    onSelect = actions::updateVideoScaleMode,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "解码策略",
            subtitle = state.appSettings.decoderStrategy.description,
            trailing = {
                ChoiceChips(
                    options = DecoderStrategy.entries,
                    selected = state.appSettings.decoderStrategy,
                    label = { it.displayName },
                    onSelect = actions::updateDecoderStrategy,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页卡片数量",
            subtitle = if (state.appSettings.videoGridColumns > 0) "每行 ${state.appSettings.videoGridColumns} 个" else "自动（按屏幕宽度）",
            trailing = {
                ChoiceChips(
                    options = listOf(0, 3, 4, 5, 6),
                    selected = state.appSettings.videoGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updateVideoGridColumns,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页热播数量",
            subtitle = quantitySummary(
                state.appSettings.homeHotContentCount,
                QuantitySettings.HomeHotContentCount,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.HomeHotContentCount.choices,
                    selected = state.appSettings.homeHotContentCount,
                    label = {
                        if (it == QuantitySettings.HomeHotContentCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateHomeHotContentCount,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "直播卡片数量",
            subtitle = if (state.appSettings.platformLiveGridColumns > 0) "每行 ${state.appSettings.platformLiveGridColumns} 个" else "自动（按设备类型）",
            trailing = {
                ChoiceChips(
                    options = listOf(0, 4, 5, 6, 7, 8),
                    selected = state.appSettings.platformLiveGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updatePlatformLiveGridColumns,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "继续观看数量",
            subtitle = quantitySummary(
                state.appSettings.continueWatchingCount,
                QuantitySettings.ContinueWatchingCount,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.ContinueWatchingCount.choices,
                    selected = state.appSettings.continueWatchingCount,
                    label = {
                        if (it == QuantitySettings.ContinueWatchingCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateContinueWatchingCount,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "EPG 节目条数",
            subtitle = quantitySummary(
                state.appSettings.epgCardRows,
                QuantitySettings.EpgCardRows,
                unit = " 条",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.EpgCardRows.choices,
                    selected = state.appSettings.epgCardRows,
                    label = { if (it == QuantitySettings.EpgCardRows.default) "默认" else "$it" },
                    onSelect = actions::updateEpgCardRows,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台候选行数",
            subtitle = quantitySummary(
                state.appSettings.liveNumberMatchRows,
                QuantitySettings.LiveNumberMatchRows,
                unit = " 行",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.LiveNumberMatchRows.choices,
                    selected = state.appSettings.liveNumberMatchRows,
                    label = { if (it == QuantitySettings.LiveNumberMatchRows.default) "默认" else "$it" },
                    onSelect = actions::updateLiveNumberMatchRows,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台匹配上限",
            subtitle = quantitySummary(
                state.appSettings.liveNumberSearchLimit,
                QuantitySettings.LiveNumberSearchLimit,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.LiveNumberSearchLimit.choices,
                    selected = state.appSettings.liveNumberSearchLimit,
                    label = {
                        if (it == QuantitySettings.LiveNumberSearchLimit.default) "默认" else "$it"
                    },
                    onSelect = actions::updateLiveNumberSearchLimit,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "相关频道数量",
            subtitle = quantitySummary(
                state.appSettings.relatedChannelCount,
                QuantitySettings.RelatedChannelCount,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.RelatedChannelCount.choices,
                    selected = state.appSettings.relatedChannelCount,
                    label = { if (it == QuantitySettings.RelatedChannelCount.default) "默认" else "$it" },
                    onSelect = actions::updateRelatedChannelCount,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "剧集排序",
            subtitle = if (state.appSettings.episodeSortReverse) "倒序（…,3,2,1）" else "正序（1,2,3,…）",
            trailing = {
                TextButton(onClick = actions::toggleEpisodeSortReverse) {
                    Text(
                        if (state.appSettings.episodeSortReverse) "切正序" else "切倒序",
                        color = TvColors.Accent,
                    )
                }
            },
        )
        DividerInGroup()
        SkipSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片头跳过",
            subtitle = if (state.appSettings.introSkipSeconds > 0) "自动跳过前 ${state.appSettings.introSkipSeconds}s" else "不跳过",
            seconds = state.appSettings.introSkipSeconds,
            onSecondsChange = actions::updateIntroSkipSeconds,
        )
        DividerInGroup()
        SkipSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片尾跳过",
            subtitle = if (state.appSettings.outroSkipSeconds > 0) "提前 ${state.appSettings.outroSkipSeconds}s 进入下一集" else "不跳过",
            seconds = state.appSettings.outroSkipSeconds,
            onSecondsChange = actions::updateOutroSkipSeconds,
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "线路统计",
            subtitle = state.playbackHealth.qualitySummaryText(),
            trailing = if (state.playbackHealth.entryCount > 0) {
                { TextButton(onClick = onClearPlaybackStats) { Text("清空", color = TvColors.Danger) } }
            } else {
                {}
            },
        )
    }
}

@Composable
private fun SourcesDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    SettingsGroup {
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "视频源",
            subtitle = state.selectedApiLine?.name ?: "未配置",
            trailing = { TextButton(onClick = actions::openVideoSourceManager) { Text("管理", color = TvColors.Accent) } },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "IPTV 直播源",
            subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
            trailing = { TextButton(onClick = actions::openLiveSourceManager) { Text("管理", color = TvColors.Accent) } },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TextButton(onClick = actions::openPlatformLive) { Text("管理", color = TvColors.Accent) } },
        )
    }
}

@Composable
private fun DataDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearHistory: () -> Unit) {
    SettingsGroup {
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "观看历史",
            subtitle = "管理本地播放记录",
            trailing = {
                if (state.historyItems.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) { Text("清空", color = TvColors.Danger) }
                }
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "历史保留条数",
            subtitle = quantitySummary(
                state.appSettings.historyMaxItems,
                QuantitySettings.HistoryMaxItems,
                unit = " 条",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.HistoryMaxItems.choices,
                    selected = state.appSettings.historyMaxItems,
                    label = { if (it == QuantitySettings.HistoryMaxItems.default) "默认" else "$it" },
                    onSelect = actions::updateHistoryMaxItems,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "搜索热词数量",
            subtitle = quantitySummary(
                state.appSettings.hotWordCount,
                QuantitySettings.HotWordCount,
                unit = " 条",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.HotWordCount.choices,
                    selected = state.appSettings.hotWordCount,
                    label = { if (it == QuantitySettings.HotWordCount.default) "默认" else "$it" },
                    onSelect = actions::updateHotWordCount,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "拼音候选数量",
            subtitle = quantitySummary(
                state.appSettings.searchSuggestionCount,
                QuantitySettings.SearchSuggestionCount,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.SearchSuggestionCount.choices,
                    selected = state.appSettings.searchSuggestionCount,
                    label = {
                        if (it == QuantitySettings.SearchSuggestionCount.default) "默认" else "$it"
                    },
                    onSelect = actions::updateSearchSuggestionCount,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "豆瓣热播每页条数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotPageSize,
                QuantitySettings.DoubanHotPageSize,
                unit = " 条",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.DoubanHotPageSize.choices,
                    selected = state.appSettings.doubanHotPageSize,
                    label = { if (it == QuantitySettings.DoubanHotPageSize.default) "默认" else "$it" },
                    onSelect = actions::updateDoubanHotPageSize,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "热播回退源数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotFallbackLines,
                QuantitySettings.DoubanHotFallbackLines,
                unit = " 路",
            ),
            trailing = {
                ChoiceChips(
                    options = QuantitySettings.DoubanHotFallbackLines.choices,
                    selected = state.appSettings.doubanHotFallbackLines,
                    label = {
                        if (it == QuantitySettings.DoubanHotFallbackLines.default) "默认" else "$it"
                    },
                    onSelect = actions::updateDoubanHotFallbackLines,
                )
            },
        )
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "多源搜索并发数",
            subtitle = quantitySummary(
                state.appSettings.maxParallelSourceRequests,
                QuantitySettings.MaxParallelSourceRequests,
                unit = " 个",
            ),
            trailing = {
                ChoiceChips(
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
private fun AboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    SettingsGroup {
        InfoRow(icon = Icons.Default.Info, title = "应用名称", subtitle = "TVBox")
        DividerInGroup()
        InfoRow(icon = Icons.Default.Info, title = "当前版本", subtitle = "v${BuildConfig.VERSION_NAME}")
        DividerInGroup()
        InfoRow(icon = Icons.Default.Info, title = "开源协议", subtitle = "GPLv3")
        DividerInGroup()
        SettingsRow(
            icon = Icons.Default.Info,
            title = "检查更新",
            subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
            trailing = {
                if (!state.updateChecking) {
                    TextButton(onClick = { actions.checkForAppUpdate(showError = true) }) {
                        Text("检查", color = TvColors.Accent)
                    }
                } else {
                    Text("检查中…", color = TvColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
        state.updateError?.let { updateError ->
            Text(
                text = updateError,
                color = TvColors.Danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 64.dp, top = 4.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

// ─────────────────────────── 通用组件 ───────────────────────────

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TvColors.Surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun DividerInGroup() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = TvColors.Border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 窄屏时给 trailing 最多占一半宽度，剩余空间留给标题，选项 chips 在自身区域内换行。
        val trailingMaxWidth = if (maxWidth < 480.dp) maxWidth * 0.52f else 320.dp
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
                Text(
                    text = title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.widthIn(max = trailingMaxWidth)) {
                trailing()
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    SettingsRow(icon = icon, title = title, subtitle = subtitle, trailing = trailing)
}

@Composable
private fun SkipSecondsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${seconds}s",
                color = TvColors.Accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onSecondsChange(it.toInt()) },
            valueRange = 0f..300f,
            steps = 59,
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = TvColors.Accent,
                thumbColor = TvColors.Accent,
                inactiveTrackColor = TvColors.SurfaceRaised,
            ),
        )
    }
}

@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    // 窄屏或大字号下自动换行，避免选项被挤出屏幕右侧。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    // 44dp 低于建议的最小触摸目标，统一抬到 48dp。
                    .heightIn(min = 48.dp)
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) TvColors.Accent.copy(alpha = 0.18f)
                        else TvColors.SurfaceRaised.copy(alpha = 0.6f)
                    )
                    .clickable(role = Role.Button) { onSelect(option) }
                    .semantics { stateDescription = if (isSelected) "已选中" else "未选中" }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
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

// ─────────────────────────── 分类定义 ───────────────────────────

private enum class MobileSettingCategory(
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
