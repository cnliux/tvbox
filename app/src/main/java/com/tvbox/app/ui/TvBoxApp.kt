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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.tvbox.app.data.TextFileExporter
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.tvbox.app.domain.VideoScaleMode
import androidx.compose.material3.Icon
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
import com.tvbox.app.ui.settings.SettingsTvSplitScreen
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens
import com.tvbox.app.BuildConfig
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
                    // 方向键上：导航栏回弹处理
                    if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && navigationUpHeld) {
                        if (event.type == KeyEventType.KeyUp) navigationUpHeld = false
                        return@onPreviewKeyEvent true
                    }
                    // 仅响应 KeyUp 事件，避免长按重复触发
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    // 数字快捷键映射（主键盘 + 小键盘统一处理）
                    val shortcutMap = mapOf(
                        AndroidKeyEvent.KEYCODE_1       to { actions.openHistory() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_1 to { actions.openHistory() },
                        AndroidKeyEvent.KEYCODE_2       to { actions.openSearch() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_2 to { actions.openSearch() },
                        AndroidKeyEvent.KEYCODE_3       to { actions.openAiRecommend() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_3 to { actions.openAiRecommend() },
                        AndroidKeyEvent.KEYCODE_4       to { actions.openLive() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_4 to { actions.openLive() },
                        AndroidKeyEvent.KEYCODE_5       to { actions.openPlatformLive() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_5 to { actions.openPlatformLive() },
                        AndroidKeyEvent.KEYCODE_6       to { actions.openSettings() },
                        AndroidKeyEvent.KEYCODE_NUMPAD_6 to { actions.openSettings() },
                    )
                    shortcutMap[event.nativeKeyEvent.keyCode]?.let { it(); true } ?: false
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
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(padding),
        ) {
            val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "历史",
                        style = if (isMobile) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = "观看记录：继续上次的线路、集数和播放进度",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (isMobile) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = state.historyItems.isNotEmpty(),
                        onClick = { confirmClearHistory = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = if (isMobile) Modifier.height(36.dp) else Modifier,
                    ) {
                        Text("清空")
                    }
                    Button(onClick = actions::goBack, shape = RoundedCornerShape(8.dp), modifier = if (isMobile) Modifier.height(36.dp) else Modifier) {
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
                val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
                // 手机模式固定 2 列，避免 Adaptive 在窄屏下算出 1 列或间距过大导致布局异常。
                val columns = if (isMobile) GridCells.Fixed(2) else GridCells.Adaptive(minSize = TvLayout.HistoryGridMinWidth)
                LazyVerticalGrid(
                    columns = columns,
                    state = historyGrid,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (isMobile) 10.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isMobile) 12.dp else 22.dp),
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
    // 新设计：左侧分类菜单 + 右侧详情（借鉴 Apple TV / Netflix 极简沉浸式风格）
    SettingsTvSplitScreen(state = state, actions = actions)
    // 远程管理弹窗保持原有逻辑
    if (state.remoteManageDialogVisible) {
        RemoteManageDialog(state = state, actions = actions)
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
        // 触控热区至少 48dp，避免手机端按钮偏小导致误触。
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    onOpenConfig: (() -> Unit)?,
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
            // 手机版不再提供网页配置入口，添加接口统一走页内"导入配置"。
            if (onOpenConfig != null) {
                SettingsActionButton(
                    text = "添加接口",
                    onClick = onOpenConfig,
                )
            }
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
        MobileOnboardingBanner(state = state, actions = actions)
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                LazyVerticalGrid(
                    // 手机竖屏按大卡片展示：常规手机 2 列，大屏/平板 3 列，避免出现 4 列以上的小图。
                    columns = GridCells.Fixed(if (screenWidthDp >= 600) 3 else 2),
                    state = movieGridState,
                    contentPadding = PaddingValues(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceManagerSetting(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
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
        // 工具按钮流式排布：电视宽屏保持一行，手机版窄屏/大字号自动换行不溢出。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                val blocked = line.id in state.appSettings.blockedSourceIds
                val selected = line.id == state.selectedApiLine?.id
                val statusSuffix = if (blocked) "（已屏蔽）" else ""
                val cardModifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                val infoBlock = @Composable {
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
                val actionBlock = @Composable {
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
                if (isMobile) {
                    // 手机版：信息在上、操作按钮流式换行在下，避免窄屏把源名挤成竖排。
                    Column(modifier = cardModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        infoBlock()
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) { actionBlock() }
                    }
                } else {
                    Row(
                        modifier = cardModifier,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) { infoBlock() }
                        actionBlock()
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
    var defaultGroup by remember { mutableStateOf(state.appSettings.liveSourceDefaultGroup) }
    // 本地保存默认源下标，与 state 保持同步（state 在加载时从持久化读取）。
    var defaultIndex by remember { mutableStateOf(state.appSettings.liveSourceDefaultIndex) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    // 将当前文本区内容解析为 URL 列表（仅在首次渲染时计算）。
    val currentUrls = remember(urlsText) {
        urlsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val defaultIdx = currentUrls.getOrNull(defaultIndex)?.let { currentUrls.indexOf(it) } ?: -1

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "自定义直播源地址（每行一个，支持 M3U/TXT）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = urlsText,
            onValueChange = { urlsText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
            placeholder = { Text("https://example.com/live.m3u\nhttps://example.com/live.txt") },
            maxLines = 8,
        )

        // 默认直播源设置区
        if (currentUrls.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "默认直播源（优先使用；不可用时自动切换备用源）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (defaultIdx >= 0) {
                        Text(
                            text = "取消默认",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable {
                                    actions.clearLiveSourceDefaultIndex()
                                    defaultIndex = -1
                                },
                        )
                    }
                }
                currentUrls.forEachIndexed { idx, url ->
                    val isDefault = idx == defaultIdx
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDefault) TvColors.AccentSoft.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = if (isDefault) "✓ $url" else url,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                        if (isDefault) {
                            SettingsActionButton(
                                text = "✓ 默认",
                                onClick = {},
                                enabled = false,
                                selected = true,
                                modifier = Modifier.height(36.dp),
                            )
                        } else {
                            SettingsActionButton(
                                text = "设为默认",
                                onClick = {
                                    actions.setLiveSourceDefaultIndex(idx)
                                    defaultIndex = idx
                                },
                                modifier = Modifier.height(36.dp),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "默认频道分组名（可选，不填则保持源内分组）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = defaultGroup,
            onValueChange = { defaultGroup = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：体育、新闻、综合") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsActionButton(
                text = "保存直播源",
                onClick = {
                    val urls = urlsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    val effectiveIdx = if (defaultIndex in urls.indices) defaultIndex else -1
                    if (effectiveIdx != defaultIndex) {
                        defaultIndex = effectiveIdx
                    }
                    actions.updateLiveSourceUrls(urls, defaultGroup, effectiveIdx)
                    savedMessage = "已保存 ${urls.size} 个直播源"
                },
            )
            SettingsActionButton(
                text = "清空直播源",
                enabled = state.appSettings.liveSourceUrls.isNotEmpty() || urlsText.isNotBlank(),
                onClick = {
                    actions.updateLiveSourceUrls(emptyList(), "", -1)
                    actions.clearLiveSourceDefaultIndex()
                    defaultIndex = -1
                    urlsText = ""
                    defaultGroup = ""
                    savedMessage = "已清空直播源"
                },
            )
        }
        savedMessage?.let { msg ->
            Text(text = msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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

/**
 * 管理页头部：电视模式保持"大标题 + 右侧按钮行"；手机版改为竖向堆叠，
 * 按钮用 FlowRow 自动换行，避免窄屏 + 大字号下标题与按钮互相挤出屏幕。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManageScreenHeader(
    title: String,
    subtitle: String,
    isMobile: Boolean,
    buttons: @Composable () -> Unit,
) {
    if (isMobile) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { buttons() }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.headlineLarge)
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        }
    }
}

@Composable
private fun SourceManagementScreen(state: TvBoxUiState, actions: TvBoxViewModel) {
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    PageSurface { padding ->
        Column(
            // 手机版为 edge-to-edge 全屏页，吸收状态栏/手势条安全区，避免标题与底部内容被遮挡。
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(padding),
        ) {
            ManageScreenHeader(
                title = "源管理",
                subtitle = if (isMobile) "视频接口、直播源与首页渲染数据统一管理（支持地址导入与页内管理）" else "视频接口、直播源与首页渲染数据统一管理（支持网页管理与地址导入）",
                isMobile = isMobile,
            ) {
                SettingsActionButton(text = "视频接口管理", onClick = actions::openVideoSourceManager)
                SettingsActionButton(text = "直播源管理", onClick = actions::openLiveSourceManager)
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
                        title = "首页渲染数据（视频接口）",
                        subtitle = "从这里进入独立的视频接口管理页，支持地址导入与页内管理。",
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
                        subtitle = "从这里进入独立的直播源管理页，支持地址导入与页内编辑。",
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
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    PageSurface { padding ->
        Column(
            // 手机版为 edge-to-edge 全屏页，吸收状态栏/手势条安全区，避免标题与底部内容被遮挡。
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(padding),
        ) {
            ManageScreenHeader(
                title = "视频接口管理",
                subtitle = if (isMobile) "首页渲染数据管理（支持地址导入、测速与源增删）" else "首页渲染数据管理（支持网页管理与地址导入以解析视频源数据）",
                isMobile = isMobile,
            ) {
                // 手机版源管理全部在页内完成，不再暴露远程网页入口。
                if (!isMobile) {
                    SettingsActionButton(text = "打开远程管理（网页）", onClick = actions::openRemoteManageDialog)
                }
                SettingsActionButton(text = "返回", onClick = actions::goBack)
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
                        onOpenConfig = if (isMobile) null else actions::openRemoteManageDialog,
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
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    PageSurface { padding ->
        Column(
            // 手机版为 edge-to-edge 全屏页，吸收状态栏/手势条安全区，避免标题与底部内容被遮挡。
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(padding),
        ) {
            ManageScreenHeader(
                title = "直播源管理",
                subtitle = if (isMobile) "直播源与平台直播站点管理（支持地址导入与页内编辑）" else "直播源与自定义直播源管理（支持网页管理与地址导入以解析直播源数据）",
                isMobile = isMobile,
            ) {
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
                        subtitle = "请添加电视直播源地址（M3U/TXT），支持地址导入以解析直播源数据；可设置默认频道分组名，为空则保持源内分组。",
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LiveSourceSetting(state = state, actions = actions)
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
@Composable
private fun MobileSettingsScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearPlaybackStats by remember { mutableStateOf(false) }

    if (state.remoteManageDialogVisible) {
        RemoteManageDialog(state = state, actions = actions)
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空历史记录") },
            text = { Text("确定要清空所有观看历史记录吗？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = {
                    confirmClearHistory = false
                    actions.clearHistory()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("取消") }
            },
        )
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

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = actions::goBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // ── 设备 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("设备")
            }
            item {
                val ctx = LocalContext.current
                SettingsRow(
                    icon = "📱",
                    title = "设备模式",
                    subtitle = "当前：${state.appSettings.deviceMode.displayName}",
                    trailing = {
                        Switch(
                            checked = state.appSettings.deviceMode == DeviceMode.Mobile,
                            onCheckedChange = { isChecked ->
                                val next = if (isChecked) DeviceMode.Mobile else DeviceMode.Television
                                actions.updateDeviceMode(next)
                                val activity = ctx as? android.app.Activity
                                activity?.runOnUiThread { activity.recreate() }
                            },
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 外观 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("外观")
            }
            item {
                SettingsEnumRow(
                    icon = "🎨",
                    title = "主题",
                    selected = state.appSettings.theme,
                    entries = TvTheme.entries,
                    onSelect = actions::updateTheme,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingsEnumRow(
                    icon = "🔤",
                    title = "字体大小",
                    selected = state.appSettings.fontScale,
                    entries = TvFontScale.entries,
                    onSelect = actions::updateFontScale,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 首页 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("首页")
            }
            item {
                SettingsRow(
                    icon = "▶",
                    title = "继续观看",
                    subtitle = if (state.appSettings.homeContinueWatchingEnabled) "显示最近观看记录" else "不显示继续观看",
                    trailing = {
                        Switch(
                            checked = state.appSettings.homeContinueWatchingEnabled,
                            onCheckedChange = actions::updateHomeContinueWatching,
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                val currentLine = state.apiLines.find { it.id == state.selectedApiLine?.id }
                SettingsButtonRow(
                    icon = "📺",
                    title = "数据源",
                    subtitle = currentLine?.name ?: "未选择",
                    onClick = actions::openVideoSourceManager,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 直播 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("直播")
            }
            item {
                val liveCount = state.appSettings.liveSourceUrls.count { it.isNotEmpty() }
                SettingsButtonRow(
                    icon = "📡",
                    title = "IPTV 直播源",
                    subtitle = if (liveCount > 0) "已配置 $liveCount 条" else "未配置",
                    onClick = actions::openLiveSourceManager,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                val blockedCount = state.appSettings.blockedPlatformSiteIds.count { it.isNotEmpty() }
                SettingsButtonRow(
                    icon = "🌐",
                    title = "平台直播",
                    subtitle = if (blockedCount > 0) "已屏蔽 $blockedCount 个站点" else "管理虎牙/斗鱼",
                    onClick = actions::openPlatformLive,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 播放 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("播放")
            }
            item {
                SettingsRow(
                    icon = "↻",
                    title = "自动换线",
                    subtitle = "播放失败或缓冲过久时自动切换",
                    trailing = {
                        Switch(
                            checked = state.appSettings.playbackAgentAutoSwitchEnabled,
                            onCheckedChange = actions::updatePlaybackAgentAutoSwitch,
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingsEnumRow(
                    icon = "🔍",
                    title = "视频缩放",
                    selected = state.appSettings.videoScaleMode,
                    entries = VideoScaleMode.entries,
                    onSelect = actions::updateVideoScaleMode,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                val introSec = state.appSettings.introSkipSeconds
                SettingsButtonRow(
                    icon = "⏩",
                    title = "片头跳过",
                    subtitle = if (introSec > 0) "跳过前 ${introSec}s" else "未启用",
                    onClick = { actions.updateIntroSkipSeconds(if (introSec > 0) 0 else 30) },
                    trailing = {
                        Text(
                            if (introSec > 0) "关闭" else "启用",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (introSec > 0) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                val outroSec = state.appSettings.outroSkipSeconds
                SettingsButtonRow(
                    icon = "⏭",
                    title = "片尾跳过",
                    subtitle = if (outroSec > 0) "跳过末尾 ${outroSec}s" else "未启用",
                    onClick = { actions.updateOutroSkipSeconds(if (outroSec > 0) 0 else 30) },
                    trailing = {
                        Text(
                            if (outroSec > 0) "关闭" else "启用",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (outroSec > 0) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── AI ────────────────────────────────────────────────
            item {
                SettingsGroupTitle("AI 推荐")
            }
            item {
                val aiProvider = AiProviders.find(state.appSettings.aiProviderId)
                SettingsButtonRow(
                    icon = "🤖",
                    title = "AI 模型",
                    subtitle = "当前：${aiProvider.name}",
                    description = if (state.appSettings.aiApiKey.isBlank()) "未配置 API Key" else "已配置 API Key",
                    onClick = actions::openRemoteManageDialog,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 数据 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("数据管理")
            }
            item {
                SettingsButtonRow(
                    icon = "📋",
                    title = "观看历史",
                    subtitle = "管理本地播放记录",
                    onClick = { confirmClearHistory = true },
                    trailing = {
                        Text("清空", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingsButtonRow(
                    icon = "📊",
                    title = "线路统计",
                    subtitle = "播放质量学习数据（${state.playbackHealth.entryCount} 条）",
                    onClick = { confirmClearPlaybackStats = true },
                    trailing = if (state.playbackHealth.entryCount > 0) {
                        { Text("清空", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error) }
                    } else null,
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }

            // ── 更新 ──────────────────────────────────────────────
            item {
                SettingsGroupTitle("更新")
            }
            item {
                SettingsRow(
                    icon = "⬇",
                    title = "启动时检查更新",
                    subtitle = if (state.appSettings.checkUpdatesOnStartup) "已开启" else "已关闭",
                    trailing = {
                        Switch(
                            checked = state.appSettings.checkUpdatesOnStartup,
                            onCheckedChange = actions::updateStartupUpdateCheck,
                        )
                    },
                )
            }
            item { Divider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingsButtonRow(
                    icon = "🔄",
                    title = "检查更新",
                    subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                    description = if (state.updateChecking) "正在检查..." else "点击检测新版本",
                    onClick = { actions.checkForAppUpdate(showError = true) },
                    enabled = !state.updateChecking,
                )
                if (state.updateError != null) {
                    Text(
                        state.updateError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 56.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // 底部版本信息
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "TVBox v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 设置行：图标 + 标题 + 副标题 + 右侧控件 */
@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** 设置按钮行：可点击，带图标 */
@Composable
private fun SettingsButtonRow(
    icon: String,
    title: String,
    subtitle: String,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .then(if (enabled) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            trailing?.let { it() }
        }
    }
}

/** 枚举选择行 */
@Composable
private fun <T : Enum<T>> SettingsEnumRow(
    icon: String,
    title: String,
    selected: T,
    entries: List<T>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val displayText = entries.find { it == selected }?.name?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: selected.name.toString()
        SettingsButtonRow(
            icon = icon,
            title = title,
            subtitle = displayText,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        val name = entry.name.replace("_", " ").replaceFirstChar { it.uppercase() }
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { expanded = false; onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun MobileOnboardingBanner(state: TvBoxUiState, actions: TvBoxViewModel) {
    if (state.appSettings.deviceMode != DeviceMode.Mobile || state.appSettings.mobileOnboarded) return
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color(0xFF1A3A2A),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("📱", fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text("手机模式已启用", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("可在设置中调整主题、字体大小或切换回电视模式",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { actions.openSettings(); actions.updateMobileOnboarded() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("设置", fontSize = 12.sp) }
                TextButton(
                    onClick = { dismissed = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("关闭", fontSize = 12.sp) }
            }
        }
    }
}


