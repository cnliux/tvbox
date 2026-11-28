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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.checkAllLiveSources
import com.tvbox.app.domain.DangerousAction
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.dangerousActionConfirmation
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.HistoryPolicy
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.DirectTuneTimeout
import com.tvbox.app.domain.ThemeAccent
import com.tvbox.app.domain.AUDIO_OUTPUT_MODE_OPTIONS
import com.tvbox.app.domain.audioOutputModeLabel
import com.tvbox.app.domain.PLAYER_SPEED_STEPS
import com.tvbox.app.domain.PlayerControlLayout
import com.tvbox.app.domain.PlayerSurface
import com.tvbox.app.domain.ParentalLock
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.QuantitySetting
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.ThirdPartyLiveProxy
import com.tvbox.app.domain.keepOnly
import com.tvbox.app.domain.learnKey
import com.tvbox.app.ui.settings.remote.RemoteProfileDialog
import com.tvbox.app.ui.settings.remote.remoteBindingRows
import com.tvbox.app.domain.UpdateChannel
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.PinPadDialog
import com.tvbox.app.ui.PinPadMode
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
    // 问题 44：双列是全屏宽电视的默认形态；分屏/小窗/竖屏盒子按「实际可用宽度」切单列，
    // 避免 132dp 高卡片在小窗口里被 48dp 边距挤到不可读。用 BoxWithConstraints 实测窗口宽
    // （LocalConfiguration.screenWidthDp 反映的是屏幕而非分屏窗口，不可靠）。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // constraints.maxWidth 是像素 Int：把 900dp 阈值按当前 density 换成像素后比较（分屏窗口实测宽度）。
        val density = LocalDensity.current
        val twoColumnMinWidthPx = with(density) { 900.dp.toPx() }
        val columns = if (constraints.maxWidth >= twoColumnMinWidthPx) 2 else 1
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
                    // 分类卡片：固定小集合（5 个，两列 3 行 / 窄窗单列 5 行）。此前两版都有「显示不正常」：
                    //   ① 嵌套 LazyVerticalGrid + 写死 .height(420dp) → 末行「关于」被挤出可视区；
                    //   ② FlowRow + fillMaxWidth(0.5f) → 每卡填满 50% 宽，两张 = 100% + 12dp
                    //      spacedBy 间距 > 可用宽度，第二张被迫换行，永远一行一张、右侧大片空白
                    //      （真机 192.168.1.45 横屏实测确认）。
                    // 改为确定性多列 Row + weight(1f)：weight 会把 spacedBy 间距计入分配，各卡
                    // 必然等宽同行，奇数尾行补 Spacer 占位保持列宽一致，高度仍固定不裁剪。
                    val rows = TvSettingCategory.entries.chunked(columns)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { rowCategories ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowCategories.forEach { category ->
                                    TvCategoryCard(
                                        category = category,
                                        onClick = { onCategoryClick(category) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // 双列尾行占位保持左列宽度；单列时唯一卡片取满宽，不补占位。
                                if (columns == 2 && rowCategories.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
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
private fun TvCategoryCard(
    category: TvSettingCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(132.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        category.gradientStart.copy(alpha = 0.22f),
                        category.gradientEnd.copy(alpha = 0.06f),
                    )
                )
            )
            .tvFocusScale(
                shape = shape,
                idleBorder = category.gradientStart.copy(alpha = 0.25f),
            )
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
                    TvSettingCategory.Playback -> TvPlaybackDetail(state, actions)
                    TvSettingCategory.Controls -> TvControlsDetail(state, actions)
                    TvSettingCategory.Search -> TvSearchDetail(state)
                    TvSettingCategory.Sources -> TvSourcesDetail(state, actions)
                    TvSettingCategory.Live -> TvLiveDetail(state, actions)
                    TvSettingCategory.Data -> TvDataDetail(state, actions, onClearHistory, onClearPlaybackStats)
                    TvSettingCategory.About -> TvAboutDetail(state, actions)
                }
            }
        }
    }
}

