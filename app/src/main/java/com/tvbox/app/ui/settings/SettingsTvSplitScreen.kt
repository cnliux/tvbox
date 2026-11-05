package com.tvbox.app.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.BuildConfig
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvColors
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * TV 端设置页 —— 全新 Icon Rail + 详情面板分栏布局。
 * 左侧窄图标导航栏（通用/播放/数据源/AI 与远程/关于），右侧渲染选中分类详情。
 */
@Composable
fun SettingsTvSplitScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf(TvSettingCategory.General) }

    Row(modifier = modifier.fillMaxSize().background(TvColors.Background)) {
        // ── 左侧 Icon Rail ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(108.dp)
                .background(TvColors.Surface)
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "设置",
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            TvSettingCategory.entries.forEach { category ->
                RailIconItem(
                    category = category,
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                )
            }
        }

        // ── 分隔线 ──
        HorizontalDivider(
            modifier = Modifier.fillMaxHeight().width(1.dp),
            color = TvColors.Border.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        // ── 右侧详情面板 ──
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(horizontal = 56.dp, vertical = 48.dp)
        ) {
            Crossfade(targetState = selectedCategory, label = "tv-settings-detail") { category ->
                TvSettingsDetail(category = category, state = state, actions = actions)
            }
        }
    }
}

// ─────────────────────────── Rail 图标项 ───────────────────────────

@Composable
private fun RailIconItem(
    category: TvSettingCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(shape)
            .background(
                if (selected) TvColors.Accent.copy(alpha = 0.18f) else Color.Transparent
            )
            .tvFocusScale(shape = shape)
            .clickable { onClick() }
            .focusable()
            .padding(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selected) TvColors.Accent.copy(alpha = 0.25f) else TvColors.SurfaceRaised
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = category.title,
                tint = if (selected) TvColors.Accent else TvColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = category.title,
            color = if (selected) TvColors.Accent else TvColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ─────────────────────────── 详情面板 ───────────────────────────

@Composable
private fun TvSettingsDetail(
    category: TvSettingCategory,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = category.title,
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = category.subtitle,
                    color = TvColors.TextTertiary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item { HorizontalDivider(color = TvColors.Border.copy(alpha = 0.3f), thickness = 1.dp) }
        item {
            when (category) {
                TvSettingCategory.General -> GeneralTvContent(state, actions)
                TvSettingCategory.Playback -> PlaybackTvContent(state, actions)
                TvSettingCategory.Sources -> SourcesTvContent(state, actions)
                TvSettingCategory.Remote -> RemoteTvContent(state, actions)
                TvSettingCategory.About -> AboutTvContent()
            }
        }
    }
}

