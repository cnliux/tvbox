package com.tvbox.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
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
import com.tvbox.app.data.liveFavoriteKey
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
import kotlin.math.abs
import kotlin.math.roundToInt
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

/** 无系统亮度可读时的兜底初始值。 */
private const val MOBILE_LIVE_DEFAULT_BRIGHTNESS = 0.5f

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

@Composable
private fun MobileLiveListScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onOpenPlatformLive: () -> Unit,
    onChannelClick: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<String?>(null) } // null = 全部
    var favoritesOnly by remember { mutableStateOf(false) }
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
                (!favoritesOnly || state.liveFavoriteKeys.contains(liveFavoriteKey(channel.groupName, channel.name)))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveBackground),
    ) {
        // 1. 顶部搜索栏 52dp（edge-to-edge：自行吸收状态栏高度，保持状态栏可见）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
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
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(LiveAccent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { query = "" }
                                .padding(4.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 收藏筛选开关（点按只显示收藏频道）。
            Text(
                text = "★",
                fontSize = 20.sp,
                color = if (favoritesOnly) LiveFavoriteGold else Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { favoritesOnly = !favoritesOnly },
            )
            // 设置入口。
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
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
                    Text("正在加载...", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
            state.liveError != null || channels.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = if (state.liveError != null) "直播源加载失败" else "没有可用直播频道",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (state.liveError != null) "请检查网络，或切换其他直播源后重试" else "可在设置中添加或恢复直播源",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(LiveAccent)
                            .clickable { actions.refreshLive() }
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("重试", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📺", fontSize = 30.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("该分组暂无频道", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("切换其他分组看看", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
            else -> LazyColumn(
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
            .clickable(enabled = !selected, onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
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
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
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
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        MobileLiveChannelLogo(logo = channel.logo, name = channel.name, width = 62.dp, height = 44.dp, initialFontSize = 11.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = nowTitle ?: channel.groupName,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!nextTitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "接下来 $nextTitle",
                    fontSize = 10.sp,
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
                    .clickable { onToggleFavorite() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "★",
                    fontSize = 16.sp,
                    color = if (isFavorite) LiveFavoriteGold else Color.White.copy(alpha = 0.45f),
                )
            }
            if (isPlaying) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "直播中",
                    fontSize = 9.sp,
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
    val playbackHost = rememberPlaybackHost()
    val player = playbackHost.player
    BindPlaybackSession(session, player)

    val channels = state.liveChannels
    val lineIndex = state.liveLineIndex.coerceIn(0, channel.lines.lastIndex)
    val currentLine = channel.lines[lineIndex]
    val latestChannel = rememberUpdatedState(channel)
    val latestLineUrl = rememberUpdatedState(currentLine.url)
    val latestChannelLabel = rememberUpdatedState("${channel.groupName}/${channel.name}")

    // 画面比例按频道记忆；未记忆时回退设置里的全局默认值。
    val scaleContentKey = liveVideoScaleKey(channel.groupName, channel.name)
    val videoScaleMode = state.appSettings.resolveVideoScaleMode(scaleContentKey)
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var channelBadgeVisible by remember { mutableStateOf(true) }
    // 按钮文案要看"播放意图"而不是 isPlaying：缓冲/起播阶段 isPlaying 仍为 false，
    // 按 isPlaying 会显示"继续"，此时点一下实际会走暂停分支，语义相反。播放意图由共享宿主快照提供。
    // 所有线路都试过仍不可用：收起原生缓冲环，给出常驻的重试/换台出口。
    var streamBlocked by remember { mutableStateOf(false) }
    var blockedFailureKind by remember { mutableStateOf<PlaybackFailureKind?>(null) }
    val playbackWatchdog = remember { PlaybackWatchdog() }

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

    LaunchedEffect(state.live.channelIndex, state.liveLineIndex, currentLine.url, state.liveReconnectNonce) {
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
    LaunchedEffect(isPortrait, controlsVisible, controlsInteraction) {
        if (isPortrait || !controlsVisible) return@LaunchedEffect
        delay(MOBILE_LIVE_CONTROLS_HIDE_MS)
        controlsVisible = false
    }

    // 播放器画面 + 遮罩控件：竖屏置于 16:9 区、横屏铺满，两处复用。
    val playerSurface: @Composable () -> Unit = {
        MobileLivePlayerSurface(
            exoPlayer = player,
            session = session,
            channel = channel,
            currentLine = currentLine,
            resizeMode = videoScaleMode,
            blockedFailureKind = blockedFailureKind,
            controlsVisible = if (isPortrait) true else controlsVisible,
            channelBadgeVisible = channelBadgeVisible,
            isFavorite = isFavorite,
            isPlaying = playbackHost.snapshot.playWhenReady,
            isPortrait = isPortrait,
            streamBlocked = streamBlocked,
            onRetryStream = {
                streamBlocked = false
                actions.reconnectLiveLine()
                controlsInteraction++
            },
            onNextChannel = {
                streamBlocked = false
                actions.playNextLiveChannel()
            },
            onToggleControls = {
                if (!isPortrait) {
                    controlsVisible = !controlsVisible
                    if (controlsVisible) controlsInteraction++
                }
            },
            onBack = onBack,
            onToggleFavorite = ::toggleFavoriteWithToast,
            onToggleOrientation = {
                activity?.requestedOrientation = if (isPortrait) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                controlsInteraction++
            },
            onToggleZoom = {
                val next = if (videoScaleMode == VideoScaleMode.Zoom) {
                    VideoScaleMode.Fit
                } else {
                    VideoScaleMode.Zoom
                }
                actions.updateContentVideoScaleMode(scaleContentKey, next)
                controlsInteraction++
            },
            onTogglePause = {
                // 走 session 记录播放意图：只用 player.pause() 会让策略仍认为用户想播，
                // 切线路/重连后 preparePlayback 会把用户已暂停的直播重新拉起。
                if (player.playWhenReady) session.pause(player) else session.play(player)
                controlsInteraction++
            },
            onToggleMute = {
                // 音量图标 = 静音/恢复；竖屏也可在右半屏上下滑调节。
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val muted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, if (muted) max / 2 else 0, 0)
                }
                controlsInteraction++
            },
            onOpenSettings = actions::openSettings,
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
    LaunchedEffect(epgPrograms) {
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
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "★",
                    fontSize = 20.sp,
                    color = if (isFavorite) LiveFavoriteGold else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { toggleFavoriteWithToast() },
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(channel.lines) { index, line ->
                    val isSelected = index == lineIndex
                    // 线路胶囊：48dp 触摸热区包裹，视觉保持 32dp 最小高度，避免误触相邻频道/节目区。
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (index != lineIndex) {
                                    actions.selectLiveLine(index)
                                    Toast.makeText(context, "已切换至${line.name}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = line.name,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) LiveAccent else LiveField)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
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
            Text("节目单", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text("今天", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
        }
        if (epgPrograms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无节目单", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
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
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatMobileLiveClock(program.startMs),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isCurrent) LiveAccent else Color.White.copy(alpha = 0.7f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = program.title,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val minutes = (program.stopMs - program.startMs) / 60_000L
                            if (minutes > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${minutes}分钟",
                                    fontSize = 10.sp,
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
            fontSize = 12.sp,
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
                Column(
                    modifier = Modifier
                        .width(100.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LiveCard)
                        .clickable { onChannelPicked(pair.index, related.number) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MobileLiveChannelLogo(logo = related.logo, name = related.name, width = 40.dp, height = 28.dp, initialFontSize = 9.sp)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = related.name,
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    val relatedNow = actions.liveEpgNow(related)?.title
                    if (!relatedNow.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = relatedNow,
                            fontSize = 9.sp,
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
    controlsVisible: Boolean,
    channelBadgeVisible: Boolean,
    isFavorite: Boolean,
    isPlaying: Boolean,
    isPortrait: Boolean,
    streamBlocked: Boolean,
    onRetryStream: () -> Unit,
    onNextChannel: () -> Unit,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleZoom: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    // 手势提示（亮度/音量），1.2 秒后自动消失。
    var gesturePrompt by remember { mutableStateOf<String?>(null) }
    var gesturePromptNonce by remember { mutableIntStateOf(0) }
    PromptTimer(nonce = gesturePromptNonce, hideDelayMs = MOBILE_LIVE_GESTURE_PROMPT_MS) {
        gesturePrompt = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isPortrait) {
                // 竖屏：上下滑调亮度/音量（左半屏亮度、右半屏音量，沿用播放页既有约定）；
                // 未滑动的单击仍然唤出/收起控件。
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val startBrightness = activity?.window?.currentScreenBrightness(context)
                        ?: MOBILE_LIVE_DEFAULT_BRIGHTNESS
                    val startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 抬起时未越过阈值 = 单击。
                            if (!dragging) onToggleControls()
                            break
                        }
                        val totalDy = change.position.y - down.position.y
                        if (!dragging && abs(totalDy) > touchSlop) dragging = true
                        if (!dragging) continue
                        if (!isPortrait) {
                            // 横屏不接管上下滑，交给外层控件显隐逻辑。
                            break
                        }
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        if (downX < size.width / 2f) {
                            val target = (startBrightness - totalDy / height).coerceIn(0.01f, 1f)
                            activity?.window?.setScreenBrightness(target)
                            gesturePrompt = "亮度 ${(target * 100).roundToInt()}%"
                        } else if (maxVolume > 0) {
                            val delta = (totalDy / height * maxVolume).roundToInt()
                            val target = (startVolume - delta).coerceIn(0, maxVolume)
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            gesturePrompt = "音量 $target/$maxVolume"
                        }
                        gesturePromptNonce++
                        change.consume()
                    }
                }
            },
    ) {
        PlayerSurface(
            player = exoPlayer,
            resizeMode = resizeMode,
            showBuffering = !streamBlocked,
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
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text("●", color = LiveAccent, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = channel.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 打断恢复：中央按钮 seekToDefaultPosition + play。
        if (session.interrupted) {
            Text(
                text = "继续播放",
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable {
                        exoPlayer.seekToDefaultPosition()
                        session.play(exoPlayer)
                    }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }

        if (controlsVisible) {
            // 上渐变遮罩：左返回、右 铺满/收藏/横屏切换。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.6f),
                            1f to Color.Transparent,
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 铺满/原始比例（RESIZE_MODE_ZOOM / FIT）。
                    Text(
                        text = if (resizeMode == VideoScaleMode.Zoom) "原始" else "铺满",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable { onToggleZoom() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "★",
                        fontSize = 19.sp,
                        color = if (isFavorite) LiveFavoriteGold else Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onToggleFavorite() },
                    )
                    // ⛶：竖屏强制横屏 / 横屏恢复传感器。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onToggleOrientation() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⛶", fontSize = 17.sp, color = Color.White)
                    }
                }
            }

            // 下渐变遮罩：左直播状态（协议大写），右暂停/继续（音量、设置暂无对应功能，隐藏）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val protocol = currentLine.protocolHint
                    Text(
                        text = if (protocol.isNotBlank()) "● 直播中 · ${protocol.uppercase()}" else "● 直播中",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (isPlaying) "⏸ 暂停" else "▶ 继续",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable { onTogglePause() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 音量：静音/恢复（竖屏也可在右半屏上下滑调音量）。
                    Text(
                        text = "🔊",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onToggleMute() },
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    // 设置：进入设置页（音量/画质等入口与项目现有设置页一致）。
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
            }
        }
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
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
