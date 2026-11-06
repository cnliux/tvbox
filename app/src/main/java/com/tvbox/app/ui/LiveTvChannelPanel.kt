package com.tvbox.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.domain.LiveChannel
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.Key as ComposeKey

/**
 * 电视直播页浮层：半透明频道菜单（左 340dp）+ 线路浮层。
 *
 * 交互模型（沿用项目 onPreviewKeyEvent 按键通道，不引入新框架）：
 * 浮层根节点持有唯一焦点，方向键由根节点统一解释后落到「分组下标 / 频道行下标」，
 * 行高亮直接由下标驱动。相比多焦点区域互相抢焦点，这种单向解释在
 * 「左右切分组 + 上下选频道」的二维移动下不会出现焦点丢失。
 *
 * 分组 Tab 严格对应源里的 `#genre#` / `group-title` 平铺一级；
 * 额外只在最前面加一个「全部」浏览入口，不对应源里的二级分类。
 */

/** 浮层里频道行焦点变化时的回调：用于 EPG 卡跟随预览，不改变播放目标。 */
internal typealias LiveChannelPreview = (LiveChannel?) -> Unit

@Composable
internal fun LiveChannelOverlayPanel(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onClose: () -> Unit,
    onChannelPicked: (Int) -> Unit,
    onInteraction: () -> Unit,
    onPreview: LiveChannelPreview = {},
) {
    val channels = state.liveChannels
    val groups = remember(channels) { listOf(LIVE_ALL_GROUP) + channels.map { it.groupName }.distinct() }

    // 打开浮层时落在当前播放频道所在分组，避免用户先翻一遍分组。
    var groupIndex by remember {
        mutableIntStateOf(
            channels.getOrNull(state.liveChannelIndex)
                ?.let { groups.indexOf(it.groupName) }
                ?.coerceAtLeast(0)
                ?: 0,
        )
    }
    val visibleChannels = remember(channels, groupIndex, groups) {
        if (groupIndex <= 0) channels.withIndex().toList()
        else {
            val groupName = groups.getOrNull(groupIndex)
            channels.withIndex().filter { it.value.groupName == groupName }
        }
    }
    // 行焦点：进入浮层时优先落在"正在播放"那一行，否则首行。
    var focusedIndex by remember(groupIndex, visibleChannels) {
        val playingNumber = channels.getOrNull(state.liveChannelIndex)?.number
        mutableIntStateOf(
            visibleChannels.indexOfFirst { it.value.number == playingNumber }.takeIf { it >= 0 } ?: 0,
        )
    }

    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val panelFocusRequester = remember { FocusRequester() }

    // 焦点行滚进可视区；分组 Tab 同步滚到选中项。
    LaunchedEffect(focusedIndex, visibleChannels) {
        if (visibleChannels.isNotEmpty()) {
            channelListState.animateScrollToItem(focusedIndex.coerceIn(0, visibleChannels.lastIndex))
        }
        onPreview(visibleChannels.getOrNull(focusedIndex)?.value)
    }
    LaunchedEffect(groupIndex) {
        groupListState.animateScrollToItem(groupIndex.coerceIn(0, (groups.lastIndex).coerceAtLeast(0)))
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { panelFocusRequester.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩 rgba(0,0,0,0.35)：画面轻微变暗但仍清晰。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) {
                    // 点击遮罩 = 收起浮层（与返回键一致）。
                    onInteraction()
                    onClose()
                },
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(340.dp)
                .background(LiveTvPanelBg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .focusRequester(panelFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    // 浮层打开期间方向键全部由本节点解释，避免外层再触发换台。
                    when (event.key) {
                        ComposeKey.DirectionLeft -> {
                            if (groupIndex > 0) groupIndex--
                            onInteraction()
                            true
                        }
                        ComposeKey.DirectionRight -> {
                            if (groupIndex < groups.lastIndex) groupIndex++
                            onInteraction()
                            true
                        }
                        ComposeKey.DirectionUp -> {
                            if (visibleChannels.isNotEmpty()) {
                                focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                            }
                            onInteraction()
                            true
                        }
                        ComposeKey.DirectionDown -> {
                            if (visibleChannels.isNotEmpty()) {
                                focusedIndex = (focusedIndex + 1).coerceAtMost(visibleChannels.lastIndex)
                            }
                            onInteraction()
                            true
                        }
                        ComposeKey.DirectionCenter, ComposeKey.Enter -> {
                            val picked = visibleChannels.getOrNull(focusedIndex)
                            if (picked != null) onChannelPicked(picked.index) else onInteraction()
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            // ── 标题行 ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "频道列表",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "确认键收起",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            // ── 分组胶囊：高 36dp，横滑 ───────────────────────────────────
            LazyRow(
                state = groupListState,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 1.dp),
            ) {
                itemsIndexed(groups) { index, group ->
                    LiveGroupPill(
                        label = group,
                        selected = index == groupIndex,
                        onClick = {
                            if (groupIndex != index) {
                                groupIndex = index
                                onInteraction()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            // ── 频道列表：行高 44dp ──────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visibleChannels.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("📺", fontSize = 26.sp, color = Color.White.copy(alpha = 0.25f))
                        Spacer(Modifier.height(8.dp))
                        Text("该分组暂无频道", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(visibleChannels, key = { _, entry -> entry.value.number }) { indexInVisible, entry ->
                            val channel = entry.value
                            LiveOverlayChannelRow(
                                channel = channel,
                                isFocused = indexInVisible == focusedIndex,
                                isPlaying = entry.index == state.liveChannelIndex,
                                isFavorite = actions.isLiveFavorite(channel),
                                nowTitle = actions.liveEpgNow(channel)?.title,
                                onClick = { onChannelPicked(entry.index) },
                            )
                        }
                    }
                }
            }
            // ── 底部快捷键提示：36dp 半透明黑 ────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiveKeyHint("◀▶", "切分组")
                LiveKeyHint("▲▼", "选频道")
                LiveKeyHint("确认", "播放/收起")
                LiveKeyHint("返回", "关闭")
            }
        }
    }
}

/** 分组胶囊：选中红底白字；焦点态（即当前选中项）红色描边 + 放大 1.04。 */
@Composable
private fun LiveGroupPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = tween(120),
        label = "live-group-pill-scale",
    )
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) LiveTvAccent else Color.White.copy(alpha = 0.08f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) LiveTvAccent else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 浮层频道行（44dp）：焦点行左侧 3dp 红竖条 + 红 0.12 底 + 放大 1.02；
 * 当前播放行红 0.05 底 + 频道名加粗。
 */
@Composable
private fun LiveOverlayChannelRow(
    channel: LiveChannel,
    isFocused: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    nowTitle: String?,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = tween(120),
        label = "live-row-scale",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isFocused -> LiveTvFocusBg
                    isPlaying -> LiveTvPlayingBg
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 焦点红竖条 / 非焦点透明占位，保证文字不横向跳动。
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isFocused) LiveTvAccent else Color.Transparent),
        )
        Spacer(Modifier.width(7.dp))
        // 台标加大：28dp -> 36dp（行高 44dp，上下各留 4dp），首字占位字号同步放大。
        LiveChannelLogo(logo = channel.logo, name = channel.name, size = 36.dp, initialFontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nowTitle ?: channel.groupName,
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        if (isFavorite) {
            Text("★", color = Color(0xFFFFB300), fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = channel.number.toString(),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/** 快捷键提示：键帽 + 说明。 */
@Composable
private fun LiveKeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = action,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

/**
 * 线路浮层：保留原有"手动切线路 / 复制 URL / 查看真实 URL"能力，
 * 由底部「⇄ 线路」按钮打开，与频道浮层互斥。
 */
@Composable
internal fun LiveLineOverlay(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    channel: LiveChannel,
    onClose: () -> Unit,
    onCopyUrl: () -> Unit,
    onViewUrl: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focusRequester.requestFocus() }
        // 展开线路即测速：让用户按实际延迟挑线路，而不是逐条点播试错。
        actions.testLiveChannelLines()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        var pickedIndex by remember { mutableIntStateOf(state.liveLineIndex.coerceIn(0, channel.lines.lastIndex)) }
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LiveTvPanelBg)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when (event.key) {
                        ComposeKey.DirectionUp -> {
                            pickedIndex = (pickedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        ComposeKey.DirectionDown -> {
                            pickedIndex = (pickedIndex + 1).coerceAtMost(channel.lines.lastIndex)
                            true
                        }
                        ComposeKey.DirectionCenter, ComposeKey.Enter -> {
                            actions.selectLiveLine(pickedIndex)
                            onClose()
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { }
                .padding(16.dp),
        ) {
            Text(
                text = "切换线路 · ${channel.name}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.height(220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(channel.lines) { index, line ->
                    val isPicked = index == pickedIndex
                    val isPlaying = index == state.liveLineIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    isPicked -> LiveTvFocusBg
                                    isPlaying -> LiveTvPlayingBg
                                    else -> Color.White.copy(alpha = 0.04f)
                                },
                            )
                            .border(
                                width = if (isPicked) 2.dp else 0.dp,
                                color = if (isPicked) LiveTvAccent else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                actions.selectLiveLine(index)
                                onClose()
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = line.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 测速结果：null=超时，进行中显示"测速中"。
                        val latency = state.liveLineLatencies[line.url]
                        if (state.liveLineTesting) {
                            Text(
                                text = "测速中",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else if (state.liveLineLatencies.isNotEmpty()) {
                            Text(
                                text = latency?.let { "${it}ms" } ?: "超时",
                                color = when {
                                    latency == null -> Color(0xFFFF7171)
                                    // 200ms 内视为流畅，800ms 内一般，更慢标黄提示。
                                    latency < 200L -> Color(0xFF1ED760)
                                    latency < 800L -> Color.White.copy(alpha = 0.7f)
                                    else -> Color(0xFFFFB300)
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (isPlaying) {
                            Box(Modifier.size(4.dp).background(LiveTvAccent, CircleShape))
                            Spacer(Modifier.width(4.dp))
                            Text("播放中", color = LiveTvAccent, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton("复制 URL", onCopyUrl)
                ControlButton("查看 URL", onViewUrl)
                ControlButton("重新测速", { actions.testLiveChannelLines() })
                ControlButton("关闭", onClose)
            }
        }
    }
}