@Composable
private fun GeneralTvContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    val detectedMode = DeviceMode.detectFromSystem(androidx.compose.ui.platform.LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TvSettingRow(
            title = "设备模式",
            subtitle = "当前：${state.appSettings.deviceMode.summaryLabel(detectedMode)}",
            trailing = {
                TvChoiceChips(
                    options = DeviceMode.entries,
                    selected = state.appSettings.deviceMode,
                    label = { it.displayName },
                    onSelect = actions::updateDeviceMode,
                )
            },
        )
        TvSettingRow(
            title = "界面主题",
            subtitle = "当前：${state.appSettings.theme.displayName}",
            trailing = {
                TvChoiceChips(
                    options = TvTheme.entries,
                    selected = state.appSettings.theme,
                    label = { it.displayName },
                    onSelect = actions::updateTheme,
                )
            },
        )
        TvSettingRow(
            title = "字体大小",
            subtitle = "当前：${state.appSettings.fontScale.displayName}",
            trailing = {
                TvChoiceChips(
                    options = TvFontScale.entries,
                    selected = state.appSettings.fontScale,
                    label = { it.displayName },
                    onSelect = actions::updateFontScale,
                )
            },
        )
        TvSettingRow(
            title = "继续观看",
            subtitle = if (state.appSettings.homeContinueWatchingEnabled) "首页显示最近观看记录" else "首页不显示继续观看",
            trailing = {
                Switch(
                    checked = state.appSettings.homeContinueWatchingEnabled,
                    onCheckedChange = actions::updateHomeContinueWatching,
                )
            },
        )
        TvSettingRow(
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
private fun PlaybackTvContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    var confirmClearStats by remember { mutableStateOf(false) }
    if (confirmClearStats) {
        AlertDialog(
            onDismissRequest = { confirmClearStats = false },
            title = { Text("清空线路统计", color = TvColors.TextPrimary) },
            text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。", color = TvColors.TextSecondary) },
            confirmButton = {
                Button(onClick = { confirmClearStats = false; actions.clearPlaybackStats() }) { Text("清空", color = MaterialTheme.colorScheme.onPrimary) }
            },
            dismissButton = { TextButton(onClick = { confirmClearStats = false }) { Text("取消", color = TvColors.TextSecondary) } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TvSettingRow(
            title = "自动换线",
            subtitle = if (state.appSettings.playbackAgentAutoSwitchEnabled) "播放失败或缓冲过久时自动切换线路" else "已关闭，播放器仍可手动换线",
            trailing = {
                Switch(
                    checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                    onCheckedChange = actions::updatePlaybackAgentAutoSwitch,
                )
            },
        )
        TvSettingRow(
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
        TvSettingRow(
            title = "首页卡片数量",
            subtitle = if (state.appSettings.videoGridColumns > 0) "每行 ${state.appSettings.videoGridColumns} 个" else "自动（按屏幕宽度）",
            trailing = {
                TvChoiceChips(
                    options = listOf(0, 3, 4, 5, 6),
                    selected = state.appSettings.videoGridColumns,
                    label = { if (it == 0) "自动" else "$it" },
                    onSelect = actions::updateVideoGridColumns,
                )
            },
        )
        TvSettingRow(
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
        TvSettingRow(
            title = "剧集排序",
            subtitle = if (state.appSettings.episodeSortReverse) "倒序（…,3,2,1）" else "正序（1,2,3,…）",
            trailing = {
                TvFocusButton(
                    text = if (state.appSettings.episodeSortReverse) "切正序" else "切倒序",
                    onClick = actions::toggleEpisodeSortReverse,
                )
            },
        )
        TvSettingRow(
            title = "片头跳过",
            subtitle = if (state.appSettings.introSkipSeconds > 0) "自动跳过前 ${state.appSettings.introSkipSeconds}s" else "不跳过",
            trailing = {
                TvSecondsStepper(
                    seconds = state.appSettings.introSkipSeconds,
                    onSecondsChange = actions::updateIntroSkipSeconds,
                )
            },
        )
        TvSettingRow(
            title = "片尾跳过",
            subtitle = if (state.appSettings.outroSkipSeconds > 0) "提前 ${state.appSettings.outroSkipSeconds}s 进入下一集" else "不跳过",
            trailing = {
                TvSecondsStepper(
                    seconds = state.appSettings.outroSkipSeconds,
                    onSecondsChange = actions::updateOutroSkipSeconds,
                )
            },
        )
        TvSettingRow(
            title = "线路质量统计",
            subtitle = state.playbackHealth.qualitySummaryText(),
            trailing = {
                TvFocusButton(
                    text = "清空统计",
                    enabled = state.playbackHealth.entryCount > 0,
                    onClick = { confirmClearStats = true },
                )
            },
        )
    }
}

@Composable
private fun SourcesTvContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TvSettingRow(
            title = "视频源",
            subtitle = state.selectedApiLine?.name ?: "未配置",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openVideoSourceManager) },
        )
        TvSettingRow(
            title = "IPTV 直播源",
            subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openLiveSourceManager) },
        )
        TvSettingRow(
            title = "平台直播",
            subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
            } else "管理虎牙/斗鱼/哔哩",
            trailing = { TvFocusButton(text = "管理", onClick = actions::openPlatformLive) },
        )
    }
}

