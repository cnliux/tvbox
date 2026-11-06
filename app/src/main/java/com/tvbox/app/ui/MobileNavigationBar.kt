package com.tvbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.DeviceMode

/**
 * 手机模式底部导航包装器：首页 / 历史 / 搜索 / 电视 / 直播。
 * 其中“电视”为电视直播（用户自定义 M3U/TXT 源，TvScreen.Live），“直播”为斗鱼/虎牙等平台直播（TvScreen.PlatformLive），
 * 与 TV 端导航命名保持一致。Player / Settings 等页面绕过底部导航直接渲染完整高度。
 */
@Composable
fun MobileNavWrapper(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    content: @Composable (navScreen: MobileNavScreen) -> Unit,
) {
    val isMobile = state.effectiveDeviceMode == DeviceMode.Mobile
    val tabs = listOf(
        MobileNavScreen.Home to "首页",
        MobileNavScreen.History to "历史",
        MobileNavScreen.Search to "搜索",
        MobileNavScreen.Live to "电视",
        MobileNavScreen.PlatformLive to "直播",
    )
    val currentTab = when (state.screen) {
        TvScreen.Home, TvScreen.Detail -> MobileNavScreen.Home
        TvScreen.History -> MobileNavScreen.History
        TvScreen.Search -> MobileNavScreen.Search
        TvScreen.Live -> MobileNavScreen.Live
        TvScreen.PlatformLive -> MobileNavScreen.PlatformLive
        TvScreen.Player, TvScreen.Settings,
        TvScreen.SourceManager, TvScreen.LiveSourceManager, TvScreen.VideoSourceManager,
        -> MobileNavScreen.Home
    }
    // 直播沉浸：仅播放页全屏（隐藏顶部栏与底部导航）；手机模式电视直播列表页保持
    // 状态栏 + Tab 导航可见（播放层状态由 MobileLiveScreen 写入 mobileLivePlayerImmersed，
    // 其播放层内部再调 FullscreenWhilePlayingPageVisible 隐藏系统栏）。
    // 平台直播播放页仍走旧的整页沉浸分支。
    val liveImmersive = when (state.screen) {
        TvScreen.Live -> !isMobile || mobileLivePlayerImmersed.value
        TvScreen.PlatformLive -> state.platformLiveDestination == PlatformLiveDestination.Player
        else -> false
    }
    // 播放页、详情页、设置页与源管理页使用整页布局，不显示顶部搜索栏和底部导航。
    val showChrome = !liveImmersive && when (state.screen) {
        TvScreen.Player, TvScreen.Detail, TvScreen.Settings,
        TvScreen.SourceManager, TvScreen.LiveSourceManager, TvScreen.VideoSourceManager,
        -> false
        else -> true
    }
    // 手机模式电视直播页自带 52dp 搜索栏（见 MobileLiveScreen），不再叠加公共顶栏；
    // 底部 Tab 导航照常显示（列表页不沉浸）。
    val showTopBar = showChrome && state.screen != TvScreen.Live
    // 手机模式统一按触摸界面渲染：关闭遥控器焦点框，避免触摸后高亮残留。
    CompositionLocalProvider(LocalTouchMode provides true) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showTopBar) {
                MobileTopBar(state = state, actions = actions)
            }
            Box(modifier = Modifier.weight(1f)) {
                content(currentTab)
            }
            // 底部导航：放在内容下方，避免遮挡首屏内容。
            if (isMobile && showChrome) {
                NavigationBar(
                    // edge-to-edge 下吸收系统导航栏高度，避免三键/手势条遮挡导航内容。
                    modifier = Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(56.dp),
                    tonalElevation = 0.dp,
                    containerColor = Color(0xFF1A1A1A),
                ) {
                    tabs.forEach { (screen, label) ->
                        val isSelected = screen == currentTab
                        // 「电视」（电视直播）Tab 使用新页面的红色强调色，其余保持绿色。
                        val selectedColor = if (screen == MobileNavScreen.Live) MobileNavLiveAccent else MobileNavDefaultAccent
                        NavigationBarItem(
                            icon = { NavIcon(screen, isSelected) },
                            label = {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) selectedColor else Color.White.copy(alpha = 0.6f),
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                if (!isSelected) actions.navigateToMobileScreen(screen)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = selectedColor,
                                unselectedIconColor = Color.White.copy(alpha = 0.6f),
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIcon(screen: MobileNavScreen, selected: Boolean) {
    val tint = when {
        selected && screen == MobileNavScreen.Live -> MobileNavLiveAccent
        selected -> MobileNavDefaultAccent
        else -> Color.White.copy(alpha = 0.6f)
    }
    val icon = when (screen) {
        MobileNavScreen.Home -> Icons.Filled.Home
        MobileNavScreen.History -> Icons.Filled.DateRange
        MobileNavScreen.Search -> Icons.Filled.Search
        MobileNavScreen.Live -> Icons.Filled.PlayArrow
        MobileNavScreen.PlatformLive -> Icons.Filled.Star
    }
    Icon(
        imageVector = icon,
        contentDescription = screen.name,
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

public enum class MobileNavScreen { Home, History, Search, Live, PlatformLive }

/** 底部导航默认选中色（绿）。 */
private val MobileNavDefaultAccent = Color(0xFF1ED760)

/** 「电视」（电视直播）Tab 选中色：与手机直播页强调色一致的红色 #E50914。 */
private val MobileNavLiveAccent = Color(0xFFE50914)

private fun TvBoxViewModel.navigateToMobileScreen(screen: MobileNavScreen) {
    when (screen) {
        MobileNavScreen.Home -> openHome()
        MobileNavScreen.History -> openHistory()
        MobileNavScreen.Search -> openSearch()
        MobileNavScreen.Live -> openLive()
        MobileNavScreen.PlatformLive -> openPlatformLive()
    }
}

/**
 * 手机模式顶部栏：左侧视频源下拉切换，右侧搜索入口，最右为设置入口。
 */
@Composable
private fun MobileTopBar(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    Surface(
        // 手机模式为 edge-to-edge 布局，顶部栏自己吸收状态栏高度，避免被状态栏遮挡。
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
        color = Color(0xFF1E1E1E),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileSourceDropdown(state = state, actions = actions)
            Spacer(modifier = Modifier.width(8.dp))
            MobileSearchEntry(
                query = state.searchQuery,
                onOpenSearch = actions::openSearch,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                // 触摸目标不小于 48dp，避免误触。
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { actions.openSettings() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MobileSourceDropdown(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    val lines = state.apiLines
    val currentLine = state.selectedApiLine
    val shape = RoundedCornerShape(18.dp)
    Box {
        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .clickable(enabled = lines.isNotEmpty()) { expanded = true },
            shape = shape,
            color = Color(0xFF2C2C2C),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = Color(0xFF1ED760),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentLine?.name ?: "选择源",
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "切换视频源",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            containerColor = Color(0xFF232323),
        ) {
            if (lines.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无可切换的视频源", color = Color.White.copy(alpha = 0.7f)) },
                    onClick = { expanded = false },
                )
            } else {
                lines.forEach { line ->
                    val selected = line.id == currentLine?.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (selected) "✓ ${line.name}" else line.name,
                                color = if (selected) Color(0xFF1ED760) else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            actions.updateHomeApiLine(line.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileSearchEntry(
    query: String,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .clickable { onOpenSearch() },
        shape = shape,
        color = Color(0xFF2C2C2C),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "搜索",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (query.isNotBlank()) query else "搜索影片...",
                color = if (query.isNotBlank()) Color.White else Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
