package com.tvbox.app.ui.settings

import androidx.activity.compose.BackHandler
import com.tvbox.app.data.LiveTuningDebugPrefs
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.tvbox.app.data.checkAllLiveSources
import com.tvbox.app.domain.DangerousAction
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.dangerousActionConfirmation
import kotlinx.coroutines.launch
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.StartupPhase
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.UpdateChannel
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.RemoteManageDialog
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.components.ChoiceChips
import com.tvbox.app.ui.components.DangerConfirmDialog
import com.tvbox.app.ui.components.HeroStat
import com.tvbox.app.ui.components.QuantityChoiceChips
import com.tvbox.app.ui.components.SettingsGroupDivider
import com.tvbox.app.ui.components.TextValueEditor
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens

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
        DangerConfirmDialog(
            title = "清空历史记录",
            text = dangerousActionConfirmation(DangerousAction.ClearHistory),
            onConfirm = { confirmClearHistory = false; actions.clearHistory() },
            onDismiss = { confirmClearHistory = false },
        )
    }
    if (confirmClearPlaybackStats) {
        DangerConfirmDialog(
            title = "清空线路统计",
            text = dangerousActionConfirmation(DangerousAction.ClearPlaybackStats),
            onConfirm = { confirmClearPlaybackStats = false; actions.clearPlaybackStats() },
            onDismiss = { confirmClearPlaybackStats = false },
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
                    actions = actions,
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
    actions: TvBoxViewModel,
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
                    text = "聚合视频 v${BuildConfig.VERSION_NAME} · GPLv3 · 仅供技术研究使用",
                    // 原先是 labelSmall + TextTertiary，手机上又小又暗，几乎读不出来。
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
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
private fun SettingsCategoryGrid(onCategoryClick: (MobileSettingCategory) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 手机竖屏按宽度自适应列数：最小 150dp/格，360dp~411dp 竖屏可放 2~3 列，宽屏自然增加。
        // TV 端走 SettingsTvSplitScreen，不经过此函数。
        // 修复（遮挡）：原先写死网格高度 + userScrollEnabled=false，当分类数超过估算行数时，
        // 多余卡片被直接裁切（看不到、也划不动）。改为内容自适应的 FlowRow，按列数自动换行，
        // 所有分类卡片完整显示，不再依赖手算高度。
        val gridSpacing = 12.dp
        val minCardWidth = 150.dp
        val capacity = ((maxWidth + gridSpacing) / (minCardWidth + gridSpacing))
            .toInt()
            .coerceAtLeast(1)
        val columns = capacity.coerceIn(2, 4)
        val cardWidth = (maxWidth - gridSpacing * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
            verticalArrangement = Arrangement.spacedBy(gridSpacing),
            maxItemsInEachRow = columns,
        ) {
            MobileSettingCategory.entries.forEach { category ->
                CategoryCard(
                    category = category,
                    onClick = { onCategoryClick(category) },
                    modifier = Modifier
                        .width(cardWidth)
                        .heightIn(min = 150.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(category: MobileSettingCategory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
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
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
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
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = DeviceMode.entries,
                    selected = state.appSettings.deviceMode,
                    label = { it.displayName },
                    onSelect = actions::updateDeviceMode,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Build,
            title = "界面主题",
            subtitle = state.appSettings.theme.displayName,
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = TvTheme.entries,
                    selected = state.appSettings.theme,
                    label = { it.displayName },
                    onSelect = actions::updateTheme,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Build,
            title = "字体大小",
            subtitle = state.appSettings.fontScale.displayName,
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = TvFontScale.entries,
                    selected = state.appSettings.fontScale,
                    label = { it.displayName },
                    onSelect = actions::updateFontScale,
                )
            },
        )
        SettingsGroupDivider()
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
        SettingsGroupDivider()
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
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "播放器详细日志",
            subtitle = if (state.appSettings.playerLogEnabled) "已开启" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.playerLogEnabled,
                    onCheckedChange = actions::updatePlayerLogEnabled,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Info,
            title = "更新渠道",
            subtitle = state.appSettings.updateChannel.displayName,
            trailing = {
                ChoiceChips(
                    options = UpdateChannel.entries,
                    selected = state.appSettings.updateChannel,
                    label = { it.displayName },
                    onSelect = actions::updateUpdateChannel,
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
        SettingsGroupDivider()
        // 「一起看」重连调优：斗鱼/虎牙各三档策略即时写回 Go，现场定位"总是重连"的最佳组合。
        val context = LocalContext.current
        listOf(LiveTuningDebugPrefs.Site.DOUYU, LiveTuningDebugPrefs.Site.HUYA).forEach { site ->
            val siteName = if (site == LiveTuningDebugPrefs.Site.DOUYU) "斗鱼" else "虎牙"
            var tuningIndex by remember(site) {
                mutableIntStateOf(LiveTuningDebugPrefs.loadProfileIndex(context, site))
            }
            val profiles = LiveTuningDebugPrefs.profiles(site)
            SettingsRow(
                icon = Icons.Default.Build,
                title = "${siteName}重连调优",
                subtitle = "档位：${profiles[tuningIndex].label}（改动仅影响${siteName}一起看）",
                stackedTrailing = true,
                trailing = {
                    ChoiceChips(
                        options = profiles.indices.toList(),
                        selected = tuningIndex,
                        label = { profiles[it].label },
                        onSelect = { idx ->
                            tuningIndex = idx
                            LiveTuningDebugPrefs.saveProfileIndex(context, site, idx)
                            LiveTuningDebugPrefs.applyToBridge(context, site)
                        },
                    )
                },
            )
            SettingsGroupDivider()
        }
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "视频缩放",
            subtitle = state.appSettings.videoScaleMode.displayName,
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = VideoScaleMode.entries,
                    selected = state.appSettings.videoScaleMode,
                    label = { it.displayName },
                    onSelect = actions::updateVideoScaleMode,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "解码策略",
            subtitle = state.appSettings.decoderStrategy.description,
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = DecoderStrategy.entries,
                    selected = state.appSettings.decoderStrategy,
                    label = { it.displayName },
                    onSelect = actions::updateDecoderStrategy,
                )
            },
        )
        SettingsGroupDivider()
        // #54/#266 按设备型号前缀强制硬解；空表示不启用，多个前缀用逗号分隔。
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "硬解设备白名单",
            subtitle = "命中 Build.MODEL 前缀的设备强制硬解，留空则不启用",
            stackedTrailing = true,
            trailing = {
                TextValueEditor(
                    value = state.appSettings.decoderDeviceWhitelist,
                    onSave = actions::updateDecoderDeviceWhitelist,
                )
            },
        )
        SettingsGroupDivider()
        // #277 切台频道名横幅时长：自动档 TV 4s / 手机 2s。
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "切台横幅时长",
            subtitle = if (state.appSettings.liveInfoBannerDurationMs == LiveInfoBannerDuration.Auto) {
                "自动（电视 4s / 手机 2s）"
            } else {
                "固定 ${state.appSettings.liveInfoBannerDurationMs}ms"
            },
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = LiveInfoBannerDuration.choices,
                    selected = state.appSettings.liveInfoBannerDurationMs,
                    label = { if (it == LiveInfoBannerDuration.Auto) "自动" else "${it / 1000}s" },
                    onSelect = actions::updateLiveInfoBannerDurationMs,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页每行卡片数",
            subtitle = if (state.appSettings.videoGridColumns > 0) "每行 ${state.appSettings.videoGridColumns} 个" else "自动（按屏幕宽度，竖屏 3~4 列）",
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = listOf(0, 1, 2, 3, 4),
                    selected = state.appSettings.videoGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updateVideoGridColumns,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "首页热播数量",
            subtitle = quantitySummary(
                state.appSettings.homeHotContentCount,
                QuantitySettings.HomeHotContentCount,
                unit = " 个",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.HomeHotContentCount,
                    selected = state.appSettings.homeHotContentCount,
                    onSelect = actions::updateHomeHotContentCount,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "直播卡片数量",
            subtitle = if (state.appSettings.platformLiveGridColumns > 0) "每行 ${state.appSettings.platformLiveGridColumns} 个" else "自动（按设备类型）",
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = listOf(0, 4, 5, 6, 7, 8),
                    selected = state.appSettings.platformLiveGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updatePlatformLiveGridColumns,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "继续观看数量",
            subtitle = quantitySummary(
                state.appSettings.continueWatchingCount,
                QuantitySettings.ContinueWatchingCount,
                unit = " 个",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.ContinueWatchingCount,
                    selected = state.appSettings.continueWatchingCount,
                    onSelect = actions::updateContinueWatchingCount,
                )
            },
        )
        // 以下四项（EPG 节目条数 / 数字选台候选行数 / 数字选台匹配上限 / 相关频道数量）
        // 仅在电视端 LiveScreen 生效：数字选台是遥控器功能，手机直播页无此交互；
        // 手机端 EPG 全量渲染、相关频道按同分组展示，均不读取这些配置。放在手机设置里
        // 改了也没有任何效果，故不在手机端展示。
        SettingsGroupDivider()
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
        SettingsGroupDivider()
        SkipSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片头跳过",
            subtitle = if (state.appSettings.introSkipSeconds > 0) "自动跳过前 ${state.appSettings.introSkipSeconds}s" else "不跳过",
            seconds = state.appSettings.introSkipSeconds,
            onSecondsChange = actions::updateIntroSkipSeconds,
        )
        SettingsGroupDivider()
        SkipSecondsRow(
            icon = Icons.Default.PlayArrow,
            title = "片尾跳过",
            subtitle = if (state.appSettings.outroSkipSeconds > 0) "提前 ${state.appSettings.outroSkipSeconds}s 进入下一集" else "不跳过",
            seconds = state.appSettings.outroSkipSeconds,
            onSecondsChange = actions::updateOutroSkipSeconds,
        )
        SettingsGroupDivider()
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
    // #282：一键检测所有直播源（复用 dry-run），与 TV 端共用同一套校验逻辑。
    val scope = rememberCoroutineScope()
    var healthChecking by remember { mutableStateOf(false) }
    var healthReport by remember { mutableStateOf<String?>(null) }
    if (healthReport != null) {
        AlertDialog(
            onDismissRequest = { healthReport = null },
            title = { Text("直播源健康检查") },
            text = { Text(healthReport.orEmpty(), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { healthReport = null }) { Text("知道了") }
            },
        )
    }
    SettingsGroup {
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "视频源",
            subtitle = state.selectedApiLine?.name ?: "未配置",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScanConfigBadge(scanState = ScanConfigState.of(state.appSettings.customVideoApiLines.isNotEmpty()))
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = actions::openVideoSourceManager) { Text("管理", color = TvColors.Accent) }
                }
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "IPTV 直播源",
            subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScanConfigBadge(scanState = ScanConfigState.of(state.appSettings.liveSourceUrls.any { it.isNotBlank() }))
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = actions::openLiveSourceManager) { Text("管理", color = TvColors.Accent) }
                }
            },
        )
        SettingsGroupDivider()
        // #282：一键检测所有已配置直播源，逐个连接并做结构校验。
        SettingsRow(
            icon = Icons.Default.Info,
            title = "直播源健康检查",
            subtitle = "共 ${state.appSettings.liveSourceUrls.count { it.isNotBlank() }} 条，逐条连接并校验",
            trailing = {
                TextButton(
                    onClick = {
                        healthChecking = true
                        scope.launch {
                            healthReport = checkAllLiveSources(state.appSettings.liveSourceUrls)
                            healthChecking = false
                        }
                    },
                    enabled = !healthChecking,
                ) {
                    Text(
                        if (healthChecking) "检测中…" else "一键检测",
                        color = TvColors.Accent,
                    )
                }
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TextButton(onClick = actions::openPlatformLive) { Text("管理", color = TvColors.Accent) } },
        )
        SettingsGroupDivider()
        // 视频源健康管理：后台周期静默测速 + 按延迟自动排序；副标题显示最近一轮体检结果。
        SettingsRow(
            icon = Icons.Default.Info,
            title = "视频源健康管理",
            subtitle = state.source.lastHealthCheckSummary
                ?: if (state.appSettings.sourceHealthCheckEnabled) "已开启，每 30 分钟后台测速并自动排序" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.sourceHealthCheckEnabled,
                    onCheckedChange = actions::updateSourceHealthCheckEnabled,
                )
            },
        )
        SettingsGroupDivider()
        // 自定义 User-Agent：视频接口、订阅拉取与直播播放共用；订阅地址也可追加 |User-Agent= 参数按源覆盖。
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "自定义 User-Agent",
            subtitle = state.appSettings.customUserAgent.ifBlank {
                "默认 ${com.tvbox.app.domain.UserAgentSetting.Default}；订阅地址可追加 |User-Agent=参数 按源覆盖"
            },
            stackedTrailing = true,
            trailing = {
                TextValueEditor(
                    value = state.appSettings.customUserAgent,
                    onSave = actions::updateCustomUserAgent,
                )
            },
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
        SettingsGroupDivider()
        // #318 历史隐私模式：只阻止新记录写入，不清空已有历史。
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "历史隐私模式",
            subtitle = if (state.appSettings.historyPrivacyMode) "已开启，不再记录新的观看历史" else "关闭，正常记录观看历史",
            trailing = {
                Switch(
                    checked = state.appSettings.historyPrivacyMode,
                    onCheckedChange = actions::updateHistoryPrivacyMode,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "历史保留条数",
            subtitle = quantitySummary(
                state.appSettings.historyMaxItems,
                QuantitySettings.HistoryMaxItems,
                unit = " 条",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.HistoryMaxItems,
                    selected = state.appSettings.historyMaxItems,
                    onSelect = actions::updateHistoryMaxItems,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "搜索热词数量",
            subtitle = quantitySummary(
                state.appSettings.hotWordCount,
                QuantitySettings.HotWordCount,
                unit = " 条",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.HotWordCount,
                    selected = state.appSettings.hotWordCount,
                    onSelect = actions::updateHotWordCount,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "拼音候选数量",
            subtitle = quantitySummary(
                state.appSettings.searchSuggestionCount,
                QuantitySettings.SearchSuggestionCount,
                unit = " 个",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.SearchSuggestionCount,
                    selected = state.appSettings.searchSuggestionCount,
                    onSelect = actions::updateSearchSuggestionCount,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "豆瓣热播每页条数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotPageSize,
                QuantitySettings.DoubanHotPageSize,
                unit = " 条",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.DoubanHotPageSize,
                    selected = state.appSettings.doubanHotPageSize,
                    onSelect = actions::updateDoubanHotPageSize,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "热播回退源数",
            subtitle = quantitySummary(
                state.appSettings.doubanHotFallbackLines,
                QuantitySettings.DoubanHotFallbackLines,
                unit = " 路",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.DoubanHotFallbackLines,
                    selected = state.appSettings.doubanHotFallbackLines,
                    onSelect = actions::updateDoubanHotFallbackLines,
                )
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "多源搜索并发数",
            subtitle = quantitySummary(
                state.appSettings.maxParallelSourceRequests,
                QuantitySettings.MaxParallelSourceRequests,
                unit = " 个",
            ),
            stackedTrailing = true,
            trailing = {
                QuantityChoiceChips(
                    setting = QuantitySettings.MaxParallelSourceRequests,
                    selected = state.appSettings.maxParallelSourceRequests,
                    onSelect = actions::updateMaxParallelSourceRequests,
                )
            },
        )
        SettingsGroupDivider()
        // #94 单源超时：低于预算则放弃慢源，避免个别源拖住整页搜索/详情。
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "单源超时",
            subtitle = if (state.appSettings.perSourceTimeoutMs == PerSourceTimeout.DefaultMs) {
                "默认 ${PerSourceTimeout.DefaultMs / 1000}s"
            } else {
                "当前 ${state.appSettings.perSourceTimeoutMs / 1000}s，默认 ${PerSourceTimeout.DefaultMs / 1000}s"
            },
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = PerSourceTimeout.choices,
                    selected = state.appSettings.perSourceTimeoutMs,
                    label = { if (it == PerSourceTimeout.DefaultMs) "默认" else "${it / 1000}s" },
                    onSelect = actions::updatePerSourceTimeoutMs,
                )
            },
        )
    }
}