@Composable
private fun RemoteTvContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    val localIp by remember { mutableStateOf(getLocalIpv4Address() ?: "192.168.x.x") }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 远程管理
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "远程管理",
                    color = TvColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.remoteManageServerUrl != null) TvColors.Success else TvColors.TextTertiary
                        )
                )
            }
            Text(
                "无需 token，同一局域网手机浏览器直接访问管理",
                color = TvColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.remoteManageServerUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TvColors.SurfaceRaised)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("局域网地址", color = TvColors.TextTertiary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(state.remoteManageServerUrl, color = TvColors.Success, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Text(
                    if (state.remoteManageDialogVisible) "正在启动服务..." else "点击下方按钮启动远程管理服务",
                    color = TvColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val isRunning = state.remoteManageServerUrl != null
            TvLargeButton(
                text = if (isRunning) "停止远程管理" else "启动远程管理",
                active = isRunning,
                onClick = {
                    if (isRunning) actions.closeRemoteManageDialog() else actions.openRemoteManageDialog()
                },
            )

            state.remoteManageServerError?.let { err ->
                Text(err, color = TvColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider(color = TvColors.Border.copy(alpha = 0.3f), thickness = 1.dp)

        // 源管理入口
        TvSettingRow(
            title = "源管理",
            subtitle = "视频接口、直播源统一管理",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvFocusButton(text = "视频接口", onClick = actions::openVideoSourceManager)
                    TvFocusButton(text = "直播源", onClick = actions::openLiveSourceManager)
                    TvFocusButton(text = "全部", onClick = actions::openSourceManager)
                }
            },
        )
    }
}

@Composable
private fun AboutTvContent() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TvSettingRow(title = "应用名称", subtitle = "TVBox", trailing = {})
        TvSettingRow(title = "当前版本", subtitle = "v${BuildConfig.VERSION_NAME}", trailing = {})
        TvSettingRow(title = "设计灵感", subtitle = "借鉴 tvOS、Android TV、Netflix 极简沉浸式设计", trailing = {})
        TvSettingRow(title = "开源协议", subtitle = "遵循 GPLv3，本地配置优先", trailing = {})
    }
}

// ─────────────────────────── TV 通用组件 ───────────────────────────

@Composable
private fun TvSettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(24.dp))
        Box(modifier = Modifier.width(260.dp), contentAlignment = Alignment.CenterEnd) {
            trailing()
        }
    }
}

@Composable
private fun <T> TvChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            TvFocusButton(
                text = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
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
            .clickable(enabled = enabled, onClick = onClick)
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

@Composable
private fun TvLargeButton(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (active) TvColors.Danger.copy(alpha = 0.18f) else TvColors.Accent.copy(alpha = 0.18f)
            )
            .tvFocusScale(shape = shape)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) TvColors.Danger else TvColors.Accent,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TvSecondsStepper(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(text = "−", onClick = { onSecondsChange((seconds - 5).coerceAtLeast(0)) })
        Text(
            text = "${seconds}s",
            color = TvColors.Accent,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp),
        )
        TvFocusButton(text = "+", onClick = { onSecondsChange((seconds + 5).coerceAtMost(300)) })
    }
}

// ─────────────────────────── 分类定义 ───────────────────────────

private enum class TvSettingCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    General("通用", "设备模式、外观、首页行为", Icons.Default.Settings),
    Playback("播放", "播放管家、缩放、跳过", Icons.Default.PlayArrow),
    Sources("数据源", "视频源、直播源、平台直播", Icons.AutoMirrored.Filled.List),
    Remote("远程管理", "局域网远程管理、源管理", Icons.Default.Settings),
    About("关于", "应用信息、版本、协议", Icons.Default.Info),
}

// 工具：获取本机局域网 IPv4
private fun getLocalIpv4Address(): String? {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (iface in interfaces) {
            val addrs = Collections.list(iface.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress
                    if (host != null && !host.startsWith("169.254.")) return host
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}
