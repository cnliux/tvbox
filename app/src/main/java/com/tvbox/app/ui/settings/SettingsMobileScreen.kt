package com.tvbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import com.tvbox.app.BuildConfig
import com.tvbox.app.domain.AiProviders
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.ui.RemoteManageDialog
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel

/**
 * 手机端设置页 — Material 3 现代卡片布局
 *
 * 设计特点：
 * - 顶部状态摘要卡片，一目了然当前配置
 * - 分组卡片，每组对应一个设置分类
 * - 统一的间距和圆角，现代感强
 * - 清晰的视觉层次和交互反馈
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMobileScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }

    if (state.remoteManageDialogVisible) {
        RemoteManageDialog(state = state, actions = actions)
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空历史记录") },
            text = { Text("确定要清空所有观看历史记录吗？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = { confirmClearHistory = false; actions.clearHistory() }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消") } },
        )
    }
    if (confirmClearPlaybackStats) {
        AlertDialog(
            onDismissRequest = { confirmClearPlaybackStats = false },
            title = { Text("清空线路统计") },
            text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。") },
            confirmButton = {
                Button(onClick = { confirmClearPlaybackStats = false; actions.clearPlaybackStats() }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearPlaybackStats = false }) { Text("取消") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部导航栏
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = actions::goBack) {
                    androidx.compose.material3.Icon(Icons.Default.ArrowBack, contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            // ── 状态摘要卡片 ──────────────────────────────────────
            item {
                SettingsSummaryCard(state = state)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── 通用设置 ──────────────────────────────────────────
            item { SettingsSectionHeader(title = "通用") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Settings,
                        title = "设备模式",
                        subtitle = state.appSettings.deviceMode.summaryLabel(
                            DeviceMode.detectFromSystem(LocalContext.current)
                        ),
                        trailing = { },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.Build,
                        title = "界面主题",
                        subtitle = state.appSettings.theme.displayName,
                        trailing = { ThemeChips(state, actions) },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "字体大小",
                        subtitle = state.appSettings.fontScale.displayName,
                        trailing = { FontChips(state, actions) },
                    )
                    Divider()
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
                    Divider()
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
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── 播放设置 ──────────────────────────────────────────
            item { SettingsSectionHeader(title = "播放") }
            item {
                SettingsCard {
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
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "视频缩放",
                        subtitle = state.appSettings.videoScaleMode.displayName,
                        trailing = { ScaleChips(state, actions) },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "剧集排序",
                        subtitle = if (state.appSettings.episodeSortReverse) "倒序（…,3,2,1）" else "正序（1,2,3,…）",
                        trailing = {
                            TextButton(onClick = actions::toggleEpisodeSortReverse) {
                                Text(if (state.appSettings.episodeSortReverse) "正序" else "倒序")
                            }
                        },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "片头跳过",
                        subtitle = if (state.appSettings.introSkipSeconds > 0) "自动跳过前 ${state.appSettings.introSkipSeconds}s" else "不跳过",
                        trailing = { },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "片尾跳过",
                        subtitle = if (state.appSettings.outroSkipSeconds > 0) "提前 ${state.appSettings.outroSkipSeconds}s 进入下一集" else "不跳过",
                        trailing = { },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "线路统计",
                        subtitle = state.playbackHealth.qualitySummaryText(),
                        trailing = if (state.playbackHealth.entryCount > 0) {
                            { TextButton(onClick = { confirmClearPlaybackStats = true }) { Text("清空", color = MaterialTheme.colorScheme.error) } }
                        } else { { } },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── 数据源 ────────────────────────────────────────────
            item { SettingsSectionHeader(title = "数据源") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "视频源",
                        subtitle = state.selectedApiLine?.name ?: "未配置",
                        trailing = { TextButton(onClick = actions::openVideoSourceManager) { Text("管理") } },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "IPTV 直播源",
                        subtitle = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条已配置",
                        trailing = { TextButton(onClick = actions::openLiveSourceManager) { Text("管理") } },
                    )
                    Divider()
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "平台直播",
                        subtitle = if (state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() } > 0) {
                            "已屏蔽 ${state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }} 个站点"
                        } else "管理虎牙/斗鱼/哔哩",
                        trailing = { TextButton(onClick = actions::openPlatformLive) { Text("管理") } },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── AI 推荐 ───────────────────────────────────────────
            item { SettingsSectionHeader(title = "AI 推荐") }
            item {
                SettingsCard {
                    val aiProvider = AiProviders.find(state.appSettings.aiProviderId)
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = "AI 模型",
                        subtitle = if (state.appSettings.aiApiKey.isBlank()) {
                            "${aiProvider.name} · 未配置 API Key"
                        } else {
                            "${aiProvider.name} · 已配置 API Key"
                        },
                        trailing = { TextButton(onClick = actions::openRemoteManageDialog) { Text("配置") } },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── 数据管理 ──────────────────────────────────────────
            item { SettingsSectionHeader(title = "数据管理") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = "观看历史",
                        subtitle = "管理本地播放记录",
                        trailing = {
                            if (state.historyItems.isNotEmpty()) {
                                TextButton(onClick = { confirmClearHistory = true }) {
                                    Text("清空", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── 更新 ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "更新") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.PlayArrow,
                        title = "检查更新",
                        subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                        trailing = {
                            if (!state.updateChecking) {
                                TextButton(onClick = { actions.checkForAppUpdate(showError = true) }) {
                                    Text("检查")
                                }
                            } else {
                                Text("检查中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                    if (state.updateError != null) {
                        androidx.compose.material3.Text(
                            text = state.updateError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 56.dp, top = 4.dp),
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ── 关于 ──────────────────────────────────────────────
            item { SettingsSectionHeader(title = "关于") }
            item {
                SettingsCard {
                    SettingsRow(icon = Icons.Default.Info, title = "应用名称", subtitle = "TVBox", trailing = { })
                    Divider()
                    SettingsRow(icon = Icons.Default.Info, title = "当前版本", subtitle = "v${BuildConfig.VERSION_NAME}", trailing = { })
                    Divider()
                    SettingsRow(icon = Icons.Default.Info, title = "开源协议", subtitle = "GPLv3", trailing = { })
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ── 底部 ──────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.Text(
                        text = "TVBox v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    androidx.compose.material3.Text(
                        text = "GPLv3 开源协议 · 仅供技术研究使用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

// ── 状态摘要卡片 ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSummaryCard(state: TvBoxUiState) {
    val detectedMode = DeviceMode.detectFromSystem(LocalContext.current)
    val aiProvider = AiProviders.find(state.appSettings.aiProviderId)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(16.dp),
    ) {
        Column {
            androidx.compose.material3.Text(
                text = "当前配置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SummaryChip(label = "源", value = state.selectedApiLine?.name ?: "未配置")
                SummaryChip(label = "直播", value = "${state.appSettings.liveSourceUrls.count { it.isNotEmpty() }} 条")
                SummaryChip(label = "AI", value = if (state.appSettings.aiApiKey.isBlank()) "${aiProvider.name} 未配置" else "${aiProvider.name} 已配置")
                SummaryChip(label = "设备", value = state.appSettings.deviceMode.summaryLabel(detectedMode))
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.material3.Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 分组标题 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    androidx.compose.material3.Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ── 设置卡片 ────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
    ) {
        content()
    }
}

// ── 设置行 ─────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.material3.Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

// ── 主题 Chips ─────────────────────────────────────────────────────────────────

@Composable
private fun ThemeChips(state: TvBoxUiState, actions: TvBoxViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(TvTheme.entries) { theme ->
            val isSelected = state.appSettings.theme == theme
            TextButton(onClick = { actions.updateTheme(theme) }) {
                Text(
                    text = if (isSelected) "✓ ${theme.displayName}" else theme.displayName,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 字体 Chips ─────────────────────────────────────────────────────────────────

@Composable
private fun FontChips(state: TvBoxUiState, actions: TvBoxViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(TvFontScale.entries) { fs ->
            val isSelected = state.appSettings.fontScale == fs
            TextButton(onClick = { actions.updateFontScale(fs) }) {
                Text(
                    text = if (isSelected) "✓ ${fs.displayName}" else fs.displayName,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 缩放 Chips ─────────────────────────────────────────────────────────────────

@Composable
private fun ScaleChips(state: TvBoxUiState, actions: TvBoxViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(VideoScaleMode.entries) { mode ->
            val isSelected = state.appSettings.videoScaleMode == mode
            TextButton(onClick = { actions.updateVideoScaleMode(mode) }) {
                Text(
                    text = if (isSelected) "✓ ${mode.displayName}" else mode.displayName,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
