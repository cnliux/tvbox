package com.tvbox.app.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.tvbox.app.ui.components.TvA11y
import androidx.activity.compose.BackHandler
import com.tvbox.app.data.LiveTuningDebugPrefs
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.checkAllLiveSources
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.DangerousAction
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.dangerousActionConfirmation
import kotlinx.coroutines.launch
import com.tvbox.app.data.reset.AndroidFactoryResetDeps
import com.tvbox.app.domain.FactoryReset
import com.tvbox.app.domain.ResetTier
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.HistoryLayout
import com.tvbox.app.domain.HistoryPolicy
import com.tvbox.app.domain.KidsLockPolicy
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.metrics
import com.tvbox.app.domain.PLAYER_SPEED_STEPS
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.PlayerControlLayout
import com.tvbox.app.domain.PlayerSurface
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.StartupPhase
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.UpdateChannel
import com.tvbox.app.domain.ThirdPartyLiveProxy
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.keepOnly
import com.tvbox.app.domain.learnKey
import com.tvbox.app.ui.settings.remote.RemoteProfileDialog
import com.tvbox.app.ui.settings.remote.remoteBindingRows
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
    var selectedCategory by remember { mutableStateOf<MobileSettingCategory?>(null) }

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
        // 分类详情是二级页面：手机返回手势/返回键优先回到分类入口。
        BackHandler(enabled = selectedCategory != null) {
            selectedCategory = null
        }

        Crossfade(targetState = selectedCategory, label = "mobile-settings-crossfade") { category ->
            if (category == null) {
                MobileSettingsLanding(
                    state = state,
                    onCategoryClick = { selectedCategory = it },
                )
            } else {
                MobileSettingsDetail(
                    category = category,
                    state = state,
                    actions = actions,
                    onBack = { selectedCategory = null },
                    onClearPlaybackStats = { confirmClearPlaybackStats = true },
                    onClearHistory = { confirmClearHistory = true },
                    onResetCategory = { confirmResetCategory = it },
                )
            }
        }
    }
}

// ─────────────────────────── 入口页：分类卡片网格（对齐 TV 版） ───────────────────────────

