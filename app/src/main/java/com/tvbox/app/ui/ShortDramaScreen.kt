package com.tvbox.app.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.ShortDrama
import com.tvbox.app.ui.theme.TvColors

/**
 * 短剧栏：数据来自红果短剧网页（`category_page.recommendList`），独立于视频源/直播源。
 *
 * 两种形态共用同一份状态：
 * - 列表态：网格展示短剧，底部/滚动到底加载下一页；
 * - 详情态：封面 + 简介 + 分集，点分集解析播放地址（不可用时降级提示，不阻塞浏览）。
 * 点「返回」从详情回到列表（由 [TvBoxViewModel.closeShortDramaDetail] 负责）。
 */
@Composable
fun ShortDramaScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val isMobile = state.effectiveDeviceMode == DeviceMode.Mobile
    val detail = state.shortDramaDetail
    if (detail != null || state.shortDramaDetailLoading || state.shortDramaDetailError != null) {
        ShortDramaDetailContent(state = state, actions = actions, isMobile = isMobile)
        return
    }
    ShortDramaListContent(state = state, actions = actions, isMobile = isMobile)
}

@Composable
private fun ShortDramaListContent(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    isMobile: Boolean,
) {
    val gridState = rememberLazyGridState()
    // 滚动接近底部时自动加载下一页（源分页 totalPages 由列表接口返回）。
    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) actions.loadShortDrama(reset = false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background),
    ) {
        // 标题栏：名称 + 数据来源说明 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isMobile) 16.dp else 28.dp,
                    end = if (isMobile) 16.dp else 28.dp,
                    top = if (isMobile) 12.dp else 20.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "短剧",
                    color = TvColors.TextPrimary,
                    fontSize = if (isMobile) 20.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (state.shortDramaItems.isEmpty()) "红果短剧" else
                        "红果短剧 · 共 ${state.shortDramaItems.size} 部 · 第 ${state.shortDramaPage}/${state.shortDramaTotalPages} 页",
                    color = TvColors.TextTertiary,
                    fontSize = 12.sp,
                )
            }
            ShortDramaActionButton(
                text = "刷新",
                enabled = !state.shortDramaLoading,
                onClick = { actions.loadShortDrama(reset = true) },
            )
        }

        when {
            state.shortDramaLoading && state.shortDramaItems.isEmpty() -> ShortDramaStateBlock("正在加载短剧…")
            state.shortDramaError != null && state.shortDramaItems.isEmpty() -> ShortDramaErrorBlock(
                message = state.shortDramaError,
                onRetry = { actions.loadShortDrama(reset = true) },
            )
            state.shortDramaItems.isEmpty() -> ShortDramaStateBlock("暂无短剧")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = if (isMobile) 104.dp else 148.dp),
                contentPadding = PaddingValues(
                    start = if (isMobile) 16.dp else 28.dp,
                    end = if (isMobile) 16.dp else 28.dp,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (isMobile) 10.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isMobile) 14.dp else 20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.shortDramaItems, key = { it.id }) { drama ->
                    ShortDramaCard(
                        drama = drama,
                        isMobile = isMobile,
                        onClick = { actions.openShortDramaDetail(drama) },
                    )
                }
            }
        }
    }
}

/** 短剧卡片：封面 2:3 + 标题 + 集数/更新状态。 */
@Composable
private fun ShortDramaCard(
    drama: ShortDrama,
    isMobile: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) TvColors.Accent else Color.Transparent,
                shape = shape,
            )
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A22)),
        ) {
            if (drama.cover.isNotBlank()) {
                AsyncImage(
                    model = drama.cover,
                    contentDescription = drama.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 右下角集数角标
            if (drama.remark.isNotBlank() || drama.episodeCount > 0) {
                Text(
                    text = drama.remark.ifBlank { "${drama.episodeCount}集" },
                    color = Color.White,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = drama.title,
            color = TvColors.TextPrimary,
            fontSize = if (isMobile) 12.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (drama.tags.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = drama.tags.take(2).joinToString(" · "),
                color = TvColors.TextTertiary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShortDramaDetailContent(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    isMobile: Boolean,
) {
    val detail = state.shortDramaDetail
    val horizontal = if (isMobile) 16.dp else 28.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(start = horizontal, end = horizontal, top = if (isMobile) 12.dp else 20.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShortDramaActionButton(text = "← 返回", enabled = true, onClick = actions::closeShortDramaDetail)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (detail != null) "短剧详情" else "短剧",
                color = TvColors.TextPrimary,
                fontSize = if (isMobile) 18.sp else 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))

        when {
            state.shortDramaDetailLoading -> ShortDramaStateBlock("正在加载详情…")
            state.shortDramaDetailError != null -> ShortDramaErrorBlock(
                message = state.shortDramaDetailError,
                onRetry = {
                    // 详情失败时退回列表，让用户换一部剧。
                    actions.closeShortDramaDetail()
                },
                retryLabel = "返回列表",
            )
            detail != null -> {
                // 头部：封面 + 元信息
                Row {
                    Box(
                        modifier = Modifier
                            .width(if (isMobile) 96.dp else 140.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1A22)),
                    ) {
                        if (detail.drama.cover.isNotBlank()) {
                            AsyncImage(
                                model = detail.drama.cover,
                                contentDescription = detail.drama.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detail.drama.title,
                            color = TvColors.TextPrimary,
                            fontSize = if (isMobile) 17.sp else 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append("共 ${detail.drama.episodeCount} 集")
                                if (detail.episodeIds.isNotEmpty()) append(" · 可播 ${detail.episodeIds.size} 集")
                                if (detail.drama.remark.isNotBlank()) append(" · ${detail.drama.remark}")
                            },
                            color = TvColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        if (detail.drama.tags.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = detail.drama.tags.take(6).joinToString(" / "),
                                color = TvColors.TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (detail.drama.intro.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = detail.drama.intro,
                        color = TvColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选集",
                        color = TvColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.shortDramaPlayUrlLoading) {
                        Text("正在解析播放地址…", color = TvColors.TextTertiary, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (detail.episodeIds.isEmpty()) {
                    Text(
                        text = "该剧暂未提供分集数据",
                        color = TvColors.TextTertiary,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(detail.episodeIds) { index, _ ->
                            val selected = index == state.shortDramaEpisodeIndex
                            var focused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) TvColors.Accent else TvColors.SurfaceRaised)
                                    .border(
                                        width = if (focused) 2.dp else 0.dp,
                                        color = if (focused) TvColors.Accent else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .onFocusChanged { focused = it.isFocused }
                                    .focusable()
                                    .clickable { actions.playShortDramaEpisode(index) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = "第${index + 1}集",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // 播放结果：成功给出地址（交由播放器打开），失败给出可读原因，不阻塞浏览。
                state.shortDramaPlayUrlError?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(text = error, color = TvColors.Danger, fontSize = 12.sp)
                }
                state.shortDramaPlayUrl?.let { url ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "第${state.shortDramaEpisodeIndex + 1}集播放地址已就绪",
                        color = TvColors.Success,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = url,
                        color = TvColors.TextTertiary,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortDramaActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) TvColors.Accent.copy(alpha = 0.22f) else TvColors.SurfaceRaised)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) TvColors.Accent else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = if (enabled) TvColors.TextPrimary else TvColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ShortDramaStateBlock(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = TvColors.TextTertiary, fontSize = 13.sp)
    }
}

@Composable
private fun ShortDramaErrorBlock(
    message: String,
    onRetry: () -> Unit,
    retryLabel: String = "重试",
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = TvColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            ShortDramaActionButton(text = retryLabel, enabled = true, onClick = onRetry)
        }
    }
}