@Composable
private fun TvGeneralDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    val detectedMode = DeviceMode.detectFromSystem(androidx.compose.ui.platform.LocalContext.current)
    // 卡 1：设备与外观（设备模式 / 主题颜色 / 字体缩放）
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
        // 主题强调色（任务7）：多主题可选，自适应 + TV 聚焦描边随主题变化。
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "主题颜色",
            subtitle = state.appSettings.themeAccent.displayName,
            trailing = {
                TvChoiceChips(
                    options = ThemeAccent.entries,
                    selected = state.appSettings.themeAccent,
                    label = { it.displayName },
                    onSelect = actions::updateThemeAccent,
                )
            },
        )
        SettingsGroupDivider()
        // TV 字体缩放：仅 TV 端生效，整体缩放设置页文字。
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "字体缩放",
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
    }
    TvSettingsGroupGap()
    // 卡 2：更新（更新渠道 / 崩溃只本地）
    TvSettingsGroup {
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
        // 导出崩溃日志：合并本地崩溃日志与面包屑，脱敏后经系统分享送出（与手机端同一实现）。
        val exportContext = LocalContext.current
        TvSettingsRow(
            icon = Icons.Default.Info,
            title = "导出崩溃日志",
            subtitle = "脱敏合并本地崩溃日志与面包屑，经系统分享导出",
            trailing = {
                TvFocusButton(text = "导出", onClick = {
                    val result = exportCrashLogs(exportContext)
                    result?.let {
                        android.widget.Toast.makeText(exportContext, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                })
            },
        )
    }
}

@Composable
private fun TvPlaybackDetail(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    // 卡 1：播放基础（倍速 / 快进 / 自动下一集）
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
    }
    TvSettingsGroupGap()
    // 卡 2：解码与音频（解码策略 / 音频输出）
    TvSettingsGroup {
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
        // 任务1 电视直播无声修复：音频输出档位。默认 auto 时 AC3/DTS 走直通，
        // 部分电视/功放不支持表现为「有画面没声音」；切到「立体声(禁直通)」强制解码为 PCM。
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "音频输出",
            subtitle = "直播没声音时切到「立体声」可恢复",
            trailing = {
                TvChoiceChips(
                    options = AUDIO_OUTPUT_MODE_OPTIONS,
                    selected = state.appSettings.audioOutputMode,
                    label = { audioOutputModeLabel(it) },
                    onSelect = { v -> actions.updateAppSettings { it.copy(audioOutputMode = v) } },
                )
            },
        )
    }
}

/** 播放分类的「找片与AI」空态卡：TV 端 AI/语音找片能力由构建期配置决定，本版本不提供开关。 */
@Composable
private fun TvSearchDetail(state: TvBoxUiState) {
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Search,
            title = "AI 找片 / 语音找片",
            subtitle = "TV 端 AI/语音能力由构建期配置决定；可在手机端开启后共享账号偏好",
            trailing = {},
        )
    }
}

