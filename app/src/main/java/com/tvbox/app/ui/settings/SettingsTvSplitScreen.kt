package com.tvbox.app.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.AiProviders
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.ui.TvBoxUiState
import com.tvbox.app.ui.TvBoxViewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * TV端设置主界面 - 借鉴 Apple TV / Netflix 极简分栏设计
 * 左侧：分类菜单（通用、播放器、网络、关于）
 * 右侧：对应设置详情，纯黑背景、无边框、极细分割线
 * 焦点：animateFloatAsState 平滑放大过渡
 */
@Composable
fun SettingsTvSplitScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    modifier: Modifier = Modifier
) {
    val categories = CATEGORIES
    var selectedCategory by remember { mutableStateOf(categories[0]) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 左侧分类菜单
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .padding(start = 40.dp, top = 48.dp, end = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "设置",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(24.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                itemsIndexed(categories) { index, category ->
                    SettingsCategoryItem(
                        category = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
        }

        // 极细分割线（#1A1A1A 几乎隐形）
        HorizontalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = Color(0xFF1A1A1A),
            thickness = 1.dp
        )

        // 右侧详情区
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(start = 48.dp, top = 48.dp, end = 72.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsDetailHeader(title = selectedCategory.displayName, subtitle = selectedCategory.description)
            }
            item {
                HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)
            }
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    selectedCategory.content(state, actions)
                }
            }
        }
    }
}

// 设置分类数据类
data class SettingsCategory(
    val displayName: String,
    val description: String,
    val content: @Composable (TvBoxUiState, TvBoxViewModel) -> Unit
)

private val CATEGORIES: List<SettingsCategory> = listOf(
    SettingsCategory("通用", "设备模式、外观、首页")       { s, a -> GeneralSettingsContent(s, a) },
    SettingsCategory("播放器", "播放管家、线路统计")       { s, a -> PlayerSettingsContent(s, a) },
    SettingsCategory("网络", "远程管理、源管理")           { s, a -> NetworkSettingsContent(s, a) },
    SettingsCategory("关于", "应用信息、版本")             { s, a -> AboutSettingsContent() },
)