@Composable
private fun AboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val context = LocalContext.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    SettingsGroup {
        InfoRow(icon = Icons.Default.Info, title = "应用名称", subtitle = "聚合视频")
        SettingsGroupDivider()
        // #217：当前版本补充版本号（versionCode），便于反馈问题时精确定位构建。
        InfoRow(icon = Icons.Default.Info, title = "当前版本", subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SettingsGroupDivider()
        InfoRow(icon = Icons.Default.Info, title = "开源协议", subtitle = "GPLv3")
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Info,
            title = "隐私政策",
            subtitle = "本地数据处理与网络访问说明",
            trailing = {
                TextButton(onClick = { showPrivacyPolicy = true }) {
                    Text("查看", color = TvColors.Accent)
                }
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Build,
            title = "导出崩溃日志",
            subtitle = "合并本地崩溃日志并通过系统分享",
            trailing = {
                TextButton(onClick = {
                    val result = exportCrashLogs(context)
                    if (result != null) {
                        android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("导出", color = TvColors.Accent) }
            },
        )
        SettingsGroupDivider()
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
    OtaCdnStatusSection(actions = actions)
    StartupTimelineSection(actions = actions)

    if (showPrivacyPolicy) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicy = false },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicy = false }) { Text("知道了") }
            },
            title = { Text("隐私政策") },
            text = { Text(PRIVACY_POLICY_TEXT) },
        )
    }
    }
}

