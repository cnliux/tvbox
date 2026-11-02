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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PlayEpisode
import com.tvbox.app.domain.PlaySource
import com.tvbox.app.domain.PlaybackAgent
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.playbackHealthKey
import com.tvbox.app.ui.components.CategoryPill
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.InfoLine
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.SmallMeta
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvLayout

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
                modifier = Modifier.padding(padding),
            )
            state.detailMovie == null -> ErrorState(
                message = "影片详情为空",
                onRetry = actions::goBack,
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
                    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
                    val compact = isMobile || maxHeight < 560.dp
                    val posterWidth = if (compact) 118.dp else 210.dp
                    val horizontalGap = if (compact) 16.dp else 28.dp
                    val headerMaxHeight = if (compact) 190.dp else 320.dp
                    val descriptionLines = if (compact) 2 else 5
                    val sectionGap = if (compact) 12.dp else 24.dp
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
                                            style = if (compact) {
                                                MaterialTheme.typography.titleLarge
                                            } else {
                                                MaterialTheme.typography.headlineLarge
                                            },
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

@Composable
private fun PlaySourceTabs(
    movie: Movie,
    sources: List<IndexedValue<PlaySource>>,
    selectedIndex: Int,
    selectedEpisodeIndex: Int,
    playbackHealth: PlaybackHealthSnapshot,
    sourceLatencies: Map<String, Long?>,
    onSelect: (Int) -> Unit,
) {
    if (sources.isEmpty()) {
        Text("暂无可播放源", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val playbackAgent = remember { PlaybackAgent() }
    val nowMs = System.currentTimeMillis()
    val recommendedIndex = playbackAgent.selectBestSource(
        movie = movie,
        requestedSourceIndex = selectedIndex,
        episodeIndex = selectedEpisodeIndex,
        healthSnapshot = playbackHealth,
        nowMs = nowMs,
    )?.sourceIndex
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(sources) { _, indexedSource ->
            val index = indexedSource.index
            val source = indexedSource.value
            val statusLabel = playSourceStatusLabel(
                movieId = movie.id,
                source = source,
                sourceIndex = index,
                episodeIndex = selectedEpisodeIndex,
                playbackHealth = playbackHealth,
                recommendedIndex = recommendedIndex,
                nowMs = nowMs,
            )
            CategoryPill(
                label = listOfNotNull(
                    source.name,
                    lineLatencyLabel(source.lineId, sourceLatencies),
                    statusLabel,
                ).joinToString(" · "),
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 已测速线路按延迟升序在前，未测速/失败线路保持原有顺序排在后面。 */
private fun orderPlaySourcesByLatency(
    sources: List<PlaySource>,
    latencies: Map<String, Long?>,
): List<IndexedValue<PlaySource>> {
    return sources.withIndex().sortedWith(
        compareBy(
            { latencies[it.value.lineId] == null },
            { latencies[it.value.lineId] ?: 0L },
            { it.index },
        ),
    )
}

private fun lineLatencyLabel(lineId: String, latencies: Map<String, Long?>): String? {
    if (lineId.isBlank() || !latencies.containsKey(lineId)) return null
    return latencies[lineId]?.let { "${it}ms" } ?: "测速失败"
}

@Composable
private fun EpisodeGrid(
    source: PlaySource?,
    selectedEpisodeIndex: Int,
    episodeSortReverse: Boolean = false,
    onToggleSort: () -> Unit = {},
    onEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = source?.episodes.orEmpty()
    if (episodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("当前播放源暂无剧集", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val displayedEpisodes = if (episodeSortReverse) episodes.asReversed() else episodes
    val safeSelectedEpisodeIndex = if (episodeSortReverse) {
        (episodes.size - 1 - selectedEpisodeIndex).coerceIn(0, displayedEpisodes.lastIndex)
    } else {
        selectedEpisodeIndex.coerceIn(0, episodes.lastIndex)
    }
    val gridState = rememberLazyGridState()
    val focusRequesters = remember(displayedEpisodes.map { it.title }) {
        List(displayedEpisodes.size) { FocusRequester() }
    }

    LaunchedEffect(safeSelectedEpisodeIndex) {
        withFrameNanos { }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == safeSelectedEpisodeIndex }) {
            gridState.scrollToItem(safeSelectedEpisodeIndex)
        }
        withFrameNanos { }
        runCatching {
            focusRequesters[safeSelectedEpisodeIndex].requestFocus()
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
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = episode.title,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun playSourceStatusLabel(
    movieId: Int,
    source: PlaySource,
    sourceIndex: Int,
    episodeIndex: Int,
    playbackHealth: PlaybackHealthSnapshot,
    recommendedIndex: Int?,
    nowMs: Long,
): String? {
    val key = playbackHealthKey(movieId, episodeIndex, source)
    return when (playbackHealth.entryFor(key)?.recentIssueType(nowMs)) {
        PlaybackIssueType.Error -> "近期失败"
        PlaybackIssueType.SlowBuffer -> "较慢"
        null -> if (sourceIndex == recommendedIndex) "推荐" else null
    }
}