// 左侧分类项
@Composable
private fun SettingsCategoryItem(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    // 焦点时轻微放大（1.02x），150ms 线性过渡
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(durationMillis = 150, easing = LinearEasing),
        label = "categoryItemScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .focusable()
            .clickable { onClick() }
            .background(
                color = when {
                    selected -> Color(0xFF1C1C1E)  // 选中态：略亮的黑
                    focused  -> Color(0xFF252525)   // 焦点态：稍亮
                    else     -> Color.Transparent   // 默认透明
                }
            )
            .scale(scale)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Column {
            Text(
                text = category.displayName,
                color = if (selected || focused) Color.White else Color(0xFF9E9E9E),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = category.description,
                color = if (selected || focused) Color(0xFFAAAAAA) else Color(0xFF666666),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// 右侧详情头部
@Composable
private fun SettingsDetailHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            color = Color(0xFF6B6B6B),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// 通用设置内容
@Composable
private fun GeneralSettingsContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 设备模式
        SettingsRowBlock(
            title = "设备模式",
            subtitle = "当前：${state.appSettings.deviceMode.displayName}",
            trailing = {
                Text(
                    text = if (state.appSettings.deviceMode == DeviceMode.Mobile) "手机" else "电视",
                    color = Color(0xFFAAAAAA),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 界面主题
        SettingsRowBlock(
            title = "界面主题",
            subtitle = "当前：${state.appSettings.theme.displayName}",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvTheme.entries.forEach { theme ->
                        val isSelected = state.appSettings.theme == theme
                        MiniFocusButton(
                            text = if (isSelected) "✓ ${theme.displayName}" else theme.displayName,
                            selected = isSelected,
                            onClick = { actions.updateTheme(theme) }
                        )
                    }
                }
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 字体大小
        SettingsRowBlock(
            title = "字体大小",
            subtitle = "当前：${state.appSettings.fontScale.displayName}",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvFontScale.entries.forEach { fs ->
                        val isSelected = state.appSettings.fontScale == fs
                        MiniFocusButton(
                            text = if (isSelected) "✓ ${fs.displayName}" else fs.displayName,
                            selected = isSelected,
                            onClick = { actions.updateFontScale(fs) }
                        )
                    }
                }
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 继续观看
        SettingsRowBlock(
            title = "继续观看",
            subtitle = if (state.appSettings.homeContinueWatchingEnabled) "首页显示最近观看记录" else "首页不显示继续观看",
            trailing = {
                Switch(
                    checked = state.appSettings.homeContinueWatchingEnabled,
                    onCheckedChange = { actions.updateHomeContinueWatching(it) }
                )
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // AI 大模型
        SettingsRowBlock(
            title = "AI 大模型",
            subtitle = "${AiProviders.find(state.appSettings.aiProviderId).name} · ${if (state.appSettings.aiApiKey.isBlank()) "未配置 API Key" else "已配置 API Key"}",
            trailing = {
                MiniFocusButton(text = "配置", onClick = actions::openRemoteManageDialog)
            }
        )
    }
}

// 播放器设置内容
@Composable
private fun PlayerSettingsContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    var confirmClearStats by remember { mutableStateOf(false) }

    // 清空统计确认对话框
    if (confirmClearStats) {
        AlertDialog(
            onDismissRequest = { confirmClearStats = false },
            title = { Text("清空线路统计") },
            text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。") },
            confirmButton = {
                Button(onClick = {
                    confirmClearStats = false
                    actions.clearPlaybackStats()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearStats = false }) { Text("取消") }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 自动换线
        SettingsRowBlock(
            title = "自动换线",
            subtitle = if (state.appSettings.playbackAgentAutoSwitchEnabled)
                "播放失败或缓冲过久时自动切换线路"
            else
                "已关闭，播放器仍可手动换线",
            trailing = {
                Switch(
                    checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                    onCheckedChange = { actions.updatePlaybackAgentAutoSwitch(it) }
                )
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 线路质量统计
        SettingsRowBlock(
            title = "线路质量统计",
            subtitle = state.playbackHealth.qualitySummaryText(),
            trailing = {
                MiniFocusButton(
                    text = "清空统计",
                    enabled = state.playbackHealth.entryCount > 0,
                    onClick = { confirmClearStats = true }
                )
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 启动时检查更新
        SettingsRowBlock(
            title = "启动时检查更新",
            subtitle = if (state.appSettings.checkUpdatesOnStartup) "已开启" else "已关闭",
            trailing = {
                Switch(
                    checked = state.appSettings.checkUpdatesOnStartup,
                    onCheckedChange = { actions.updateStartupUpdateCheck(it) }
                )
            }
        )

        if (state.updateError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.updateError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// 网络设置内容（包含远程管理核心区块）
@Composable
private fun NetworkSettingsContent(state: TvBoxUiState, actions: TvBoxViewModel) {
    // 获取局域网 IP（首次渲染时计算）
    val localIp by remember { mutableStateOf(getLocalIpv4Address() ?: "192.168.x.x") }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 远程管理核心区块
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRowBlock(
                title = "远程管理",
                subtitle = "无需 token，同一局域网手机浏览器直接访问管理",
                trailing = {
                    // 状态指示器
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(
                                color = if (state.remoteManageServerUrl != null)
                                    Color(0xFF55F0A1)  // 运行中绿色
                                else
                                    Color(0xFF666666)  // 未运行灰色
                            )
                    )
                }
            )

            // 显示连接地址
            if (state.remoteManageServerUrl != null) {
                RemoteAddressBar(url = state.remoteManageServerUrl, localIp = localIp)
            } else {
                Text(
                    text = if (state.remoteManageDialogVisible) "正在启动服务..."
                           else "点击下方按钮启动远程管理服务",
                    color = Color(0xFF6B6B6B),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 启动/停止大按钮（焦点明显）
            val isRunning = state.remoteManageServerUrl != null
            LargeFocusButton(
                text = if (isRunning) "停止远程管理" else "启动远程管理",
                active = isRunning,
                onClick = {
                    if (isRunning) actions.closeRemoteManageDialog()
                    else         actions.openRemoteManageDialog()
                }
            )

            // 错误提示
            state.remoteManageServerError?.let { err ->
                Text(
                    text = err,
                    color = Color(0xFFFF7171),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 源管理入口
        SettingsRowBlock(
            title = "源管理",
            subtitle = "视频接口、直播源统一管理",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniFocusButton(text = "视频接口", onClick = actions::openVideoSourceManager)
                    MiniFocusButton(text = "直播源",   onClick = actions::openLiveSourceManager)
                    MiniFocusButton(text = "全部",     onClick = actions::openSourceManager)
                }
            }
        )

        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)

        // 直播源状态
        SettingsRowBlock(
            title = "自定义直播源",
            subtitle = if (state.appSettings.liveSourceUrls.isEmpty()) "未配置，将使用导入的源"
                       else "已配置 ${state.appSettings.liveSourceUrls.size} 条",
            trailing = {}
        )
    }
}

// 关于设置内容
@Composable
private fun AboutSettingsContent() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsRowBlock(title = "应用名称", subtitle = "TVBox", trailing = {})
        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)
        SettingsRowBlock(
            title = "设计灵感",
            subtitle = "借鉴 Apple TV、Netflix TV、YouTube TV 极简沉浸式设计",
            trailing = {}
        )
        HorizontalDivider(color = Color(0xFF141414), thickness = 1.dp)
        SettingsRowBlock(
            title = "开源协议",
            subtitle = "遵循 GPLv3，本地配置优先",
            trailing = {}
        )
    }
}

// 远程管理地址栏（精简展示）
@Composable
private fun RemoteAddressBar(url: String, localIp: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "局域网地址",
                color = Color(0xFF6B6B6B),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = url,
                color = Color(0xFF55F0A1),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// 通用设置行（无卡片，极简排版）
@Composable
private fun SettingsRowBlock(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color(0xFF888888),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(modifier = Modifier.width(140.dp), contentAlignment = Alignment.CenterEnd) {
            trailing()
        }
    }
}

// 迷你焦点按钮（用于主题/字体选择等）
@Composable
private fun MiniFocusButton(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "miniButtonScale"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .onFocusChanged { focused = enabled && (it.isFocused || it.hasFocus) }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .background(
                color = when {
                    !enabled -> Color(0xFF2A2A2A)
                    selected -> Color(0xFF2D2D2D)
                    focused  -> Color(0xFF333333)
                    else     -> Color(0xFF1E1E1E)
                }
            )
            .scale(scale)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> Color(0xFF4A4A4A)
                selected -> Color.White
                focused  -> Color(0xFFF0F0F0)
                else     -> Color(0xFFAAAAAA)
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// 大型焦点按钮（远程管理专用）
@Composable
private fun LargeFocusButton(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.015f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "largeButtonScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .focusable()
            .clickable { onClick() }
            .background(
                color = when {
                    active && focused -> Color(0xFF3A2020)  // 停止态高亮红色调
                    active            -> Color(0xFF2A2A2A)   // 停止态默认
                    focused           -> Color(0xFF1E3A20)   // 启动态高亮绿色调
                    else              -> Color(0xFF1A1A1A)   // 默认深色
                }
            )
            .scale(scale)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color(0xFFFF7171) else Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

// 工具函数：获取本机局域网 IPv4 地址
private fun getLocalIpv4Address(): String? {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (iface in interfaces) {
            val addrs = Collections.list(iface.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress
                    if (host != null && !host.startsWith("169.254.")) {
                        return host
                    }
                }
            }
        }
    } catch (_: Exception) {
        // 忽略权限问题
    }
    return null
}