// 第 314–315 轮 · OTA 下载线路状态与启动耗时接入「关于」分组
@Composable
private fun OtaCdnStatusSection(actions: TvBoxViewModel) {
    val rows = remember(actions) { actions.cdnStatusRows() }
    SettingsGroup {
        SettingsRow(
            icon = Icons.Default.Build,
            title = "下载线路状态",
            subtitle = if (rows.isEmpty()) "暂无数据，检查更新后生成" else "${rows.size} 条候选线路",
            trailing = {},
        )
        if (rows.isNotEmpty()) {
            SettingsGroupDivider()
            rows.forEachIndexed { index, row ->
                SettingsRow(
                    icon = Icons.Default.Build,
                    title = row.label,
                    subtitle = row.status,
                    trailing = {
                        if (row.selected) {
                            Text(
                                "当前",
                                color = TvColors.Accent,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                )
                if (index < rows.lastIndex) SettingsGroupDivider()
            }
        }
    }
}

@Composable
private fun StartupTimelineSection(actions: TvBoxViewModel) {
    val total = actions.startupTotalDurationMs
    val phases = actions.startupPhaseDurationsMs
    SettingsGroup {
        SettingsRow(
            icon = Icons.Default.Info,
            title = "启动总耗时",
            subtitle = total?.let { "到可交互 ${it}ms" } ?: "尚未采集",
            trailing = {},
        )
        if (phases.isNotEmpty()) {
            SettingsGroupDivider()
            val order = listOf(
                StartupPhase.ProcessStart,
                StartupPhase.ApplicationInit,
                StartupPhase.FirstFrame,
                StartupPhase.Interactive,
                StartupPhase.Idle,
            )
            order.forEachIndexed { index, phase ->
                phases[phase]?.let { ms ->
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = startupPhaseLabel(phase),
                        subtitle = "${ms}ms",
                        trailing = {},
                    )
                    if (index < order.size - 1) SettingsGroupDivider()
                }
            }
        }
    }
}

private fun startupPhaseLabel(phase: StartupPhase): String = when (phase) {
    StartupPhase.ProcessStart -> "进程启动"
    StartupPhase.ApplicationInit -> "Application 初始化"
    StartupPhase.FirstFrame -> "首帧"
    StartupPhase.Interactive -> "可交互"
    StartupPhase.Idle -> "空闲"
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
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    stackedTrailing: Boolean = false,
) {
    // 修复（手机端文字被遮挡 / 无法显示）：原先标题与右侧控件横向并排，trailing 被限制在
    // 52% 宽度内，标题又强制单行省略；超大字号下文字被压成窄条，选项 chips 在窄条里
    // 反复换行，出现互相覆盖、只剩半个字、说明文字被截掉等问题。
    // 现在标题与说明始终占满整行并自然换行；宽控件（选项组）独占下方一整行，
    // 开关 / 按钮等窄控件仍留在右侧，但不再挤压文字区。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TvDimens.SettingsIconBoxSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.AccentSoft.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = TvColors.Accent, modifier = Modifier.size(TvDimens.SettingsRowIconSize))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!stackedTrailing) {
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.widthIn(max = 180.dp)) {
                    trailing()
                }
            }
        }
        if (stackedTrailing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 50.dp),
            ) {
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
                    .size(TvDimens.SettingsIconBoxSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.AccentSoft.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = TvColors.Accent, modifier = Modifier.size(TvDimens.SettingsRowIconSize))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
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