@Composable
private fun TvControlsDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showShortcutHelp by remember { mutableStateOf(false) }
    var showKeyBindingEditor by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    TvSettingsGroup {
        // #224：遥控器快捷键说明入口（纯 UI，键位参考 PlayerInput.kt / TvBoxApp.kt）。
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "遥控器快捷键",
            subtitle = "方向键导航 / 确认 / 返回 / 菜单倍速 / 数字键选台 · 跳页",
            trailing = {
                TvFocusButton(text = "查看", onClick = { showShortcutHelp = true })
            },
        )
        SettingsGroupDivider()
        // TV 端自定义键位绑定：与手机端「自定义键位绑定」编辑器同语义，把物理键码映射到统一动作。
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "自定义键位绑定",
            subtitle = "把物理键码映射到统一动作，跨页面一致",
            trailing = {
                TvFocusButton(text = "编辑", onClick = { showKeyBindingEditor = true })
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.Settings,
            title = "遥控器预设与迁移",
            subtitle = "标准 / 老盒子 / 无障碍 · 导出导入 remote_profile.json",
            trailing = {
                TvFocusButton(text = "管理", onClick = { showProfileDialog = true })
            },
        )
    }
    // 三类播放器按钮按场景独立增删；核心控件（播放/切集/切台/切房/返回）始终显示。
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.Build,
            title = "播放器按钮（按场景）",
            subtitle = "点播 / 电视直播 / 平台直播各自独立配置",
            trailing = {},
        )
        SettingsGroupDivider()
        PlayerSurface.entries.forEach { surface ->
            TvSettingsRow(
                icon = Icons.Default.Build,
                title = "— ${surface.label} —",
                subtitle = "该场景的默认按钮集合",
                trailing = {},
            )
            SettingsGroupDivider()
            PlayerControlLayout.configurableButtons(surface).forEach { button ->
                TvSettingsRow(
                    icon = Icons.Default.Build,
                    title = button.displayName,
                    subtitle = "${surface.label}：显示「${button.displayName}」按钮",
                    trailing = {
                        Switch(
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
                    },
                )
                SettingsGroupDivider()
            }
        }
        TvSettingsRow(
            icon = Icons.Default.Build,
            title = "恢复三个场景默认按钮",
            subtitle = "清空所有场景的自定义隐藏项",
            trailing = {
                TvFocusButton(text = "恢复", onClick = {
                    actions.updateAppSettings { s -> s.copy(playerHiddenControlsBySurface = emptyMap()) }
                })
            },
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
    // TV 端自定义键位绑定编辑器：与手机端同语义，遥控器可全程操作（捕获按键 + 逐动作绑定）。
    if (showKeyBindingEditor) {
        TvRemoteKeyBindingEditorDialog(
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
            onDismiss = { showKeyBindingEditor = false },
        )
    }
}

@Composable
private fun TvLiveDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    // T6-42 少儿锁 PIN 设置弹窗（开启后未设置 PIN 时自动弹出；「更换」入口随时可用）。
    var showKidsLockSetup by remember { mutableStateOf(false) }
    val kidsLockConfigured = ParentalLock.isConfigured(state.appSettings.parentalLockPin)
    // 弹窗触发控件的回焦锚点（V7）：PIN 盘关闭后把焦点还给「设置/更换」按钮，
    // 否则焦点残留在已销毁的弹窗焦点树上，遥控 OK/方向键全部失灵。
    val kidsLockBtnFocus = remember { FocusRequester() }
    var kidsLockDialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showKidsLockSetup) {
        if (showKidsLockSetup) {
            kidsLockDialogWasOpen = true
            return@LaunchedEffect
        }
        if (!kidsLockDialogWasOpen) return@LaunchedEffect // 初始组合不是「刚关闭」，不抢焦点
        kidsLockDialogWasOpen = false
        // 弹窗节点可能尚未移除：逐帧重试直到触发控件可聚焦。
        for (attempt in 0..4) {
            withFrameNanos { }
            if (runCatching { kidsLockBtnFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    TvSettingsGroup {
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
        // EPG 地址：用户显式配置优先，未配置时用内置默认节目单，内置不可用时再尝试订阅自带（M3U url-tvg）。
        // 复用 TvSourcesDetail 的 TextValueEditor 文本编辑模式，沿用 TvSettingsRow 焦点结构，不新增自定义焦点逻辑。
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "EPG 地址",
            subtitle = state.appSettings.epgUrl.ifBlank {
                "未配置 · 使用内置节目单，内置不可用时再尝试订阅自带（兼容 DIYP/百川/TVBox）；可在此填写自定义地址"
            },
            trailing = {
                TextValueEditor(
                    value = state.appSettings.epgUrl,
                    onSave = actions::updateEpgUrl,
                    fieldModifier = Modifier.width(220.dp).focusable(),
                    saveButton = { onSaveDraft -> TvFocusButton(text = "保存", onClick = onSaveDraft) },
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
            title = "数字选台超时",
            subtitle = DirectTuneTimeout.displayName(state.appSettings.liveNumberAutoCommitMs),
            trailing = {
                TvChoiceChips(
                    options = DirectTuneTimeout.choices,
                    selected = state.appSettings.liveNumberAutoCommitMs,
                    label = { DirectTuneTimeout.displayName(it) },
                    onSelect = actions::updateLiveNumberAutoCommitMs,
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
        SettingsGroupDivider()
        // T6-42 人因合规：少儿锁开关行。开启后遥控数字 4/5（电视直播/平台直播）入口需 PIN。
        // PIN 只存 SHA-256 摘要（parentalLockPin），绝不存明文；开启但尚未设置 PIN 时引导立即设置。
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "少儿锁",
            subtitle = when {
                state.appSettings.parentalLockEnabled && kidsLockConfigured ->
                    "已开启 · 遥控 4/5 入口需 PIN，仅保存 PIN 摘要"
                state.appSettings.parentalLockEnabled ->
                    "已开启 · PIN 尚未设置，请点「设置」"
                else ->
                    "关闭 · 开启后遥控 4/5（电视直播/平台直播）入口需 PIN"
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.appSettings.parentalLockEnabled) {
                        TvFocusButton(
                            text = if (kidsLockConfigured) "更换" else "设置",
                            onClick = { showKidsLockSetup = true },
                            modifier = Modifier.focusRequester(kidsLockBtnFocus),
                        )
                    }
                    Switch(
                        checked = state.appSettings.parentalLockEnabled,
                        onCheckedChange = {
                            actions.updateAppSettings { s -> s.copy(parentalLockEnabled = it) }
                            // 打开且尚无 PIN：立即引导设置，避免「开了锁却没 PIN 可输」。
                            if (it && !kidsLockConfigured) showKidsLockSetup = true
                        },
                    )
                }
            },
        )
    }
    TvSettingsGroupGap()
    // 卡 2：第三方代理解析（平台直播）：开关 + 模板编辑，与手机端设置页同一组配置字段。
    val thirdPartyContext = LocalContext.current
    var showThirdPartyEditor by remember { mutableStateOf(false) }
    val templateCount = state.appSettings.platformThirdPartyProxyTemplates
        .lines()
        .count { it.contains("{") }
    TvSettingsGroup {
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "第三方代理解析",
            subtitle = "各平台优先用第三方地址加载，多个第三方互为备用",
            trailing = {
                Switch(
                    checked = state.appSettings.platformThirdPartyProxyEnabled,
                    onCheckedChange = actions::updatePlatformThirdPartyProxyEnabled,
                )
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "第三方地址模板",
            subtitle = if (templateCount > 0) "$templateCount 条模板 · 支持按站点覆盖" else "未自定义 · 使用内置默认模板",
            trailing = {
                TvFocusButton(text = "编辑", onClick = { showThirdPartyEditor = true })
            },
        )
    }
    if (showThirdPartyEditor) {
        val current = state.appSettings.platformThirdPartyProxyTemplates
        var draft by remember(current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { showThirdPartyEditor = false },
            title = { Text("第三方地址模板") },
            text = {
                Column {
                    Text(
                        text = "每行一条，支持 {site}/{room} 占位符（另有 {siteAlias} 短码）；留空使用内置默认。",
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = 5,
                        maxLines = 10,
                        placeholder = { Text(ThirdPartyLiveProxy.defaultTemplates.joinToString("\n")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.updatePlatformThirdPartyProxyTemplates(draft)
                    showThirdPartyEditor = false
                    android.widget.Toast.makeText(thirdPartyContext, "第三方模板已保存", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("保存", color = TvColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showThirdPartyEditor = false }) { Text("取消") }
            },
        )
    }
    // T6-42 少儿锁 PIN 设置/更换：两次输入一致才把摘要落盘；关闭弹窗不影响开关状态。
    if (showKidsLockSetup) {
        PinPadDialog(
            mode = PinPadMode.Setup,
            storedHash = state.appSettings.parentalLockPin,
            onPinHashSaved = { hash ->
                actions.updateAppSettings { s -> s.copy(parentalLockPin = hash) }
                showKidsLockSetup = false
            },
            onDismiss = { showKidsLockSetup = false },
        )
    }
}

@Composable
private fun TvSourcesDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    // 卡 1：源入口（视频源 / IPTV 直播源）
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
    }
    TvSettingsGroupGap()
    // 卡 2：请求与探测（UA / 探测超时 / 并发数 / 失败熔断）
    TvSettingsGroup {
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
private fun TvDataDetail(state: TvBoxUiState, actions: TvBoxViewModel, onClearHistory: () -> Unit, onClearPlaybackStats: () -> Unit) {
    // 卡 1：观看历史（管理 / 隐私模式 / 保留条数）
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
        // S1-06：观看历史记录策略三档（完整 / 仅片名 / 关闭）。
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "历史记录策略",
            subtitle = historyPolicySummary(state.appSettings.historyPolicy),
            trailing = {
                TvChoiceChips(
                    options = HistoryPolicy.entries,
                    selected = state.appSettings.historyPolicy,
                    label = { it.displayName },
                    onSelect = actions::updateHistoryPolicy,
                )
            },
        )
        SettingsGroupDivider()
        // S1-06：搜索历史存活天数。
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "搜索历史保留",
            subtitle = searchHistoryTtlSummary(state.appSettings.searchHistoryTtlDays),
            trailing = {
                TvChoiceChips(
                    options = listOf(0, 1, 7, 30, 90, 365),
                    selected = state.appSettings.searchHistoryTtlDays,
                    label = { searchHistoryTtlSummary(it) },
                    onSelect = actions::updateSearchHistoryTtlDays,
                )
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
    TvSettingsGroupGap()
    // 卡 2：备份与清除数据：线路统计清空（危险操作，走二次确认）。
    TvSettingsGroup {
        // 播放管家自动换线：播放失败/首帧超时/频繁卡顿时按证据自动切换到下一条候选线路。
        // 此前 UI 层一度丢失此开关（数据层与逻辑一直在），现恢复入口。
        TvSettingsRow(
            icon = Icons.Default.PlayArrow,
            title = "播放管家自动换线",
            subtitle = "播放失败/首帧超时/频繁卡顿时自动切到下一条线路",
            trailing = {
                Switch(
                    checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                    onCheckedChange = actions::updatePlaybackAgentAutoSwitch,
                )
            },
        )
        SettingsGroupDivider()
        TvSettingsRow(
            icon = Icons.Default.Delete,
            title = "清空线路统计",
            subtitle = "清除自动换线健康度统计，不影响观看历史",
            trailing = {
                TvFocusButton(text = "清空", onClick = onClearPlaybackStats)
            },
        )
    }
}

@Composable
private fun TvAboutDetail(state: TvBoxUiState, actions: TvBoxViewModel) {
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showOssNotices by remember { mutableStateOf(false) }
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
            title = "开源许可",
            subtitle = "查看全部第三方依赖的许可证与版权声明",
            trailing = {
                TvFocusButton(text = "查看", onClick = { showOssNotices = true })
            },
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

// ─────────────────────────── TV 自定义键位绑定编辑器 ───────────────────────────

/**
 * TV 端自定义键位绑定编辑器：与手机端「自定义键位绑定」同语义，但全部用遥控器操作。
 * - 先聚焦「监听按键」按确认开始捕获，再按实体遥控键得到键码；
 * - 上下移动焦点到目标动作行，按确认把已捕获的键码设为该动作；
 * - 用户自定义过的动作显示 ✕，聚焦后按确认清除该动作的自定义键。
 * 自定义键会「替换」该动作的默认键；清除自定义则恢复默认。
 */
@Composable
private fun TvRemoteKeyBindingEditorDialog(
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
                    .then(
                        if (listening) {
                            // 捕获态：吃掉硬件键码，其余键不拦截（交给焦点系统）。
                            Modifier.onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    val native = event.nativeKeyEvent
                                    pendingKey = native.keyCode
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
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "先聚焦「监听按键」按确认，再按实体遥控键捕获键码；上下移动到动作行按确认绑定；✕ 清除该动作的自定义键。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "按键码：${pendingKey?.let { "0x%03X".format(it) } ?: "未选择"}" +
                            " · scanCode：${if (pendingScan > 0) "0x%03X".format(pendingScan) else "不支持"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TvFocusButton(
                        text = if (listening) "监听中…按任意键" else "监听按键",
                        selected = listening,
                        onClick = { listening = true },
                    )
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
                    val rows = remoteBindingRows(current)
                    rows.forEach { row ->
                        val keys = current.keysFor(row.action)
                        val action = row.action
                        val conflict = row.conflicts.firstOrNull()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
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
                                TvFocusButton(
                                    text = "仅保留此键",
                                    onClick = { onKeep(action, conflict.keyCode) },
                                )
                            }
                            if (row.hasCustomBinding) {
                                TvFocusButton(
                                    text = "✕ 清除",
                                    onClick = { onClear(action) },
                                )
                            } else {
                                TvFocusButton(
                                    text = "绑定当前键",
                                    enabled = pendingKey != null,
                                    onClick = {
                                        val code = pendingKey ?: return@TvFocusButton
                                        onAssign(action, code)
                                        if (pendingScan > 0) onLearn(action, pendingDevice, code, pendingScan)
                                    },
                                )
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

/** 同一分类下拆出的多张卡片之间的间隔（详情页内联使用，与手机端 SettingsGroupGap 对应）。 */
@Composable
private fun TvSettingsGroupGap() {
    Spacer(Modifier.height(14.dp))
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
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
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

// ─────────────────────────── 分类定义（与手机端一致：8 分类同名同序） ───────────────────────────

private enum class TvSettingCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    // F-07：渐变取自 SettingCategoryPalette 唯一真源（与手机版共用，杜绝两份调色板漂移）。
    General("通用与外观", "设备·主题·更新", Icons.Default.Settings, SettingCategoryPalette.gradientStart(0), SettingCategoryPalette.gradientEnd(0)),
    Playback("播放", "倍速·解码·音频", Icons.Default.PlayArrow, SettingCategoryPalette.gradientStart(1), SettingCategoryPalette.gradientEnd(1)),
    Controls("手势与控制", "快捷键·键位绑定", Icons.Default.Build, SettingCategoryPalette.gradientStart(2), SettingCategoryPalette.gradientEnd(2)),
    Search("找片与AI", "AI·语音·脱敏", Icons.Default.Search, SettingCategoryPalette.gradientStart(3), SettingCategoryPalette.gradientEnd(3)),
    Sources("数据源与网络", "视频源·直播源·UA", Icons.AutoMirrored.Filled.List, SettingCategoryPalette.gradientStart(4), SettingCategoryPalette.gradientEnd(4)),
    Live("直播", "EPG·选台·频道", Icons.AutoMirrored.Filled.List, SettingCategoryPalette.gradientStart(5), SettingCategoryPalette.gradientEnd(5)),
    Data("历史与数据", "历史·备份·清理", Icons.Default.Delete, SettingCategoryPalette.gradientStart(6), SettingCategoryPalette.gradientEnd(6)),
    About("关于", "版本·协议·更新", Icons.Default.Info, SettingCategoryPalette.gradientStart(7), SettingCategoryPalette.gradientEnd(7)),
}
