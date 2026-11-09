package com.tvbox.app.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvbox.app.data.FlvContinuityProxy
import platformlive.FlvProxyHandle
import com.tvbox.app.domain.PlaybackWatchdog
import com.tvbox.app.domain.PlaybackFailureKind
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.roomVideoScaleKey
import com.tvbox.app.domain.resolveVideoScaleMode
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.ResolvedPlatformLiveStream
import com.tvbox.app.ui.components.EmptyState
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvLayout
import com.tvbox.app.util.logSafeHeaderNames
import com.tvbox.app.util.logSafeText
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.delay

@Composable
fun PlatformLiveScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    BackHandler { actions.goBack() }
    when (state.platformLiveDestination) {
        PlatformLiveDestination.Sites -> PlatformLiveSitesScreen(state = state, actions = actions)
        PlatformLiveDestination.Favorites -> PlatformLiveRoomsScreen(state = state, actions = actions, favoritesMode = true)
        PlatformLiveDestination.ParentCategories -> PlatformLiveParentCategoriesScreen(state = state, actions = actions)
        PlatformLiveDestination.Categories -> PlatformLiveCategoriesScreen(state = state, actions = actions)
        PlatformLiveDestination.Rooms -> PlatformLiveRoomsScreen(state = state, actions = actions)
        PlatformLiveDestination.Player -> PlatformLivePlayerScreen(state = state, actions = actions)
    }
}

@Composable
private fun PlatformLiveSitesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val touchMode = LocalTouchMode.current
    val firstCardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 手机触摸模式不需要自动聚焦，避免首张卡片常驻焦点框。
        // runCatching：目标节点尚未进入组合时 requestFocus 会抛 IllegalStateException。
        if (!touchMode) runCatching { firstCardFocusRequester.requestFocus() }
    }
    PlatformLiveBrowseSurface(
        title = "直播",
        subtitle = when {
            state.platformLiveLoading -> "正在加载直播平台，收藏仍可使用"
            state.platformLiveError != null -> "平台加载失败：${state.platformLiveError}"
            state.platformLiveFavoriteError != null -> "收藏读取失败：${state.platformLiveFavoriteError}"
            else -> "选择直播平台或本机收藏"
        },
        loading = false,
        error = null,
        isEmpty = false,
        emptyMessage = "没有可用平台直播服务",
        onRetry = actions::refreshPlatformLive,
        columnsOverride = state.appSettings.platformLiveGridColumns,
    ) {
        item(key = "favorites") {
            PlatformLiveTextCard(
                title = "收藏",
                subtitle = "本机已收藏 ${state.platformLiveFavorites.size} 个房间",
                label = "我的收藏",
                onClick = actions::openPlatformLiveFavorites,
                modifier = Modifier.focusRequester(firstCardFocusRequester),
            )
        }
        itemsIndexed(
            state.platformLiveQuickCategories,
            key = { _, quick -> "quick_${quick.site.id}_${quick.category.id}" },
        ) { _, quick ->
            PlatformLiveTextCard(
                title = "${quick.site.name}·${quick.category.name}",
                subtitle = quick.category.parentName,
                label = "常用分类",
                onClick = { actions.openPlatformLiveQuickCategory(quick) },
            )
        }
        itemsIndexed(state.platformLiveSites, key = { _, site -> site.id }) { _, site ->
            PlatformLiveSiteCard(
                site = site,
                onClick = { actions.selectPlatformLiveSite(site) },
            )
        }
        if (state.platformLiveError != null) {
            item(key = "retry-platforms") {
                PlatformLiveTextCard(
                    title = "重试加载平台",
                    subtitle = "收藏不受平台列表加载失败影响",
                    label = "重试",
                    onClick = actions::refreshPlatformLive,
                )
            }
        }
    }
}

