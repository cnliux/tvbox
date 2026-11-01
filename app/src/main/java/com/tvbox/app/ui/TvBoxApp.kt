package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Divider
import com.tvbox.app.data.BuiltInLiveSources
import com.tvbox.app.data.TextFileExporter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import com.tvbox.app.domain.continueWatching
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.AiProvider
import com.tvbox.app.domain.AiProviders
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.ui.components.AppHeader
import com.tvbox.app.ui.components.AppNavigationRail
import com.tvbox.app.ui.components.CategoryPill
import com.tvbox.app.ui.components.DoubanHotPosterCard
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.HistoryItemCard
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.MoviePosterCard
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens
import com.tvbox.app.ui.theme.TvLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun TvBoxApp(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onStartAiVoiceInput: () -> Unit = {},
    onStartUpdateDownload: () -> Unit = actions::startUpdateDownload,
    onInstallUpdate: (String) -> Unit = {},
    onOpenUpdateSettings: () -> Unit = {},
    onSaveUpdateApk: (String) -> Unit = {},
) {
    val screenStateHolder = rememberSaveableStateHolder()
    Box(modifier = Modifier.fillMaxSize()) {
        val showNavigationRail = state.appSettings.theme == TvTheme.Cinema &&
            state.screen != TvScreen.Player &&
            state.screen != TvScreen.Live
        if (showNavigationRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavigationRail(
                    screen = state.screen,
                    onHome = actions::openHome,
                    onAiRecommend = actions::openAiRecommend,
                    onLive = actions::openLive,
                    onPlatformLive = actions::openPlatformLive,
                    onSettings = actions::openSettings,
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(TvColors.TextTertiary.copy(alpha = 0.55f)),
                )
                Box(modifier = Modifier.weight(1f)) {
                    TvBoxScreen(
                        holder = screenStateHolder,
                        state = state,
                        actions = actions,
                        onStartAiVoiceInput = onStartAiVoiceInput,
                    )
                }
            }
        } else {
            val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
            if (isMobile) {
                MobileNavWrapper(
                    state = state,
                    actions = actions,
                    onStartAiVoiceInput = onStartAiVoiceInput,
                ) { navScreen ->
                    MobileNavScreenContent(
                        holder = screenStateHolder,
                        state = state,
                        actions = actions,
                        navScreen = navScreen,
                        onStartAiVoiceInput = onStartAiVoiceInput,
                    )
                }
            } else {
                TvBoxScreen(
                    holder = screenStateHolder,
                    state = state,
                    actions = actions,
                    onStartAiVoiceInput = onStartAiVoiceInput,
                )
            }
        }
        AppUpdateDialog(
            state = state,
            actions = actions,
            onStartUpdateDownload = onStartUpdateDownload,
            onInstallUpdate = onInstallUpdate,
            onSaveUpdateApk = onSaveUpdateApk,
        )
        UpdateDownloadCard(
            state = state,
            onRetry = onStartUpdateDownload,
            onInstall = onInstallUpdate,
            onOpenSettings = onOpenUpdateSettings,
            onSaveApk = onSaveUpdateApk,
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        )
    }
}

@Composable
private fun UpdateDownloadCard(
    state: TvBoxUiState,
    onRetry: () -> Unit,
    onInstall: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveApk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val update = state.availableUpdate ?: return
    if (state.updateDialogVisible || (!state.updateDownloading && state.updateDownloadedApkPath == null && state.updateError == null)) return
    Surface(
        modifier = modifier.width(240.dp), shape = RoundedCornerShape(14.dp),
        color = Color(0xEE151C19), contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("更新 ${update.versionName}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (state.updateDownloading) Text(state.updateDownloadProgress?.let { "$it%" } ?: "下载中",
                    style = MaterialTheme.typography.labelMedium, color = Color(0xFF7AE2BA))
            }
            if (state.updateDownloading) {
                val progress = state.updateDownloadProgress
                if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF7AE2BA), trackColor = Color.White.copy(alpha = 0.15f))
                Text("可继续使用，完成后打开安装界面", style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f))
            } else if (state.updateDownloadedApkPath != null) {
                Text(if (state.updateAwaitingInstallPermission) "下载完成，等待安装授权" else "下载完成",
                    style = MaterialTheme.typography.bodySmall)
                state.updateError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                }
                state.updateExportMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                }
                TextButton(onClick = { onInstall(state.updateDownloadedApkPath) }, enabled = !state.updateExporting) {
                    Text(when {
                        state.updateAwaitingInstallPermission -> "授权并安装"
                        state.updateError != null -> "重试安装"
                        else -> "安装更新"
                    }, color = Color.White)
                }
                TextButton(onClick = { onSaveApk(state.updateDownloadedApkPath) }, enabled = !state.updateExporting) {
                    Text(if (state.updateExporting) "正在保存…" else "保存安装包", color = Color.White)
                }
                if (state.updateAwaitingInstallPermission) {
                    TextButton(onClick = onOpenSettings) { Text("系统设置", color = Color.White) }
                }
            } else {
                Text(state.updateError.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                TextButton(onClick = onRetry) { Text("重新下载", color = Color.White) }
            }
        }
    }
}

@Composable
private fun TvBoxScreen(
    holder: SaveableStateHolder,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onStartAiVoiceInput: () -> Unit,
) {
    holder.SaveableStateProvider(state.screen.name) {
    when (state.screen) {
        TvScreen.Home -> HomeScreen(state = state, actions = actions)
        TvScreen.History -> HistoryScreen(state = state, actions = actions)
        TvScreen.Search -> SearchScreen(state = state, actions = actions)
        TvScreen.Detail -> DetailScreen(state = state, actions = actions)
        TvScreen.Player -> PlayerScreen(state = state, actions = actions)
        TvScreen.Live -> LiveScreen(state = state, actions = actions)
        TvScreen.PlatformLive -> PlatformLiveScreen(state = state, actions = actions)
        TvScreen.Settings -> SettingsScreen(state = state, actions = actions)
        TvScreen.SourceManager -> SourceManagementScreen(state = state, actions = actions)
        TvScreen.LiveSourceManager -> LiveSourceManagementScreen(state = state, actions = actions)
        TvScreen.VideoSourceManager -> VideoSourceManagementScreen(state = state, actions = actions)
        TvScreen.AiRecommend -> AiRecommendScreen(
            state = state,
            actions = actions,
            onStartVoiceInput = onStartAiVoiceInput,
        )
    }
}
}

