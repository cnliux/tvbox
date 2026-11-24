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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.DangerousAction
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.dangerousActionConfirmation
import kotlinx.coroutines.launch
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.HistoryLayout
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.PLAYER_SPEED_STEPS
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
 * 手机端设置页 —— 扁平分组列表结构（对照 mobile-settings-preview.html 参考稿）：
 * 顶部栏含「N 项已偏离默认」；分类筛选 chips；所有分组直接铺开，
 * 每组带图标标题、偏离计数与「重置本组」。不再进入二级详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMobileScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }
    var confirmResetCategory by remember { mutableStateOf<MobileSettingCategory?>(null) }
    var filterCategory by remember { mutableStateOf<MobileSettingCategory?>(null) }

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
    confirmResetCategory?.let { category ->
        DangerConfirmDialog(
            title = "重置「${category.title}」",
            text = "将该分组内全部设置恢复为默认值，确认继续？",
            onConfirm = {
                actions.resetCategorySettings(category.name)
                confirmResetCategory = null
            },
            onDismiss = { confirmResetCategory = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
    ) {
        // 顶部栏：大标题 + 偏离默认计数
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
            actions = {
                val deviation = settingsDeviationCount(state.appSettings)
                Text(
                    text = if (deviation > 0) "$deviation 项已偏离默认" else "已全部默认",
                    color = if (deviation > 0) TvColors.Accent else TvColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 16.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = TvColors.TextPrimary,
            ),
            windowInsets = WindowInsets.statusBars,
        )

        // 分类筛选 chips：全部 + 各分组
        SettingsFilterChips(
            selected = filterCategory,
            onSelect = { filterCategory = it },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val visibleCategories = MobileSettingCategory.entries.filter {
                filterCategory == null || it == filterCategory
            }
            visibleCategories.forEach { category ->
                item(key = category.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsSectionHeader(
                            category = category,
                            deviation = categoryDeviationCount(category, state.appSettings),
                            onReset = { confirmResetCategory = category },
                        )
                        when (category) {
                            MobileSettingCategory.Playback -> PlaybackDetail(state, actions) {
                                confirmClearPlaybackStats = true
                            }
                            MobileSettingCategory.NetworkSources -> NetworkSourcesDetail(state, actions)
                            MobileSettingCategory.Interface -> InterfaceDetail(state, actions)
                            MobileSettingCategory.TvRemote -> TvRemoteDetail(state, actions)
                            MobileSettingCategory.SystemData -> SystemDataDetail(state, actions) {
                                confirmClearHistory = true
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    text = "聚合视频 v${BuildConfig.VERSION_NAME} · GPLv3 · 仅供技术研究使用",
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────── 扁平分组列表组件 ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsFilterChips(
    selected: MobileSettingCategory?,
    onSelect: (MobileSettingCategory?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = TvColors.Surface,
                    labelColor = TvColors.TextSecondary,
                    selectedContainerColor = TvColors.Accent.copy(alpha = 0.18f),
                    selectedLabelColor = TvColors.Accent,
                ),
            )
        }
        items(MobileSettingCategory.entries, key = { it.name }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(category.title) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = TvColors.Surface,
                    labelColor = TvColors.TextSecondary,
                    selectedContainerColor = TvColors.Accent.copy(alpha = 0.18f),
                    selectedLabelColor = TvColors.Accent,
                ),
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    category: MobileSettingCategory,
    deviation: Int,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(category.gradientStart.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = category.gradientStart,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = category.title,
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (deviation > 0) {
            Text(
                text = "$deviation 项偏离",
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (category != MobileSettingCategory.TvRemote) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onReset) {
                Text("重置本组", color = TvColors.Accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ─────────────────────────── 偏离计数 ───────────────────────────

private fun settingsDeviationCount(settings: AppSettings): Int =
    MobileSettingCategory.entries.sumOf { categoryDeviationCount(it, settings) }

private fun categoryDeviationCount(category: MobileSettingCategory, settings: AppSettings): Int {
    val d = AppSettings()
    return when (category) {
        MobileSettingCategory.Playback -> listOf(
            settings.playerSpeed != d.playerSpeed,
            settings.playerBrightness != d.playerBrightness,
            settings.autoNextEpisodeEnabled != d.autoNextEpisodeEnabled,
            settings.playerGesturesEnabled != d.playerGesturesEnabled,
            settings.decoderStrategy != d.decoderStrategy,
            settings.fastSeekStepSeconds != d.fastSeekStepSeconds,
        ).count { it }
        MobileSettingCategory.NetworkSources -> listOf(
            settings.sourceCircuitBreakerEnabled != d.sourceCircuitBreakerEnabled,
            settings.customUserAgent != d.customUserAgent,
            settings.perSourceTimeoutMs != d.perSourceTimeoutMs,
            settings.maxParallelSourceRequests != d.maxParallelSourceRequests,
        ).count { it }
        MobileSettingCategory.Interface -> listOf(
            settings.deviceMode != d.deviceMode,
            settings.theme != d.theme,
            settings.historyLayout != d.historyLayout,
            settings.historyCoverBadgeEnabled != d.historyCoverBadgeEnabled,
        ).count { it }
        MobileSettingCategory.TvRemote -> 0
        MobileSettingCategory.SystemData -> listOf(
            settings.updateChannel != d.updateChannel,
            settings.historyMaxItems != d.historyMaxItems,
            settings.historyPrivacyMode != d.historyPrivacyMode,
            settings.crashLogLocalOnly != d.crashLogLocalOnly,
        ).count { it }
    }
}








@Composable
private fun InterfaceDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
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
        // 历史页布局：卡片 / 紧凑 / 网格（仅手机端历史页生效）。
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "历史页布局",
            subtitle = "历史记录封面的展示密度",
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = HistoryLayout.entries,
                    selected = state.appSettings.historyLayout,
                    label = { it.displayName },
                    onSelect = actions::updateHistoryLayout,
                )
            },
        )
        SettingsGroupDivider()
        // 封面更新角标：有更新剧集的条目在封面右上角显示角标。
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "封面更新角标",
            subtitle = if (state.appSettings.historyCoverBadgeEnabled) "已开启，封面显示更新标记" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.historyCoverBadgeEnabled,
                    onCheckedChange = actions::updateHistoryCoverBadgeEnabled,
                )
            },
        )
    }
}

@Composable
private fun PlaybackDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearPlaybackStats: () -> Unit) {
    SettingsGroup {
        // 默认倍速（持久化偏好，与播放器内倍速联动）
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "默认倍速",
            subtitle = "播放默认速度，播放器内可临时切换",
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = PLAYER_SPEED_STEPS,
                    selected = state.appSettings.playerSpeed,
                    label = { String.format(java.util.Locale.US, "%.2f×", it) },
                    onSelect = actions::updatePlaybackSpeed,
                )
            },
        )
        SettingsGroupDivider()
        // 画面亮度：0=跟随系统，1..100=覆盖百分比
        BrightnessRow(
            value = state.appSettings.playerBrightness,
            onValueChange = actions::updatePlayerBrightness,
        )
        SettingsGroupDivider()
        // 自动下一集：片尾倒计时自动续播
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "自动下一集",
            subtitle = if (state.appSettings.autoNextEpisodeEnabled) "片尾倒计时自动进入下一集" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.autoNextEpisodeEnabled,
                    onCheckedChange = actions::updateAutoNextEpisodeEnabled,
                )
            },
        )
        SettingsGroupDivider()
        // 手机播放手势：左亮度/右音量/横滑进度
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "手机播放手势",
            subtitle = if (state.appSettings.playerGesturesEnabled) "左亮度 / 右音量 / 横滑进度" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.playerGesturesEnabled,
                    onCheckedChange = actions::updatePlayerGesturesEnabled,
                )
            },
        )
        SettingsGroupDivider()
        // 快进步长：5/10/15/30 秒
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "快进步长",
            subtitle = "快进/快退步长 ${state.appSettings.fastSeekStepSeconds}s",
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = FastSeekSetting.choices,
                    selected = state.appSettings.fastSeekStepSeconds,
                    label = { "${it}s" },
                    onSelect = actions::updateFastSeekStepSeconds,
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
    }
}

