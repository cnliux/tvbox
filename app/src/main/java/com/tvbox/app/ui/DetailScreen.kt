package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PlayEpisode
import com.tvbox.app.domain.PlaySource
import com.tvbox.app.ui.components.CategoryPill
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.InfoLine
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.SmallMeta
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvLayout

/** 选集网格顶部「选集/排序」行占用的 item 数，用于把选集下标换算成网格 item 下标。 */
private const val EPISODE_HEADER_ITEM_COUNT = 1

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    PageSurface { padding ->
        when {
            state.detailLoading -> LoadingState(text = "正在加载详情", modifier = Modifier.padding(padding))
            state.detailError != null -> ErrorState(
                message = state.detailError,
                onRetry = actions::retryCurrent,
                // 明确失败态并给出「返回」出口：请求失败不能只留「重试」，也不能静默退回首页。
                title = "详情加载失败",
                secondaryActionLabel = "返回",
                onSecondaryAction = { actions.goBack() },
                modifier = Modifier.padding(padding),
            )
            // 这里不是「可重试」的错误：详情为空时重试只会拿同一请求再查一次，
            // 正确动作是退回来源页重新选择影片，因此用空态而不是带「重试」按钮的错误态。
            state.detailMovie == null -> EmptyState(
                title = "影片详情为空",
                subtitle = "请返回列表重新选择影片",
                actionLabel = "返回",
                onAction = actions::goBack,
                modifier = Modifier.padding(padding),
            )
            else -> {
                val movie = state.detailMovie
                BoxWithConstraints(
                    // 手机版为 edge-to-edge 全屏页，吸收状态栏/手势条安全区，避免顶部海报与标题被遮挡。
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(padding),
                ) {
                    val isMobile = state.effectiveDeviceMode == DeviceMode.Mobile
                    val compact = isMobile || maxHeight < 560.dp
                    val posterWidth = if (compact) 118.dp else 210.dp
                    val horizontalGap = if (compact) 16.dp else 28.dp
                    val headerMaxHeight = if (compact) 190.dp else 320.dp
                    val descriptionLines = if (compact) 2 else 5
                    val sectionGap = if (compact) 12.dp else 24.dp
                    // 手机端把头部信息作为列表首项参与整页滚动：旧布局把主演/导演/地区/语言/简介
                    // 塞进「海报高度范围内、最多 190dp 高」的右侧小列，超出部分被直接裁掉，
                    // 只剩一块不明显的内部滚动区；竖屏大字号与横屏（可用高度更小）都会看不全。
                    if (isMobile) {
                        MobileDetailContent(
                            movie = movie,
                            state = state,
                            actions = actions,
                            posterWidth = posterWidth,
                            horizontalGap = horizontalGap,
                            sectionGap = sectionGap,
                        )
                    } else {
                        val selectedSource = movie.playSources.getOrNull(state.selectedSourceIndex)
                        val canPlay = selectedSource?.episodes?.isNotEmpty() == true
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = headerMaxHeight),
                            ) {
                                PosterImage(
                                    movie = movie,
                                    modifier = Modifier
                                        .width(posterWidth)
                                        .aspectRatio(2f / 3f),
                                )
                                Spacer(modifier = Modifier.width(horizontalGap))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = movie.name,
                                                style = MaterialTheme.typography.headlineLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                SmallMeta(movie.typeName)
                                                SmallMeta(movie.year)
                                                SmallMeta(movie.remarks)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Button(
                                                onClick = {
                                                    actions.openPlayer(
                                                        state.selectedSourceIndex,
                                                        state.selectedEpisodeIndex,
                                                    )
                                                },
                                                enabled = canPlay,
                                            ) {
                                                Text("立即播放")
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(onClick = actions::goBack) {
                                                Text("返回")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(if (compact) 10.dp else 18.dp))
                                    InfoLine(label = "主演", value = movie.actor)
                                    InfoLine(label = "导演", value = movie.director)
                                    InfoLine(label = "地区", value = movie.area)
                                    InfoLine(label = "语言", value = movie.language)
                                    Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
                                    Text(
                                        text = movie.description.ifBlank { "暂无简介" },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = descriptionLines,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(sectionGap))
                            EpisodeGrid(
                                source = movie.playSources.getOrNull(state.selectedSourceIndex),
                                selectedEpisodeIndex = state.selectedEpisodeIndex,
                                episodeSortReverse = state.appSettings.episodeSortReverse,
                                onToggleSort = { actions.toggleEpisodeSortReverse() },
                                onEpisode = { episodeIndex -> actions.openPlayer(state.selectedSourceIndex, episodeIndex) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 手机端详情页：头部（海报 + 标题 + 操作按钮 + 影片信息 + 简介）与选集放进同一个
 * LazyVerticalGrid，整页只有一个滚动容器。头部不再受海报高度和固定上限约束，
 * 竖屏、横屏与大字号下都能滚动看到完整信息，也不会剩下一小块不明显的内部滚动区。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileDetailContent(
    movie: Movie,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    posterWidth: Dp,
    horizontalGap: Dp,
    sectionGap: Dp,
) {
    val selectedSource = movie.playSources.getOrNull(state.selectedSourceIndex)
    val canPlay = selectedSource?.episodes?.isNotEmpty() == true
    EpisodeGrid(
        source = selectedSource,
        selectedEpisodeIndex = state.selectedEpisodeIndex,
        episodeSortReverse = state.appSettings.episodeSortReverse,
        onToggleSort = { actions.toggleEpisodeSortReverse() },
        // 头部信息作为首项参与同一次滚动，避免嵌套滚动把影片信息裁掉。
        header = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    PosterImage(
                        movie = movie,
                        modifier = Modifier
                            .width(posterWidth)
                            .aspectRatio(2f / 3f),
                    )
                    Spacer(modifier = Modifier.width(horizontalGap))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = movie.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SmallMeta(movie.typeName)
                            SmallMeta(movie.year)
                            SmallMeta(movie.remarks)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    actions.openPlayer(
                                        state.selectedSourceIndex,
                                        state.selectedEpisodeIndex,
                                    )
                                },
                                enabled = canPlay,
                            ) {
                                Text("立即播放")
                            }
                            Button(onClick = actions::goBack) {
                                Text("返回")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(sectionGap))
                // 影片信息全宽展示，不再挤在海报右侧的窄列里被省略号截断。
                InfoLine(label = "主演", value = movie.actor)
                InfoLine(label = "导演", value = movie.director)
                InfoLine(label = "地区", value = movie.area)
                InfoLine(label = "语言", value = movie.language)
                Spacer(modifier = Modifier.height(8.dp))
                // 简介跟随整页滚动，不再用 maxLines 截断。
                Text(
                    text = movie.description.ifBlank { "暂无简介" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        onEpisode = { episodeIndex -> actions.openPlayer(state.selectedSourceIndex, episodeIndex) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EpisodeGrid(
    source: PlaySource?,
    selectedEpisodeIndex: Int,
    episodeSortReverse: Boolean = false,
    onToggleSort: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    onEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = source?.episodes.orEmpty()
    if (episodes.isEmpty()) {
        if (header == null) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前播放源暂无剧集", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        // 手机端没有剧集时头部信息仍要完整展示，空状态作为列表项跟随整页滚动。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TvLayout.EpisodeGridMinWidth),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                header()
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("当前播放源暂无剧集", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val leadingItemCount = EPISODE_HEADER_ITEM_COUNT + if (header != null) 1 else 0
    val displayedEpisodes = if (episodeSortReverse) episodes.asReversed() else episodes
    val safeSelectedEpisodeIndex = if (episodeSortReverse) {
        (episodes.size - 1 - selectedEpisodeIndex).coerceIn(0, displayedEpisodes.lastIndex)
    } else {
        selectedEpisodeIndex.coerceIn(0, episodes.lastIndex)
    }
    val gridState = rememberLazyGridState()
    // 选集列表变化（切换线路）或正倒序切换后，按当前显示顺序重建焦点请求器。
    val focusRequesters = remember(displayedEpisodes) {
        List(displayedEpisodes.size) { FocusRequester() }
    }
    // 触摸模式（手机）由用户自己点选集，不主动抢焦点；否则进详情页会莫名出现焦点框，
    // 从播放页返回时还会把用户已滚动的位置拉回选中集。
    val touchMode = LocalTouchMode.current

    // 仅依赖选中下标时，正倒序切换/换线路后下标可能不变，effect 不会重跑，
    // 导致新列表未滚动到选中集、焦点也留在已重建的旧请求器上。
    LaunchedEffect(safeSelectedEpisodeIndex, displayedEpisodes, episodeSortReverse, touchMode, leadingItemCount) {
        if (touchMode) return@LaunchedEffect
        withFrameNanos { }
        // LazyVerticalGrid 里「选集/排序」标题行占一个 item；手机端头部信息再多占一个 item，
        // 选集下标要加上这些前置项才是网格 item 下标。
        val targetItemIndex = safeSelectedEpisodeIndex + leadingItemCount
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetItemIndex }) {
            gridState.scrollToItem(targetItemIndex)
        }
        withFrameNanos { }
        runCatching {
            focusRequesters.getOrNull(safeSelectedEpisodeIndex)?.requestFocus()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TvLayout.EpisodeGridMinWidth),
        state = gridState,
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { headerContent ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                headerContent()
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("选集（共 ${episodes.size} 集）", style = MaterialTheme.typography.titleSmall)
                CategoryPill(label = if (episodeSortReverse) "正序" else "倒序", selected = false, onClick = onToggleSort)
            }
        }
        itemsIndexed(
            items = displayedEpisodes,
            key = { index, episode -> "$index-${episode.url}" },
        ) { index, episode ->
            val realIndex = if (episodeSortReverse) episodes.lastIndex - index else index
            EpisodeButton(
                episode = episode,
                selected = realIndex == selectedEpisodeIndex,
                focusRequester = focusRequesters[index],
                onClick = { onEpisode(realIndex) },
            )
        }
    }
}

@Composable
private fun EpisodeButton(
    episode: PlayEpisode,
    selected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    val containerColor = if (selected || focused) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected || focused) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .tvFocusScale(shape = shape)
            .focusRequester(focusRequester)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .background(containerColor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .focusable(),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        // 文字撑高约 46dp，触摸模式下不足 48dp 最小热区，统一抬到 48dp 并垂直居中。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = episode.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
