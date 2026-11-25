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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalFocusManager
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
import com.tvbox.app.data.PinyinInitialsMatcher
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.SearchSourceGroup
import com.tvbox.app.domain.activeSearchProgressText
import com.tvbox.app.domain.buildSearchSourceGroups
import com.tvbox.app.domain.completedSearchProgressText
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.MoviePosterCard
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.components.TvFocusableSurface
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

/** 窄屏一体化搜索网格里，结果卡片之前的全宽 item 数（搜索框 + 结果状态区）。 */
private const val SEARCH_RESULT_LEADING_ITEMS = 2

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
    val searchFieldFocus = rememberTvFocusRequest()
    val resultsFocus = rememberTvFocusRequest()
    val moveToResults: () -> Unit = {
        if (state.searchResults.isNotEmpty()) {
            resultsFocus.request()
        }
    }
    // 离开搜索页（点进详情/播放、切底部 Tab、按返回）时把关键词与结果一起清掉。
    // 顶部公共栏的 MobileSearchEntry 直接读 state.searchQuery，不清就会在其它页面
    // 一直挂着上一次的搜索词，下次进搜索页也还留着旧结果。
    DisposableEffect(Unit) {
        onDispose { actions.clearSearch() }
    }
    var savedQuery by rememberSaveable { mutableStateOf(state.searchQuery) }
    var fieldValue by remember { mutableStateOf(queryFieldValue(state.searchQuery)) }
    val hotWords = state.searchHotWords.ifEmpty { FallbackSearchHotWords }
    // #字母索引：TV 宽屏搜索的字母浏览状态。null=非字母浏览（显示普通搜索结果）；
    // 否则为选中的首字母（A-Z），结果区显示以该字母开头的当前影片目录。
    var selectedInitial by rememberSaveable { mutableStateOf<String?>(null) }

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
        if (returnFocus.lastKey == null) searchFieldFocus.request()
    }
    // #8 空列表焦点兜底：搜索无结果（非错误/加载中）时把焦点拉回搜索框，避免焦点悬空
    // 无法用方向键继续操作；错误态由 ErrorState 的重试按钮承接焦点，不抢焦点。
    LaunchedEffect(state.searchQuery, state.searchResults.isEmpty(), state.searchError) {
        if (state.searchQuery.isNotBlank() &&
            state.searchResults.isEmpty() &&
            state.searchError == null &&
            !state.searchLoading
        ) {
            searchFieldFocus.request()
        }
    }
    // 焦点恢复在 PageSurface 内按布局做下标换算：宽屏结果网格从 0 开始，
    // 窄屏一体化网格前有搜索框/状态区两个全宽 item。

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
            // 字母浏览：从当前影片目录过滤出以选中首字母开头的影片（仅在宽屏启用）。
            val alphaMovies = if (wideLayout) {
                val letter = selectedInitial
                if (letter != null) {
                    state.movies.filter { movie ->
                        PinyinInitialsMatcher.firstInitial(movie.name)?.uppercaseChar()?.toString() == letter
                    }
                } else {
                    null
                }
            } else {
                null
            }
            RestoreGridFocus(
                returnFocus,
                grid,
                (alphaMovies ?: state.searchResults).mapIndexed { index, item ->
                    "${item.apiLineId}:${item.id}" to (
                        index + if (wideLayout) 0 else SEARCH_RESULT_LEADING_ITEMS
                        )
                },
            )
            if (wideLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SearchControlPanel(
                        state = state,
                        fieldValue = fieldValue,
                        fieldFocus = searchFieldFocus.requester,
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
                        tvMode = state.effectiveDeviceMode != com.tvbox.app.domain.DeviceMode.Mobile,
                        hotWords = hotWords,
                        onSelectCandidate = ::searchHotWord,
                        onClearHistory = actions::clearSearchHistory,
                        onRemoveHistory = actions::removeSearchHistory,
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
                    Column(
                        modifier = Modifier
                            .width(164.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HotSearchPanel(
                            hotWords = hotWords,
                            onSelect = ::searchHotWord,
                            modifier = Modifier.weight(1f),
                        )
                        // 搜索记录：放在热词下方同一栏，都是「输入前的候选词」。
                        if (state.searchHistory.isNotEmpty()) {
                            SearchHistoryPanel(
                                history = state.searchHistory,
                                onSelect = ::searchHotWord,
                                onRemove = actions::removeSearchHistory,
                                onClear = actions::clearSearchHistory,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(22.dp))
                    // 字母索引栏：点击字母，结果区自动加载以该字母开头的影片。
                    AlphabetIndexBar(
                        selected = selectedInitial,
                        onSelect = { letter -> selectedInitial = letter },
                        modifier = Modifier.width(44.dp).fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    SearchResultsPanel(
                        state = state,
                        actions = actions,
                        grid = grid,
                        returnFocus = returnFocus,
                        resultsFocus = resultsFocus,
                        alphaMovies = alphaMovies,
                        alphaTitle = selectedInitial,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                // 一体滚动：搜索框、热门搜索与结果列表在同一个 LazyVerticalGrid 中，
                // 随用户滑动一起滚出屏幕；固定搜索框不再挤压结果区域。
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    state = grid,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    NarrowSearchGridContent(
                        state = state,
                        actions = actions,
                        fieldValue = fieldValue,
                        fieldFocus = searchFieldFocus.requester,
                        onMoveToResults = moveToResults,
                        onValueChange = { value ->
                            fieldValue = value
                            actions.updateSearchQuery(value.text)
                        },
                        onSearch = actions::submitSearch,
                        onClear = ::clearQuery,
                        onBack = actions::goBack,
                        hotWords = hotWords,
                        onSelectHotWord = ::searchHotWord,
                        grid = grid,
                        returnFocus = returnFocus,
                        resultsFocus = resultsFocus,
                    )
                }
            }
        }
    }
}

/**
 * 窄屏一体化搜索网格的内容：搜索框、热词、搜索记录、状态区与结果卡片。
 * 独立成函数，避免宽窄两个分支重复同一份网格 item 定义。
 * 作为 LazyGridScope 的扩展：item/itemsIndexed 只在 LazyVerticalGrid 作用域内可用。
 */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.NarrowSearchGridContent(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    fieldValue: TextFieldValue,
    fieldFocus: FocusRequester,
    onMoveToResults: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    hotWords: List<String>,
    onSelectHotWord: (String) -> Unit,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    returnFocus: ReturnFocus,
    resultsFocus: TvFocusRequest,
) {
    item(key = "search-header", span = { GridItemSpan(maxLineSpan) }) {
        SearchCompactBar(
            state = state,
            fieldValue = fieldValue,
            fieldFocus = fieldFocus,
            onMoveToResults = onMoveToResults,
            onValueChange = onValueChange,
            onSearch = onSearch,
            onClear = onClear,
            onBack = onBack,
        )
    }
    if (state.searchQuery.isBlank() && state.searchResults.isEmpty() && !state.searchLoading) {
        item(key = "search-hot", span = { GridItemSpan(maxLineSpan) }) {
            HotSearchStrip(hotWords = hotWords, onSelect = onSelectHotWord)
        }
    }
    // 搜索记录：热词条下方，点选即重新搜索；可单条删除或一键清空。
    if (state.searchHistory.isNotEmpty()) {
        item(key = "search-history", span = { GridItemSpan(maxLineSpan) }) {
            SearchHistoryStrip(
                history = state.searchHistory,
                onSelect = onSelectHotWord,
                onRemove = actions::removeSearchHistory,
                onClear = actions::clearSearchHistory,
            )
        }
    }
    item(key = "search-status", span = { GridItemSpan(maxLineSpan) }) {
        SearchResultsHeader(state = state)
    }
    when {
        state.searchLoading && state.searchResults.isEmpty() -> {
            item(key = "search-loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingState(text = "正在搜索多个影视来源")
                }
            }
        }

        state.searchError != null && state.searchResults.isEmpty() -> {
            item(key = "search-error", span = { GridItemSpan(maxLineSpan) }) {
                ErrorState(
                    message = state.searchError,
                    onRetry = onSearch,
                )
            }
        }

        state.searchResults.isEmpty() -> {
            item(key = "search-empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SearchEmptyState(query = state.searchQuery)
                }
            }
        }

        else -> {
            itemsIndexed(
                items = state.searchResults,
                key = { _, movie -> "result-${movie.apiLineId}-${movie.id}" },
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
                                resultsFocus.anchor
                            } else {
                                Modifier
                            },
                        ),
                )
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
    tvMode: Boolean,
    hotWords: List<String>,
    onSelectCandidate: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val softwareKeyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
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
            // TV 模式只读：只读输入框聚焦/点击都不唤起系统 IME，彻底杜绝软键盘弹出；
            // 遥控器一律走下方 SearchKeyboard。手机端保持可编辑、用系统软键盘。
            onValueChange = onValueChange,
            readOnly = tvMode,
            // #22/#7 TV 模式屏蔽软键盘：遥控器走下方 SearchKeyboard 输入，聚焦输入框不弹 IME，
            // 避免软键盘抢焦点与遥控器冲突；手机端保留系统软键盘。
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .onFocusChanged { focusState ->
                    if (tvMode && focusState.isFocused) {
                        runCatching { softwareKeyboardController?.hide() }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            // 单行搜索框里的"下键"没有光标移动意义：直接进入结果卡片列表，
                            // 避免遥控器焦点被 TextField 吞掉后在输入框里"卡死"。
                            Key.DirectionDown -> {
                                onMoveToResults()
                                true
                            }
                            // 左右键在只读框里没有移动光标的对象，吃掉避免焦点逃到左侧"选择源"等相邻控件。
                            Key.DirectionLeft, Key.DirectionRight -> tvMode
                            else -> false
                        }
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
        // 候选词：搜索记录（Accent 绿）与热门（琥珀）合并在同一区，用颜色区分；
        // 放在键盘上方，遥控器从输入框下键可先经过候选词直达结果，不再卡在键盘。
        if (state.searchQuery.isBlank() && state.searchResults.isEmpty() && !state.searchLoading) {
            if (state.searchHistory.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "搜索记录",
                        style = MaterialTheme.typography.titleSmall,
                        color = TvColors.Accent,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = TvColors.TextTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onClearHistory)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 8.dp),
                ) {
                    items(state.searchHistory, key = { "history-$it" }) { word ->
                        SearchHistoryChip(
                            word = word,
                            onSelect = { onSelectCandidate(word) },
                            onRemove = { onRemoveHistory(word) },
                        )
                    }
                }
            }
            Text(
                text = "热门搜索",
                style = MaterialTheme.typography.titleSmall,
                color = TvColors.Warning,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(hotWords, key = { "hot-$it" }) { word ->
                    HotSearchWord(
                        word = word,
                        onClick = { onSelectCandidate(word) },
                    )
                }
            }
        }
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
            color = TvColors.Warning,
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