@Composable
private fun MobileSettingsLanding(
    state: TvBoxUiState,
    onCategoryClick: (MobileSettingCategory) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶部栏：大标题 + 偏离默认计数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "设置",
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            val deviation = settingsDeviationCount(state.appSettings)
            Text(
                text = if (deviation > 0) "$deviation 项已偏离默认" else "已全部默认",
                color = if (deviation > 0) TvColors.Accent else TvColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // 手机端「当前配置」卡：与 TV 端一致，顶部直接给出当前视频源/直播源/设备，免去逐层点进。
                MobileSettingsHeroCard(state = state)
            }
            item {
                // 分类卡片：固定小集合（5 个），两列 3 行。此前两版都有「显示不正常」：
                //   ① 写死 .height(360dp) → 第 5 张卡被挤出可视区；
                //   ② LazyColumn.item{} 内嵌 LazyVerticalGrid → 外层滚动容器给不出确定
                //      宽度约束，inner grid 的 Fixed(2) 塌成 wrap-content，双列退化成
                //      单列靠左、右列空白，高度只按一列算导致内容纵向溢出。
                // 5 张卡是固定小集合，无需 Lazy；改为确定性两列 Row 排布，宽度约束由
                // fillMaxWidth + weight(1f) 明确给出，每张卡等宽、高度按内容自然撑开。
                val rows = MobileSettingCategory.entries.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { rowCategories ->
                        // IntrinsicSize.Min：同一行两张卡取最大内容高度，保证同行等高；
                        // 卡片本身再带统一 minHeight，跨行也不再出现高矮不一。
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.height(IntrinsicSize.Min),
                        ) {
                            rowCategories.forEach { category ->
                                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                    MobileCategoryCard(
                                        category = category,
                                        deviation = categoryDeviationCount(category, state.appSettings),
                                        onClick = { onCategoryClick(category) },
                                    )
                                }
                            }
                            // 奇数行（最后只剩 1 张）补占位，保持左列卡片宽度与其它行一致
                            if (rowCategories.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
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

@Composable
private fun MobileCategoryCard(
    category: MobileSettingCategory,
    deviation: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .heightIn(min = 132.dp)
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
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${category.title}，${category.subtitle}"
            }
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
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
                if (deviation > 0) {
                    Text(
                        text = "$deviation 项偏离",
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column {
                Text(
                    text = category.title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
private fun MobileSettingsHeroCard(state: TvBoxUiState) {
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
            HeroStat("设备", state.appSettings.deviceMode.summaryLabel(detectedMode), Modifier.fillMaxWidth(),
                labelColor = TvColors.TextTertiary, labelStyle = MaterialTheme.typography.labelSmall, valueSingleLine = true)
        }
    }
}

@Composable
private fun MobileSettingsDetail(
    category: MobileSettingCategory,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onBack: () -> Unit,
    onClearPlaybackStats: () -> Unit,
    onClearHistory: () -> Unit,
    onResetCategory: (MobileSettingCategory) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TvColors.TextPrimary,
                )
            }
            Text(
                text = category.title,
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (category != MobileSettingCategory.About) {
                val deviation = categoryDeviationCount(category, state.appSettings)
                if (deviation > 0) {
                    TextButton(onClick = { onResetCategory(category) }) {
                        Text("重置本组", color = TvColors.Accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(
            text = category.subtitle,
            color = TvColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 20.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                when (category) {
                    MobileSettingCategory.General -> GeneralDetail(state, actions)
                    MobileSettingCategory.Playback -> PlaybackDetail(state, actions) {
                        onClearPlaybackStats()
                    }
                    MobileSettingCategory.Controls -> ControlsDetail(state, actions)
                    MobileSettingCategory.Search -> SearchDetail(state, actions)
                    MobileSettingCategory.Sources -> SourcesDetail(state, actions)
                    MobileSettingCategory.Live -> LiveDetail(state, actions)
                    MobileSettingCategory.Data -> DataDetail(state, actions) {
                        onClearHistory()
                    }
                    MobileSettingCategory.About -> AboutDetail(state, actions)
                }
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
        MobileSettingCategory.General -> listOf(
            settings.deviceMode != d.deviceMode,
            settings.themeAccent != d.themeAccent,
            settings.updateChannel != d.updateChannel,
            settings.crashLogLocalOnly != d.crashLogLocalOnly,
        ).count { it }
        MobileSettingCategory.Playback -> listOf(
            settings.playerSpeed != d.playerSpeed,
            settings.playerBrightness != d.playerBrightness,
            settings.autoNextEpisodeEnabled != d.autoNextEpisodeEnabled,
            settings.autoNextCountdownSeconds != d.autoNextCountdownSeconds,
            settings.decoderStrategy != d.decoderStrategy,
            settings.fastSeekStepSeconds != d.fastSeekStepSeconds,
            settings.videoScaleMode != d.videoScaleMode,
            settings.firstPacketTimeoutMs != d.firstPacketTimeoutMs,
            settings.reconnectAttempts != d.reconnectAttempts,
            settings.bufferTargetMs != d.bufferTargetMs,
            settings.backgroundPlaybackMode != d.backgroundPlaybackMode,
            settings.audioOutputMode != d.audioOutputMode,
        ).count { it }
        MobileSettingCategory.Controls -> listOf(
            settings.playerGesturesEnabled != d.playerGesturesEnabled,
            settings.gestureSensitivity != d.gestureSensitivity,
            settings.playerHiddenControls != d.playerHiddenControls,
        ).count { it }
        MobileSettingCategory.Search -> listOf(
            settings.aiSearchEnabled != d.aiSearchEnabled,
            settings.voiceSearchEnabled != d.voiceSearchEnabled,
            settings.aiTextRedactionEnabled != d.aiTextRedactionEnabled,
            settings.aiOnlyTitleYearEnabled != d.aiOnlyTitleYearEnabled,
            settings.aiMissBehavior != d.aiMissBehavior,
            settings.aiResultCacheMinutes != d.aiResultCacheMinutes,
        ).count { it }
        MobileSettingCategory.Sources -> listOf(
            settings.sourceCircuitBreakerEnabled != d.sourceCircuitBreakerEnabled,
            settings.customUserAgent != d.customUserAgent,
            settings.perSourceTimeoutMs != d.perSourceTimeoutMs,
            settings.maxParallelSourceRequests != d.maxParallelSourceRequests,
        ).count { it }
        MobileSettingCategory.Live -> listOf(
            settings.platformThirdPartyProxyEnabled != d.platformThirdPartyProxyEnabled,
            settings.platformThirdPartyProxyTemplates != d.platformThirdPartyProxyTemplates,
        ).count { it }
        MobileSettingCategory.Data -> listOf(
            settings.historyMaxItems != d.historyMaxItems,
            settings.historyPrivacyMode != d.historyPrivacyMode,
            settings.historyLayout != d.historyLayout,
            settings.historyCoverBadgeEnabled != d.historyCoverBadgeEnabled,
            settings.continueWatchingMaxItems != d.continueWatchingMaxItems,
            settings.historyAutoCleanupEnabled != d.historyAutoCleanupEnabled,
            settings.resumeConflictPolicy != d.resumeConflictPolicy,
        ).count { it }
        MobileSettingCategory.About -> 0
    }
}








@Composable
private fun GeneralDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val detectedMode = DeviceMode.detectFromSystem(LocalContext.current)
    // 通用与外观：设备模式（历史页布局/封面角标归入「历史与数据」，避免与播放分类重复）。
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
    }
}

@Composable
private fun SearchDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    SettingsGroup {
        InfoRow(icon = Icons.Default.Info, title = "找片与隐私", subtitle = "AI/语音能力由构建期配置决定；Key 不写入本地偏好")
        SettingsGroupDivider()
        if (state.aiSearchAvailable) {
            BoolSettingRow("AI 找片", "只返回候选，点击卡片才查当前源", state.appSettings.aiSearchEnabled) { actions.updateAppSettings { s -> s.copy(aiSearchEnabled = it) } }
        } else {
            InfoRow(icon = Icons.Default.Info, title = "AI 找片", subtitle = "当前构建未注入 API Key，入口已隐藏（S1-04）")
        }
        if (state.aiSearchAvailable && state.appSettings.aiSearchEnabled) {
            SettingsGroupDivider()
            SettingsRow(
                icon = Icons.Default.Info,
                title = "打开 AI 找片",
                subtitle = "输入描述 → 候选片名 → 点击后才查当前源",
                trailing = { TextButton(onClick = actions::openAiSearch) { Text("进入", color = TvColors.Accent) } },
            )
        }
        SettingsGroupDivider()
        BoolSettingRow("语音找片", "调用系统语音识别，由系统应用持有麦克风权限", state.appSettings.voiceSearchEnabled) { actions.updateAppSettings { s -> s.copy(voiceSearchEnabled = it) } }
        SettingsGroupDivider()
        BoolSettingRow("文本脱敏上传", "默认开；移除历史/身份信息", state.appSettings.aiTextRedactionEnabled) { actions.updateAppSettings { s -> s.copy(aiTextRedactionEnabled = it) } }
        SettingsGroupDivider()
        BoolSettingRow("只传片名/年份", "不传观看历史", state.appSettings.aiOnlyTitleYearEnabled) { actions.updateAppSettings { s -> s.copy(aiOnlyTitleYearEnabled = it) } }
        SettingsGroupDivider()
        ChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "未命中行为",
            subtitle = "留在推荐页，不跳空白播放器",
            options = listOf("stay", "search"),
            selected = state.appSettings.aiMissBehavior,
            label = { if (it == "search") "切普通搜索" else "留在推荐页" },
            onSelect = { v -> actions.updateAppSettings { it.copy(aiMissBehavior = v) } },
        )
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "结果缓存",
            subtitle = "同一查询的本地缓存时长",
            options = listOf(0, 15, 30, 60),
            selected = state.appSettings.aiResultCacheMinutes,
            label = { if (it == 0) "不缓存" else "${it} 分钟" },
            onSelect = { v -> actions.updateAppSettings { it.copy(aiResultCacheMinutes = v) } },
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
        SettingsGroupDivider()
        // 画面比例：全局默认，播放器内按内容记忆优先。
        SettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "画面比例",
            subtitle = "全局默认；播放时可按片源单独记忆",
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
    }
    SettingsGroupGap()
    SettingsGroup {
        IntChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "续播倒计时",
            subtitle = "片尾切集前的可见倒计时",
            options = listOf(3, 5, 8),
            selected = state.appSettings.autoNextCountdownSeconds,
            label = { "${it}s" },
            onSelect = { v -> actions.updateAppSettings { it.copy(autoNextCountdownSeconds = v) } },
        )
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "首包超时",
            subtitle = "超过该时间未收到首帧即触发换线/重试",
            options = listOf(4_000, 8_000, 15_000),
            selected = state.appSettings.firstPacketTimeoutMs.toInt(),
            label = { "${it / 1000}s" },
            onSelect = { v -> actions.updateAppSettings { it.copy(firstPacketTimeoutMs = v.toLong()) } },
        )
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "断线重连",
            subtitle = "0=关闭；-1=持续重试",
            options = listOf(0, 1, 3, -1),
            selected = state.appSettings.reconnectAttempts,
            label = { if (it < 0) "持续" else "$it 次" },
            onSelect = { v -> actions.updateAppSettings { it.copy(reconnectAttempts = v) } },
        )
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "缓冲目标",
            subtitle = "越大越稳，首帧越慢；按网络环境选择",
            options = listOf(8_000, 15_000, 25_000),
            selected = state.appSettings.bufferTargetMs.toInt(),
            label = { "${it / 1000}s" },
            onSelect = { v -> actions.updateAppSettings { it.copy(bufferTargetMs = v.toLong()) } },
        )
        SettingsGroupDivider()
        ChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "后台行为",
            subtitle = "退到后台时暂停 / 音频续播 / 画中画（需设备支持）",
            options = listOf("pause", "audio", "pip"),
            selected = state.appSettings.backgroundPlaybackMode,
            label = {
                when (it) { "audio" -> "音频续播"; "pip" -> "画中画"; else -> "暂停" }
            },
            onSelect = { v -> actions.updateAppSettings { it.copy(backgroundPlaybackMode = v) } },
        )
        SettingsGroupDivider()
        ChoiceSettingRow(
            icon = Icons.Default.PlayArrow,
            title = "音频输出",
            subtitle = "直通需设备支持，不支持时自动回退立体声",
            options = listOf("auto", "stereo", "passthrough"),
            selected = state.appSettings.audioOutputMode,
            label = {
                when (it) { "stereo" -> "立体声"; "passthrough" -> "直通"; else -> "自动" }
            },
            onSelect = { v -> actions.updateAppSettings { it.copy(audioOutputMode = v) } },
        )
    }
}