@Composable
private fun PlatformLiveParentCategoriesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val touchMode = LocalTouchMode.current
    val gridState = rememberLazyGridState()
    val firstCardFocusRequester = remember { FocusRequester() }
    val targetIndex = state.platformLiveParentIndex.coerceIn(
        0,
        state.platformLiveParentCategories.lastIndex.coerceAtLeast(0),
    )
    LaunchedEffect(state.platformLiveParentCategories, targetIndex) {
        if (state.platformLiveParentCategories.isEmpty()) return@LaunchedEffect
        // 返回一级分类页时回到上次选中的分类，而不是永远落在第一项。
        // 触摸模式只恢复滚动位置，不主动聚焦，避免首张卡片常驻焦点框。
        gridState.scrollToItem(targetIndex)
        if (touchMode) return@LaunchedEffect
        runCatching { firstCardFocusRequester.requestFocus() }
    }
    val site = state.platformLiveSelectedSite
    PlatformLiveBrowseSurface(
        title = site?.name ?: "直播",
        subtitle = "选择直播大类",
        loading = state.platformLiveLoading,
        error = state.platformLiveError,
        isEmpty = state.platformLiveParentCategories.isEmpty(),
        emptyMessage = "没有可用直播大分类",
        onRetry = actions::refreshPlatformLive,
        gridState = gridState,
        columnsOverride = state.appSettings.platformLiveGridColumns,
    ) {
        itemsIndexed(
            state.platformLiveParentCategories,
            key = { _, category -> category.id },
        ) { index, category ->
            val childCount = state.platformLiveCategories.count { it.parentId == category.id }
            PlatformLiveParentCategoryCard(
                category = category,
                childCount = childCount,
                onClick = { actions.selectPlatformLiveParentCategory(category) },
                modifier = if (index == targetIndex) Modifier.focusRequester(firstCardFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun PlatformLiveCategoriesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val touchMode = LocalTouchMode.current
    val gridState = rememberLazyGridState()
    val firstCardFocusRequester = remember { FocusRequester() }
    val site = state.platformLiveSelectedSite
    val parentCategory = state.platformLiveSelectedParentCategory
    val categories = state.platformLiveCategories.filter { it.parentId == parentCategory?.id }
    val targetIndex = state.platformLiveCategoryIndex.coerceIn(0, categories.lastIndex.coerceAtLeast(0))
    LaunchedEffect(categories, targetIndex) {
        if (categories.isEmpty()) return@LaunchedEffect
        // 从房间页返回时回到上次选中的二级分类。
        // 触摸模式只恢复滚动位置，不主动聚焦。
        gridState.scrollToItem(targetIndex)
        if (touchMode) return@LaunchedEffect
        runCatching { firstCardFocusRequester.requestFocus() }
    }
    PlatformLiveBrowseSurface(
        title = parentCategory?.name ?: site?.name ?: "直播分类",
        subtitle = "选择二级分类",
        loading = state.platformLiveLoading,
        error = state.platformLiveError,
        isEmpty = categories.isEmpty(),
        emptyMessage = "没有可用二级分类",
        onRetry = actions::refreshPlatformLive,
        gridState = gridState,
        columnsOverride = state.appSettings.platformLiveGridColumns,
    ) {
        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
            PlatformLiveCategoryCard(
                category = category,
                onClick = { actions.selectPlatformLiveCategory(category) },
                modifier = if (index == targetIndex) Modifier.focusRequester(firstCardFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun PlatformLiveRoomsScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    favoritesMode: Boolean = false,
) {
    val touchMode = LocalTouchMode.current
    val gridState = rememberLazyGridState()
    val selectedRoomFocusRequester = remember { FocusRequester() }
    val emptyFavoriteFocusRequester = remember { FocusRequester() }
    val selectedRoomIndex = state.platformLiveRoomIndex.coerceIn(
        0,
        state.platformLiveRooms.lastIndex.coerceAtLeast(0),
    )
    LaunchedEffect(
        state.platformLiveDestination,
        state.platformLiveRoomIndex,
        // 仅首条数据变化（初次加载/切换分类/收藏增删）时归位；翻页追加数据时不回滚滚动位置。
        state.platformLiveRooms.firstOrNull()?.id,
    ) {
        if (state.platformLiveRooms.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(selectedRoomIndex)
        if (touchMode) return@LaunchedEffect
        withFrameNanos { }
        runCatching { selectedRoomFocusRequester.requestFocus() }
    }
    LaunchedEffect(favoritesMode, state.platformLiveRooms.isEmpty()) {
        if (touchMode) return@LaunchedEffect
        if (favoritesMode && state.platformLiveRooms.isEmpty()) {
            runCatching { emptyFavoriteFocusRequester.requestFocus() }
        }
    }
    // 滑动/遥控下拉接近列表底部时自动加载下一页，替代手动“加载更多”按钮。
    // 提前半屏预取，保证遥控焦点下移时下一页已经就位。
    val nearListEnd by remember(gridState) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val remaining = layoutInfo.totalItemsCount - 1 - lastVisible.index
            remaining <= (layoutInfo.visibleItemsInfo.size / 2).coerceAtLeast(1)
        }
    }
    // 记录最近一次自动加载发起时的页码，避免接口返回重复页或失败时反复触发。
    var autoLoadFromPage by remember(state.platformLiveSelectedCategory?.id) { mutableIntStateOf(0) }
    LaunchedEffect(
        nearListEnd,
        state.platformLiveRoomPage,
        state.platformLiveRoomPageCount,
        state.platformLiveLoading,
        state.platformLiveLoadingMore,
        state.platformLiveError,
        state.platformLiveLoadingMoreError,
        favoritesMode,
    ) {
        if (state.platformLiveLoadingMoreError != null) {
            // 分页失败时页码不推进，必须清空「已从此页发起过加载」的标记，
            // 否则自动翻页永久停摆，只剩底部手动重试一个出口。
            autoLoadFromPage = -1
            return@LaunchedEffect
        }
        if (favoritesMode || !nearListEnd) return@LaunchedEffect
        if (state.platformLiveLoading || state.platformLiveLoadingMore) return@LaunchedEffect
        if (state.platformLiveRoomPage >= state.platformLiveRoomPageCount) return@LaunchedEffect
        if (state.platformLiveRoomPage == autoLoadFromPage) return@LaunchedEffect
        // 等待上一页数据完成测量后再确认是否仍接近底部，避免用旧布局连续翻页。
        withFrameNanos { }
        if (!nearListEnd) return@LaunchedEffect
        // 确认仍需加载后才记录页码，避免误标导致之后滚到底部不再自动加载。
        autoLoadFromPage = state.platformLiveRoomPage
        actions.loadMorePlatformLiveRooms()
    }
    val category = state.platformLiveSelectedCategory
    val site = state.platformLiveSelectedSite
    if (favoritesMode && state.platformLiveRooms.isEmpty()) {
        PageSurface { padding ->
            // 收藏为空是一种正常空态，不再自绘「标题 + 卡片」，统一走 EmptyState；
            // 遥控端初始焦点仍落在空态动作上（actionModifier），与改造前一致。
            EmptyState(
                title = "还没有收藏的直播房间",
                subtitle = state.platformLiveFavoriteError
                    ?.let { "收藏读取失败：$it" }
                    ?: "在主播房间卡片上选择星星即可收藏",
                actionLabel = "返回直播平台",
                onAction = actions::goBack,
                actionModifier = Modifier.focusRequester(emptyFavoriteFocusRequester),
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    PlatformLiveBrowseSurface(
        title = if (favoritesMode) "收藏" else category?.name ?: "直播间",
        subtitle = if (favoritesMode) "本机收藏的直播房间" else category?.parentName?.ifBlank { site?.name ?: "直播" } ?: site?.name ?: "直播",
        loading = !favoritesMode && state.platformLiveLoading,
        error = if (favoritesMode) null else state.platformLiveError,
        isEmpty = state.platformLiveRooms.isEmpty(),
        emptyMessage = if (favoritesMode) "还没有收藏的直播房间" else "这个分类暂时没有正在直播的房间",
        onRetry = actions::refreshPlatformLive,
        gridState = gridState,
        notice = state.platformLiveFavoriteError,
        loadingMore = !favoritesMode && state.platformLiveLoadingMore,
        loadingMoreError = if (favoritesMode) null else state.platformLiveLoadingMoreError,
        onRetryLoadMore = actions::loadMorePlatformLiveRooms,
    ) {
        itemsIndexed(state.platformLiveRooms, key = { _, room -> room.id }) { index, room ->
            PlatformLiveRoomCard(
                room = room,
                onClick = { actions.openPlatformLiveRoom(room) },
                isFavorite = state.platformLiveFavorites.any { it.id == room.id },
                onToggleFavorite = { actions.togglePlatformLiveFavorite(room) },
                favoritesMode = favoritesMode,
                modifier = if (index == selectedRoomIndex) {
                    Modifier.focusRequester(selectedRoomFocusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun PlatformLiveBrowseSurface(
    title: String,
    subtitle: String,
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    emptyMessage: String,
    onRetry: () -> Unit,
    gridState: LazyGridState? = null,
    notice: String? = null,
    loadingMore: Boolean = false,
    loadingMoreError: String? = null,
    onRetryLoadMore: (() -> Unit)? = null,
    columnsOverride: Int = 0,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    PageSurface(
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
    ) { padding ->
        when {
            loading -> LoadingState(text = "正在加载$title", modifier = Modifier.padding(padding))
            error != null -> ErrorState(message = error, onRetry = onRetry, modifier = Modifier.padding(padding))
            isEmpty -> EmptyState(
                title = emptyMessage,
                subtitle = "可稍后刷新，或切换其他分类看看",
                actionLabel = "刷新",
                onAction = onRetry,
                modifier = Modifier.padding(padding),
            )
            else -> {
                val resolvedGridState = gridState ?: rememberLazyGridState()
                val headerVisible by remember {
                    derivedStateOf {
                        resolvedGridState.firstVisibleItemIndex == 0 && resolvedGridState.firstVisibleItemScrollOffset < 24
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(
                            horizontal = if (LocalTouchMode.current) 3.dp else 29.dp,
                            vertical = if (LocalTouchMode.current) 10.dp else 24.dp,
                        ),
                ) {
                    AnimatedVisibility(visible = headerVisible) {
                        Column {
                            // 站点名/分类名可能很长（分类页直接用二级分类名当标题），限制两行避免页头无限增高。
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitle,
                                modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (notice != null) {
                                Text(
                                    text = notice,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    val touchMode = LocalTouchMode.current
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // 列数随可用宽度推导：每格不小于 176dp，竖屏自然回落到 2–3 列，
                        // 避免 4 列时平台名/房间名只剩 1–2 个字符。
                        val touchCapacity = (maxWidth / 176.dp).toInt().coerceIn(2, 4)
                        val effectiveColumns = when {
                            columnsOverride > 0 ->
                                if (touchMode) columnsOverride.coerceAtMost(touchCapacity) else columnsOverride
                            touchMode -> touchCapacity
                            else -> TvLayout.PlatformLiveColumns
                        }
                        LazyVerticalGrid(
                            state = resolvedGridState,
                            columns = GridCells.Fixed(effectiveColumns),
                            contentPadding = PaddingValues(if (touchMode) 2.dp else 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (touchMode) 8.dp else 14.dp),
                            verticalArrangement = Arrangement.spacedBy(if (touchMode) 8.dp else 14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            content()
                            // 分页失败时在列表末尾插入整行「重试」卡片：不打断已加载列表，
                            // 触摸可直接点，遥控也能顺焦点下移到达。
                            if (loadingMoreError != null && onRetryLoadMore != null) {
                                item(key = "load-more-error", span = { GridItemSpan(maxLineSpan) }) {
                                    PlatformLiveTextCard(
                                        title = "加载更多失败",
                                        subtitle = loadingMoreError,
                                        label = "点击重试",
                                        onClick = onRetryLoadMore,
                                    )
                                }
                            }
                        }
                        // 自动加载更多时的轻量提示，不参与网格布局与焦点遍历。
                        if (loadingMore) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = if (touchMode) 12.dp else 18.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(text = "正在加载更多", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformLiveSiteCard(
    site: PlatformLiveSite,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveTextCard(
        title = site.name,
        subtitle = site.description.ifBlank { "直播分类与房间浏览" },
        label = "直播平台",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveParentCategoryCard(
    category: PlatformLiveParentCategory,
    childCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveTextCard(
        title = category.name,
        subtitle = if (childCount > 0) "$childCount 个二级分类" else "选择二级分类",
        label = "",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveCategoryCard(
    category: PlatformLiveCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveImageCard(
        title = category.name,
        subtitle = category.parentName.ifBlank { "直播分类" },
        imageUrl = category.cover,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveRoomCard(
    room: PlatformLiveRoom,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    favoritesMode: Boolean,
    modifier: Modifier = Modifier,
) {
    PlatformLiveImageCard(
        title = room.title,
        subtitle = if (favoritesMode) {
            listOf(room.anchor, room.site.uppercase()).filter { it.isNotBlank() }.joinToString(" / ")
        } else {
            listOf(room.anchor, formatOnline(room.online)).filter { it.isNotBlank() }.joinToString(" / ")
        },
        imageUrl = room.cover,
        onClick = onClick,
        favorite = isFavorite,
        onFavoriteClick = onToggleFavorite,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveTextCard(
    title: String,
    subtitle: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val touchMode = LocalTouchMode.current
    // 卡片内「分类 / 站名 / 描述」是三个文本节点，合并成一条播报。
    val cardLabel = buildString {
        if (label.isNotBlank()) append(label).append("，")
        append(title)
        if (subtitle.isNotBlank()) append("，").append(subtitle)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusScale(shape = shape)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .background(if (focused) Color(0xFF252525) else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cardLabel }
            .focusable()
            .padding(if (touchMode) 14.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(if (touchMode) 4.dp else 8.dp),
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                style = if (touchMode) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            text = title,
            style = if (touchMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            // 触摸模式允许两行：窄屏 2–3 列时站名/分类名不至于只剩首字。
            maxLines = if (touchMode) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (touchMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlatformLiveImageCard(
    title: String,
    subtitle: String,
    imageUrl: String,
    onClick: () -> Unit,
    favorite: Boolean? = null,
    onFavoriteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val touchMode = LocalTouchMode.current
    // 触摸模式：横向封面 + 封面右上角收藏星，文字占满卡宽。
    val hasFavorite = favorite != null && onFavoriteClick != null
    // 图片卡的封面 contentDescription 与标题/副标题是分开的节点；整卡合并播报。
    val cardLabel = if (subtitle.isBlank()) title else "$title，$subtitle"
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 触摸模式按实测卡宽选档位：4 列房间卡（约 76–104dp）用 titleSmall，2 列卡用 titleMedium；
        // 副标题统一不低于 labelMedium，避免 labelSmall 在窄卡上小到看不清。
        val narrowCard = maxWidth < 132.dp
        Column(
            modifier = modifier
                .fillMaxWidth()
                .tvFocusScale(shape = shape)
                .clip(shape)
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .background(if (focused) Color(0xFF252525) else MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = cardLabel }
                .focusable(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (touchMode) 1.15f else 1.7778f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        imageLoader = com.tvbox.app.ui.components.rememberTvImageLoader(),
                    )
                } else {
                    Text(
                        text = "直播",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (touchMode && hasFavorite) {
                    PlatformLiveFavoriteStar(
                        favorite = favorite,
                        onFavoriteClick = onFavoriteClick,
                        title = title,
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                        size = 26.dp,
                    )
                }
            }
            Text(
                text = title,
                modifier = Modifier.padding(
                    start = if (touchMode) 8.dp else 12.dp,
                    top = if (touchMode) 6.dp else 10.dp,
                    end = if (touchMode || favorite == null) (if (touchMode) 8.dp else 12.dp) else 62.dp,
                ),
                style = when {
                    !touchMode -> MaterialTheme.typography.titleSmall
                    narrowCard -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.titleMedium
                },
                fontWeight = if (touchMode) FontWeight.Medium else FontWeight.Normal,
                maxLines = if (touchMode) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.ifBlank { "正在直播" },
                modifier = Modifier.padding(
                    start = if (touchMode) 8.dp else 12.dp,
                    top = 2.dp,
                    end = if (touchMode || favorite == null) (if (touchMode) 8.dp else 12.dp) else 62.dp,
                    bottom = if (touchMode) 7.dp else 12.dp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 12sp 的 labelSmall 在 4 列窄卡上不可读；触摸模式统一用 labelMedium(14sp) 作为下限。
                style = if (touchMode) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!touchMode && hasFavorite) {
            PlatformLiveFavoriteStar(
                favorite = favorite,
                onFavoriteClick = onFavoriteClick,
                title = title,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                size = 44.dp,
            )
        }
    }
}

@Composable
private fun PlatformLiveFavoriteStar(
    favorite: Boolean,
    onFavoriteClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp,
) {
    var starFocused by remember { mutableStateOf(false) }
    val touchMode = LocalTouchMode.current
    // 触控热区至少 48dp（手机端视觉仍是 26dp），满足最小可点击尺寸。
    val touchTarget = if (size < 48.dp) 48.dp else size
    Box(
        modifier = modifier
            .size(touchTarget)
            .onFocusChanged { starFocused = it.isFocused || it.hasFocus }
            .semantics {
                contentDescription = if (favorite) "取消收藏 $title" else "收藏 $title"
                stateDescription = if (favorite) "已收藏" else "未收藏"
            }
            .clickable(role = Role.Button, onClick = onFavoriteClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(MaterialTheme.shapes.small)
                .background(if (starFocused) MaterialTheme.colorScheme.primary else Color(0xCC101010))
                .border(
                    width = if (starFocused) 2.dp else 0.dp,
                    color = if (starFocused) Color.White else Color.Transparent,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (favorite) "★" else "☆",
                color = if (starFocused) MaterialTheme.colorScheme.onPrimary else Color.White,
                style = if (touchMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlatformLivePlayerScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val room = state.platformLiveRooms.getOrNull(state.platformLiveRoomIndex)
    if (room == null) {
        PageSurface { padding ->
            ErrorState(
                message = "未选择直播间",
                onRetry = { actions.goBack() },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    KeepScreenOnWhileVisible()
    val stream = state.platformLiveStream?.takeIf {
        it.channelId == room.id && it.activeCandidateOrNull?.url?.isNotBlank() == true
    }
    // 虎牙单次 FLV 拉流约 2 分钟被 CDN 关闭：改由 Go 侧本机连续代理续流，
    // 播放器只连本地一条永不结束的 FLV，避免每 2 分钟一次 ENDED 断流。
    // Go 桥接可能耗时或阻塞，必须在 IO 线程启动，避免 Compose 主线程卡死/ANR。
    val continuityProxyState = androidx.compose.runtime.produceState<FlvProxyHandle?>(
        initialValue = null,
        key1 = room.id,
        key2 = stream?.url,
        key3 = stream?.safeActiveCandidateIndex,
    ) {
        val resolved = stream
        if (resolved == null || !FlvContinuityProxy.shouldWrap(room.site, resolved.protocol)) {
            value = null
            return@produceState
        }
        val candidate = resolved.activeCandidateOrNull
        val candidateUrl = candidate?.url.orEmpty()
        if (candidateUrl.isBlank()) {
            value = null
            return@produceState
        }
        val handle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FlvContinuityProxy.start(
                site = room.site,
                roomId = room.id,
                candidateIndex = resolved.safeActiveCandidateIndex,
                url = candidateUrl,
                headers = resolved.headers,
            )
        }
        value = handle
        awaitDispose { handle?.close() }
    }
    val continuityProxy = continuityProxyState.value
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackUrl = continuityProxy?.localUrl() ?: stream?.activeCandidateOrNull?.url
    val context = androidx.compose.ui.platform.LocalContext.current
    val isMobile = state.effectiveDeviceMode == com.tvbox.app.domain.DeviceMode.Mobile
    val activity = remember(context) { context.findActivity() }
    var landscapeRequested by remember { mutableStateOf(false) }
    val initialRequestedOrientation = remember(activity) { activity?.requestedOrientation }
    if (isMobile) {
        // 手机模式直播播放页沉浸全屏，退出时恢复系统栏与屏幕方向。
        FullscreenWhilePlayingPageVisible(activity)
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation =
                initialRequestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val decoderStrategy = state.appSettings.decoderStrategy
    val player = rememberManagedExoPlayerOrNull(
        playbackUrl,
        stream?.headers,
        decoderStrategy,
    ) { context ->
        val targetUrl = playbackUrl
        if (stream == null || targetUrl.isNullOrBlank()) {
            null
        } else {
            val resolved = stream
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            // User-Agent 属于 ExoPlayer 受限请求头，必须走 setUserAgent，其余（Referer/Cookie）走通用请求头。
            val userAgentEntry = resolved.headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) && it.value.isNotBlank() }
            userAgentEntry?.let { dataSourceFactory.setUserAgent(it.value) }
            val otherHeaders = resolved.headers.filter { !it.key.equals("User-Agent", ignoreCase = true) }
            if (otherHeaders.isNotEmpty()) {
                dataSourceFactory.setDefaultRequestProperties(otherHeaders)
            }
            android.util.Log.d(
                PLATFORM_LIVE_LOG_TAG,
                "【构建播放器】room=${room.title}(${room.id}), site=${room.site}, url=${logSafeUrl(resolved.activeCandidateOrNull?.url)}, localUrl=${logSafeUrl(targetUrl)}, quality=${resolved.quality}, protocol=${resolved.protocol}, headerNames=${logSafeHeaderNames(resolved.headers)}, activeCdn=${resolved.activeCandidateOrNull?.cdn.orEmpty()}, candidates=${resolved.candidates.size}, continuityProxy=${continuityProxy != null}"
            )
            buildManagedExoPlayer(context, decoderStrategy) { builder ->
                builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            }
        }
    }
    // 解码策略变化会重建播放器：重连当前频道/线路时提示一次（地址变化属正常换流，不提示）。
    val lastPlayerStrategy = remember { mutableStateOf(decoderStrategy) }
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        if (lastPlayerStrategy.value != decoderStrategy) {
            lastPlayerStrategy.value = decoderStrategy
            android.widget.Toast.makeText(context, "解码器已切换，正在重连当前频道", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 控件显隐/自动隐藏/返回层级统一走共享状态；平台直播保留自己的房间与候选语义。
    val chrome = remember { PlayerChromeState(initialVisible = true) }
    val playerFocusRequester = chrome.rootFocus
    // 播放信息只在面板打开时采样，关闭即停。
    val diagnostics = rememberPlayerDiagnostics(player, chrome.panel == PlayerPanel.Info)
    val session = rememberPlaybackSession()
    BindPlaybackSession(session, player)
    HardwareDecoderFallbackToast()
    // 候选/地址变化时重建快照，保持原 playerState 的重置语义。
    val playbackHost = rememberPlaybackHostOrNull(
        player,
        resetKey = stream?.url to stream?.safeActiveCandidateIndex,
    )
    var playbackError by remember(stream?.url, stream?.safeActiveCandidateIndex) { mutableStateOf<String?>(null) }
    // 共享看门狗：首帧超时与出帧后卡死同一套判定；候选/地址变化时重建计时。
    var playbackWatchdog by remember(stream?.url, stream?.safeActiveCandidateIndex) {
        mutableStateOf(PlaybackWatchdog())
    }
    // 手机"全屏"：按房间记忆裁剪铺满；未记忆时回退设置里的全局默认值。
    val scaleContentKey = roomVideoScaleKey(room.site, room.id)
    val videoScaleMode = state.appSettings.resolveVideoScaleMode(scaleContentKey)

    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e(
                        PLATFORM_LIVE_LOG_TAG,
                        "【平台直播错误】room=${room.id}, title=${room.title}, code=${error.errorCode}, name=${error.errorCodeName}, msg=${logSafeText(error.message)}, url=${logSafeUrl(playbackUrl)}, sourceUrl=${logSafeUrl(stream?.url)}, activeCdn=${stream?.activeCandidate?.cdn}, candidateIndex=${stream?.activeCandidateIndex}/${stream?.candidates?.size}"
                    )
                    if (!session.canPlay) return
                    // 不展示 ExoPlayer 原始异常文本，统一为可读提示。
                    playbackError = "播放失败，正在尝试恢复"
                    chrome.bump()
                    actions.recoverPlatformLiveChannel(LiveLineFallbackReason.Error)
                }

                override fun onRenderedFirstFrame() {
                    playbackWatchdog.onFirstFrameRendered(System.currentTimeMillis())
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val name = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN"
                    }
                    android.util.Log.d(
                        PLATFORM_LIVE_LOG_TAG,
                        "【状态】$name, playWhenReady=${player.playWhenReady}, buffered=${player.bufferedPercentage}%, url=${logSafeUrl(playbackUrl)}"
                    )
                    if (playbackState == Player.STATE_READY) {
                        // 画面恢复即清除失败提示：重解析得到同一地址时 effect 不会重跑，避免提示常驻。
                        playbackError = null
                        // 画面恢复即认为当前 CDN 健康，清零自动恢复计数，避免周期性关流被累积成"换线路"。
                        actions.notifyPlatformLivePlaybackReady()
                    }
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
            }
        }
    }

    LaunchedEffect(player, playbackUrl) {
        val targetUrl = playbackUrl
        if (player == null || stream == null || targetUrl.isNullOrBlank()) return@LaunchedEffect
        android.util.Log.d(
            PLATFORM_LIVE_LOG_TAG,
            "【开始播放】room=${room.title}(${room.id}), site=${room.site}, url=${logSafeUrl(targetUrl)}, sourceUrl=${logSafeUrl(stream.activeCandidateOrNull?.url)}, quality=${stream.quality}, protocol=${stream.protocol}, activeCdn=${stream.activeCandidateOrNull?.cdn.orEmpty()}, candidateIndex=${stream.safeActiveCandidateIndex}/${stream.candidates.size}"
        )
        playbackError = null
        chrome.bump()
        playbackWatchdog.onSourceRequested(System.currentTimeMillis())
        player.setMediaItem(MediaItem.fromUri(targetUrl))
        player.prepare()
        session.preparePlayback(player)
    }

    // 连续代理会周期性续流，定期把代理统计打进日志，便于确认续流是否正常。
    // 退到后台时停止轮询，避免无意义的定时唤醒与日志刷屏。
    LaunchedEffect(continuityProxy, lifecycleOwner) {
        val handle = continuityProxy ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10_000)
                android.util.Log.d(PLATFORM_LIVE_LOG_TAG, "【连续代理】${handle.stats()}")
            }
        }
    }

    // 控制条与角标显示 4 秒后自动隐藏，点击画面可再次唤出（与电视直播页一致）。
    PlayerChromeAutoHide(chrome, playing = true, autoHideMs = PLATFORM_LIVE_BADGE_HIDE_DELAY_MS)

    LaunchedEffect(playbackHost?.snapshot?.playbackState, player, playbackUrl, session.canPlay) {
        if (player == null || stream == null || !session.canPlay) return@LaunchedEffect
        if (playbackHost?.snapshot?.playbackState == Player.STATE_ENDED) {
            // 虎牙等平台的 FLV 带签名有效期，到点被服务端正常关闭 → 属于 Ended，
            // 应重新解析拿新地址（沿用当前 CDN），不能当成 CDN 故障去轮换线路。
            android.util.Log.w(PLATFORM_LIVE_LOG_TAG, "【播放结束】room=${room.id}, 判定为流被关闭，走同线路重解析")
            actions.recoverPlatformLiveChannel(LiveLineFallbackReason.Ended)
        }
    }

    // 共享看门狗：覆盖「取流/建源 → 首帧」整段与出帧后卡死；平台直播仍使用自己的 FLV 续流代理。
    LaunchedEffect(player, playbackUrl, lifecycleOwner, playbackWatchdog) {
        if (player == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playbackWatchdog.onPaused()
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val failure = playbackWatchdog.checkFirstFrame(
                    nowMs = now,
                    playWhenReady = player.playWhenReady && session.canPlay,
                ) ?: playbackWatchdog.onProgressSample(
                    isPlaying = player.playWhenReady,
                    positionMs = player.currentPosition,
                    nowMs = now,
                )
                if (failure != null) {
                    android.util.Log.w(
                        PLATFORM_LIVE_LOG_TAG,
                        "【看门狗触发】kind=${failure.kind}, room=${room.id}, activeCdn=${stream?.activeCandidate?.cdn}, candidateIndex=${stream?.activeCandidateIndex}, elapsedMs=${failure.elapsedMs}",
                    )
                    playbackError = when (failure.kind) {
                        PlaybackFailureKind.FirstFrameTimeout -> "直播连接较慢，正在重新获取播放地址"
                        PlaybackFailureKind.Stalled -> "直播连接中断，正在重新获取播放地址"
                        PlaybackFailureKind.SourceFailed -> "播放失败，正在尝试恢复"
                    }
                    chrome.bump()
                    actions.recoverPlatformLiveChannel(LiveLineFallbackReason.Stall)
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { playerFocusRequester.requestFocus() } }

    // 自动/手动切换线路的结果必须有明确可见提示：仅靠 2 秒角标在电视端几乎看不到。
    LaunchedEffect(state.platformLiveNotice) {
        val notice = state.platformLiveNotice ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_SHORT).show()
        android.util.Log.i(PLATFORM_LIVE_LOG_TAG, "【线路提示】$notice")
        chrome.bump()
        actions.consumePlatformLiveNotice()
    }

    var showUrlOverlay by remember { mutableStateOf(false) }
    var showLineControl by remember { mutableStateOf(false) }
    // 返回键仍逐层收起页面浮层：URL 浮层 → 候选线路浮层 → 退出；不额外增加“先隐藏控件”一层。
    PlayerChromeBackHandler(
        state = chrome,
        onExit = { actions.goBack() },
        dismissControls = false,
        extraDismiss = listOf(
            {
                if (showUrlOverlay) {
                    showUrlOverlay = false
                    true
                } else {
                    false
                }
            },
            {
                if (showLineControl) {
                    showLineControl = false
                    true
                } else {
                    false
                }
            },
        ),
    )
    val lineListState = rememberLazyListState()
    val lineFocusRequester = remember { FocusRequester() }
    var focusedCandidateIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(showLineControl, stream?.safeActiveCandidateIndex, stream?.candidates?.size) {
        if (!showLineControl) {
            // 关闭浮层后焦点回到播放器根节点，避免焦点遗留在已移除的候选行上。
            runCatching { playerFocusRequester.requestFocus() }
            return@LaunchedEffect
        }
        val target = stream?.safeActiveCandidateIndex ?: 0
        val bounded = target.coerceIn(0, (stream?.candidates?.lastIndex ?: 0).coerceAtLeast(0))
        lineListState.scrollToItem(bounded)
        runCatching { lineFocusRequester.requestFocus() }
    }

    // 遥控键位收敛到共享 PlayerRoomKeyMap：页面只提供房间/候选/线路浮层语义。
    val roomKeyMap = remember {
        PlayerRoomKeyMap(
            PlayerRoomKeyMap.Callbacks(
                isLineControlVisible = { false },
                onPreviousRoom = {},
                onNextRoom = {},
                onPreviousCandidate = {},
                onNextCandidate = {},
                onOpenLine = {},
                onReconnect = {},
            ),
        )
    }
    roomKeyMap.callbacks = PlayerRoomKeyMap.Callbacks(
        isLineControlVisible = { showLineControl },
        onPreviousRoom = { actions.playPreviousPlatformLiveRoom() },
        onNextRoom = { actions.playNextPlatformLiveRoom() },
        onPreviousCandidate = { actions.playPreviousPlatformLiveCandidate() },
        onNextCandidate = { actions.playNextPlatformLiveCandidate() },
        onOpenLine = {
            if (session.interrupted && player != null) {
                session.play(player)
                actions.reconnectPlatformLiveChannel()
            } else {
                showLineControl = true
            }
        },
        onReconnect = { actions.reconnectPlatformLiveChannel() },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .playerKeyInput(roomKeyMap, chrome)
            .focusable(),
    ) {
        if (player != null) {
            PlayerSurface(player = player, resizeMode = videoScaleMode)
        }
        if (isMobile) {
            // 点击画面切换控制条显隐；覆盖层位于按钮与角标之下，不拦截它们的手势。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures { chrome.toggle() }
                    },
            )
        }
        if (session.interrupted && player != null) {
            ControlButton("继续播放", {
                session.play(player)
                actions.reconnectPlatformLiveChannel()
            }, Modifier.align(Alignment.Center))
        }
        if ((chrome.visible || state.platformLiveResolving || state.platformLiveError != null) && !isMobile) {
            PlatformLivePlayerBadge(
                room = room,
                stream = stream,
                error = state.platformLiveError ?: playbackError,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(28.dp),
            )
        }
        if (isMobile) {
            PlayerChrome(
                state = chrome,
                data = PlayerChromeData(
                    title = stream?.title ?: room.title,
                    subtitle = state.platformLiveError
                        ?: playbackError
                        ?: stream?.let { resolved ->
                            buildString {
                                append(resolved.quality)
                                if (resolved.hasCandidates) {
                                    val candidate = resolved.activeCandidateOrNull
                                    append(" · ${resolved.safeActiveCandidateIndex + 1}/${resolved.candidates.size}")
                                    candidate?.cdn?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                }
                            }
                        },
                    playing = player?.let { it.playWhenReady && !session.interrupted } ?: false,
                    resizeMode = videoScaleMode,
                    buffering = playbackHost?.snapshot?.isBuffering == true,
                    playbackInfo = diagnostics?.info,
                    decoderStrategy = state.appSettings.decoderStrategy,
                    decoderFallbackKinds = HardwareDecoderFallbackNotice.kinds,
                    playbackUrls = buildList {
                        playbackUrl?.let { actual ->
                            add(
                                PlayerChromeUrl(
                                    label = if (continuityProxy != null) {
                                        "实际播放地址（本机续流代理）"
                                    } else {
                                        "实际播放地址（平台线路）"
                                    },
                                    url = actual,
                                ),
                            )
                        }
                        val original = stream?.url
                        if (original != null && original != playbackUrl) {
                            add(PlayerChromeUrl("原始地址（平台源）", original))
                        }
                    },
                ),
                capabilities = PlayerChromeCapabilities(
                    seek = false,
                    resize = true,
                    orientation = true,
                    room = true,
                    lines = stream?.hasCandidates == true,
                    info = true,
                    url = true,
                ),
                actions = PlayerChromeActions(
                    onToggle = {
                        val currentPlayer = player ?: return@PlayerChromeActions
                        if (session.interrupted) {
                            session.play(currentPlayer)
                            actions.reconnectPlatformLiveChannel()
                        } else if (currentPlayer.playWhenReady) {
                            session.pause(currentPlayer)
                        } else {
                            session.play(currentPlayer)
                        }
                        chrome.bump()
                    },
                    onPrevious = { actions.playPreviousPlatformLiveRoom(); chrome.bump() },
                    onNext = { actions.playNextPlatformLiveRoom(); chrome.bump() },
                    onSelectResize = { mode -> actions.updateContentVideoScaleMode(scaleContentKey, mode); chrome.bump() },
                    onOpenLinePanel = {
                        if (stream?.hasCandidates == true) {
                            showLineControl = true
                            chrome.bump()
                        }
                    },
                    onOrientation = {
                        landscapeRequested = !landscapeRequested
                        activity?.requestedOrientation = if (landscapeRequested) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                        chrome.bump()
                    },
                    onBack = { actions.goBack() },
                    onInteraction = { chrome.bump() },
                ),
                touch = true,
                fade = true,
            )
        }
        if (state.platformLiveResolving) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在解析 ${room.title}",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (showUrlOverlay && stream != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .pointerInput(Unit) { detectTapGestures { showUrlOverlay = false } }
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("当前实际取流 URL", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = playbackUrl ?: stream.url,
                        color = Color(0xFF7AE2BA),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(16.dp)
                    )
                    if (continuityProxy != null && playbackUrl != stream.url) {
                        Text(
                            text = "原始地址：${middleEllipsis(stream.url)}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "房间：${room.title} | 站点：${room.site} | 质量：${stream.quality} | 协议：${stream.protocol}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("点击屏幕关闭", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (showLineControl && stream?.hasCandidates == true) {
            val lineStream = stream
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .pointerInput(Unit) { detectTapGestures { showLineControl = false } }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(420.dp)
                        .clip(MaterialTheme.shapes.medium),
                    color = Color(0xFF1A1A1A),
                    contentColor = Color.White
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "候选线路切换 - ${room.title}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(
                            state = lineListState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            lazyItemsIndexed(lineStream.candidates) { index, candidate ->
                                val selected = index == lineStream.safeActiveCandidateIndex
                                val focused = focusedCandidateIndex == index
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(
                                            when {
                                                focused -> Color.White.copy(alpha = 0.10f)
                                                selected -> Color(0xFF7AE2BA).copy(alpha = 0.2f)
                                                else -> Color.Transparent
                                            },
                                        )
                                        .border(
                                            width = if (focused) 1.dp else 0.dp,
                                            color = if (focused) Color(0xFF7AE2BA) else Color.Transparent,
                                            shape = MaterialTheme.shapes.small,
                                        )
                                        .then(
                                            if (selected) Modifier.focusRequester(lineFocusRequester) else Modifier,
                                        )
                                        .onFocusChanged { if (it.isFocused) focusedCandidateIndex = index }
                                        .clickable(role = Role.Button) {
                                            android.util.Log.i(PLATFORM_LIVE_LOG_TAG, "【手动切换候选】from=${lineStream.safeActiveCandidateIndex}, to=$index, cdn=${candidate.cdn}")
                                            actions.switchPlatformLiveCandidate(index)
                                            showLineControl = false
                                            chrome.bump()
                                        }
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = buildString {
                                                append(index + 1).append("，")
                                                append(candidate.cdn).append("，")
                                                append(candidate.protocol.uppercase())
                                                if (selected) append("，当前线路")
                                            }
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${index + 1}", modifier = Modifier.width(24.dp), color = if (selected) Color(0xFF7AE2BA) else Color.White.copy(alpha = 0.6f))
                                    Text(
                                        text = "${candidate.cdn} | ${candidate.protocol.uppercase()}",
                                        modifier = Modifier.weight(1f),
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.8f),
                                        // CDN 名称较长时单行省略，避免每行高度不一致、挤占线路列表。
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) Text("当前", color = Color(0xFF7AE2BA), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val url = lineStream.activeCandidateOrNull?.url.orEmpty()
                                if (url.isNotBlank()) {
                                    val clip = android.content.ClipData.newPlainText("live_url", url)
                                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                                }
                            }) { Text("复制 URL") }
                            TextButton(onClick = { showUrlOverlay = true; showLineControl = false }) { Text("查看完整 URL") }
                            TextButton(onClick = { showLineControl = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformLivePlayerBadge(
    room: PlatformLiveRoom,
    stream: ResolvedPlatformLiveStream?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color(0x99000000))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = stream?.title ?: room.title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stream?.takeIf { it.hasCandidates }?.let {
                val candidate = it.activeCandidateOrNull
                "${it.quality} / ${it.protocol.uppercase()} / ${candidate?.cdn.orEmpty()} ${it.safeActiveCandidateIndex + 1}/${it.candidates.size}"
            } ?: room.anchor,
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatOnline(value: Int): String {
    return when {
        value <= 0 -> "正在直播"
        value < 10_000 -> "$value 人气"
        else -> "${value / 10_000}.${(value % 10_000) / 1_000} 万人气"
    }
}

private const val PLATFORM_LIVE_BADGE_HIDE_DELAY_MS = 4_000L
private const val PLATFORM_LIVE_LOG_TAG = "TVBoxPlatformLive"
// 首帧未出时的起播等待：平台解析 + CDN 建连在弱网下常超过 5s，此前 5s 判定过激会误切线路。
private const val PLATFORM_LIVE_FIRST_FRAME_TIMEOUT_MS = 12_000L
// 已经出过画面后再缓冲，判定为断流的等待时间。
private const val PLATFORM_LIVE_STALL_TIMEOUT_MS = 6_000L

