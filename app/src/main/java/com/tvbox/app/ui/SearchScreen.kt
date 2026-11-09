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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import coil.compose.AsyncImage
import com.tvbox.app.ui.components.MediaCard
import com.tvbox.app.ui.components.rememberTvImageLoader
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
    if (state.effectiveDeviceMode == DeviceMode.Mobile) {
        MobileSearchScreen(state = state, actions = actions)
        return
    }
    val grid = rememberLazyGridState()
    val returnFocus = rememberReturnFocus()
    val searchFieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val moveToResults: () -> Unit = {
        if (state.searchResults.isNotEmpty()) {
            runCatching { resultsFocus.requestFocus() }
        }
    }
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
                        onMoveToResults = moveToResults,
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
                        resultsFocus = resultsFocus,
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
                        onMoveToResults = moveToResults,
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
                        resultsFocus = resultsFocus,
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
    onMoveToResults: () -> Unit,
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
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { event ->
                    // 单行搜索框里的"下键"没有光标移动意义：直接进入结果卡片列表，
                    // 避免遥控器焦点被 TextField 吞掉后在输入框里"卡死"。
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        onMoveToResults()
                        true
                    } else {
                        false
                    }
                },
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
    onMoveToResults: () -> Unit,
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
                    .focusRequester(fieldFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            onMoveToResults()
                            true
                        } else {
                            false
                        }
                    },
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
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "热门搜索：$word" }
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
            // 遥控键盘按键文字约 44dp 高，触摸模式不足最小热区，统一抬到 48dp。
            .heightIn(min = 48.dp)
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "键盘按键 $text" }
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
    resultsFocus: FocusRequester,
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
                    itemsIndexed(
                        items = state.searchResults,
                        key = { _, movie -> "${movie.apiLineId}-${movie.id}" },
                    ) { index, movie ->
                        SearchResultCard(
                            movie = movie,
                            onClick = {
                                returnFocus.mark("${movie.apiLineId}:${movie.id}", index)
                                actions.openDetail(movie.id, movie.apiLineId)
                            },
                            modifier = returnFocus
                                .modifier("${movie.apiLineId}:${movie.id}", index)
                                .then(
                                    if (index == 0 && returnFocus.lastKey == null) {
                                        Modifier.focusRequester(resultsFocus)
                                    } else {
                                        Modifier
                                    },
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
    val isMobile = state.effectiveDeviceMode == DeviceMode.Mobile
    if (!isMobile) return
    // 用 TextFieldValue 保留光标在末尾的语义（PR#5 修复的核心逻辑）。
    // TextFieldValue 无法写入 Bundle，不能用 rememberSaveable，不然切换页面保存状态时会抛 IllegalArgumentException。
    var fieldValue by remember(state.searchQuery) {
        mutableStateOf(queryFieldValue(state.searchQuery))
    }
    val hotWords = state.searchHotWords.ifEmpty { FallbackSearchHotWords }
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.searchQuery) {
        if (fieldValue.text != state.searchQuery) {
            fieldValue = queryFieldValue(state.searchQuery)
        }
        // 换关键词后回到结果顶部，避免沿用上一批结果的滚动位置。
        gridState.scrollToItem(0)
    }

    fun submit() {
        val q = fieldValue.text.trim()
        if (q.isEmpty()) return
        actions.updateSearchQuery(q)
        actions.submitSearch()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 使用 LazyVerticalGrid 替代行分块，支持自适应列数、更好的视觉密度和更流畅的滚动
        val gridSpacing = 8.dp
        val gridPadding = 8.dp
        // 与首页信息流同一档密度：360~411dp 竖屏 3 列，宽屏自然增加列数。
        val minCardWidth = 108.dp
        val capacity = ((maxWidth - gridPadding * 2 + gridSpacing) / (minCardWidth + gridSpacing))
            .toInt()
            .coerceAtLeast(1)
        val requested = state.appSettings.videoGridColumns
        val columns = if (requested > 0) requested.coerceAtMost(capacity).coerceAtLeast(1) else capacity
        Column(modifier = Modifier.fillMaxSize()) {
            // 搜索框固定在顶部：改关键词不必先滚回列表顶部，符合手机端输入习惯。
            Surface(
                color = Color(0xFF1E1E1E),
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it; actions.updateSearchQuery(it.text) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        singleLine = true,
                        placeholder = { Text("搜索影片") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Color(0xFF2A2A2A),
                            focusedContainerColor = Color(0xFF2A2A2A),
                            unfocusedTextColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedBorderColor = Color(0xFF1ED760),
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !state.searchLoading,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (state.searchLoading) "搜索中…" else "搜索")
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(start = gridPadding, top = 8.dp, end = gridPadding, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                when {
                    state.searchLoading && state.searchResults.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LoadingState(text = "正在搜索多个来源…", modifier = Modifier.fillMaxWidth().height(200.dp))
                        }
                    }

                    state.searchError != null && state.searchResults.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ErrorState(message = state.searchError, onRetry = actions::submitSearch, modifier = Modifier.fillMaxWidth().height(200.dp))
                        }
                    }

                    state.searchResults.isEmpty() && state.searchQuery.isBlank() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            // 热门搜索：用标签流式排列（横向不再二次缩进，与结果卡左缘对齐）
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
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
                    }

                    state.searchResults.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SearchEmptyState(query = state.searchQuery)
                        }
                    }

                    else -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val progressText = if (state.searchTotalSources > 0) {
                                "正在搜索 ${state.searchTotalSources} 个来源（已完成 ${state.searchCompletedSources}，找到 ${state.searchResults.size} 条结果）"
                            } else null
                            progressText?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                            }
                            state.searchError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            if (state.searchLoading && state.searchResults.isNotEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        items(state.searchResults, key = { "${it.apiLineId}-${it.id}" }) { movie ->
                            SearchResultCardMobile(movie = movie, onClick = {
                                actions.openDetail(movie.id, movie.apiLineId)
                            })
                        }
                        // 搜索结果随多源完成持续增量合并，没有分页；加载完成后补底部留白。
                        if (!state.searchLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
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
                    .clickable(role = Role.Button) { onWordClick(word) }
                    .semantics { contentDescription = "热门搜索：$word" },
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF2A2A2A),
            ) {
                Box(
                    modifier = Modifier
                        // 热词原先由文字撑高（约 36dp），统一抬到 48dp 触摸目标。
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCardMobile(
    movie: Movie,
    onClick: () -> Unit,
) {
    // 复用 MediaCard 底座，统一圆角、间距、字号与触摸反馈
    var posterFailed by remember(movie.posterUrl) { mutableStateOf(false) }
    MediaCard(
        title = movie.name,
        onClick = onClick,
        badge = movie.remarks.takeIf { it.isNotBlank() },
        cover = {
            if (movie.posterUrl.isNotBlank() && !posterFailed) {
                AsyncImage(
                    model = movie.posterUrl,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
                    imageLoader = rememberTvImageLoader(),
                )
            } else {
                Text(
                    text = "无海报",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        coverOverlay = {
            // 来源角标
            if (movie.apiLineName.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xCC1ED760),
                ) {
                    Text(
                        movie.apiLineName,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}