@Composable
private fun NetworkSourcesDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
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
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TextButton(onClick = actions::openPlatformLive) { Text("管理", color = TvColors.Accent) } },
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
        SettingsGroupDivider()
        // 源探测超时：低于预算则放弃慢源，避免个别源拖住整页搜索/详情。
        SettingsRow(
            icon = Icons.Default.Info,
            title = "源探测超时",
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
        SettingsGroupDivider()
        // 多源搜索并发数
        SettingsRow(
            icon = Icons.Default.Info,
            title = "多源并发数",
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
        // 源失败熔断：连续 5 次超时自动休眠 10 分钟，避免反复打到坏源拖慢自动换线。
        SettingsRow(
            icon = Icons.Default.Info,
            title = "源失败熔断",
            subtitle = if (state.appSettings.sourceCircuitBreakerEnabled) {
                "连续 5 次超时自动休眠 10 分钟"
            } else {
                "已关闭：失败源不再自动休眠"
            },
            trailing = {
                Switch(
                    checked = state.appSettings.sourceCircuitBreakerEnabled,
                    onCheckedChange = actions::updateSourceCircuitBreakerEnabled,
                )
            },
        )
    }
}

@Composable
private fun TvRemoteDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showShortcutHelp by remember { mutableStateOf(false) }
    SettingsGroup {
        // 远程管理：通过浏览器 / 手机控制本机
        SettingsRow(
            icon = Icons.Default.Build,
            title = "远程管理",
            subtitle = "通过浏览器或手机遥控本机，需与电视在同一网络",
            trailing = {
                TextButton(onClick = actions::openRemoteManageDialog) {
                    Text("打开", color = TvColors.Accent)
                }
            },
        )
        SettingsGroupDivider()
        // 遥控器快捷键说明
        SettingsRow(
            icon = Icons.Default.Build,
            title = "遥控器快捷键",
            subtitle = "方向键 / 确认 / 返回 / 菜单 / 数字键 说明",
            trailing = {
                TextButton(onClick = { showShortcutHelp = true }) {
                    Text("查看", color = TvColors.Accent)
                }
            },
        )
    }
    if (showShortcutHelp) {
        val shortcutHelpText = """
            方向键（上/下/左/右）：移动焦点、浏览列表；播放时左右=快进/快退，上下=换台/换线路。
            确认键（OK）：确认选择；播放时暂停/继续。
            返回键：返回上一级 / 关闭当前浮层。
            菜单键：点播时切换倍速；电视直播时打开线路浮层；平台直播时强制重连。
            数字键：首页 1=历史 2=搜索 4=电视 5=直播 6=设置 7=豆瓣热播 8=源管理 9=刷新 0=远程管理；电视直播中直接输入频道号选台。
            播放/暂停键：播放或暂停。
            上一集/下一集键（或数字 1/3）：点播时切换剧集。
            信息键（INFO）：查看线路信息 / 节目单。
        """.trimIndent()
        AlertDialog(
            onDismissRequest = { showShortcutHelp = false },
            confirmButton = {
                TextButton(onClick = { showShortcutHelp = false }) { Text("知道了") }
            },
            title = { Text("遥控器快捷键") },
            text = { Text(shortcutHelpText) },
        )
    }
}

