package com.tvbox.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvbox.app.data.isLiveFavorite as containsLiveFavorite
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.LiveChannelLine
import com.tvbox.app.domain.PlaybackWatchdog
import com.tvbox.app.domain.PlaybackFailureKind
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.resolveVideoScaleMode
import com.tvbox.app.domain.liveVideoScaleKey
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.domain.probeLiveLineMimeType
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机模式「电视直播」两层页面：
 * - [MobileLiveScreen] 列表层：竖屏（搜索 + 电视/平台直播 Tab + 分组横滑 + 频道卡片列表），
 *   显示系统状态栏与底部导航（沉浸开关见 [mobileLivePlayerImmersed]，由 MobileNavWrapper 读取）。
 * - 播放层 [MobileLivePlayerScreen]：竖屏 16:9 播放器 + 信息/线路 + EPG 节目单 + 相关频道，
 *   进入即全屏沉浸，支持旋转/按钮切换横屏全屏（横屏仅播放区铺满）。
 *
 * 电视端仍走 LiveScreen.kt；本文件只在手机模式被调用，内部不再判断 DeviceMode。
 */

// ---- 设计规范配色（严格执行设计稿） ----
private val LiveAccent = Color(0xFFE50914)
private val LiveBackground = Color(0xFF0D0D10)
private val LiveCard = Color(0xFF16161B)
private val LiveDivider = Color(0xFF141418)
private val LiveField = Color(0xFF1A1A1E)
private val LiveLogoPlaceholderBg = Color(0xFF1A1A22)
private val LiveFavoriteGold = Color(0xFFFFB300)
private val LiveEpgCurrentBg = Color(0x0AE50914)

private const val MOBILE_LIVE_LOG_TAG = "TVBoxMobileLive"
private const val MOBILE_LIVE_CONNECT_TIMEOUT_MS = 8_000
private const val MOBILE_LIVE_READ_TIMEOUT_MS = 12_000
private const val MOBILE_LIVE_BADGE_HIDE_MS = 2_000L
private const val MOBILE_LIVE_CONTROLS_HIDE_MS = 4_000L
private const val MOBILE_LIVE_EPG_TICK_MS = 30_000L

/** 手势提示（亮度/音量）停留时长。 */
private const val MOBILE_LIVE_GESTURE_PROMPT_MS = 1_200L

/**
 * 播放页沉浸标记：[MobileLiveScreen] 内部切到播放层时置 true，[MobileNavWrapper] 以此决定
 * 是否隐藏顶部栏与底部导航；列表页保持 false（状态栏 + 底部 Tab 可见）。
 */
internal val mobileLivePlayerImmersed = mutableStateOf(false)

@Composable
fun MobileLiveScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onOpenPlatformLive: () -> Unit,
) {
    val context = LocalContext.current
    // 非负即进入播放层（哨兵 -1 = 列表层）；具体播放频道始终跟随 state.liveChannelIndex。
    var selectedChannelNumber by rememberSaveable { mutableStateOf(-1) }
    val playerOpen = selectedChannelNumber >= 0 && state.liveChannels.isNotEmpty()

    // 播放层进出同步给底部导航的沉浸开关。
    LaunchedEffect(playerOpen) {
        mobileLivePlayerImmersed.value = playerOpen
    }
    DisposableEffect(Unit) {
        onDispose { mobileLivePlayerImmersed.value = false }
    }

    // ViewModel 侧容错提示统一转 Toast 并消费（替代电视端 LivePrompt）。
    LaunchedEffect(state.liveNotice) {
        val notice = state.liveNotice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        actions.consumeLiveNotice()
    }

    BackHandler(enabled = playerOpen) {
        selectedChannelNumber = -1
    }

    val currentChannel = state.liveChannels.getOrNull(state.liveChannelIndex)
    // 列表页筛选状态（搜索/分组/只看收藏）与滚动位置都挂在本层，进出播放层不丢失。
    val listFilters = remember { MobileLiveListFilters() }
    val listScrollState = remember { LazyListState() }
    if (playerOpen && currentChannel != null) {
        MobileLivePlayerScreen(
            state = state,
            actions = actions,
            channel = currentChannel,
            onBack = { selectedChannelNumber = -1 },
            onChannelPicked = { index, number ->
                actions.selectLiveChannel(index)
                selectedChannelNumber = number
            },
        )
    } else {
        MobileLiveListScreen(
            state = state,
            actions = actions,
            filters = listFilters,
            listScrollState = listScrollState,
            onOpenPlatformLive = onOpenPlatformLive,
            onChannelClick = { index ->
                actions.selectLiveChannel(index)
                selectedChannelNumber = state.liveChannels.getOrNull(index)?.number ?: -1
            },
        )
    }
}