@Composable
private fun ControlsDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showShortcutHelp by remember { mutableStateOf(false) }
    var showBindingEditor by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    // 卡 1：手机播放手势（总开关 + 8 分项 + 灵敏度 + 误触保护 + 手势锁）。
    SettingsGroup {
        SettingsRow(
            icon = Icons.Default.Build,
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
        InfoRow(
            icon = Icons.Default.Build,
            title = "手机播放手势 · 分项",
            subtitle = "总开关关闭时分项全部失效",
        )
        SettingsGroupDivider()
        BoolSettingRow("左侧竖滑：亮度", state.appSettings.gestureLeftBrightnessEnabled) { actions.updateAppSettings { s -> s.copy(gestureLeftBrightnessEnabled = it) } }
        BoolSettingRow("右侧竖滑：音量", state.appSettings.gestureRightVolumeEnabled) { actions.updateAppSettings { s -> s.copy(gestureRightVolumeEnabled = it) } }
        BoolSettingRow("横向拖动：进度", state.appSettings.gestureHorizontalSeekEnabled) { actions.updateAppSettings { s -> s.copy(gestureHorizontalSeekEnabled = it) } }
        BoolSettingRow("双击侧边：±10s", state.appSettings.gestureDoubleTapSideEnabled) { actions.updateAppSettings { s -> s.copy(gestureDoubleTapSideEnabled = it) } }
        BoolSettingRow("双击中心：播放/暂停", state.appSettings.gestureDoubleTapCenterEnabled) { actions.updateAppSettings { s -> s.copy(gestureDoubleTapCenterEnabled = it) } }
        BoolSettingRow("长按 2×：临时加速", state.appSettings.gestureLongPressSpeedEnabled) { actions.updateAppSettings { s -> s.copy(gestureLongPressSpeedEnabled = it) } }
        BoolSettingRow("双指缩放：画面填充", state.appSettings.gesturePinchZoomEnabled) { actions.updateAppSettings { s -> s.copy(gesturePinchZoomEnabled = it) } }
        BoolSettingRow("顶部横滑：返回（防误触默认关）", state.appSettings.gestureTopSwipeBackEnabled) { actions.updateAppSettings { s -> s.copy(gestureTopSwipeBackEnabled = it) } }
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.Build,
            title = "手势灵敏度",
            subtitle = "低 / 中 / 高",
            options = listOf(0, 1, 2),
            selected = state.appSettings.gestureSensitivity,
            label = { listOf("低", "中", "高")[it] },
            onSelect = { v -> actions.updateAppSettings { it.copy(gestureSensitivity = v) } },
        )
        SettingsGroupDivider()
        BoolSettingRow("误触保护：底部 24dp 不响应进度", state.appSettings.gestureEdgeGuardEnabled) { actions.updateAppSettings { s -> s.copy(gestureEdgeGuardEnabled = it) } }
        BoolSettingRow("手势锁：只保留单击唤出控制栏", state.appSettings.gestureLockEnabled) { actions.updateAppSettings { s -> s.copy(gestureLockEnabled = it) } }
    }
    SettingsGroupGap()
    // 卡 2：播放器底栏按钮显隐 —— 按点播 / 电视直播 / 平台直播三个场景独立配置。
    // 核心控件（播放/暂停、上下集/切台/切房、返回）始终显示，不在此列。
    SettingsGroup {
        InfoRow(
            icon = Icons.Default.Build,
            title = "播放器按钮（按场景）",
            subtitle = "三类播放器各自独立增删；核心控件始终显示",
        )
        SettingsGroupDivider()
        PlayerSurface.entries.forEach { surface ->
            Text(
                text = "— ${surface.label} —",
                color = TvColors.Accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            PlayerControlLayout.configurableButtons(surface).forEach { button ->
                BoolSettingRow(
                    title = button.displayName,
                    subtitle = "${surface.label}：显示「${button.displayName}」按钮",
                    checked = PlayerControlLayout.isVisible(
                        state.appSettings.playerHiddenControlsBySurface, surface, button,
                    ),
                    onCheckedChange = { visible ->
                        actions.updateAppSettings { s ->
                            s.copy(
                                playerHiddenControlsBySurface = PlayerControlLayout.setVisible(
                                    s.playerHiddenControlsBySurface, surface, button, visible,
                                ),
                            )
                        }
                    },
                )
            }
        }
        TextButton(onClick = {
            actions.updateAppSettings { s -> s.copy(playerHiddenControlsBySurface = emptyMap()) }
        }) {
            Text("恢复三个场景默认按钮", color = TvColors.Accent)
        }
    }
    SettingsGroupGap()
    // 卡 3：遥控器 / 远程管理（快捷键说明 + 自定义键位绑定）。
    SettingsGroup {
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
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "自定义键位绑定",
            subtitle = "把物理键码映射到统一动作，跨页面一致",
            trailing = {
                TextButton(onClick = { showBindingEditor = true }) {
                    Text("编辑", color = TvColors.Accent)
                }
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "遥控器预设与迁移",
            subtitle = "标准 / 老盒子 / 无障碍 · 导出导入 remote_profile.json",
            trailing = {
                TextButton(onClick = { showProfileDialog = true }) {
                    Text("管理", color = TvColors.Accent)
                }
            },
        )
    }
    if (showBindingEditor) {
        RemoteKeyBindingEditorDialog(
            current = state.appSettings.remoteKeyBindings,
            onAssign = { action, keyCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.withBinding(action, keyCode))
                }
            },
            onClear = { action ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.withoutBinding(action))
                }
            },
            onReset = {
                actions.updateAppSettings { s -> s.copy(remoteKeyBindings = RemoteKeyBindings.Default) }
            },
            onLearn = { action, deviceId, keyCode, scanCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.learnKey(action, deviceId, keyCode, scanCode))
                }
            },
            onKeep = { action, keyCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.keepOnly(action, keyCode))
                }
            },
            onDismiss = { showBindingEditor = false },
        )
    }
    if (showProfileDialog) {
        RemoteProfileDialog(
            bindings = state.appSettings.remoteKeyBindings,
            onApply = { next -> actions.updateAppSettings { s -> s.copy(remoteKeyBindings = next) } },
            onDismiss = { showProfileDialog = false },
        )
    }
    if (showShortcutHelp) {
        val shortcutHelpText = """
            方向键（上/下/左/右）：移动焦点、浏览列表；播放时左右=快进/快退，上下=换台/换线路。
            确认键（OK）：确认选择；电视直播/平台直播播放时打开频道（节目）列表。
            返回键：返回上一级 / 关闭当前浮层。
            菜单键：点播时切换倍速；电视直播时打开频道列表；平台直播时强制重连。
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
private fun SourcesDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
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
        SettingsGroupDivider()
        ChoiceSettingRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = "UA 作用域",
            subtitle = "统一自定义 / 按用途分工；所有输入都会拦截换行与控制字符",
            options = listOf("unified", "scoped"),
            selected = state.appSettings.uaScopeMode,
            label = { if (it == "scoped") "按用途分工" else "统一自定义" },
            onSelect = { v -> actions.updateAppSettings { it.copy(uaScopeMode = v) } },
        )
        if (state.appSettings.uaScopeMode == "scoped") {
            TextSettingRow("点播 API UA", "跟随全局时留空", state.appSettings.uaForApi) { actions.updateScopedUserAgent("api", it) }
            SettingsGroupDivider()
            TextSettingRow("Spider / JAR UA", "跟随全局时留空", state.appSettings.uaForSpider) { actions.updateScopedUserAgent("spider", it) }
            // P20：明确当前版本不执行 Spider/JAR 脚本，避免该 UA 输入行被误解为支持脚本执行。
            InfoRow(
                icon = Icons.Default.Info,
                title = "Spider / JAR 说明",
                subtitle = "当前版本不执行 Spider/JAR 脚本；此 UA 仅用于用户自定义请求头场景",
            )
            SettingsGroupDivider()
            TextSettingRow("直播 M3U UA", "跟随全局时留空", state.appSettings.uaForLive) { actions.updateScopedUserAgent("live", it) }
            SettingsGroupDivider()
            TextSettingRow("EPG UA", "跟随全局时留空", state.appSettings.uaForEpg) { actions.updateScopedUserAgent("epg", it) }
            SettingsGroupDivider()
            TextSettingRow("播放请求 UA", "跟随全局时留空", state.appSettings.uaForPlayback) { actions.updateScopedUserAgent("playback", it) }
            SettingsGroupDivider()
            TextSettingRow("WebView UA", "跟随全局时留空", state.appSettings.uaForWebView) { actions.updateScopedUserAgent("webview", it) }
        }
        SettingsGroupDivider()
        BoolSettingRow("导出 UA 脱敏", state.appSettings.uaExportRedacted) { actions.updateAppSettings { s -> s.copy(uaExportRedacted = it) } }
    }
}

@Composable
private fun LiveDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    SettingsGroup {
        IntChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "EPG 缓存时长",
            subtitle = "节目单本地缓存，过期后回退到下一路 EPG",
            options = listOf(1, 6, 12, 24, 72),
            selected = state.appSettings.epgCacheHours,
            label = { "${it}h" },
            onSelect = { v -> actions.updateAppSettings { it.copy(epgCacheHours = v) } },
        )
        TextSettingRow("EPG 时区", "留空使用系统时区", state.appSettings.epgTimezone) { actions.updateAppSettings { s -> s.copy(epgTimezone = it) } }
        // 备用 EPG 地址：订阅自带 EPG 时优先使用订阅自带地址，本字段仅作回退。
        TextSettingRow(
            "备用 EPG 地址",
            "订阅自带 EPG 时优先使用订阅，未自带才用此地址；留空则回退到内置兜底",
            state.appSettings.epgUrl,
        ) { actions.updateEpgUrl(it) }
        ChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "节目单越界处理",
            subtitle = "当前时间不在任何节目区间时",
            options = listOf("clip", "next", "blank"),
            selected = state.appSettings.epgOutOfRangePolicy,
            label = { when (it) { "next" -> "显示下一档"; "blank" -> "留空"; else -> "裁剪到最近档" } },
            onSelect = { v -> actions.updateAppSettings { it.copy(epgOutOfRangePolicy = v) } },
        )
        ChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "平台直播默认清晰度",
            subtitle = "各平台不支持时自动回退到可用档",
            options = listOf("original", "high", "low", "auto"),
            selected = state.appSettings.platformDefaultQuality,
            label = { when (it) { "high" -> "高清"; "low" -> "标清"; "auto" -> "弱网自动"; else -> "原画" } },
            onSelect = { v -> actions.updateAppSettings { it.copy(platformDefaultQuality = v) } },
        )
        ChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "FLV 连续播放代理",
            subtitle = "Go 代理为唯一路径；启动失败会回退直连",
            options = listOf("auto", "force", "off"),
            selected = state.appSettings.flvProxyMode,
            label = { when (it) { "force" -> "强制"; "off" -> "关闭"; else -> "自动" } },
            onSelect = { v -> actions.updateAppSettings { it.copy(flvProxyMode = v) } },
        )
        BoolSettingRow("第三方代理解析", "各平台优先用第三方地址加载，多个第三方互为备用", state.appSettings.platformThirdPartyProxyEnabled) {
            actions.updateAppSettings { s -> s.copy(platformThirdPartyProxyEnabled = it) }
        }
        SettingsGroupDivider()
        // 地址框始终展开显示，不随开关折叠；内置默认地址直接呈现，避免用户看不到实际生效的默认值。
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "第三方代理地址",
            subtitle = "用 # 站点名 分组（如 # douyu / # 虎牙），组内每行一条模板；未分组的行对所有平台兜底。占位符 {site} 站点、{siteAlias} 短码、{room} 房间号",
            stackedTrailing = true,
            trailing = {
                val current = state.appSettings.platformThirdPartyProxyTemplates
                val draft = remember(current) { mutableStateOf(current) }
                TextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text(ThirdPartyLiveProxy.defaultTemplates.joinToString("\n")) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (draft.value != current) {
                        TextButton(onClick = { draft.value = current }) { Text("还原") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = {
                        actions.updateAppSettings { it.copy(platformThirdPartyProxyTemplates = draft.value) }
                    }) { Text("保存") }
                }
                if (current.isBlank()) {
                    Text(
                        "留空＝使用内置默认（上方灰色示例地址）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        BoolSettingRow("局域网解析服务", "默认关闭；首次开启需确认安全提示", state.appSettings.lanResolverEnabled) {
            actions.updateAppSettings { s -> s.copy(lanResolverEnabled = it, lanResolverConfirmed = it || s.lanResolverConfirmed) }
        }
    }
}

@Composable
private fun TvRemoteDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showShortcutHelp by remember { mutableStateOf(false) }
    var showBindingEditor by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
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
        SettingsGroupDivider()
        // 自定义键位绑定：单条 (键码, 模式) → Action
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "自定义键位绑定",
            subtitle = "把物理键码映射到统一动作，跨页面一致",
            trailing = {
                TextButton(onClick = { showBindingEditor = true }) {
                    Text("编辑", color = TvColors.Accent)
                }
            },
        )
        SettingsGroupDivider()
        SettingsRow(
            icon = Icons.Default.Settings,
            title = "遥控器预设与迁移",
            subtitle = "标准 / 老盒子 / 无障碍 · 导出导入 remote_profile.json",
            trailing = {
                TextButton(onClick = { showProfileDialog = true }) {
                    Text("管理", color = TvColors.Accent)
                }
            },
        )
    }
    if (showBindingEditor) {
        RemoteKeyBindingEditorDialog(
            current = state.appSettings.remoteKeyBindings,
            onAssign = { action, keyCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.withBinding(action, keyCode))
                }
            },
            onClear = { action ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.withoutBinding(action))
                }
            },
            onReset = {
                actions.updateAppSettings { s -> s.copy(remoteKeyBindings = RemoteKeyBindings.Default) }
            },
            onLearn = { action, deviceId, keyCode, scanCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.learnKey(action, deviceId, keyCode, scanCode))
                }
            },
            onKeep = { action, keyCode ->
                actions.updateAppSettings { s ->
                    s.copy(remoteKeyBindings = s.remoteKeyBindings.keepOnly(action, keyCode))
                }
            },
            onDismiss = { showBindingEditor = false },
        )
    }
    if (showProfileDialog) {
        RemoteProfileDialog(
            bindings = state.appSettings.remoteKeyBindings,
            onApply = { next -> actions.updateAppSettings { s -> s.copy(remoteKeyBindings = next) } },
            onDismiss = { showProfileDialog = false },
        )
    }
    if (showShortcutHelp) {
        val shortcutHelpText = """
            方向键（上/下/左/右）：移动焦点、浏览列表；播放时左右=快进/快退，上下=换台/换线路。
            确认键（OK）：确认选择；电视直播/平台直播播放时打开频道（节目）列表。
            返回键：返回上一级 / 关闭当前浮层。
            菜单键：点播时切换倍速；电视直播时打开频道列表；平台直播时强制重连。
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
private fun DataDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearHistory: () -> Unit) {
    val context = LocalContext.current
    // P0-S4 / P0-02：恢复出厂设置真实执行。FactoryReset 提供三档骨架与 DELETE 令牌；
    // AndroidFactoryResetDeps 做白名单路径删除（只清本 App 私有数据，绝不越界）。
    val factoryResetScope = rememberCoroutineScope()
    var factoryResetMessage by remember { mutableStateOf<String?>(null) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetConfirmText by remember { mutableStateOf("") }
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
        // S1-06：观看历史记录策略三档（完整 / 仅片名 / 关闭）。
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "历史记录策略",
            subtitle = historyPolicySummary(state.appSettings.historyPolicy),
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = HistoryPolicy.entries,
                    selected = state.appSettings.historyPolicy,
                    label = { it.displayName },
                    onSelect = actions::updateHistoryPolicy,
                )
            },
        )
        SettingsGroupDivider()
        // S1-06：搜索历史存活天数。
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "搜索历史保留",
            subtitle = searchHistoryTtlSummary(state.appSettings.searchHistoryTtlDays),
            stackedTrailing = true,
            trailing = {
                ChoiceChips(
                    options = listOf(0, 1, 7, 30, 90, 365),
                    selected = state.appSettings.searchHistoryTtlDays,
                    label = { searchHistoryTtlSummary(it) },
                    onSelect = actions::updateSearchHistoryTtlDays,
                )
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
        InfoRow(icon = Icons.Default.Info, title = "更新策略", subtitle = "SHA-256 校验为强制项，不提供关闭开关")
        SettingsGroupDivider()
        BoolSettingRow("仅 Wi-Fi 下载更新", "避免移动网络下载 APK", state.appSettings.updateWifiOnly) { actions.updateAppSettings { s -> s.copy(updateWifiOnly = it) } }
        BoolSettingRow("更新前自动导出设置", "保留最近 2 份", state.appSettings.updateExportBeforeInstall) { actions.updateAppSettings { s -> s.copy(updateExportBeforeInstall = it) } }
        IntChoiceSettingRow(
            icon = Icons.Default.Info,
            title = "回滚包保留",
            subtitle = "过期后自动清理上一版 APK",
            options = listOf(0, 12, 24, 72),
            selected = state.appSettings.updateRollbackHours,
            label = { if (it == 0) "不保留" else "${it}h" },
            onSelect = { v -> actions.updateAppSettings { it.copy(updateRollbackHours = v) } },
        )
        BoolSettingRow("强提醒仅安全/播放阻断级", "减少非必要更新打扰", state.appSettings.updateStrongReminderOnly) { actions.updateAppSettings { s -> s.copy(updateStrongReminderOnly = it) } }
        SettingsGroupDivider()
        // P0-S4：危险操作显式可达——重置全部偏好（占位按钮，双因子确认）。
        SettingsRow(
            icon = Icons.Default.Delete,
            title = "重置全部偏好",
            subtitle = "长按触发 · 需输入「RESET」确认",
            trailing = {
                Text(
                    text = "长按",
                    color = TvColors.Danger,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, TvColors.Danger, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "长按重置全部偏好" }
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showFactoryResetDialog = true })
                        },
                )
            },
        )
    }
    // T6-42 人因合规：少儿锁 / 就寝锁 / 车载模式，默认关闭，运行时拦截由消费侧接入。
    SettingsGroupGap()
    SettingsGroup {
        KidsLockPinRow(state, actions)
        SettingsGroupDivider()
        IntChoiceSettingRow(
            icon = Icons.Default.Settings,
            title = "使用时长就寝锁",
            subtitle = if (state.appSettings.screenTimeLimitMinutes == 0) {
                "关闭，不限制使用时长"
            } else {
                "连续使用 ${state.appSettings.screenTimeLimitMinutes} 分钟后锁定"
            },
            options = listOf(0, 30, 60, 90, 120, 180),
            selected = state.appSettings.screenTimeLimitMinutes,
            label = { if (it == 0) "关闭" else "$it 分钟" },
            onSelect = { v -> actions.updateAppSettings { s -> s.copy(screenTimeLimitMinutes = v) } },
        )
        SettingsGroupDivider()
        BoolSettingRow(
            title = "车载模式",
            subtitle = "简化交互，减少行驶中的操作复杂度",
            checked = state.appSettings.drivingMode,
        ) { actions.updateAppSettings { s -> s.copy(drivingMode = it) } }
    }
    // P0-S4：双因子确认弹窗（长按 + 输入「RESET」）；当前版本仅占位提示。
    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false; factoryResetConfirmText = "" },
            title = { Text("重置全部偏好") },
            text = {
                Column {
                    Text(
                        "将清除偏好设置并恢复默认值；视频源、网络缓存与观看历史保留。此操作不可撤销。",
                        color = TvColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = factoryResetConfirmText,
                        onValueChange = { factoryResetConfirmText = it },
                        placeholder = { Text("输入 RESET 确认") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = factoryResetConfirmText == "RESET",
                    onClick = {
                        showFactoryResetDialog = false
                        factoryResetConfirmText = ""
                        // P0-02：真实执行「仅偏好设置」重置。白名单路径删除 + 审计 JSON。
                        factoryResetScope.launch {
                            val result = try {
                                FactoryReset.reset(
                                    tier = ResetTier.PreferencesOnly,
                                    deps = AndroidFactoryResetDeps(context),
                                )
                            } catch (e: Exception) {
                                null
                            }
                            factoryResetMessage = result?.message ?: "重置失败，请查看日志"
                        }
                    },
                ) { Text("确认重置", color = if (factoryResetConfirmText == "RESET") TvColors.Danger else TvColors.TextSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryResetDialog = false; factoryResetConfirmText = "" }) {
                    Text("取消", color = TvColors.TextSecondary)
                }
            },
        )
    }
    // P0-02：重置结果提示（Toast 一次性）。
    LaunchedEffect(factoryResetMessage) {
        factoryResetMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            factoryResetMessage = null
        }
    }
    // 关于：版本 / 隐私政策 / 更新检查 / OTA 线路状态 / 启动耗时
    AboutDetail(state, actions)
}