/** TV 宽屏左侧栏的搜索记录面板：竖向列表，单条右侧删除 + 顶部一键清空。 */
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "清空",
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(history, key = { it }) { word ->
                SearchHistoryRow(
                    word = word,
                    onSelect = { onSelect(word) },
                    onRemove = { onRemove(word) },
                )
            }
        }
    }
}

/** TV 宽屏搜索记录单行：整行点击重搜，右侧 ✕ 删除单条。 */
@Composable
private fun SearchHistoryRow(
    word: String,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics { contentDescription = "搜索记录：$word" }
            .focusable(),
        shape = shape,
        color = if (focused) TvColors.SurfaceRaised else TvColors.Surface,
        contentColor = if (focused) TvColors.TextPrimary else TvColors.TextSecondary,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 窄屏 / 手机端搜索记录条：横向 chips，每条带 ✕ 删除，末尾「清空」。 */
@Composable
private fun SearchHistoryStrip(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索记录",
                style = MaterialTheme.typography.titleSmall,
                color = TvColors.Accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "清空",
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(history, key = { it }) { word ->
                SearchHistoryChip(
                    word = word,
                    onSelect = { onSelect(word) },
                    onRemove = { onRemove(word) },
                )
            }
        }
    }
}

/** 搜索记录 chip：点击重搜，右侧 ✕ 删除单条。 */
@Composable
private fun SearchHistoryChip(
    word: String,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics { contentDescription = "搜索记录：$word" }
            .focusable(),
        shape = shape,
        color = if (focused) TvColors.SurfaceRaised else TvColors.Surface,
        contentColor = if (focused) TvColors.TextPrimary else TvColors.TextSecondary,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
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
        // 热门候选用琥珀色调背景，与「搜索记录」的 Accent 绿色调区分开。
        color = if (focused) TvColors.SurfaceRaised else TvColors.AccentSoft.copy(alpha = 0.22f),
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
    TvFocusableSurface(
        shape = RoundedCornerShape(8.dp),
        onClick = onClick,
        focusedContainerColor = TvColors.Accent,
        idleContainerColor = TvColors.SurfaceRaised,
        modifier = modifier,
        focusedContentColor = TvColors.Background,
        idleContentColor = TvColors.TextPrimary,
        // 遥控键盘按键文字约 44dp 高，触摸模式不足最小热区，统一抬到 48dp。
        containerMinHeight = 48.dp,
        descriptionText = "键盘按键 $text",
    ) { focused ->
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** 搜索结果状态区：标题 + 计数 + 流式加载进度/进度条 + 错误文案；由 TV 面板与手机一体化网格共用。 */
@Composable
private fun SearchResultsHeader(state: TvBoxUiState) {
    val progress = completedSearchProgressText(
        completedSources = state.searchCompletedSources,
        totalSources = state.searchTotalSources,
        resultCount = state.searchResults.size,
    ).takeIf { it.isNotEmpty() }

    Column {
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
    }
}

@Composable
private fun SearchResultsPanel(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    returnFocus: ReturnFocus,
    resultsFocus: TvFocusRequest,
    alphaMovies: List<Movie>? = null,
    alphaTitle: String? = null,
    modifier: Modifier = Modifier,
) {
    // 字母浏览模式：结果区显示以选中首字母开头的影片目录（本地过滤 state.movies）。
    val inAlphaMode = alphaMovies != null
    val displayMovies = if (inAlphaMode) alphaMovies.orEmpty() else state.searchResults
    // 切换字母时结果区自动回到顶部，让「点击字母→自动加载」的定位更直观。
    LaunchedEffect(alphaTitle, inAlphaMode) {
        if (inAlphaMode) grid.scrollToItem(0)
    }
    Column(modifier = modifier) {
        if (inAlphaMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "字母 ${alphaTitle ?: ""} · ${displayMovies.size} 部",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "来自当前分类影片目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextTertiary,
                )
            }
        } else {
            SearchResultsHeader(state = state)
        }
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val loadingProgress = completedSearchProgressText(
                completedSources = state.searchCompletedSources,
                totalSources = state.searchTotalSources,
                resultCount = state.searchResults.size,
            ).takeIf { it.isNotEmpty() }
            when {
                inAlphaMode && displayMovies.isEmpty() -> AlphaEmptyState(
                    letter = alphaTitle ?: "",
                    catalogEmpty = state.movies.isEmpty(),
                )

                !inAlphaMode && state.searchLoading && state.searchResults.isEmpty() -> LoadingState(
                    text = loadingProgress ?: "正在搜索多个影视来源",
                )

                !inAlphaMode && state.searchResults.isEmpty() && state.searchQuery.isBlank() ->
                    SearchEmptyState(query = state.searchQuery)

                !inAlphaMode && state.searchError != null && state.searchResults.isEmpty() -> ErrorState(
                    message = state.searchError,
                    onRetry = actions::submitSearch,
                )

                !inAlphaMode && state.searchResults.isEmpty() -> SearchEmptyState(query = state.searchQuery)

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    state = grid,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = displayMovies,
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
                                        resultsFocus.anchor
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

/** 字母浏览空态：该字母暂无影片，或当前无影片目录可用。 */
@Composable
private fun AlphaEmptyState(letter: String, catalogEmpty: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (catalogEmpty) "暂无影片目录" else "字母 $letter 暂无影片",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (catalogEmpty) "请先在首页切换分类浏览，再回来使用字母索引" else "换个字母试试，或点击「全部」返回普通搜索",
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.TextSecondary,
        )
    }
}

/** 字母索引栏（TV 宽屏搜索）：竖向排列 A-Z + 「全部」，点击后结果区自动加载对应首字母影片。 */
@Composable
private fun AlphabetIndexBar(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "首字母",
            style = MaterialTheme.typography.labelMedium,
            color = TvColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item(key = "alpha-all") {
                AlphaLetterCell(
                    letter = "全部",
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            items(('A'..'Z').map { it.toString() }, key = { it }) { letter ->
                AlphaLetterCell(
                    letter = letter,
                    selected = selected == letter,
                    onClick = { onSelect(letter) },
                )
            }
        }
    }
}

@Composable
private fun AlphaLetterCell(
    letter: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "首字母：$letter" }
            .focusable(),
        shape = shape,
        color = when {
            selected -> TvColors.Accent.copy(alpha = 0.22f)
            focused -> TvColors.SurfaceRaised
            else -> TvColors.Surface
        },
        contentColor = if (selected) TvColors.Accent else TvColors.TextSecondary,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
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
    // 注意：这里**不能**用 remember(state.searchQuery) 作 key —— 输入时每个字符都会回写
    // searchQuery，key 一变 state 就被重建，selection 被强制挪到末尾、composition 被清空，
    // 中文拼音会在组合区就被打断。改为空 key + LaunchedEffect 单向同步（与电视版一致）。
    // 手机端搜索是底部常驻 Tab：切到别的 Tab / 点结果进详情再返回时，用户期望搜索词、
    // 结果与滚动位置都还在（SaveableStateHolder 已为此保存了 gridState）。因此不在
    // onDispose 时清空——电视版 Search 是独立页面，离开即离开，二者语义不同。
    // 需要清空时由搜索框的 ✕ 显式调用 clearSearch()。
    var fieldValue by remember { mutableStateOf(queryFieldValue(state.searchQuery)) }
    val hotWords = state.searchHotWords.ifEmpty { FallbackSearchHotWords }
    val gridState = rememberLazyGridState()
    // 分组折叠状态：默认全部展开；用源 id 作 key，搜索结果刷新不影响展开状态。
    var collapsedSources by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 按用户自定义源顺序分组；仅保留有结果的源。手机端据此渲染分组网格。
    val sourceGroups = remember(state.searchResultsBySource, state.apiLines) {
        buildSearchSourceGroups(state.searchResultsBySource, state.apiLines)
    }
    // 应用源筛选：空=全部源，否则只看选中的源（底层仍搜全部源，这里只影响展示）。
    val visibleGroups = remember(sourceGroups, state.searchSourceFilter) {
        if (state.searchSourceFilter.isEmpty()) sourceGroups
        else sourceGroups.filter { it.sourceId == state.searchSourceFilter }
    }

    LaunchedEffect(state.searchQuery) {
        if (fieldValue.text != state.searchQuery) {
            fieldValue = queryFieldValue(state.searchQuery)
        }
        // 换关键词后回到结果顶部，避免沿用上一批结果的滚动位置。
        gridState.scrollToItem(0)
    }

    // 切换源筛选后分组内容变化大，滚回顶部避免停留在旧滚动位置的错位处。
    LaunchedEffect(state.searchSourceFilter) {
        gridState.scrollToItem(0)
    }

    val focusManager = LocalFocusManager.current

    fun submit() {
        val q = fieldValue.text.trim()
        if (q.isEmpty()) return
        actions.updateSearchQuery(q)
        actions.submitSearch()
        // 提交后收起键盘：否则 IME 会把结果区压掉大半屏，还得手动按返回键收起来。
        focusManager.clearFocus()
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
        val columns = capacity
        Column(modifier = Modifier.fillMaxSize()) {
            // 搜索框固定在顶部：改关键词不必先滚回列表顶部，符合手机端输入习惯。
            // 搜索页不渲染公共顶栏，状态栏 inset 由这里自行吸收，
            // 否则 edge-to-edge 下搜索框顶边会被状态栏压住、视觉上超出页面边界。
            Surface(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
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
                        // 只更新本地输入框：把每个字符都回写 searchQuery 会触发
                        // LaunchedEffect 里的「回到结果顶部」，用户一边打字一边被拽回顶部。
                        // searchQuery 代表「已提交的搜索词」，只在 submit / 热词 / 清空时改写。
                        onValueChange = { fieldValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        singleLine = true,
                        placeholder = { Text("搜索影片") },
                        trailingIcon = {
                            if (fieldValue.text.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    color = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .clickable {
                                            fieldValue = queryFieldValue("")
                                            actions.clearSearch()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        },
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
            // 源筛选条：默认「全部」，点选某源只看该源分组（不改变底层搜索）。
            // 仅当有搜索结果时显示，避免空态/热词页出现一排无意义的源按钮。
            if (sourceGroups.isNotEmpty()) {
                SourceFilterBar(
                    groups = sourceGroups,
                    selectedId = state.searchSourceFilter,
                    totalCount = state.searchResultsBySource.values.sumOf { it.size },
                    onSelect = actions::setSearchSourceFilter,
                )
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
                            // 手机上错误文案常折到 3~4 行，之前固定 height(200.dp) 会把文字顶出
                            // 布局只留下一个「重试」按钮 —— 用户看不到失败原因，只觉得"点了没反应"。
                            ErrorState(
                                message = state.searchError,
                                onRetry = actions::submitSearch,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 200.dp, max = 420.dp),
                            )
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
                            val progressText = activeSearchProgressText(
                                completedSources = state.searchCompletedSources,
                                totalSources = state.searchTotalSources,
                                resultCount = state.searchResults.size,
                            ).takeIf { it.isNotEmpty() }
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
                        // 按源分组渲染：每组一个可折叠的分组头（跨整行），组内用原网格卡片。
                        visibleGroups.forEach { group ->
                            val collapsed = group.sourceId in collapsedSources
                            item(key = "header_${group.sourceId}", span = { GridItemSpan(maxLineSpan) }) {
                                SearchGroupHeader(
                                    group = group,
                                    collapsed = collapsed,
                                    onToggle = {
                                        collapsedSources = if (collapsed) {
                                            collapsedSources - group.sourceId
                                        } else {
                                            collapsedSources + group.sourceId
                                        }
                                    },
                                )
                            }
                            if (!collapsed) {
                                items(group.movies, key = { "${group.sourceId}-${it.id}" }) { movie ->
                                    SearchResultCardMobile(
                                        movie = movie,
                                        showSourceBadge = state.searchSourceFilter.isEmpty(),
                                        onClick = { actions.openDetail(movie.id, movie.apiLineId) },
                                    )
                                }
                            }
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
    showSourceBadge: Boolean = true,
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
            // 来源角标：在「全部源」视图下显示以区分来源；选中单源分组时隐藏避免冗余。
            if (showSourceBadge && movie.apiLineName.isNotBlank()) {
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

/**
 * 搜索源筛选条：横向滚动的胶囊按钮。「全部」显示总数，各源显示自己的结果数。
 * 选中态用品牌绿填充，未选中用深灰底；点击只切换展示，不重新发起搜索。
 */
@Composable
private fun SourceFilterBar(
    groups: List<SearchSourceGroup>,
    selectedId: String,
    totalCount: Int,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616)),
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "__all__") {
                SourceFilterChip(
                    label = "全部",
                    count = totalCount,
                    selected = selectedId.isEmpty(),
                    onClick = { onSelect("") },
                )
            }
            items(groups, key = { "filter_${it.sourceId}" }) { group ->
                SourceFilterChip(
                    label = group.sourceName,
                    count = group.movies.size,
                    selected = selectedId == group.sourceId,
                    onClick = { onSelect(group.sourceId) },
                )
            }
        }
    }
}

@Composable
private fun SourceFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) Color(0xFF1ED760) else Color(0xFF2A2A2A)
    val content = if (selected) Color.Black else Color.White.copy(alpha = 0.85f)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = container,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "搜索源：$label，$count 个结果" },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1)
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 按源分组的分组头：左侧品牌绿竖条 + 源名 + 结果数 + 折叠箭头。
 * 整行占满网格宽度，点击可折叠/展开该源下的影片。
 */
@Composable
private fun SearchGroupHeader(
    group: SearchSourceGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF222222),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = "${group.sourceName}，${group.movies.size} 个结果，${if (collapsed) "已折叠" else "已展开"}" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1ED760)),
            )
            Text(
                text = group.sourceName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${group.movies.size} 部",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
            Text(
                text = if (collapsed) "▸" else "▾",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}