// ---------------------------------------------------------------------------
// A. 列表页
// ---------------------------------------------------------------------------

/** 列表页筛选状态容器：由 [MobileLiveScreen] 持有，避免进入播放层后丢失。 */
private class MobileLiveListFilters {
    val query = mutableStateOf("")
    val selectedGroup = mutableStateOf<String?>(null) // null = 全部
    val favoritesOnly = mutableStateOf(false)
}

@Composable
private fun MobileLiveListScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    filters: MobileLiveListFilters,
    listScrollState: LazyListState,
    onOpenPlatformLive: () -> Unit,
    onChannelClick: (Int) -> Unit,
) {
    // 筛选状态由上层持有：进入播放层时本组合被移除，若留在本层会随重组一起丢失。
    var query by filters.query
    var selectedGroup by filters.selectedGroup // null = 全部
    var favoritesOnly by filters.favoritesOnly
    val context = LocalContext.current
    val channels = state.liveChannels
    val currentPlayingIndex = state.liveChannelIndex
    val groups = remember(channels) {
        listOf("全部") + channels.map { it.groupName }.distinct()
    }
    val filtered = remember(channels, query, selectedGroup, favoritesOnly, state.liveFavoriteKeys) {
        val keyword = query.trim()
        channels.withIndex().filter { (_, channel) ->
            (selectedGroup == null || channel.groupName == selectedGroup) &&
                (keyword.isEmpty() || channel.name.contains(keyword, ignoreCase = true)) &&
                (!favoritesOnly || containsLiveFavorite(state.liveFavoriteKeys, channel.groupName, channel.name))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveBackground),
    ) {
        // 1. 顶部搜索栏 56dp（与手机端全局顶栏一致；edge-to-edge 下自行吸收状态栏高度）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(LiveField)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索频道/节目",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = Color.White),
                            cursorBrush = SolidColor(LiveAccent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { query = "" }
                                .semantics { contentDescription = "清空搜索" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 收藏筛选开关（点按只显示收藏频道）。
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { favoritesOnly = !favoritesOnly }
                    .semantics {
                        contentDescription = if (favoritesOnly) "取消只看收藏频道" else "只看收藏频道"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (favoritesOnly) LiveFavoriteGold else Color.White.copy(alpha = 0.6f),
                )
            }
            // 设置入口。
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { actions.openSettings() }
                    .semantics(mergeDescendants = true) { contentDescription = "设置" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 2. 「电视直播｜平台直播」Tab 行 48dp。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            MobileLiveTabItem(label = "电视直播", selected = true, onClick = {})
            MobileLiveTabItem(label = "平台直播", selected = false, onClick = onOpenPlatformLive)
        }

        // 3. 分组横滑（48dp 触摸热区，视觉胶囊仍保持原尺寸）。
        if (channels.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(groups) { group ->
                    val isSelected = (group == "全部" && selectedGroup == null) || group == selectedGroup
                    MobileLiveFilterChip(
                        text = group,
                        selected = isSelected,
                        onClick = { selectedGroup = if (group == "全部") null else group },
                    )
                }
            }
        }

        // 5. 状态区：加载中 / 错误 / 空 / 列表。
        when {
            state.liveLoading && channels.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LiveAccent, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在加载...", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                }
            }
            state.liveError != null || channels.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // 加载失败与「没有频道」分开给动作：前者重试有意义，后者应先回设置里添加/恢复直播源。
                val hasError = state.liveError != null
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = if (hasError) "直播源加载失败" else "没有可用直播频道",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasError) "请检查网络，或切换其他直播源后重试" else "可在设置中添加或恢复直播源",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(LiveAccent)
                            .clickable(role = Role.Button) {
                                if (hasError) actions.refreshLive() else actions.openSettings()
                            }
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (hasError) "重试" else "去设置", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    if (!hasError) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(role = Role.Button) { actions.refreshLive() }
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("刷新频道", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📺", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("该分组暂无频道", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("切换其他分组看看", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                }
            }
            else -> LazyColumn(
                state = listScrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(filtered, key = { _, pair -> pair.index }) { _, pair ->
                    val channel = pair.value
                    MobileLiveChannelRow(
                        channel = channel,
                        isPlaying = pair.index == currentPlayingIndex,
                        isFavorite = actions.isLiveFavorite(channel),
                        nowTitle = actions.liveEpgNow(channel)?.title,
                        nextTitle = actions.liveEpgNext(channel)?.title,
                        onClick = { onChannelClick(pair.index) },
                        onToggleFavorite = {
                            val wasFavorite = actions.isLiveFavorite(channel)
                            actions.toggleLiveFavorite(channel)
                            Toast.makeText(
                                context,
                                if (wasFavorite) "已取消收藏" else "已收藏",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileLiveTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !selected, role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) LiveAccent else Color.Transparent),
        )
    }
}

@Composable
private fun MobileLiveFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 外层 48dp 触摸热区，内层保持原视觉胶囊高度，避免点到 chip 边缘时落到相邻频道行。
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) LiveAccent else LiveField)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun MobileLiveChannelRow(
    channel: LiveChannel,
    isPlaying: Boolean,
    isFavorite: Boolean,
    nowTitle: String?,
    nextTitle: String?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 用最小高度而不是固定高度：频道名/节目名三行文本在放大字号后会超过 64dp，
            // 固定高度会把"接下来…"整行裁掉。
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) LiveField else LiveCard)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) { onClick() }
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        MobileLiveChannelLogo(logo = channel.logo, name = channel.name, width = 62.dp, height = 44.dp, initialFontSize = 11.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = nowTitle ?: channel.groupName,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!nextTitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "接下来 $nextTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = 6.dp),
        ) {
            // 收藏星标使用 48dp 触摸热区（视觉 16sp），避免点按边缘时落进整行的频道点击区。
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onToggleFavorite() }
                    .semantics {
                        contentDescription =
                            if (isFavorite) "取消收藏 ${channel.name}" else "收藏 ${channel.name}"
                        stateDescription = if (isFavorite) "已收藏" else "未收藏"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFavorite) LiveFavoriteGold else Color.White.copy(alpha = 0.45f),
                )
            }
            if (isPlaying) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "直播中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LiveAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * C. 频道 Logo：底层先画首字占位，logo 非空时叠 Coil AsyncImage；
 * 加载中/失败时 AsyncImage 不绘制内容，占位自然透出（无需 SubcomposeAsyncImage）。
 * 台标为长方形（宽 > 高）。
 */