@Composable
private fun AboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val context = LocalContext.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showOssNotices by remember { mutableStateOf(false) }
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
            title = "开源许可",
            subtitle = "查看全部第三方依赖的许可证与版权声明",
            trailing = {
                TextButton(onClick = { showOssNotices = true }) {
                    Text("查看", color = TvColors.Accent)
                }
            },
        )
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

    if (showOssNotices) {
        OssNoticesDialog(onDismiss = { showOssNotices = false })
    }
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


// ─────────────────────────── 通用设置行（v2 新增项） ───────────────────────────

@Composable
private fun ChoiceSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selected: String,
    label: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        stackedTrailing = true,
        trailing = {
            ChoiceChips(options = options, selected = selected, label = label, onSelect = onSelect)
        },
    )
}

@Composable
private fun IntChoiceSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        stackedTrailing = true,
        trailing = {
            ChoiceChips(options = options, selected = selected, label = label, onSelect = onSelect)
        },
    )
}

@Composable
private fun BoolSettingRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    BoolSettingRow(title = title, subtitle = "", checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
private fun BoolSettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    BoolSettingRow(
        icon = Icons.Default.Settings,
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun BoolSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/**
 * T6-42 少儿锁 PIN 设置行：输入框只保留草稿，保存时立刻哈希，
 * 写进 AppSettings 的永远只有摘要，界面也不回显 PIN。
 */
@Composable
private fun KidsLockPinRow(state: TvBoxUiState, actions: TvBoxViewModel) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = KidsLockPolicy.isEnabled(state.appSettings.kidsLockPinHash)
    SettingsRow(
        icon = Icons.Default.Settings,
        title = "少儿锁",
        subtitle = if (enabled) "已开启 · 仅保存 PIN 摘要，不保存明文" else "关闭 · 设置 4~6 位数字 PIN 后开启",
        stackedTrailing = true,
        trailing = {
            // M-05：键盘弹起时把本行顶上来，避免「保存 PIN」被输入法遮挡。
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.imePadding(),
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    placeholder = { Text("4~6 位数字 PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    // M-05：PIN 应弹数字密码键盘而不是全键盘；Done 收起键盘避免遮挡保存按钮。
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.semantics {
                        // M-11：输入框只播报用途，不念出已输入内容。
                        contentDescription = TvA11y.input("少儿锁 PIN，4~6 位数字")
                    },
                )
                error?.let {
                    Text(it, color = TvColors.Danger, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                        if (!KidsLockPolicy.isValidPin(draft)) {
                            error = "请输入 4~6 位数字 PIN"
                        } else {
                            val hash = KidsLockPolicy.hashPin(draft)
                            actions.updateAppSettings { s -> s.copy(kidsLockPinHash = hash) }
                            draft = ""
                            error = null
                        }
                    },
                    ) { Text("保存 PIN", color = TvColors.Accent) }
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                        actions.updateAppSettings { s -> s.copy(kidsLockPinHash = "") }
                        draft = ""
                        error = null
                    },
                    ) { Text("关闭锁", color = TvColors.TextSecondary) }
                }
            }
        },
    )
}

