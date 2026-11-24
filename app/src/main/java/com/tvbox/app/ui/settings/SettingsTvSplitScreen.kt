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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.checkAllLiveSources
import com.tvbox.app.domain.DangerousAction
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.dangerousActionConfirmation
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.PLAYER_SPEED_STEPS
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.QuantitySetting
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.UpdateChannel
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.components.DangerConfirmDialog
import com.tvbox.app.ui.components.HeroStat
import com.tvbox.app.ui.components.SettingsGroupDivider
import com.tvbox.app.ui.components.TextValueEditor
import com.tvbox.app.ui.components.TvChoiceChips
import com.tvbox.app.ui.components.QuantityChoiceChips
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
                    items(TvSettingCategory.entries, key = { it.name }) { category ->
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
                HeroStat("视频源", state.selectedApiLine?.name ?: "未配置", Modifier.weight(1f),
                    labelColor = TvColors.TextTertiary, labelStyle = MaterialTheme.typography.labelSmall, valueSingleLine = true)
                HeroStat("直播源", "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条", Modifier.weight(1f),
                    labelColor = TvColors.TextTertiary, labelStyle = MaterialTheme.typography.labelSmall, valueSingleLine = true)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeroStat("设备", state.appSettings.deviceMode.summaryLabel(detectedMode), Modifier.weight(1f),
                    labelColor = TvColors.TextTertiary, labelStyle = MaterialTheme.typography.labelSmall, valueSingleLine = true)
            }
        }
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
        SettingsGroupDivider()
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
        SettingsGroupDivider()
        // 崩溃只本地：不上报远端
        TvSettingsRow(
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
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "更新渠道",
            subtitle = "后台下载 → SHA-256 → 系统安装器 · 当前 ${state.appSettings.updateChannel.displayName}",
            trailing = {
                TvChoiceChips(
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
private fun TvPlaybackDetail(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onClearPlaybackStats: () -> Unit,
) {
    TvSettingsGroup {
        // 默认倍速（持久化偏好，与播放器内倍速联动）
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "默认倍速",
            subtitle = "播放默认速度，播放器内可临时切换",
            trailing = {
                TvChoiceChips(
                    options = PLAYER_SPEED_STEPS,
                    selected = state.appSettings.playerSpeed,
                    label = { String.format(java.util.Locale.US, "%.2f×", it) },
                    onSelect = actions::updatePlaybackSpeed,
                )
            },
        )
        SettingsGroupDivider()
        // 自动下一集：片尾倒计时自动续播
        TvSettingsRow(
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
        // 快进步长：5/10/15/30 秒
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "快进步长",
            subtitle = "快进/快退步长 ${state.appSettings.fastSeekStepSeconds}s",
            trailing = {
                TvChoiceChips(
                    options = FastSeekSetting.choices,
                    selected = state.appSettings.fastSeekStepSeconds,
                    label = { "${it}s" },
                    onSelect = actions::updateFastSeekStepSeconds,
                )
            },
        )
        SettingsGroupDivider()
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
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "EPG 节目条数",
            subtitle = quantitySummary(
                state.appSettings.epgCardRows,
                QuantitySettings.EpgCardRows,
                unit = " 条",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.EpgCardRows,
                    selected = state.appSettings.epgCardRows,
                    onSelect = actions::updateEpgCardRows,
                )
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台候选行数",
            subtitle = quantitySummary(
                state.appSettings.liveNumberMatchRows,
                QuantitySettings.LiveNumberMatchRows,
                unit = " 行",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.LiveNumberMatchRows,
                    selected = state.appSettings.liveNumberMatchRows,
                    onSelect = actions::updateLiveNumberMatchRows,
                )
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "数字选台匹配上限",
            subtitle = quantitySummary(
                state.appSettings.liveNumberSearchLimit,
                QuantitySettings.LiveNumberSearchLimit,
                unit = " 个",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.LiveNumberSearchLimit,
                    selected = state.appSettings.liveNumberSearchLimit,
                    onSelect = actions::updateLiveNumberSearchLimit,
                )
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "相关频道数量",
            subtitle = quantitySummary(
                state.appSettings.relatedChannelCount,
                QuantitySettings.RelatedChannelCount,
                unit = " 个",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.RelatedChannelCount,
                    selected = state.appSettings.relatedChannelCount,
                    onSelect = actions::updateRelatedChannelCount,
                )
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
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "IPTV 直播源",
            subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openLiveSourceManager) },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openPlatformLive) },
        )
        SettingsGroupDivider()
        // 自定义 User-Agent：视频接口、订阅拉取与直播播放共用；订阅地址也可追加 |User-Agent= 参数按源覆盖。
        TvSettingsRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "自定义 User-Agent",
            subtitle = state.appSettings.customUserAgent.ifBlank {
                "默认 ${com.tvbox.app.domain.UserAgentSetting.Default}；订阅地址可追加 |User-Agent=参数 按源覆盖"
            },
            trailing = {
                TextValueEditor(
                    value = state.appSettings.customUserAgent,
                    onSave = actions::updateCustomUserAgent,
                    fieldModifier = Modifier.width(220.dp).focusable(),
                    saveButton = { onSaveDraft -> TvFocusButton(text = "保存", onClick = onSaveDraft) },
                )
            },
        )
        SettingsGroupDivider()
        // 源探测超时：低于预算则放弃慢源，避免个别源拖住整页搜索/详情。
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "源探测超时",
            subtitle = if (state.appSettings.perSourceTimeoutMs == PerSourceTimeout.DefaultMs) {
                "默认 ${PerSourceTimeout.DefaultMs / 1000}s"
            } else {
                "当前 ${state.appSettings.perSourceTimeoutMs / 1000}s，默认 ${PerSourceTimeout.DefaultMs / 1000}s"
            },
            trailing = {
                TvChoiceChips(
                    options = PerSourceTimeout.choices,
                    selected = state.appSettings.perSourceTimeoutMs,
                    label = { if (it == PerSourceTimeout.DefaultMs) "默认" else "${it / 1000}s" },
                    onSelect = actions::updatePerSourceTimeoutMs,
                )
            },
        )
        SettingsGroupDivider()
        // 多源并发数
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "多源并发数",
            subtitle = quantitySummary(
                state.appSettings.maxParallelSourceRequests,
                QuantitySettings.MaxParallelSourceRequests,
                unit = " 个",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.MaxParallelSourceRequests,
                    selected = state.appSettings.maxParallelSourceRequests,
                    onSelect = actions::updateMaxParallelSourceRequests,
                )
            },
        )
        SettingsGroupDivider()
        // 源失败熔断：连续 5 次超时自动休眠 10 分钟。
        TvSettingsRow(
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
        SettingsGroupDivider()
        // #318 历史隐私模式：只阻止新记录写入，不清空已有历史。
        TvSettingsRow(
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
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "历史保留条数",
            subtitle = quantitySummary(
                state.appSettings.historyMaxItems,
                QuantitySettings.HistoryMaxItems,
                unit = " 条",
            ),
            trailing = {
                TvQuantityChips(
                    setting = QuantitySettings.HistoryMaxItems,
                    selected = state.appSettings.historyMaxItems,
                    onSelect = actions::updateHistoryMaxItems,
                )
            },
        )
    }
}