@Composable
private fun MobileLiveChannelLogo(
    logo: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    initialFontSize: TextUnit = 11.sp,
    stretch: Boolean = false,
) {
    val contentScale = if (stretch) ContentScale.FillBounds else ContentScale.Crop
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(LiveLogoPlaceholderBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = initialFontSize,
            color = Color.White.copy(alpha = 0.6f),
        )
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// B. 播放页
// ---------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun MobileLivePlayerScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    channel: LiveChannel,
    onBack: () -> Unit,
    onChannelPicked: (index: Int, number: Int) -> Unit,
) {
    KeepScreenOnWhileVisible()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // 播放页全屏沉浸（隐藏状态栏/导航栏），退出恢复。
    FullscreenWhilePlayingPageVisible(activity)
    // 播放页保持默认方向传感器；退出恢复进入前的方向锁定。
    val initialRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    DisposableEffect(activity, initialRequestedOrientation) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = initialRequestedOrientation }
    }

    val session = rememberPlaybackSession()
    val playbackHost = rememberPlaybackHost(state.appSettings.decoderStrategy)
    val player = playbackHost.player
    BindPlaybackSession(session, player)
    HardwareDecoderFallbackToast()
    // 解码策略变化会重建播放器：重连当前频道/线路时提示一次。
    var playerBuilt by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        if (playerBuilt) {
            Toast.makeText(context, "解码器已切换，正在重连当前线路", Toast.LENGTH_SHORT).show()
        }
        playerBuilt = true
    }

    val channels = state.liveChannels
    // 线路为空时 coerceIn(0, -1) 会抛异常；给出可重试的错误态，而不是闪退/黑屏。
    val lineIndex = state.liveLineIndex.coerceIn(0, channel.lines.lastIndex.coerceAtLeast(0))
    val currentLine = channel.lines.getOrNull(lineIndex)
    if (currentLine == null) {
        ErrorState(
            message = "当前频道没有可播放的线路，请刷新或更换直播源",
            onRetry = actions::refreshLive,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val latestChannel = rememberUpdatedState(channel)
    val latestLineUrl = rememberUpdatedState(currentLine.url)
    val latestChannelLabel = rememberUpdatedState("${channel.groupName}/${channel.name}")

    // 画面比例按频道记忆；未记忆时回退设置里的全局默认值。
    val scaleContentKey = liveVideoScaleKey(channel.groupName, channel.name)
    val videoScaleMode = state.appSettings.resolveVideoScaleMode(scaleContentKey)
    // 控件显隐统一走共享状态；竖屏常驻、横屏 4 秒后自动收起。
    val chrome = remember { PlayerChromeState(initialVisible = true) }
    // 播放信息只在面板打开时采样，关闭即停。
    val diagnostics = rememberPlayerDiagnostics(player, chrome.panel == PlayerPanel.Info)
    var channelBadgeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(isPortrait) { if (isPortrait) chrome.bump() }
    // 按钮文案要看"播放意图"而不是 isPlaying：缓冲/起播阶段 isPlaying 仍为 false，
    // 按 isPlaying 会显示"继续"，此时点一下实际会走暂停分支，语义相反。播放意图由共享宿主快照提供。
    // 所有线路都试过仍不可用：收起原生缓冲环，给出常驻的重试/换台出口。
    var streamBlocked by remember { mutableStateOf(false) }
    var blockedFailureKind by remember { mutableStateOf<PlaybackFailureKind?>(null) }
    val playbackWatchdog = remember { PlaybackWatchdog() }
    // 按线路暂存最近一次「建源 → 首帧」耗时，与线路测速一起帮助判断卡在哪一段。
    var lineFirstFrameMs by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    val isFavorite = actions.isLiveFavorite(channel)

    fun toggleFavoriteWithToast() {
        val wasFavorite = actions.isLiveFavorite(channel)
        actions.toggleLiveFavorite(channel)
        Toast.makeText(context, if (wasFavorite) "已取消收藏" else "已收藏", Toast.LENGTH_SHORT).show()
    }

    fun switchLineAfterIssue(reason: LiveLineFallbackReason) {
        if (!session.canPlay) return
        val latest = latestChannel.value
        if (latest.lines.size <= 1 && reason == LiveLineFallbackReason.Error) {
            // 单线频道硬错误：直接换台，避免手机端长时间黑屏。
            android.util.Log.w(
                MOBILE_LIVE_LOG_TAG,
                "【自动切换】单线频道硬错误，切换到下一台 channel=${latestChannelLabel.value}",
            )
            actions.playNextLiveChannel()
            return
        }
        android.util.Log.w(
            MOBILE_LIVE_LOG_TAG,
            "【线路容错】reason=$reason channel=${latestChannelLabel.value} url=${logSafeUrl(latestLineUrl.value)}",
        )
        if (!actions.advanceLiveLineAfterFailure(reason)) {
            // 兜底耗尽：不再无限转圈，交给用户选择重试或换台。
            blockedFailureKind = when {
                !playbackWatchdog.hasFirstFrame -> PlaybackFailureKind.FirstFrameTimeout
                reason == LiveLineFallbackReason.Error -> PlaybackFailureKind.SourceFailed
                else -> PlaybackFailureKind.Stalled
            }
            streamBlocked = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(
                    MOBILE_LIVE_LOG_TAG,
                    "【播放器错误】code=${error.errorCodeName} channel=${latestChannelLabel.value} url=${logSafeUrl(latestLineUrl.value)}",
                    error,
                )
                switchLineAfterIssue(LiveLineFallbackReason.Error)
            }

            override fun onRenderedFirstFrame() {
                playbackWatchdog.onFirstFrameRendered(System.currentTimeMillis())
                playbackWatchdog.firstFrameElapsedMs?.let { elapsed ->
                    lineFirstFrameMs = lineFirstFrameMs + (latestLineUrl.value to elapsed)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        streamBlocked = false
                        actions.notifyLivePlaybackReady()
                    }
                    Player.STATE_ENDED -> switchLineAfterIssue(LiveLineFallbackReason.Ended)
                    else -> Unit
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player, state.live.channelIndex, state.liveLineIndex, currentLine.url, state.liveReconnectNonce) {
        // 不同频道/线路可能共用同一 URL（同地址不同 Referer），只按 URL 比较会漏掉重新取流。
        android.util.Log.d(
            MOBILE_LIVE_LOG_TAG,
            "【切换线路】channel=${channel.name} line=$lineIndex protocol=${currentLine.protocolHint} nonce=${state.liveReconnectNonce}",
        )
        channelBadgeVisible = true
        streamBlocked = false
        blockedFailureKind = null
        playbackWatchdog.onSourceRequested(System.currentTimeMillis())
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(MOBILE_LIVE_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(MOBILE_LIVE_READ_TIMEOUT_MS)
        val safeHeaders = currentLine.headers
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() && value.none { ch -> ch == '\r' || ch == '\n' } }
        // User-Agent 属受限请求头，必须单独设置，其余走默认请求头。
        safeHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { httpFactory.setUserAgent(it.value) }
        val otherHeaders = safeHeaders.filter { !it.key.equals("User-Agent", ignoreCase = true) }
        if (otherHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(otherHeaders)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        // 无扩展名地址先探测真实 MIME，避免被当 progressive 流而必然黑屏。
        val mime = probeLiveLineMimeType(currentLine.url, safeHeaders)
        val mediaItem = MediaItem.Builder()
            .setUri(currentLine.url)
            .apply { if (mime.isNotBlank()) setMimeType(mime) }
            .build()
        player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        player.prepare()
        session.preparePlayback(player)
        delay(MOBILE_LIVE_BADGE_HIDE_MS)
        channelBadgeVisible = false
    }

    // 共享看门狗：首帧超时与出帧后卡死走同一套判定，可见时才计时。
    val watchdogLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(player, currentLine.url, watchdogLifecycleOwner) {
        watchdogLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playbackWatchdog.onPaused()
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val failure = playbackWatchdog.checkFirstFrame(
                    nowMs = now,
                    playWhenReady = player.playWhenReady && !session.interrupted,
                ) ?: playbackWatchdog.onProgressSample(
                    // 缓冲时 isPlaying=false；按播放意图判定才能捕获"出帧后卡死"。
                    isPlaying = player.playWhenReady,
                    positionMs = player.currentPosition,
                    nowMs = now,
                )
                if (failure != null) {
                    android.util.Log.w(
                        MOBILE_LIVE_LOG_TAG,
                        "【看门狗触发】kind=${failure.kind} channel=${latestChannelLabel.value} elapsedMs=${failure.elapsedMs}",
                    )
                    switchLineAfterIssue(LiveLineFallbackReason.Stall)
                }
            }
        }
    }

    // 横屏时控件 4s 自动隐藏（竖屏常驻）；单击画面唤出。
    PlayerChromeAutoHide(
        chrome,
        playing = true,
        autoHideMs = MOBILE_LIVE_CONTROLS_HIDE_MS,
        holdVisible = isPortrait,
    )

    // 播放器画面 + 遮罩控件：竖屏置于 16:9 区、横屏铺满，两处复用。
    val playerSurface: @Composable () -> Unit = {
        MobileLivePlayerSurface(
            exoPlayer = player,
            session = session,
            channel = channel,
            currentLine = currentLine,
            resizeMode = videoScaleMode,
            blockedFailureKind = blockedFailureKind,
            channelBadgeVisible = channelBadgeVisible,
            isPortrait = isPortrait,
            streamBlocked = streamBlocked,
            onRetryStream = {
                streamBlocked = false
                actions.reconnectLiveLine()
                chrome.bump()
            },
            onNextChannel = {
                streamBlocked = false
                actions.playNextLiveChannel()
            },
            onToggleControls = {
                if (!isPortrait) chrome.toggle()
            },
            controls = {
                PlayerChrome(
                    state = chrome,
                    data = PlayerChromeData(
                        title = channel.name,
                        subtitle = buildString {
                            append("线路 ${lineIndex + 1}/${channel.lines.size}")
                            if (currentLine.protocolHint.isNotBlank()) {
                                append(" · ${currentLine.protocolHint.uppercase()}")
                            }
                        },
                        playing = playbackHost.snapshot.playWhenReady && !session.interrupted,
                        resizeMode = videoScaleMode,
                        buffering = playbackHost.snapshot.isBuffering,
                        playbackInfo = diagnostics?.info,
                        decoderStrategy = state.appSettings.decoderStrategy,
                        decoderFallbackKinds = HardwareDecoderFallbackNotice.kinds,
                        playbackUrls = listOf(
                            PlayerChromeUrl(
                                label = "实际播放地址（播放列表线路）",
                                url = currentLine.url,
                            ),
                        ),
                    ),
                    capabilities = PlayerChromeCapabilities(
                        seek = false,
                        resize = true,
                        favorite = true,
                        favoriteLabel = "★",
                        favoriteSelected = isFavorite,
                        orientation = true,
                        mute = true,
                        settings = true,
                        channel = true,
                        info = true,
                        url = true,
                    ),
                    actions = PlayerChromeActions(
                        onToggle = {
                            // 走 session 记录播放意图：只用 player.pause() 会让策略仍认为用户想播，
                            // 切线路/重连后 preparePlayback 会把用户已暂停的直播重新拉起。
                            if (session.interrupted) {
                                player.seekToDefaultPosition()
                                session.play(player)
                            } else if (player.playWhenReady) {
                                session.pause(player)
                            } else {
                                session.play(player)
                            }
                            chrome.bump()
                        },
                        onPrevious = { actions.playPreviousLiveChannel(); chrome.bump() },
                        onNext = { actions.playNextLiveChannel(); chrome.bump() },
                        onSelectResize = { mode ->
                            actions.updateContentVideoScaleMode(scaleContentKey, mode)
                            chrome.bump()
                        },
                        onFavorite = ::toggleFavoriteWithToast,
                        onOrientation = {
                            activity?.requestedOrientation = if (isPortrait) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                            chrome.bump()
                        },
                        onMute = {
                            // 音量图标 = 静音/恢复；竖屏也可在右半屏上下滑调节。
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            if (am != null) {
                                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val muted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, if (muted) max / 2 else 0, 0)
                            }
                            chrome.bump()
                        },
                        onSettings = actions::openSettings,
                        onBack = onBack,
                        onInteraction = { chrome.bump() },
                    ),
                    touch = true,
                    fade = true,
                )
            },
        )
    }

    if (!isPortrait) {
        // 横屏全屏：仅播放区铺满（控件 4s 自动隐藏，单击唤出）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            playerSurface()
        }
        return
    }

    val epgPrograms = remember(channel, state.liveEpgByChannel) { actions.liveEpgPrograms(channel) }
    // 每 30s 刷新一次“当前时刻”，驱动当前节目高亮随时间移动。
    var epgNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val epgLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(epgLifecycleOwner) {
        // 退到后台后不再每 30 秒唤醒刷新节目单；恢复时立即刷新一次，
        // 否则“当前节目”会用后台前的旧时刻，可能指到已播完的节目。
        epgLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            epgNowMs = System.currentTimeMillis()
            while (true) {
                delay(MOBILE_LIVE_EPG_TICK_MS)
                epgNowMs = System.currentTimeMillis()
            }
        }
    }
    val currentEpgIndex = epgPrograms.indexOfFirst { epgNowMs in it.startMs until it.stopMs }
    val epgListState = rememberLazyListState()
    // 当前节目随 30 秒时钟变化：高亮换了，节目单也要跟着滚到新的节目行。
    LaunchedEffect(epgPrograms, currentEpgIndex) {
        if (epgPrograms.isNotEmpty() && currentEpgIndex > 0) epgListState.scrollToItem(currentEpgIndex)
    }

    // 相关频道：同组优先，不足补全局邻近。
    val relatedChannels = remember(channel, channels, state.appSettings.relatedChannelCount) {
        val selfIndex = channels.indexOfFirst { it.number == channel.number }
        val indexed = channels.withIndex().filter { it.index != selfIndex }
        val sameGroup = indexed.filter { it.value.groupName == channel.groupName }
        val others = indexed.filter { it.value.groupName != channel.groupName }
        (sameGroup + others).take(state.appSettings.relatedChannelCount)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveBackground),
    ) {
        // 1. 顶部 16:9 播放区。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            playerSurface()
        }

        // 2. 频道信息区（约 80dp）。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "★",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isFavorite) LiveFavoriteGold else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { toggleFavoriteWithToast() }
                        .semantics {
                            contentDescription = if (isFavorite) {
                                "取消收藏 ${channel.name}"
                            } else {
                                "收藏 ${channel.name}"
                            }
                        },
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(channel.lines) { index, line ->
                    val isSelected = index == lineIndex
                    val hasResult = state.liveLineLatencies.containsKey(line.url)
                    val latency = state.liveLineLatencies[line.url]
                    // 线路胶囊：48dp 触摸热区包裹，名称下方展示测速状态与该线路最近一次首帧耗时。
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) {
                                if (index != lineIndex) {
                                    actions.selectLiveLine(index)
                                    Toast.makeText(context, "已切换至${line.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .semantics { stateDescription = if (isSelected) "播放中" else "未选中" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) LiveAccent else LiveField)
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = if (isSelected) "${line.name}（播放中）" else line.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            )
                            Text(
                                text = buildString {
                                    append(liveLineLatencyLabel(hasResult, latency, state.liveLineTesting))
                                    lineFirstFrameMs[line.url]?.let { append(" · 首帧 ${formatFirstFrame(it)}") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = liveLineLatencyColor(hasResult, latency, state.liveLineTesting),
                            )
                        }
                    }
                }
                item {
                    // 测速只在用户点击时执行：不轮询，也不随面板开关自动触发。
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(LiveField)
                            .clickable(role = Role.Button, enabled = !state.liveLineTesting) {
                                actions.testLiveChannelLines()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.liveLineTesting) "测速中…" else "重新测速",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = if (state.liveLineTesting) 0.4f else 1f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LiveDivider),
        )

        // 3. EPG 节目单区。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("节目单", style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f))
            Text("今天", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        }
        if (epgPrograms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无节目单", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        } else {
            LazyColumn(
                state = epgListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(epgPrograms) { index, program ->
                    val isCurrent = index == currentEpgIndex
                    val minutes = (program.stopMs - program.startMs) / 60_000L
                    // 整行合并成一条播报：时间 + 节目名 + 时长，当前节目补「正在播放」，
                    // 避免读屏把三个文本节点逐条朗读、也避免听不出哪一条是当前节目。
                    val epgLabel = buildString {
                        append(formatMobileLiveClock(program.startMs)).append(' ')
                        append(program.title)
                        if (minutes > 0) append("，").append(minutes).append("分钟")
                        if (isCurrent) append("，正在播放")
                    }
                    Column {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(LiveDivider),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isCurrent) LiveEpgCurrentBg else Color.Transparent)
                                .semantics(mergeDescendants = true) { contentDescription = epgLabel }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatMobileLiveClock(program.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isCurrent) LiveAccent else Color.White.copy(alpha = 0.7f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = program.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val rowMinutes = minutes
                            if (rowMinutes > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${rowMinutes}分钟",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LiveDivider),
        )

        // 4. 相关频道区。
        Text(
            text = "相关频道",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 6.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(86.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(relatedChannels, key = { it.value.number }) { pair ->
                val related = pair.value
                // 提前取「正在播」用于整卡合并播报，避免读屏逐个朗读名称与节目。
                val relatedNow = actions.liveEpgNow(related)?.title
                Column(
                    modifier = Modifier
                        .width(100.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LiveCard)
                        .clickable(role = Role.Button) { onChannelPicked(pair.index, related.number) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (relatedNow.isNullOrBlank()) {
                                related.name
                            } else {
                                "${related.name}，正在播 $relatedNow"
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MobileLiveChannelLogo(logo = related.logo, name = related.name, width = 40.dp, height = 28.dp, initialFontSize = 9.sp)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = related.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    if (!relatedNow.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = relatedNow,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播放画面 + 遮罩控件（竖屏 16:9 区与横屏铺满共用）。
 * 竖屏控件常驻；横屏由外层控制 4s 自动隐藏、单击唤出。
 */
@OptIn(UnstableApi::class)
@Composable
private fun MobileLivePlayerSurface(
    exoPlayer: ExoPlayer,
    session: PlaybackSession,
    channel: LiveChannel,
    currentLine: LiveChannelLine,
    resizeMode: VideoScaleMode,
    blockedFailureKind: PlaybackFailureKind?,
    channelBadgeVisible: Boolean,
    isPortrait: Boolean,
    streamBlocked: Boolean,
    onRetryStream: () -> Unit,
    onNextChannel: () -> Unit,
    onToggleControls: () -> Unit,
    controls: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    // 竖滑手势会直接改写 Window 亮度，退出页面必须恢复进入前的设置（与点播播放页一致）。
    val initialScreenBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    // 手势提示（亮度/音量），1.2 秒后自动消失。
    var gesturePrompt by remember { mutableStateOf<String?>(null) }
    var gesturePromptNonce by remember { mutableIntStateOf(0) }
    PromptTimer(nonce = gesturePromptNonce, hideDelayMs = MOBILE_LIVE_GESTURE_PROMPT_MS) {
        gesturePrompt = null
    }

    // 触摸手势收敛到共享 PlayerTouchGestureHandler：本页只启用单击唤出与竖滑亮度/音量。
    val touchHandler = remember {
        PlayerTouchGestureHandler(
            activity = activity,
            audioManager = audioManager,
            capabilities = PlayerTouchCapabilities(
                seekDrag = false,
                doubleTapSeek = false,
                longPressSpeed = false,
                lock = false,
                verticalSwipeEnabled = isPortrait,
                verticalSwipeLoose = true,
            ),
            callbacks = PlayerTouchCallbacks(
                isLocked = { false },
                currentPositionMs = { 0L },
                durationMs = { null },
                onToggleControls = {},
                onLockedTap = {},
                onPlaybackToggle = {},
                onSeekBy = { _, _ -> },
                onSeekDragPreview = { _, _ -> },
                onSeekDragCommit = {},
                onBrightnessChanged = { _, _ -> },
                onVolumeChanged = { _, _ -> },
                onLongPressStart = {},
                onLongPressEnd = {},
            ),
        )
    }
    touchHandler.capabilities = PlayerTouchCapabilities(
        seekDrag = false,
        doubleTapSeek = false,
        longPressSpeed = false,
        lock = false,
        verticalSwipeEnabled = isPortrait,
        verticalSwipeLoose = true,
    )
    touchHandler.callbacks = PlayerTouchCallbacks(
        isLocked = { false },
        currentPositionMs = { 0L },
        durationMs = { null },
        onToggleControls = onToggleControls,
        onLockedTap = {},
        onPlaybackToggle = {},
        onSeekBy = { _, _ -> },
        onSeekDragPreview = { _, _ -> },
        onSeekDragCommit = {},
        onBrightnessChanged = { _, percent ->
            gesturePrompt = "亮度 $percent%"
            gesturePromptNonce++
        },
        onVolumeChanged = { volume, maxVolume ->
            gesturePrompt = "音量 $volume/$maxVolume"
            gesturePromptNonce++
        },
        onLongPressStart = {},
        onLongPressEnd = {},
    )
    DisposableEffect(touchHandler, activity, initialScreenBrightness) {
        onDispose {
            touchHandler.release()
            activity?.window?.restoreScreenBrightness(initialScreenBrightness)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        PlayerSurface(
            player = exoPlayer,
            resizeMode = resizeMode,
            showBuffering = !streamBlocked,
            configure = { view -> view.setOnTouchListener(touchHandler) },
        )

        // 线路全部不可用：常驻卡片承接反馈，提供「重试」与「下一台」两个出口。
        if (streamBlocked) {
            val kind = blockedFailureKind ?: PlaybackFailureKind.Stalled
            PlaybackStatusOverlay(
                status = PlaybackStatus.Failed(
                    kind = kind,
                    title = playbackFailureTitle(kind),
                    message = playbackFailureMessage(kind) + " 当前频道所有线路均已尝试。",
                    nextLabel = "下一台",
                ),
                onRetry = onRetryStream,
                onNext = onNextChannel,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 居中频道名徽标：● 红点 + 22sp 频道名，播放 2s 后隐藏。
        if (channelBadgeVisible && !session.interrupted && !streamBlocked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.Center)
                    // 红点符号会被读屏念成「黑色圆点」；徽标只播报频道名。
                    .clearAndSetSemantics { contentDescription = channel.name },
            ) {
                Text("●", color = LiveAccent, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 打断恢复：中央按钮 seekToDefaultPosition + play。
        // 原文字撑高约 26dp，触摸热区不足；统一抬到 48dp 并补按钮角色。
        if (session.interrupted) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(role = Role.Button) {
                        exoPlayer.seekToDefaultPosition()
                        session.play(exoPlayer)
                    }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "继续播放",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        controls()
    }
}

private val MOBILE_LIVE_CLOCK_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatMobileLiveClock(ms: Long): String = MOBILE_LIVE_CLOCK_FORMAT.format(Date(ms))

/** 直播兜底卡片里的操作按钮：触摸热区不小于 48dp，语义角色为按钮。 */
@Composable
private fun LiveBlockedAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(LiveAccent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