@Composable
private fun TextSettingRow(title: String, subtitle: String, value: String, onSave: (String) -> Unit) {
    TextSettingRow(
        icon = Icons.Default.Settings,
        title = title,
        subtitle = subtitle,
        value = value,
        onSave = onSave,
    )
}

@Composable
private fun TextSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    onSave: (String) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        stackedTrailing = true,
        trailing = { TextValueEditor(value = value, onSave = onSave) },
    )
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

/** 同一分类下拆出的多张卡片之间的间隔（详情页内联使用）。 */
@Composable
private fun SettingsGroupGap() {
    Spacer(Modifier.height(12.dp))
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

// ─────────────────────────── 自定义键位绑定编辑器 ───────────────────────────

/**
 * 自定义键位绑定对话框：
 * - 顶部「监听按键」捕获硬件 KeyEvent.keyCode。
 * - 列表逐动作显示当前绑定的全部键码（默认 + 用户自定义），点击行把当前监听到的键码追加到该动作。
 * - 「恢复默认」一键回到 RemoteKeyBindings.Default（清除全部用户自定义）。
 */
@Composable
private fun RemoteKeyBindingEditorDialog(
    current: RemoteKeyBindings,
    onAssign: (RemoteKeyAction, Int) -> Unit,
    onClear: (RemoteKeyAction) -> Unit,
    onReset: () -> Unit,
    onLearn: (RemoteKeyAction, String, Int, Int) -> Unit,
    onKeep: (RemoteKeyAction, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingKey by remember { mutableStateOf<Int?>(null) }
    var pendingScan by remember { mutableStateOf(0) }
    var pendingDevice by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val keyListener = if (listening) {
        Modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                val native = event.nativeKeyEvent
                pendingKey = native.keyCode
                // P1-09：keyCode 相同的两个物理键靠 scanCode 区分（OK / PLAY_PAUSE 同码魔盒）。
                pendingScan = native.scanCode
                pendingDevice = native.deviceId.toString()
                listening = false
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = { onReset(); onDismiss() }) { Text("恢复默认", color = TvColors.Danger) }
        },
        title = { Text("自定义键位绑定") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(keyListener),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "先点「监听按键」并按下实体遥控键，再点对应动作行把该键追加到动作；点 ✕ 清除该动作的用户自定义键。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "按键码：${pendingKey?.let { "0x%03X".format(it) } ?: "未选择"}" +
                            " · scanCode：${if (pendingScan > 0) "0x%03X".format(pendingScan) else "不支持"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { listening = true }) {
                        Text(if (listening) "监听中…按任意键" else "监听按键", color = TvColors.Accent)
                    }
                }
                Text(
                    text = "绑定时同时记录 scanCode，用于区分同 keyCode 的物理键；冲突项在下方以红字标出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    remoteBindingRows(current).forEach { row ->
                        val keys = current.keysFor(row.action)
                        val conflict = row.conflicts.firstOrNull()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val code = pendingKey ?: return@clickable
                                    onAssign(row.action, code)
                                    if (pendingScan > 0) {
                                        onLearn(row.action, pendingDevice, code, pendingScan)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = row.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = row.boundKeysSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (keys.isEmpty()) TvColors.TextSecondary else TvColors.Accent,
                                )
                                if (conflict != null) {
                                    Text(
                                        text = "⚠ ${conflict.summary}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TvColors.Danger,
                                    )
                                }
                            }
                            if (conflict != null) {
                                TextButton(onClick = { onKeep(row.action, conflict.keyCode) }) {
                                    Text("仅保留", color = TvColors.Danger)
                                }
                            }
                            if (row.hasCustomBinding) {
                                TextButton(onClick = { onClear(row.action) }) {
                                    Text("✕", color = TvColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "自定义键会「替换」该动作的默认键；清除自定义则恢复默认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
            }
        },
    )
}