@Composable
private fun TvAboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    // #224：遥控器快捷键说明弹窗开关。
    var showShortcutHelp by remember { mutableStateOf(false) }
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "应用名称",
            subtitle = "聚合视频",
            trailing = {},
        )
        SettingsGroupDivider()
        // #217：当前版本补充版本号（versionCode），便于反馈问题时精确定位构建。
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "当前版本",
            subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            trailing = {},
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "开源协议",
            subtitle = "GPLv3",
            trailing = {},
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "隐私政策",
            subtitle = "本地数据处理与网络访问说明",
            trailing = {
                TvFocusButton(text = "查看", onClick = { showPrivacyPolicy = true })
            },
        )
        SettingsGroupDivider()
        // #224：TV 设置-关于 增加遥控器快捷键说明入口（纯 UI，键位参考 PlayerInput.kt / TvBoxApp.kt）。
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "遥控器快捷键",
            subtitle = "方向键导航 / 确认 / 返回 / 菜单倍速 / 数字键选台 · 跳页",
            trailing = {
                TvFocusButton(text = "查看", onClick = { showShortcutHelp = true })
            },
        )
        SettingsGroupDivider()
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
        // #224：列出遥控器主要按键，内容覆盖首页/点播/电视直播/平台直播的通用键位。
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
                maxLines = 2,
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
                maxLines = 2,
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

/**
 * 数量类设置的遥控器版 chips：把 [TvChoiceChips]（components 共享）接到
 * [QuantityChoiceChips] 的插槽上，数量档位与「默认」标签逻辑统一由组件维护。
 */
@Composable
private fun TvQuantityChips(setting: QuantitySetting, selected: Int, onSelect: (Int) -> Unit) =
    QuantityChoiceChips(setting, selected, onSelect) { options, selectedValue, label, select ->
        TvChoiceChips(options, selectedValue, label, select)
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
            // 与手机端一致：最小 48dp 触摸/遥控目标。
            .heightIn(min = 48.dp)
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
