package com.tvbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.Movie
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.MoviePosterCard
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvColors

private val FallbackSearchHotWords = listOf(
    "庆余年",
    "狂飙",
    "繁花",
    "与凤行",
    "追风者",
    "南来北往",
    "漫长的季节",
    "三体",
)

@Composable
fun SearchScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    if (state.appSettings.deviceMode == DeviceMode.Mobile) {
        MobileSearchScreen(state = state, actions = actions)
        return
    }
    val grid = rememberLazyGridState()
    val returnFocus = rememberReturnFocus()
    val searchFieldFocus = remember { FocusRequester() }
    var savedQuery by rememberSaveable { mutableStateOf(state.searchQuery) }
    var fieldValue by remember { mutableStateOf(queryFieldValue(state.searchQuery)) }
    val hotWords = state.searchHotWords.ifEmpty { FallbackSearchHotWords }

    LaunchedEffect(state.searchQuery) {
        if (fieldValue.text != state.searchQuery) {
            fieldValue = queryFieldValue(state.searchQuery)
        }
        if (savedQuery != state.searchQuery) {
            returnFocus.reset()
            grid.scrollToItem(0)
            savedQuery = state.searchQuery
        }
    }
    LaunchedEffect(Unit) {
        if (returnFocus.lastKey == null) runCatching { searchFieldFocus.requestFocus() }
    }
    RestoreGridFocus(returnFocus, grid, state.searchResults.mapIndexed { index, item -> "${item.apiLineId}:${item.id}" to index })

    fun setQuery(text: String) {
        fieldValue = queryFieldValue(text)
        actions.updateSearchQuery(text)
    }

    fun insertKey(key: String) {
        val text = fieldValue.text
        val start = fieldValue.selection.start.coerceIn(0, text.length)
        val end = fieldValue.selection.end.coerceIn(start, text.length)
        val next = text.replaceRange(start, end, key)
        val caret = start + key.length
        fieldValue = TextFieldValue(next, TextRange(caret))
        actions.updateSearchQuery(next)
    }

    fun deleteKey() {
        val text = fieldValue.text
        val start = fieldValue.selection.start.coerceIn(0, text.length)
        val end = fieldValue.selection.end.coerceIn(start, text.length)
        val next = when {
            start != end -> text.removeRange(start, end)
            start > 0 -> text.removeRange(start - 1, start)
            else -> text
        }
        val caret = if (start != end) start else (start - 1).coerceAtLeast(0)
        fieldValue = TextFieldValue(next, TextRange(caret))
        actions.updateSearchQuery(next)
    }

    fun clearQuery() {
        fieldValue = queryFieldValue("")
        actions.updateSearchQuery("")
    }

    fun searchHotWord(word: String) {
        setQuery(word)
        actions.submitSearch()
    }

    PageSurface { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wideLayout = maxWidth >= 860.dp
            if (wideLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SearchControlPanel(
                        state = state,
                        fieldValue = fieldValue,
                        fieldFocus = searchFieldFocus,
                        onValueChange = {
                            fieldValue = it
                            actions.updateSearchQuery(it.text)
                        },
                        onSearch = actions::submitSearch,
                        onClear = ::clearQuery,
                        onBack = actions::goBack,
                        onKey = ::insertKey,
                        onDelete = ::deleteKey,
                        modifier = Modifier
                            .width(356.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                            .background(TvColors.Border),
                    )
                    Spacer(modifier = Modifier.width(18.dp))
                    HotSearchPanel(
                        hotWords = hotWords,
                        onSelect = ::searchHotWord,
                        modifier = Modifier
                            .width(164.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(22.dp))
                    SearchResultsPanel(
                        state = state,
                        actions = actions,
                        grid = grid,
                        returnFocus = returnFocus,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SearchCompactBar(
                        state = state,
                        fieldValue = fieldValue,
                        fieldFocus = searchFieldFocus,
                        onValueChange = {
                            fieldValue = it
                            actions.updateSearchQuery(it.text)
                        },
                        onSearch = actions::submitSearch,
                        onClear = ::clearQuery,
                        onBack = actions::goBack,
                    )
                    if (state.searchQuery.isBlank() && state.searchResults.isEmpty() && !state.searchLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HotSearchStrip(hotWords = hotWords, onSelect = ::searchHotWord)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    SearchResultsPanel(
                        state = state,
                        actions = actions,
                        grid = grid,
                        returnFocus = returnFocus,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchControlPanel(
    state: TvBoxUiState,
    fieldValue: TextFieldValue,
    fieldFocus: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "搜索",
            style = MaterialTheme.typography.headlineMedium,
        )
        OutlinedTextField(
            value = fieldValue,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus),
            singleLine = true,
            label = { Text("影片名称") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchActionButton(
                text = if (state.searchLoading) "搜索中" else "搜索",
                onClick = onSearch,
                enabled = !state.searchLoading,
                modifier = Modifier.weight(1.1f),
            )
            SearchActionButton(
                text = "清空",
                onClick = onClear,
                modifier = Modifier.weight(1f),
            )
            SearchActionButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "遥控器键盘",
            style = MaterialTheme.typography.labelMedium,
            color = TvColors.TextSecondary,
        )
        SearchKeyboard(
            onKey = onKey,
            onDelete = onDelete,
            onClear = onClear,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "支持中文、拼音首字母（如 xclf）和遥控器输入，也可以点击热门搜索。",
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextTertiary,
        )
    }
}

@Composable
private fun SearchCompactBar(
    state: TvBoxUiState,
    fieldValue: TextFieldValue,
    fieldFocus: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus),
                singleLine = true,
                label = { Text("影片名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            SearchActionButton(
                text = if (state.searchLoading) "搜索中" else "搜索",
                onClick = onSearch,
                enabled = !state.searchLoading,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchActionButton(text = "清空", onClick = onClear, modifier = Modifier.weight(1f))
            SearchActionButton(text = "返回", onClick = onBack, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HotSearchPanel(
    hotWords: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "热门搜索",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (hotWords.isEmpty()) {
            Text(
                text = "暂无热门搜索",
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextTertiary,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(hotWords, key = { it }) { word ->
                    HotSearchWord(
                        word = word,
                        onClick = { onSelect(word) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HotSearchStrip(
    hotWords: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "热门搜索",
            style = MaterialTheme.typography.titleSmall,
            color = TvColors.TextSecondary,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(hotWords, key = { it }) { word ->
                HotSearchWord(
                    word = word,
                    onClick = { onSelect(word) },
                )
            }
        }
    }
}

@Composable
private fun HotSearchWord(
    word: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = if (focused) TvColors.SurfaceRaised else TvColors.Surface,
        contentColor = if (focused) TvColors.TextPrimary else TvColors.TextSecondary,
    ) {
        Text(
            text = word,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchKeyboard(
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val keys = remember { (('A'..'Z') + ('0'..'9')).map { it.toString() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchKeyboardKey(
                text = "清空",
                onClick = onClear,
                modifier = Modifier.weight(1f),
            )
            SearchKeyboardKey(
                text = "删除",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
        keys.chunked(6).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEach { key ->
                    SearchKeyboardKey(
                        text = key,
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchKeyboardKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(44.dp)
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = if (focused) TvColors.Accent else TvColors.SurfaceRaised,
        contentColor = if (focused) TvColors.Background else TvColors.TextPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SearchResultsPanel(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    returnFocus: ReturnFocus,
    modifier: Modifier = Modifier,
) {
    val progress = state.searchTotalSources.takeIf { it > 0 }?.let { total ->
        "已完成 ${state.searchCompletedSources}/$total 条线路 · 找到 ${state.searchResults.size} 个结果"
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索结果",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.searchResults.isNotEmpty()) {
                Text(
                    text = "${state.searchResults.size} 个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.TextSecondary,
                )
            }
        }
        progress?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.searchLoading && state.searchResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.searchError != null && state.searchResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.searchLoading && state.searchResults.isEmpty() -> LoadingState(
                    text = progress ?: "正在搜索多个影视来源",
                )

                state.searchResults.isEmpty() && state.searchQuery.isBlank() ->
                    SearchEmptyState(query = state.searchQuery)

                state.searchError != null && state.searchResults.isEmpty() -> ErrorState(
                    message = state.searchError,
                    onRetry = actions::submitSearch,
                )

                state.searchResults.isEmpty() -> SearchEmptyState(query = state.searchQuery)

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    state = grid,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.searchResults, key = { "${it.apiLineId}-${it.id}" }) { movie ->
                        SearchResultCard(
                            movie = movie,
                            onClick = {
                                returnFocus.mark("${movie.apiLineId}:${movie.id}", state.searchResults.indexOf(movie))
                                actions.openDetail(movie.id, movie.apiLineId)
                            },
                            modifier = returnFocus.modifier(
                                "${movie.apiLineId}:${movie.id}",
                                state.searchResults.indexOf(movie),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        MoviePosterCard(
            movie = movie,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            shape = RoundedCornerShape(6.dp),
            color = TvColors.SurfaceOverlay,
            contentColor = TvColors.AccentStrong,
        ) {
            Text(
                text = movie.apiLineName,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchEmptyState(query: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (query.isBlank()) "输入关键词开始搜索" else "没有找到相关影片",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (query.isBlank()) "也可以点击热门搜索快速查找" else "换个关键词再试试，拼音首字母请写全（如 xclf）",
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.TextSecondary,
        )
    }
}

// 搜索页重新进入时，String 版输入框会以 TextRange.Zero 重建内部光标，
// 遥控器只有退格键，光标之后的旧关键词将无法删除；统一用光标在末尾的 TextFieldValue，
// 每次进入组合都从 searchQuery 重建，光标位置不落盘恢复。
private fun queryFieldValue(query: String): TextFieldValue =
    TextFieldValue(query, TextRange(query.length))

@Composable
private fun SearchActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .onFocusChanged { focused = enabled && (it.isFocused || it.hasFocus) },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = shape,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            focusedElevation = 10.dp,
        ),
    ) {
        Text(
            text = text,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// ── 手机模式搜索页 ──────────────────────────────────────────────────────────────

@Composable
internal fun MobileSearchScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val isMobile = state.appSettings.deviceMode == DeviceMode.Mobile
    if (!isMobile) return
    // 用 TextFieldValue 保留光标在末尾的语义（PR#5 修复的核心逻辑）。
    // TextFieldValue 无法写入 Bundle，不能用 rememberSaveable，不然切换页面保存状态时会抛 IllegalArgumentException。
    var fieldValue by remember(state.searchQuery) {
        mutableStateOf(queryFieldValue(state.searchQuery))
    }
    val hotWords = state.searchHotWords.ifEmpty { FallbackSearchHotWords }

    LaunchedEffect(state.searchQuery) {
        if (fieldValue.text != state.searchQuery) {
            fieldValue = queryFieldValue(state.searchQuery)
        }
    }

    fun submit() {
        val q = fieldValue.text.trim()
        if (q.isEmpty()) return
        actions.updateSearchQuery(q)
        actions.submitSearch()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索输入区
        Surface(
            color = Color(0xFF1E1E1E),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it; actions.updateSearchQuery(it.text) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索影片") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color(0xFF2A2A2A),
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { submit() },
                    enabled = !state.searchLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (state.searchLoading) "搜索中…" else "搜索")
                }
            }
        }

        // 内容区
        when {
            state.searchLoading && state.searchResults.isEmpty() ->
                LoadingState(text = "正在搜索多个来源…", modifier = Modifier.weight(1f))

            state.searchError != null && state.searchResults.isEmpty() ->
                ErrorState(message = state.searchError, onRetry = actions::submitSearch, modifier = Modifier.weight(1f))

            state.searchResults.isEmpty() && state.searchQuery.isBlank() -> {
                // 热门搜索：用标签流式排列，不再铺成整行的下拉列表
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "热门搜索",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    HotSearchChips(hotWords = hotWords, onWordClick = { word ->
                        fieldValue = queryFieldValue(word)
                        actions.updateSearchQuery(word)
                        actions.submitSearch()
                    })
                }
            }

            state.searchResults.isEmpty() ->
                SearchEmptyState(query = state.searchQuery)

            else -> {
                // 进度提示
                val progress = state.searchTotalSources.takeIf { it > 0 }?.let { total ->
                    "已完成 ${state.searchCompletedSources}/$total 条线路 · 找到 ${state.searchResults.size} 个结果"
                }
                progress?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                state.searchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (state.searchLoading && state.searchResults.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // 卡片网格：横屏最多三列，铺满宽度并使用大尺寸海报
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val columns = if (maxWidth >= 560.dp) 3 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.searchResults, key = { "${it.apiLineId}-${it.id}" }) { movie ->
                            SearchResultCardMobile(movie = movie, onClick = {
                                actions.openDetail(movie.id, movie.apiLineId)
                            })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotSearchChips(
    hotWords: List<String>,
    onWordClick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hotWords.forEach { word ->
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onWordClick(word) },
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF2A2A2A),
            ) {
                Text(
                    text = word,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchResultCardMobile(
    movie: Movie,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick),
    ) {
        // 大尺寸海报
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(Color(0xFF2A2A2A)),
        ) {
            if (movie.posterUrl.isNotBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(movie.posterUrl).build()
                    ),
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "无海报",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // 来源角标
            if (movie.apiLineName.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xCC1ED760),
                ) {
                    Text(
                        movie.apiLineName,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        maxLines = 1,
                    )
                }
            }
            // 更新信息
            if (movie.remarks.isNotBlank()) {
                Text(
                    text = movie.remarks,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                movie.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = movie.subtitle.ifBlank { movie.typeName }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