@Composable
private fun AppUpdateDialog(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    onStartUpdateDownload: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onSaveUpdateApk: (String) -> Unit,
) {
    val update = state.availableUpdate ?: return
    if (!state.updateDialogVisible) return
    val downloadedApkPath = state.updateDownloadedApkPath
    AlertDialog(
        onDismissRequest = actions::dismissUpdateDialog,
        title = {
            Text("发现新版本 ${update.versionName}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前版本可更新到 ${update.versionName}，请选择是否现在更新。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.changelog.isNotEmpty()) {
                    Text(
                        text = update.changelog.joinToString(separator = "\n") { "- $it" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.updateDownloading) {
                    val progress = state.updateDownloadProgress
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("下载进度 $progress%")
                    }
                }
                if (downloadedApkPath != null) {
                    Text(
                        text = "安装包已下载完成，可直接安装或保存后手动安装。",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { onSaveUpdateApk(downloadedApkPath) }, enabled = !state.updateExporting) {
                        Text(if (state.updateExporting) "正在保存…" else "保存安装包")
                    }
                    state.updateExportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (state.updateError != null) {
                    Text(
                        text = state.updateError,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !state.updateDownloading && !state.updateExporting,
                onClick = {
                    if (downloadedApkPath != null) {
                        onInstallUpdate(downloadedApkPath)
                    } else {
                        onStartUpdateDownload()
                    }
                },
            ) {
                Text(
                    when {
                        state.updateDownloading -> "下载中"
                        downloadedApkPath != null && state.updateAwaitingInstallPermission -> "授权并安装"
                        downloadedApkPath != null && state.updateError != null -> "重试安装"
                        downloadedApkPath != null -> "安装更新"
                        state.updateError != null -> "重新下载"
                        else -> "立即更新"
                    },
                )
            }
        },
        dismissButton = if (!update.force) {
            {
                TextButton(
                    enabled = !state.updateDownloading,
                    onClick = actions::dismissUpdateDialog,
                ) {
                    Text("稍后再说")
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun HomeScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    if (isMobile) {
        MobileHomeFeed(state = state, actions = actions)
        return
    }
    PageSurface { padding ->
        val apiLineName = state.selectedApiLine?.name ?: "资源"
        val cinemaTheme = state.appSettings.theme == TvTheme.Cinema
        val homeSubtitle = when {
            state.isShowingDoubanHot -> "豆瓣热播 / 共 ${state.total} 部"
            state.doubanHotFallbackActive -> "$apiLineName 最近更新 / 共 ${state.total} 部"
            else -> "$apiLineName 数据 / 共 ${state.total} 部影片"
        }
        val allCategoryFocusRequester = remember { FocusRequester() }
        val navigationFocusRequester = remember { FocusRequester() }
        val navigationScope = rememberCoroutineScope()
        var forceNavigationVisible by remember { mutableStateOf(false) }
        var movingToNavigation by remember { mutableStateOf(false) }
        var navigationUpHeld by remember { mutableStateOf(false) }
        val movieGridState = rememberLazyGridState()
        val homeFocus = rememberReturnFocus()
        val homeTopContentVisible by remember {
            derivedStateOf {
                movieGridState.firstVisibleItemIndex == 0 &&
                    movieGridState.firstVisibleItemScrollOffset == 0
            }
        }
        val showHomeTopContent = forceNavigationVisible || homeTopContentVisible || state.homeLoading || state.homeError != null
        LaunchedEffect(Unit) {
            if (homeFocus.lastKey == null) runCatching { allCategoryFocusRequester.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && navigationUpHeld) {
                        if (event.type == KeyEventType.KeyUp) navigationUpHeld = false
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_1,
                        AndroidKeyEvent.KEYCODE_NUMPAD_1,
                        -> {
                            actions.openHistory()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_2,
                        AndroidKeyEvent.KEYCODE_NUMPAD_2,
                        -> {
                            actions.openSearch()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_3,
                        AndroidKeyEvent.KEYCODE_NUMPAD_3,
                        -> {
                            actions.openAiRecommend()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_4,
                        AndroidKeyEvent.KEYCODE_NUMPAD_4,
                        -> {
                            actions.openLive()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_5,
                        AndroidKeyEvent.KEYCODE_NUMPAD_5,
                        -> {
                            actions.openPlatformLive()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_6,
                        AndroidKeyEvent.KEYCODE_NUMPAD_6,
                        -> {
                            actions.openSettings()
                            true
                        }
                        else -> false
                    }
                }
                .padding(padding),
        ) {
            AnimatedVisibility(visible = showHomeTopContent) {
                Column {
                    AppHeader(
                        title = if (cinemaTheme) "首页" else "TVBox",
                        subtitle = homeSubtitle,
                        onHistory = actions::openHistory,
                        onSearch = actions::openSearch,
                        onAiRecommend = actions::openAiRecommend,
                        onLive = actions::openLive,
                        onPlatformLive = actions::openPlatformLive,
                        onSettings = actions::openSettings,
                        showShortcutActions = !cinemaTheme,
                        firstActionModifier = Modifier.focusRequester(navigationFocusRequester),
                        titleContent = {
                            HomeSourceDropdownTitle(
                                state = state,
                                actions = actions,
                                cinemaTheme = cinemaTheme,
                            )
                        },
                    )
                    if (cinemaTheme && !state.isShowingDoubanHot) {
                        state.movies.firstOrNull()?.let { featuredMovie ->
                            FeaturedMovieHero(
                                movie = featuredMovie,
                                onOpen = { actions.openDetail(featuredMovie.id) },
                            )
                            Spacer(modifier = Modifier.height(TvDimens.SectionGap))
                        }
                    }
                    HomeCategoryRows(
                        state = state,
                        onHot = actions::selectDoubanHot,
                        onParent = actions::selectParentCategory,
                        onChild = actions::selectChildCategory,
                        hotCategoryModifier = Modifier.focusRequester(allCategoryFocusRequester),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
            when {
                state.homeLoading -> LoadingState(
                    text = "正在加载影片",
                    modifier = Modifier.weight(1f),
                )
                state.homeError != null -> ErrorState(
                    message = state.homeError,
                    onRetry = { actions.refreshHome(forceRefresh = true) },
                    modifier = Modifier.weight(1f),
                )
                else -> MovieGrid(
                    state = state,
                    gridState = movieGridState,
                    onMovieClick = actions::openDetail,
                    onDoubanHotClick = actions::openDoubanHotDetail,
                    onLoadMore = actions::loadNextPage,
                    focus = homeFocus,
                    onContinue = { actions.resumeHistory(it, TvScreen.Home) },
                    onMoveToNavigation = {
                        navigationUpHeld = true
                        forceNavigationVisible = true
                        if (!movingToNavigation) {
                            movingToNavigation = true
                            navigationScope.launch {
                                try {
                                    movieGridState.scrollToItem(0, 0)
                                    // The navigation can be outside composition until AnimatedVisibility expands it.
                                    for (attempt in 0..20) {
                                        withFrameNanos { }
                                        if (runCatching { navigationFocusRequester.requestFocus() }.getOrDefault(false)) break
                                    }
                                } finally {
                                    movingToNavigation = false
                                }
                            }
                        }
                    },
                    onContentFocused = { if (!movingToNavigation) forceNavigationVisible = false },
                    showSectionHeader = cinemaTheme,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeaturedMovieHero(
    movie: com.tvbox.app.domain.Movie,
    onOpen: () -> Unit,
) {
    val heroShape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 165.dp, max = 190.dp)
            .clip(heroShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(TvColors.Surface, TvColors.SurfaceOverlay, Color.Transparent),
                ),
            ),
    ) {
        PosterImage(
            movie = movie,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.38f),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            TvColors.Surface,
                            TvColors.SurfaceOverlay.copy(alpha = 0.82f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "今日热播",
                color = TvColors.AccentStrong,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = movie.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(movie.year, movie.typeName, movie.remarks)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("  ·  "),
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (movie.description.isNotBlank()) {
                Text(
                    text = movie.description,
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) {
                    Text("播放")
                }
                TextButton(onClick = onOpen) {
                    Text("详情")
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    PageSurface { padding ->
        var confirmClearHistory by remember { mutableStateOf(false) }
        val historyGrid = rememberLazyGridState()
        val historyFocus = rememberReturnFocus()
        RestoreGridFocus(historyFocus, historyGrid, state.historyItems.mapIndexed { index, item -> "${item.apiLineId}:${item.movieId}" to index })
        if (confirmClearHistory) {
            AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { Text("清空历史") },
                text = { Text("确定要清空全部观看历史吗？清空后无法从历史页继续播放。") },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmClearHistory = false
                            actions.clearHistory()
                        },
                    ) {
                        Text("清空")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearHistory = false }) {
                        Text("取消")
                    }
                },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "历史",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = "观看记录：继续上次的线路、集数和播放进度",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = state.historyItems.isNotEmpty(),
                        onClick = { confirmClearHistory = true },
                    ) {
                        Text("清空历史")
                    }
                    Button(onClick = actions::goBack) {
                        Text("返回")
                    }
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
            if (state.historyItems.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("暂无观看历史", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = actions::goBack) {
                        Text("去看影片")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = TvLayout.HistoryGridMinWidth),
                    state = historyGrid,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.historyItems, key = { "${it.apiLineId}:${it.movieId}" }) { item ->
                        HistoryItemCard(item = item, onClick = {
                            historyFocus.mark("${item.apiLineId}:${item.movieId}", state.historyItems.indexOf(item))
                            actions.resumeHistory(item, TvScreen.History)
                        },
                            modifier = historyFocus.modifier("${item.apiLineId}:${item.movieId}", state.historyItems.indexOf(item)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    if (state.appSettings.deviceMode == DeviceMode.Mobile) {
        MobileSettingsScreen(state = state, actions = actions)
        return
    }
    PageSurface { padding ->
        var confirmClearPlaybackStats by remember { mutableStateOf(false) }
        if (confirmClearPlaybackStats) {
            AlertDialog(
                onDismissRequest = { confirmClearPlaybackStats = false },
                title = { Text("清空线路统计") },
                text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。") },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmClearPlaybackStats = false
                            actions.clearPlaybackStats()
                        },
                    ) {
                        Text("清空")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearPlaybackStats = false }) {
                        Text("取消")
                    }
                },
            )
        }
        if (state.remoteManageDialogVisible) {
            RemoteManageDialog(state = state, actions = actions)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = "设备模式、外观与播放设置",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsActionButton(text = "返回", onClick = actions::goBack)
            }
            Spacer(modifier = Modifier.height(22.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TvLayout.SettingsGridMinWidth),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "设备模式",
                        subtitle = "默认使用电视模式；手机用户可选择手机模式。设置保存在本机。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DeviceModeSetting(
                        selectedMode = state.appSettings.deviceMode,
                        onModeSelect = actions::updateDeviceMode,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "外观",
                        subtitle = "默认主题保留原有界面；影院主题使用左侧图标导航和影院色板。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ThemeSetting(
                        selectedTheme = state.appSettings.theme,
                        onThemeSelect = actions::updateTheme,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FontScaleSetting(
                        selectedFontScale = state.appSettings.fontScale,
                        onFontScaleSelect = actions::updateFontScale,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "首页",
                        subtitle = "控制首页是否显示“继续观看”入口，默认关闭。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "继续观看",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (state.appSettings.homeContinueWatchingEnabled) {
                                    "已开启，首页会显示最近观看记录"
                                } else {
                                    "已关闭，首页不显示继续观看"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.appSettings.homeContinueWatchingEnabled,
                            onCheckedChange = actions::updateHomeContinueWatching,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "视频接口",
                        subtitle = "不内置视频接口，可扫码或导入添加 MacCms 自定义视频接口。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeApiLineSetting(
                        apiLines = state.apiLines,
                        selectedApiLine = state.selectedApiLine,
                        customLineCount = state.appSettings.customVideoApiLines.size,
                        onSelect = actions::updateHomeApiLine,
                        onOpenConfig = actions::openRemoteManageDialog,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "源管理",
                        subtitle = "直播源、自定义直播源与首页渲染数据均进入独立管理页，支持网页管理，并保留地址导入以解析视频源与直播源数据。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "源管理入口", style = MaterialTheme.typography.titleMedium)
                            Text(text = "视频接口、直播源与首页渲染数据统一管理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsActionButton(text = "视频接口管理", onClick = actions::openVideoSourceManager)
                            SettingsActionButton(text = "直播源管理", onClick = actions::openLiveSourceManager)
                            SettingsActionButton(text = "全部源管理", onClick = actions::openSourceManager)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "手机远程管理", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "无需 token，同一局域网手机浏览器可管理视频源与直播源、导入订阅、屏蔽视频分类",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsActionButton(text = "打开远程管理（网页）", onClick = actions::openRemoteManageDialog)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "大模型",
                        subtitle = "未填写 API Key 时使用 APK 内置 AI 配置。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AiProviderSetting(
                        selectedProvider = AiProviders.find(state.appSettings.aiProviderId),
                        modelName = state.appSettings.aiModelName,
                        apiKey = state.appSettings.aiApiKey,
                        onProviderSelect = actions::updateAiProvider,
                        onOpenConfig = actions::openRemoteManageDialog,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "播放管家",
                        subtitle = "控制播放失败或卡顿时是否自动尝试其它线路。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动换线",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (state.appSettings.playbackAgentAutoSwitchEnabled) {
                                    "播放失败或缓冲过久时自动切换线路"
                                } else {
                                    "已关闭，播放器仍可手动换线"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                            onCheckedChange = actions::updatePlaybackAgentAutoSwitch,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "线路质量统计",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = state.playbackHealth.qualitySummaryText(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsActionButton(
                            text = "清空统计",
                            enabled = state.playbackHealth.entryCount > 0,
                            onClick = { confirmClearPlaybackStats = true },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "更新",
                        subtitle = "控制应用启动时是否自动检查 GitHub Release 更新。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启动时检查更新",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (state.appSettings.checkUpdatesOnStartup) "已开启" else "已关闭",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.updateError != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = state.updateError,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsActionButton(
                                text = if (state.updateChecking) "检查中" else "立即检查",
                                enabled = !state.updateChecking,
                                onClick = { actions.checkForAppUpdate(showError = true) },
                            )
                            Switch(
                                checked = state.appSettings.checkUpdatesOnStartup,
                                onCheckedChange = actions::updateStartupUpdateCheck,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteManageDialog(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val configUrl = state.remoteManageServerUrl
    AlertDialog(
        onDismissRequest = actions::closeRemoteManageDialog,
        title = { Text("手机远程管理") },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (configUrl == null) {
                    Box(
                        modifier = Modifier.size(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在启动远程管理服务...")
                    }
                } else {
                    val qrBitmap = remember(configUrl) { createQrBitmap(configUrl, 320) }
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "远程管理二维码",
                        modifier = Modifier.size(220.dp),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "手机扫码，或同一局域网浏览器访问：",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = configUrl ?: "正在生成地址...",
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "无需 token。可管理视频源与直播源、导入订阅、删除平台站点，并屏蔽视频分类。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "页面里的修改会立即同步到电视；也可填写 AI 模型与 API Key。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.remoteManageMessage != null) {
                        Text(
                            text = state.remoteManageMessage,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.remoteManageServerError != null) {
                        Text(
                            text = state.remoteManageServerError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            SettingsActionButton(text = "完成", onClick = actions::closeRemoteManageDialog)
        },
    )
}


@Composable
private fun AiProviderSetting(
    selectedProvider: AiProvider,
    modelName: String,
    apiKey: String,
    onProviderSelect: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayModelName = modelName.ifBlank { selectedProvider.defaultModel }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(0.9f)) {
            Text(
                text = "大模型选择",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box {
                SettingsActionButton(
                    text = "${selectedProvider.name} ▾",
                    onClick = { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    AiProviders.all.forEach { provider ->
                        val selected = provider.id == selectedProvider.id
                        DropdownMenuItem(
                            text = { Text(if (selected) "已选 · ${provider.name}" else provider.name) },
                            onClick = {
                                expanded = false
                                onProviderSelect(provider.id)
                            },
                        )
                    }
                }
            }
        }
        SettingsActionButton(
            text = "模型：$displayModelName",
            onClick = onOpenConfig,
            modifier = Modifier.weight(1.25f),
        )
        SettingsActionButton(
            text = if (apiKey.isBlank()) "API Key：未配置" else "API Key：已配置",
            onClick = onOpenConfig,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier.tvFocusScale(
                        shape = shape,
                        idleBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .onFocusChanged { focused = enabled && (it.isFocused || it.hasFocus) }
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled = enabled),
        shape = shape,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            focused -> MaterialTheme.colorScheme.primary
            selected -> TvColors.AccentSoft
            else -> TvColors.Surface
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            focused -> MaterialTheme.colorScheme.onPrimary
            selected -> TvColors.AccentStrong
            else -> MaterialTheme.colorScheme.onSurface
        },
        tonalElevation = if (focused) 8.dp else 0.dp,
        shadowElevation = if (focused) 10.dp else 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeSourceDropdownTitle(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    cinemaTheme: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val currentLine = state.selectedApiLine
    val lines = state.apiLines
    val shape = RoundedCornerShape(12.dp)
    Box {
        Surface(
            modifier = Modifier
                .clip(shape)
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .clickable(enabled = lines.isNotEmpty()) { expanded = true }
                .focusable(enabled = lines.isNotEmpty()),
            shape = shape,
            color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentLine?.name ?: "选择视频源",
                    style = if (cinemaTheme) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineLarge
                    },
                    fontWeight = if (cinemaTheme) FontWeight.Medium else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvColors.TextSecondary,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (lines.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无可切换的视频源") },
                    onClick = { expanded = false },
                )
            } else {
                lines.forEach { line ->
                    val selected = line.id == currentLine?.id
                    DropdownMenuItem(
                        text = { Text(if (selected) "已选 · ${line.name}" else line.name) },
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
private fun HomeApiLineSetting(
    apiLines: List<ApiLine>,
    selectedApiLine: ApiLine?,
    customLineCount: Int,
    onSelect: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "首页渲染数据",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = selectedApiLine?.let { "当前使用：${it.name}" } ?: "请选择首页资源站",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (customLineCount > 0) {
                    "已添加 $customLineCount 条自定义 MacCms 接口"
                } else {
                    "暂无自定义接口"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                SettingsActionButton(
                    text = "${selectedApiLine?.name ?: "选择资源"} ▾",
                    enabled = apiLines.isNotEmpty(),
                    onClick = { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    apiLines.forEach { line ->
                        val selected = line.id == selectedApiLine?.id
                        DropdownMenuItem(
                            text = {
                                Text(if (selected) "已选 · ${line.name}" else line.name)
                            },
                            onClick = {
                                expanded = false
                                onSelect(line.id)
                            },
                        )
                    }
                }
            }
            SettingsActionButton(
                text = "添加接口",
                onClick = onOpenConfig,
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String,
    subtitle: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeCategoryRows(
    state: TvBoxUiState,
    onHot: () -> Unit,
    onParent: (Int) -> Unit,
    onChild: (Int) -> Unit,
    hotCategoryModifier: Modifier = Modifier,
) {
    val parentCategories = state.categories.filter { it.parentId == 0 }
    val selectedParent = state.selectedParentCategoryId
        ?.let { parentId -> state.categories.firstOrNull { it.id == parentId } }
    val childCategories = selectedParent
        ?.let { parent -> state.categories.filter { it.parentId == parent.id } }
        .orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                CategoryPill(
                    label = "热播",
                    selected = state.homeFeedMode == HomeFeedMode.DoubanHot,
                    onClick = onHot,
                    modifier = hotCategoryModifier,
                )
            }
            items(parentCategories, key = { it.id }) { category ->
                CategoryPill(
                    label = category.name,
                    selected = state.selectedParentCategoryId == category.id,
                    onClick = { onParent(category.id) },
                )
            }
        }
        if (selectedParent != null && childCategories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    CategoryPill(
                        label = selectedParent.allChildrenLabel(),
                        selected = state.selectedCategoryId == null,
                        onClick = { onParent(selectedParent.id) },
                    )
                }
                items(childCategories, key = { it.id }) { category ->
                    CategoryPill(
                        label = category.name,
                        selected = state.selectedCategoryId == category.id,
                        onClick = { onChild(category.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieGrid(
    state: TvBoxUiState,
    gridState: LazyGridState,
    onMovieClick: (Int) -> Unit,
    onDoubanHotClick: (com.tvbox.app.domain.DoubanHotItem) -> Unit,
    onLoadMore: () -> Unit,
    showSectionHeader: Boolean,
    focus: ReturnFocus,
    onContinue: (com.tvbox.app.domain.WatchHistoryItem) -> Unit,
    onMoveToNavigation: () -> Unit,
    onContentFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadMoreFocused by remember { mutableStateOf(false) }
    val contextKey = "${state.selectedApiLineId}:${state.homeFeedMode}:${state.selectedParentCategoryId}:${state.selectedCategoryId}"
    var savedContext by rememberSaveable { mutableStateOf(contextKey) }
    LaunchedEffect(contextKey) {
        if (savedContext != contextKey) {
            focus.reset()
            gridState.scrollToItem(0)
            savedContext = contextKey
        }
        loadMoreFocused = false
    }
    val recent = if (state.appSettings.homeContinueWatchingEnabled) {
        state.historyItems.continueWatching()
    } else {
        emptyList()
    }
    val itemOffset = (if (recent.isNotEmpty()) 1 else 0) + (if (state.homeNotice != null) 1 else 0)
    val focusItems = recent.map { "continue:${it.apiLineId}:${it.movieId}" to 0 } +
        if (state.isShowingDoubanHot) state.doubanHotMovies.mapIndexed { index, item -> "douban:${item.doubanId}" to index + itemOffset }
        else state.movies.mapIndexed { index, movie -> "${movie.apiLineId}:${movie.id}" to index + itemOffset }
    RestoreGridFocus(focus, gridState, focusItems)
    Column(modifier = modifier.fillMaxSize()) {
        if (showSectionHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.isShowingDoubanHot) "豆瓣热播" else "正在热播",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${state.homeItemCount} / ${state.total} 部",
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TvLayout.PosterGridMinWidth),
            state = gridState,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (recent.isNotEmpty()) {
                item(key = "continue-watching", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        Text("继续观看", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) false
                                else {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onMoveToNavigation()
                                    true
                                }
                            }.focusGroup(),
                        ) {
                            items(recent, key = { "${it.apiLineId}:${it.movieId}" }) { history ->
                                HistoryItemCard(
                                    item = history,
                                    onClick = {
                                        focus.mark("continue:${history.apiLineId}:${history.movieId}", 0)
                                        onContinue(history)
                                    },
                                    modifier = Modifier.width(220.dp).then(
                                        focus.modifier("continue:${history.apiLineId}:${history.movieId}", 0)
                                    ).onFocusChanged { if (it.hasFocus) onContentFocused() },
                                )
                            }
                        }
                    }
                }
            }
            state.homeNotice?.let { notice ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        color = TvColors.SurfaceRaised,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = notice,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = TvColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (state.isShowingDoubanHot) {
                items(state.doubanHotMovies, key = { it.doubanId.ifBlank { it.title } }) { item ->
                    DoubanHotPosterCard(
                        item = item,
                        resolving = state.resolvingDoubanHotId == item.doubanId,
                        onClick = {
                            focus.mark("douban:${item.doubanId}", state.doubanHotMovies.indexOf(item) + itemOffset)
                            onDoubanHotClick(item)
                        },
                        modifier = focus.modifier("douban:${item.doubanId}", state.doubanHotMovies.indexOf(item) + itemOffset)
                            .onFocusChanged { if (it.hasFocus) onContentFocused() },
                        posterAspectRatio = 0.78f,
                    )
                }
            } else {
                items(state.movies, key = { "${it.apiLineId}-${it.id}" }) { movie ->
                    MoviePosterCard(
                        movie = movie,
                        onClick = {
                            focus.mark("${movie.apiLineId}:${movie.id}", state.movies.indexOf(movie) + itemOffset)
                            onMovieClick(movie.id)
                        },
                        modifier = focus.modifier("${movie.apiLineId}:${movie.id}", state.movies.indexOf(movie) + itemOffset)
                            .onFocusChanged { if (it.hasFocus) onContentFocused() },
                        posterAspectRatio = 0.78f,
                    )
                }
            }
            if (state.canLoadMore || state.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(
                            onClick = {
                                if (!state.loadingMore) {
                                    onLoadMore()
                                }
                            },
                            modifier = Modifier.onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    if (!loadMoreFocused && !state.loadingMore) {
                                        onLoadMore()
                                    }
                                    loadMoreFocused = true
                                } else {
                                    loadMoreFocused = false
                                }
                            },
                        ) {
                            Text(if (state.loadingMore) "加载中..." else "加载更多")
                        }
                    }
                }
            } else if (state.homeItemCount > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "已经到底了",
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = TvColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/** 手机模式首页竖向信息流：继续观看横滑卡片 + 分类切换 + 竖排电影卡片 + 触底加载更多 */
@Composable
private fun MobileHomeFeed(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val movieGridState = rememberLazyGridState()
    val contextKey = "${state.selectedApiLineId}:${state.homeFeedMode}:${state.selectedParentCategoryId}:${state.selectedCategoryId}"
    var savedContext by rememberSaveable { mutableStateOf(contextKey) }
    LaunchedEffect(contextKey) {
        if (savedContext != contextKey) {
            movieGridState.scrollToItem(0)
            savedContext = contextKey
        }
    }
    // 触底加载更多
    var canLoadMore by remember { mutableStateOf(false) }
    LaunchedEffect(movieGridState.layoutInfo) {
        val info = movieGridState.layoutInfo
        canLoadMore = info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 1 } == true
    }
    LaunchedEffect(canLoadMore, state.canLoadMore, state.loadingMore) {
        if (canLoadMore && state.canLoadMore && !state.loadingMore) {
            actions.loadNextPage()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // 分类标签（热播 / 目录）- 顶部横滑
        val parentCategories = state.categories.filter { it.parentId == 0 }
        val selectedParent = state.selectedParentCategoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
        val childCategories = selectedParent?.let { parent ->
            state.categories.filter { it.parentId == parent.id }
        }.orEmpty()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item {
                CategoryPill(
                    label = "热播",
                    selected = state.homeFeedMode == HomeFeedMode.DoubanHot,
                    onClick = actions::selectDoubanHot,
                )
            }
            items(parentCategories, key = { it.id }) { cat ->
                CategoryPill(
                    label = cat.name,
                    selected = state.selectedParentCategoryId == cat.id,
                    onClick = { actions.selectParentCategory(cat.id) },
                )
            }
        }
        if (selectedParent != null && childCategories.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                item {
                    CategoryPill(
                        label = selectedParent.allChildrenLabel(),
                        selected = state.selectedCategoryId == null,
                        onClick = { actions.selectParentCategory(selectedParent.id) },
                    )
                }
                items(childCategories, key = { it.id }) { cat ->
                    CategoryPill(
                        label = cat.name,
                        selected = state.selectedCategoryId == cat.id,
                        onClick = { actions.selectChildCategory(cat.id) },
                    )
                }
            }
        }
        // 继续观看
        val recent = if (state.appSettings.homeContinueWatchingEnabled) {
            state.historyItems.continueWatching()
        } else emptyList()
        if (recent.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "继续观看",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    items(recent, key = { "${it.apiLineId}:${it.movieId}" }) { history ->
                        HistoryItemCard(
                            item = history,
                            onClick = { actions.resumeHistory(history, TvScreen.Home) },
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
        // 提示
        state.homeNotice?.let { notice ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = TvColors.SurfaceRaised,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = TvColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // 影片列表
        when {
            state.homeLoading -> LoadingState(text = "正在加载影片", modifier = Modifier.weight(1f))
            state.homeError != null -> ErrorState(
                message = state.homeError,
                onRetry = { actions.refreshHome(forceRefresh = true) },
                modifier = Modifier.weight(1f),
            )
            else -> {
                val apiLineName = state.selectedApiLine?.name ?: "资源"
                val feedTitle = when {
                    state.isShowingDoubanHot -> "豆瓣热播 · 共 ${state.total} 部"
                    state.doubanHotFallbackActive -> "$apiLineName 最近更新 · 共 ${state.total} 部"
                    else -> "$apiLineName · 共 ${state.total} 部"
                }
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    state = movieGridState,
                    contentPadding = PaddingValues(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isShowingDoubanHot) {
                        items(state.doubanHotMovies, key = { it.doubanId.ifBlank { it.title } }) { item ->
                            DoubanHotPosterCard(
                                item = item,
                                resolving = state.resolvingDoubanHotId == item.doubanId,
                                onClick = { actions.openDoubanHotDetail(item) },
                                posterAspectRatio = 0.78f,
                            )
                        }
                    } else {
                        items(state.movies, key = { "${it.apiLineId}-${it.id}" }) { movie ->
                            MoviePosterCard(
                                movie = movie,
                                onClick = { actions.openDetail(movie.id) },
                                posterAspectRatio = 0.78f,
                            )
                        }
                    }
                    if (state.canLoadMore || state.loadingMore) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                } else {
                                    Button(onClick = actions::loadNextPage) { Text("加载更多") }
                                }
                            }
                        }
                    } else if (state.homeItemCount > 0) {
                        item {
                            Text(
                                text = "已经到底了",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                color = TvColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSetting(
    selectedTheme: TvTheme,
    onThemeSelect: (TvTheme) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("界面主题", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "当前：${selectedTheme.displayName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "选择后立即生效，设置会保存到本机。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTheme.entries.forEach { theme ->
                SettingsActionButton(
                    text = if (selectedTheme == theme) "✓ ${theme.displayName}" else theme.displayName,
                    selected = selectedTheme == theme,
                    onClick = { onThemeSelect(theme) },
                )
            }
        }
    }
}

@Composable
private fun DeviceModeSetting(selectedMode: DeviceMode, onModeSelect: (DeviceMode) -> Unit) {
    val context = LocalContext.current
    val detectedMode = remember(context) { context.detectDeviceMode() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("当前：${selectedMode.displayName}", style = MaterialTheme.typography.titleMedium)
        Text(selectedMode.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text("系统自动识别：${detectedMode.displayName}。播放行为以你的选择为准。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(DeviceMode.entries, key = { it.storageKey }) { mode ->
                SettingsActionButton(
                    text = if (selectedMode == mode) "✓ ${mode.displayName}" else mode.displayName,
                    selected = selectedMode == mode,
                    onClick = { onModeSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun FontScaleSetting(
    selectedFontScale: TvFontScale,
    onFontScaleSelect: (TvFontScale) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("字体大小", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "当前：${selectedFontScale.displayName}。文字和内容卡片会立即按此大小调整。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TvFontScale.entries, key = { it.storageKey }) { fontScale ->
                SettingsActionButton(
                    text = if (selectedFontScale == fontScale) {
                        "✓ ${fontScale.displayName}"
                    } else {
                        fontScale.displayName
                    },
                    selected = selectedFontScale == fontScale,
                    onClick = { onFontScaleSelect(fontScale) },
                )
            }
        }
    }
}

private fun Category.allChildrenLabel(): String {
    val baseName = name.removeSuffix("片")
    return "全部$baseName"
}

private fun PlaybackHealthSnapshot.qualitySummaryText(): String {
    if (entryCount <= 0) return "暂无线路质量记录"
    return "已记录 $entryCount 条线路表现｜成功 $successCount｜失败 $failureCount｜卡顿 $slowBufferCount｜保留最近 30 天"
}

private fun createQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}


// ===== Source Management Screens =====

@Composable
private fun SourceManagerSetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importDialogVisible by remember { mutableStateOf(false) }
    var importDraft by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    fun exportToDownloads(fileName: String, content: String) {
        scope.launch {
            try {
                val location = TextFileExporter(context).save(fileName, content)
                exportMessage = "已导出到 $location"
            } catch (error: Exception) {
                exportMessage = "导出失败：${error.message ?: error.toString()}"
            }
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsActionButton(
                text = if (state.sourceTesting) "测速 ${state.sourceTestCompleted}/${state.sourceTestTotal}" else "一键测速并排列",
                enabled = !state.sourceTesting && state.apiLines.isNotEmpty(),
                onClick = actions::testAllSourcesAndSort,
            )
            SettingsActionButton(
                text = "按测速排序",
                enabled = !state.sourceTesting && !state.sourceImporting && state.apiLines.isNotEmpty(),
                onClick = actions::sortSourcesBySpeed,
            )
            SettingsActionButton(
                text = "删除无效源",
                enabled = !state.sourceTesting && !state.sourceImporting && state.apiLines.isNotEmpty(),
                onClick = actions::deleteInvalidSources,
            )
            SettingsActionButton(
                text = "导入配置",
                enabled = !state.sourceImporting,
                onClick = { importDraft = ""; actions.dismissSourceImportMessage(); importDialogVisible = true },
            )
            SettingsActionButton(
                text = "导出 TVBox",
                enabled = !state.sourceImporting && state.apiLines.isNotEmpty(),
                onClick = { exportToDownloads("tvbox_sources.json", actions.sourceConfigTvboxJson()) },
            )
            SettingsActionButton(
                text = "导出 JSON",
                enabled = !state.sourceImporting && state.apiLines.isNotEmpty(),
                onClick = { exportToDownloads("sources.json", actions.sourceConfigSourcesJson()) },
            )
            SettingsActionButton(
                text = "恢复内置",
                enabled = !state.sourceImporting && state.appSettings.disabledBuiltInLineIds.isNotEmpty(),
                onClick = actions::restoreBuiltInLines,
            )
        }
        state.sourceManagerMessage?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = msg, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        exportMessage?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = msg, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (state.sourceTesting) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (state.sourceTestTotal <= 0) 0f else state.sourceTestCompleted.toFloat() / state.sourceTestTotal },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.allSourceLines.isEmpty()) {
                Text(
                    text = "暂无可管理的视频源",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.allSourceLines.forEach { line ->
                val isBuiltIn = state.appSettings.customVideoApiLines.none { it.id == line.id }
                val deleted = isBuiltIn && line.id in state.appSettings.disabledBuiltInLineIds
                val blocked = line.id in state.appSettings.blockedSourceIds
                val selected = line.id == state.selectedApiLine?.id
                val statusSuffix = when {
                    deleted -> "（已删除）"
                    blocked -> "（已屏蔽）"
                    !isBuiltIn -> "（自定义）"
                    else -> ""
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (selected) "已选 " else "") + line.name + statusSuffix,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(sourceLatencyLabel(line.id, state.sourceLatencies))
                                append(" | ")
                                append(line.baseUrls.firstOrNull() ?: "")
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (deleted) {
                        SettingsActionButton(
                            text = "恢复",
                            onClick = { actions.restoreBuiltInLine(line.id) },
                        )
                    } else {
                        if (!blocked) {
                            SettingsActionButton(
                                text = if (state.sourceTesting) "..." else "测速",
                                enabled = !state.sourceTesting,
                                onClick = { actions.testSourceSpeed(line.id) },
                            )
                            SettingsActionButton(
                                text = "屏蔽",
                                onClick = { actions.blockSource(line.id) },
                            )
                        } else {
                            SettingsActionButton(
                                text = "取消屏蔽",
                                onClick = { actions.unblockSource(line.id) },
                            )
                        }
                        SettingsActionButton(
                            text = "删除",
                            onClick = { actions.deleteApiLine(line.id) },
                        )
                    }
                }
            }
        }
    }

    if (importDialogVisible) {
        val importMessage = state.sourceImportMessage
        AlertDialog(
            onDismissRequest = { importDialogVisible = false; actions.dismissSourceImportMessage() },
            title = { Text("导入源配置") },
            text = {
                Column {
                    Text(
                        text = "粘贴 TVBox JSON（含 sites 或加密壳 ** / 2423 AES）、storeHouse 或 sources.json 数组，也可直接输入订阅地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importDraft,
                        onValueChange = { importDraft = it },
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                        placeholder = { Text("https://example.com/tvbox.json 或 {\"sites\":[...]}") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                importDraft = ""
                            },
                        ) { Text("清空草稿") }
                    }
                    if (importMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importMessage,
                            color = if (
                                importMessage.contains("失败") ||
                                importMessage.contains("错误") ||
                                importMessage.contains("不合法") ||
                                importMessage.contains("缺少")
                            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                SettingsActionButton(
                    text = "导入",
                    enabled = importDraft.isNotBlank() && !state.sourceImporting,
                    onClick = { actions.importSourceConfig(importDraft); importDialogVisible = false },
                )
            },
            dismissButton = {
                TextButton(onClick = { importDialogVisible = false; actions.dismissSourceImportMessage() }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LiveSourceSetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var urlsText by remember { mutableStateOf(state.appSettings.liveSourceUrls.joinToString("\n")) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = "自定义直播源地址（每行一个，支持 M3U/TXT）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = urlsText,
            onValueChange = { urlsText = it },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            placeholder = { Text("https://example.com/live.m3u\nhttps://example.com/live.txt") },
            maxLines = 8,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsActionButton(
                text = "保存直播源",
                onClick = {
                    val urls = urlsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    actions.updateLiveSourceUrls(urls)
                    savedMessage = "已保存 ${urls.size} 个直播源"
                },
            )
            SettingsActionButton(
                text = "清空直播源",
                enabled = state.appSettings.liveSourceUrls.isNotEmpty(),
                onClick = {
                    actions.updateLiveSourceUrls(emptyList())
                    urlsText = ""
                    savedMessage = "已清空直播源"
                },
            )
        }
        savedMessage?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BuiltInLiveSourceSetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val deletedIds = state.appSettings.disabledBuiltInLiveSourceIds
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "内置源与自定义源组合使用", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.appSettings.includeBuiltInLiveSources) {
                        "已开启：内置源 + ${state.appSettings.liveSourceUrls.size} 个自定义源"
                    } else {
                        "已关闭：仅使用自定义源"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.appSettings.includeBuiltInLiveSources,
                onCheckedChange = actions::updateIncludeBuiltInLiveSources,
            )
        }
        BuiltInLiveSources.all.forEach { source ->
            val deleted = source.id in deletedIds
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name + if (deleted) "（已删除）" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = source.url,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SettingsActionButton(
                    text = if (deleted) "恢复" else "删除",
                    onClick = {
                        if (deleted) {
                            actions.restoreBuiltInLiveSource(source.id)
                        } else {
                            actions.deleteBuiltInLiveSource(source.id)
                        }
                    },
                )
            }
        }
        if (deletedIds.isNotEmpty()) {
            SettingsActionButton(
                text = "恢复全部内置直播源",
                onClick = actions::restoreBuiltInLiveSources,
            )
        }
    }
}

@Composable
private fun PlatformLiveSiteSetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val blockedIds = state.appSettings.blockedPlatformSiteIds
    val sites = state.platformLiveAllSites.ifEmpty { state.platformLiveSites }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sites.isEmpty()) {
            Text(
                text = "站点列表加载中或当前不可用",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        sites.forEach { site ->
            val blocked = site.id in blockedIds
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = site.name + if (blocked) "（已删除）" else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (blocked) "已删除，可在本页恢复" else site.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SettingsActionButton(
                    text = if (blocked) "恢复" else "删除",
                    onClick = { actions.togglePlatformLiveSiteBlocked(site.id) },
                )
            }
        }
    }
}

private fun sourceLatencyLabel(lineId: String, latencies: Map<String, Long?>): String {
    return when (val latency = latencies[lineId]) {
        null -> if (lineId in latencies) "超时" else "未测速"
        else -> "${latency}ms"
    }
}

@Composable
private fun SourceManagementScreen(state: TvBoxUiState, actions: TvBoxViewModel) {
    PageSurface { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "源管理", style = MaterialTheme.typography.headlineLarge)
                    Text(text = "视频接口、直播源与首页渲染数据统一管理（支持网页管理与地址导入）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsActionButton(text = "视频接口管理", onClick = actions::openVideoSourceManager)
                    SettingsActionButton(text = "直播源管理", onClick = actions::openLiveSourceManager)
                    SettingsActionButton(text = "返回", onClick = actions::goBack)
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TvLayout.SettingsGridMinWidth),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "首页渲染数据（视频接口）",
                        subtitle = "从这里进入独立的视频接口管理页，支持网页管理与地址导入以解析视频源数据。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "当前首页接口：${state.selectedApiLine?.name ?: "未选择"}", style = MaterialTheme.typography.titleMedium)
                            Text(text = "视频接口总数：${state.apiLines.size}（自定义：${state.appSettings.customVideoApiLines.size}）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SettingsActionButton(text = "进入视频接口管理", onClick = actions::openVideoSourceManager)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "直播源",
                        subtitle = "从这里进入独立的直播源管理页，支持网页管理与地址导入以解析直播源数据。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "自定义直播源：${state.appSettings.liveSourceUrls.size} 条", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (state.appSettings.liveSourceUrls.isEmpty()) "未配置自定义直播源（直播将使用导入的源）" else "已配置自定义直播源",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsActionButton(text = "进入直播源管理", onClick = actions::openLiveSourceManager)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSourceManagementScreen(state: TvBoxUiState, actions: TvBoxViewModel) {
    PageSurface { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "视频接口管理", style = MaterialTheme.typography.headlineLarge)
                    Text(text = "首页渲染数据管理（支持网页管理与地址导入以解析视频源数据）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsActionButton(text = "打开远程管理（网页）", onClick = actions::openRemoteManageDialog)
                    SettingsActionButton(text = "返回", onClick = actions::goBack)
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
            if (state.remoteManageDialogVisible) {
                RemoteManageDialog(state = state, actions = actions)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TvLayout.SettingsGridMinWidth),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeApiLineSetting(
                        apiLines = state.apiLines,
                        selectedApiLine = state.selectedApiLine,
                        customLineCount = state.appSettings.customVideoApiLines.size,
                        onSelect = actions::updateHomeApiLine,
                        onOpenConfig = actions::openRemoteManageDialog,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "源管理",
                        subtitle = "测速排序、删除/恢复、导入/导出，支持地址导入以解析视频源数据。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SourceManagerSetting(state = state, actions = actions)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "屏蔽视频分类",
                        subtitle = "屏蔽后该分类不再出现在电视首页的分类与影片列表；可随时恢复。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BlockedVideoCategorySetting(state = state, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun LiveSourceManagementScreen(state: TvBoxUiState, actions: TvBoxViewModel) {
    PageSurface { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "直播源管理", style = MaterialTheme.typography.headlineLarge)
                    Text(text = "直播源与自定义直播源管理（支持网页管理与地址导入以解析直播源数据）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsActionButton(text = "返回", onClick = actions::goBack)
            }
            Spacer(modifier = Modifier.height(22.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TvLayout.SettingsGridMinWidth),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "直播源",
                        subtitle = "自定义直播源地址（M3U/TXT），支持地址导入以解析直播源数据；留空则使用内置直播源。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LiveSourceSetting(state = state, actions = actions)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "内置电视直播源",
                        subtitle = "内置源可与自定义源组合使用，也可逐条删除并随时恢复。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BuiltInLiveSourceSetting(state = state, actions = actions)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsSectionTitle(
                        title = "平台直播站点",
                        subtitle = "斗鱼、虎牙等平台直播站点；删除后可在本页恢复。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PlatformLiveSiteSetting(state = state, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun BlockedVideoCategorySetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var categoryDraft by remember { mutableStateOf("") }
    val blockedCategories = state.appSettings.blockedVideoCategories
    val availableCategories = remember(state.categories, blockedCategories) {
        state.categories.map { it.name }.distinct()
            .filter { it.isNotBlank() && it !in blockedCategories }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (blockedCategories.isEmpty()) {
            Text(
                text = "尚未屏蔽任何视频分类，下方可直接添加分类名。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            blockedCategories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsActionButton(
                        text = "恢复显示",
                        onClick = { actions.removeBlockedVideoCategory(category) },
                    )
                }
            }
            SettingsActionButton(
                text = "全部恢复",
                onClick = actions::clearBlockedVideoCategories,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = categoryDraft,
                onValueChange = { categoryDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入要屏蔽的视频分类名，如 伦理片、福利") },
            )
            SettingsActionButton(
                text = "添加屏蔽",
                enabled = categoryDraft.isNotBlank(),
                onClick = {
                    actions.addBlockedVideoCategory(categoryDraft)
                    categoryDraft = ""
                },
            )
        }
        if (availableCategories.isNotEmpty()) {
            Text(
                text = "快捷屏蔽当前视频源分类：",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableCategories) { category ->
                    SettingsActionButton(
                        text = category,
                        onClick = { actions.addBlockedVideoCategory(category) },
                    )
                }
            }
        }
    }
}

/**
 * 手机模式下按底部导航标签分发内容。Detail / Player / Settings 等子页面
 * 仍由 TvBoxScreen 直接渲染，不经过底部导航包装，以保持系统返回栈完整。
 */
@Composable
private fun MobileNavScreenContent(
    holder: SaveableStateHolder,
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    navScreen: MobileNavScreen,
    onStartAiVoiceInput: () -> Unit,
) {
    when (state.screen) {
        // 详情页、播放页、设置页与源管理页不是底部导航标签页，必须交给 TvBoxScreen 按 screen 渲染，
        // 否则会被映射成 Home 标签页的内容（点海报后看起来像没反应）。
        // 另外 TvBoxScreen 内部已经用同一个 key 注册 SaveableStateProvider，这里不能再包一层，否则 key 重复会崩溃。
        TvScreen.Detail, TvScreen.Player, TvScreen.Settings,
        TvScreen.SourceManager, TvScreen.LiveSourceManager, TvScreen.VideoSourceManager,
        TvScreen.PlatformLive, TvScreen.AiRecommend,
        -> TvBoxScreen(
            holder = holder,
            state = state,
            actions = actions,
            onStartAiVoiceInput = onStartAiVoiceInput,
        )
        else -> holder.SaveableStateProvider(state.screen.name) {
            when (navScreen) {
                MobileNavScreen.Home -> HomeScreen(state = state, actions = actions)
                MobileNavScreen.History -> HistoryScreen(state = state, actions = actions)
                MobileNavScreen.Search -> SearchScreen(state = state, actions = actions)
                MobileNavScreen.Live -> LiveScreen(state = state, actions = actions)
                // 平台直播在 TvBoxScreen 里整页渲染，这里只是保证 when 完整。
                MobileNavScreen.PlatformLive -> PlatformLiveScreen(state = state, actions = actions)
            }
        }
    }
}

// ── 手机模式设置页（竖向卡片列表）────────────────────────────────────────────────

@Composable
private fun MobileSettingsScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }
    // 手机设置页也要渲染远程管理弹窗，否则这里的入口点了没有任何反应。
    if (state.remoteManageDialogVisible) {
        RemoteManageDialog(state = state, actions = actions)
    }
    if (confirmClearPlaybackStats) {
        AlertDialog(
            onDismissRequest = { confirmClearPlaybackStats = false },
            title = { Text("清空线路统计") },
            text = { Text("确定要清空线路质量统计吗？清空后播放管家会重新学习线路表现。") },
            confirmButton = {
                Button(onClick = {
                    confirmClearPlaybackStats = false
                    actions.clearPlaybackStats()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearPlaybackStats = false }) { Text("取消") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Button(onClick = actions::goBack, shape = RoundedCornerShape(8.dp)) { Text("返回") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 设备与外观
            item { SettingsCardSection("设备与外观") }
            item { MobileDeviceModeSetting(state.appSettings.deviceMode, actions::updateDeviceMode) }
            item { MobileThemeSetting(state.appSettings.theme, actions::updateTheme) }
            item { MobileFontScaleSetting(state.appSettings.fontScale, actions::updateFontScale) }

            // 首页
            item { SettingsCardSection("首页") }
            item {
                MobileSwitchRow(
                    label = "继续观看",
                    desc = if (state.appSettings.homeContinueWatchingEnabled) "首页显示最近观看记录" else "首页不显示继续观看",
                    checked = state.appSettings.homeContinueWatchingEnabled,
                    onCheck = actions::updateHomeContinueWatching,
                )
            }
            item {
                MobileHomeApiLineSetting(
                    state.apiLines, state.selectedApiLine, state.appSettings.customVideoApiLines.size,
                    actions::updateHomeApiLine, actions::openRemoteManageDialog,
                )
            }

            // 源管理
            item { SettingsCardSection("源管理") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MobileSettingButton("视频接口管理", onClick = actions::openVideoSourceManager)
                    MobileSettingButton("直播源管理", onClick = actions::openLiveSourceManager)
                    MobileSettingButton("全部源管理", onClick = actions::openSourceManager)
                }
            }
            item {
                MobileSettingButton(
                    "手机远程管理（网页）",
                    subtitle = "无需 token，同一局域网可管理",
                    onClick = actions::openRemoteManageDialog,
                )
            }

            // AI 配置
            item { SettingsCardSection("AI 配置") }
            item {
                MobileAiProviderSetting(
                    selectedProvider = AiProviders.find(state.appSettings.aiProviderId),
                    modelName = state.appSettings.aiModelName,
                    apiKey = state.appSettings.aiApiKey,
                    onProviderSelect = actions::updateAiProvider,
                    onOpenConfig = actions::openRemoteManageDialog,
                )
            }

            // 播放设置
            item { SettingsCardSection("播放设置") }
            item {
                MobileSwitchRow(
                    label = "自动换线",
                    desc = if (state.appSettings.playbackAgentAutoSwitchEnabled) "播放失败或缓冲过久时自动切换线路" else "已关闭，播放器仍可手动换线",
                    checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                    onCheck = actions::updatePlaybackAgentAutoSwitch,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("线路质量统计", style = MaterialTheme.typography.titleSmall)
                        Text(state.playbackHealth.qualitySummaryText(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { confirmClearPlaybackStats = true },
                        enabled = state.playbackHealth.entryCount > 0,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(40.dp),
                    ) { Text("清空") }
                }
            }

            // 更新
            item { SettingsCardSection("更新") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时检查更新", style = MaterialTheme.typography.titleSmall)
                        Text(if (state.appSettings.checkUpdatesOnStartup) "已开启" else "已关闭", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (state.updateError != null) {
                            Text(state.updateError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { actions.checkForAppUpdate(showError = true) },
                            enabled = !state.updateChecking,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(40.dp),
                        ) { Text(if (state.updateChecking) "检查中..." else "立即检查") }
                        Switch(checked = state.appSettings.checkUpdatesOnStartup, onCheckedChange = actions::updateStartupUpdateCheck)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCardSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF1ED760),
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp))
}

@Composable
private fun MobileDeviceModeSetting(selectedMode: DeviceMode, onModeSelect: (DeviceMode) -> Unit) {
    val context = LocalContext.current
    val detectedMode = remember(context) { context.detectDeviceMode() }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("当前：${selectedMode.displayName}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(selectedMode.description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Text("系统自动识别：${detectedMode.displayName}。播放行为以你的选择为准。", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceMode.entries.forEach { mode ->
                Button(
                    onClick = { onModeSelect(mode) },
                    enabled = mode != selectedMode,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedMode == mode) Color(0xFF1ED760) else Color(0xFF2A2A2A),
                        contentColor = if (selectedMode == mode) Color.Black else Color.White,
                    ),
                ) {
                    Text(if (selectedMode == mode) "✓ ${mode.displayName}" else mode.displayName, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun MobileThemeSetting(selectedTheme: TvTheme, onThemeSelect: (TvTheme) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("界面主题", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text("当前：${selectedTheme.displayName}。选择后立即生效，设置保存到本机。", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTheme.entries.forEach { theme ->
                Button(
                    onClick = { onThemeSelect(theme) },
                    enabled = theme != selectedTheme,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTheme == theme) Color(0xFF1ED760) else Color(0xFF2A2A2A),
                        contentColor = if (selectedTheme == theme) Color.Black else Color.White,
                    ),
                ) { Text(theme.displayName, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MobileFontScaleSetting(selected: TvFontScale, onSelect: (TvFontScale) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("字体大小", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvFontScale.entries.forEach { fs ->
                Button(
                    onClick = { onSelect(fs) },
                    enabled = fs != selected,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected == fs) Color(0xFF1ED760) else Color(0xFF2A2A2A),
                        contentColor = if (selected == fs) Color.Black else Color.White,
                    ),
                ) { Text(fs.displayName, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MobileSwitchRow(
    label: String, desc: String, checked: Boolean, onCheck: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun MobileSettingButton(text: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun MobileHomeApiLineSetting(
    apiLines: List<ApiLine>,
    selectedApiLine: ApiLine?,
    customLineCount: Int,
    onSelect: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("首页渲染数据", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text("当前：${selectedApiLine?.name ?: "未选择"}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        if (customLineCount > 0) {
            Text("已添加 $customLineCount 条自定义 MacCms 接口", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Button(
                    onClick = { expanded = true },
                    enabled = apiLines.isNotEmpty(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(40.dp).fillMaxWidth(),
                ) { Text("${selectedApiLine?.name ?: "选择资源"} ▾", fontSize = 13.sp) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    apiLines.forEach { line ->
                        DropdownMenuItem(
                            text = { Text(if (line.id == selectedApiLine?.id) "已选 · ${line.name}" else line.name) },
                            onClick = { expanded = false; onSelect(line.id) },
                        )
                    }
                }
            }
            Button(onClick = onOpenConfig, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(40.dp)) {
                Text("添加接口", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MobileAiProviderSetting(
    selectedProvider: AiProvider,
    modelName: String,
    apiKey: String,
    onProviderSelect: (String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayModelName = modelName.ifBlank { selectedProvider.defaultModel }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("大模型选择", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text("未填写 API Key 时使用 APK 内置 AI 配置", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
        Box {
            Button(
                onClick = { expanded = true },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp).fillMaxWidth(),
            ) { Text("${selectedProvider.name} ▾", fontSize = 13.sp) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AiProviders.all.forEach { provider ->
                    val selected = provider.id == selectedProvider.id
                    DropdownMenuItem(
                        text = { Text(if (selected) "已选 · ${provider.name}" else provider.name) },
                        onClick = { expanded = false; onProviderSelect(provider.id) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenConfig,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp).weight(1f),
            ) { Text("模型：$displayModelName", fontSize = 13.sp) }
            Button(
                onClick = onOpenConfig,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp).weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
            ) {
                Text(if (apiKey.isBlank()) "API Key：未配置" else "API Key：已配置", fontSize = 13.sp)
            }
        }
    }
}