@Composable
private fun SystemDataDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearHistory: () -> Unit) {
    SettingsGroup {
        // OTA 通道：正式版 / 预览版
        SettingsRow(
            icon = Icons.Default.Info,
            title = "OTA通道",
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
        SettingsGroupDivider()
        // 崩溃只本地：不上报远端
        SettingsRow(
            icon = Icons.Default.Info,
            title = "崩溃只本地",
            subtitle = if (state.appSettings.crashLogLocalOnly) "崩溃日志仅保存在本地" else "崩溃日志允许上报",
            trailing = {
                Switch(
                    checked = state.appSettings.crashLogLocalOnly,
                    onCheckedChange = actions::updateCrashLogLocalOnly,
                )
            },
        )
        SettingsGroupDivider()
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
    }
    // 关于：版本 / 隐私政策 / 更新检查 / OTA 线路状态 / 启动耗时
    AboutDetail(state, actions)
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

@Composable
private fun BrightnessRow(value: Int, onValueChange: (Int) -> Unit) {
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
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.Accent, modifier = Modifier.size(TvDimens.SettingsRowIconSize))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "画面亮度",
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (value == 0) "跟随系统，不覆盖播放器亮度" else "播放时覆盖亮度 $value%",
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (value == 0) "系统" else "$value%",
                color = TvColors.Accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            steps = 99,
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
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    Playback("播放", Icons.Default.PlayArrow, Color(0xFF1ED760), Color(0xFF0E4A23)),
    NetworkSources("网络与源", Icons.AutoMirrored.Filled.List, Color(0xFFFFA42B), Color(0xFF5C3A0E)),
    Interface("界面与设备", Icons.Default.Settings, Color(0xFF4DA3FF), Color(0xFF1E3A5F)),
    TvRemote("电视与遥控", Icons.Default.Build, Color(0xFF9C9CFF), Color(0xFF2E2E5C)),
    SystemData("系统与数据", Icons.Default.Info, Color(0xFF55F0A1), Color(0xFF14532D)),
}