// ─────────────────────────── 分类定义 ───────────────────────────

private enum class MobileSettingCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    // F-07：渐变取自 SettingCategoryPalette 唯一真源，下标与下列顺序一致。
    General("通用与外观", "设备·主题·更新", Icons.Default.Settings, SettingCategoryPalette.gradientStart(0), SettingCategoryPalette.gradientEnd(0)),
    Playback("播放", "倍速·解码·音频", Icons.Default.PlayArrow, SettingCategoryPalette.gradientStart(1), SettingCategoryPalette.gradientEnd(1)),
    Controls("手势与控制", "手势·按钮·键位", Icons.Default.Build, SettingCategoryPalette.gradientStart(2), SettingCategoryPalette.gradientEnd(2)),
    Search("找片与AI", "AI·语音·脱敏", Icons.Default.Search, SettingCategoryPalette.gradientStart(3), SettingCategoryPalette.gradientEnd(3)),
    Sources("数据源与网络", "视频源·直播源·UA", Icons.AutoMirrored.Filled.List, SettingCategoryPalette.gradientStart(4), SettingCategoryPalette.gradientEnd(4)),
    Live("直播", "平台直播·代理·EPG", Icons.Default.PlayArrow, SettingCategoryPalette.gradientStart(5), SettingCategoryPalette.gradientEnd(5)),
    Data("历史与数据", "历史·备份·清理", Icons.Default.Delete, SettingCategoryPalette.gradientStart(6), SettingCategoryPalette.gradientEnd(6)),
    About("关于", "版本·协议·更新", Icons.Default.Info, SettingCategoryPalette.gradientStart(7), SettingCategoryPalette.gradientEnd(7)),
}
