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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
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
import com.tvbox.app.domain.displayEpisodeTitle
import com.tvbox.app.domain.resumePositionForEpisode
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
                                            val resumeMs = resumePositionForEpisode(
                                                movie = movie,
                                                historyItems = state.historyItems,
                                                sourceIndex = state.selectedSourceIndex,
                                                episodeIndex = state.selectedEpisodeIndex,
                                            )
                                            Button(
                                                onClick = {
                                                    actions.openPlayer(
                                                        state.selectedSourceIndex,
                                                        state.selectedEpisodeIndex,
                                                        resumeMs,
                                                    )
                                                },
                                                enabled = canPlay,
                                            ) {
                                                // 有进度时直接告诉用户会从哪里接着播，避免"点了却从头上开始"的困惑。
                                                Text(if (resumeMs > 0L) "继续播放 ${formatPlayerTime(resumeMs)}" else "立即播放")
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
                                    CollapsibleDescription(
                                        text = movie.description.ifBlank { "暂无简介" },
                                        collapsedLines = descriptionLines,
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
                                restoreScrollIndex = actions.detailScrollIndexFor(
                                    movie.id,
                                    state.selectedSourceIndex,
                                ),
                                onScrollIndexChanged = { index ->
                                    actions.rememberDetailScrollIndex(
                                        movie.id,
                                        state.selectedSourceIndex,
                                        index,
                                    )
                                },
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
                            val resumeMs = resumePositionForEpisode(
                                movie = movie,
                                historyItems = state.historyItems,
                                sourceIndex = state.selectedSourceIndex,
                                episodeIndex = state.selectedEpisodeIndex,
                            )
                            Button(
                                onClick = {
                                    actions.openPlayer(
                                        state.selectedSourceIndex,
                                        state.selectedEpisodeIndex,
                                        resumeMs,
                                    )
                                },
                                enabled = canPlay,
                            ) {
                                Text(if (resumeMs > 0L) "继续播放 ${formatPlayerTime(resumeMs)}" else "立即播放")
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
                // 简介默认折叠：长简介会把选集挤出首屏，展开后跟随整页滚动。
                CollapsibleDescription(
                    text = movie.description.ifBlank { "暂无简介" },
                    collapsedLines = 3,
                )
            }
        },
        onEpisode = { episodeIndex -> actions.openPlayer(state.selectedSourceIndex, episodeIndex) },
        modifier = Modifier.fillMaxSize(),
        restoreScrollIndex = actions.detailScrollIndexFor(movie.id, state.selectedSourceIndex),
        onScrollIndexChanged = { index ->
            actions.rememberDetailScrollIndex(movie.id, state.selectedSourceIndex, index)
        },
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
    /** 上次离开详情页时的滚动位置；-1 表示无记忆，回落到"滚到选中集"。 */
    restoreScrollIndex: Int = -1,
    onScrollIndexChanged: (Int) -> Unit = {},
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
        // 列宽与下方「有剧集」分支保持一致：触摸模式用 92dp，否则 TV 档位；
        // 否则无剧集的空态页会沿用 TV 的 132dp，头部信息在手机上排得过于稀疏。
        val emptyTouchMode = LocalTouchMode.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (emptyTouchMode) 92.dp else TvLayout.EpisodeGridMinWidth),
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
        // 优先恢复上次离开详情页时的滚动位置：否则用户浏览到第 40 集再进播放页返回，
        // 会被"滚到选中集"强制拉回第 1 集附近（#6 详情页位置记忆）。
        val remembered = restoreScrollIndex
        val targetItemIndex = when {
            remembered >= leadingItemCount -> remembered
            else -> safeSelectedEpisodeIndex + leadingItemCount
        }.coerceAtLeast(0)
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetItemIndex }) {
            gridState.scrollToItem(targetItemIndex)
        }
        withFrameNanos { }
        runCatching {
            focusRequesters.getOrNull(safeSelectedEpisodeIndex)?.requestFocus()
        }
    }

    // 持续把"首个可见项"回报给上层：返回详情页时据此恢复，而不是每次都被拉回选中集。
    LaunchedEffect(gridState, episodes) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { index -> onScrollIndexChanged(index) }
    }

    LazyVerticalGrid(
        // 触摸模式下选集按钮多为「第N集/HD」短文本，132dp 的 TV 档在竖屏只排 3 列太稀疏；
        // 手机收紧到 92dp 可排 4~5 列，百集剧翻页深度明显下降。
        columns = GridCells.Adaptive(minSize = if (touchMode) 92.dp else TvLayout.EpisodeGridMinWidth),
        state = gridState,
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (touchMode) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (touchMode) 8.dp else 12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { headerContent ->
            item(key = "detail-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                headerContent()
            }
        }
        item(key = "episode-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
            // key 只取剧集内容：正倒序切换不应让 LazyGrid 认为整批都是新 item，
            // 否则滚动位置归零、焦点请求器重建。
            key = { _, episode -> episode.url },
        ) { index, episode ->
            val realIndex = if (episodeSortReverse) episodes.lastIndex - index else index
            EpisodeButton(
                episode = episode,
                realIndex = realIndex,
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
    realIndex: Int,
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
                // 去掉「1.」「第01集」这类纯编号前缀：各源标题风格不一，
                // 直接显示会让选集网格变成一排没有意义的数字。
                text = com.tvbox.app.domain.displayEpisodeTitle(episode.title, realIndex),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 可折叠的文字介绍：默认只显示 [collapsedLines] 行，点「展开」后完整显示。
 *
 * 长简介会把下方的选集挤出首屏，所以默认折叠；展开按钮用 TextButton 而不是让整段文字
 * 可点击——电视端只有可聚焦控件才能用遥控选中，纯文字点击在遥控上完全做不到。
 */
@Composable
private fun CollapsibleDescription(
    text: String,
    collapsedLines: Int,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(if (expanded) "收起介绍" else "展开介绍")
        }
    }
}
