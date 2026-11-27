package com.tvbox.app.ai

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.ui.SearchKeyboard
import com.tvbox.app.ui.rememberTvFocusRequest
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.TvFocusableSurface
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens

/** AI 找片页面状态：所有数据由外部（ViewModel）提供，页面本身不发请求、不跳播放器。 */
data class AiSearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val candidates: List<AiCandidate> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val missBehavior: AiMissBehavior = AiMissBehavior.Stay,
    /** 语音按钮是否展示（来自设置项 voiceSearchEnabled）。 */
    val voiceEnabled: Boolean = false,
    /** 只读展示，例如 AiSearchRepository.apiKeySourceLabel()。 */
    val keySourceLabel: String = "",
)

/**
 * AI 找片最小闭环 UI：文本输入 + 建议 + 候选卡片 + 错误态 + 空态双按钮。
 * 候选卡片只把片名回调给 onCandidateClick，由外部决定下一步（普通搜索/详情），
 * 页面不会自行打开播放器。所有配色走现有 Theme token，不硬编码背景色。
 */
@Composable
fun AiSearchScreen(
    state: AiSearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCandidateClick: (String) -> Unit,
    onGoSearch: () -> Unit,
    onImportSource: () -> Unit,
    onVoiceResult: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 语音结果默认回填输入框；调用方通常传入「回填 + 立即提交」的组合回调。
    val requestVoice = rememberVoiceSearchRequest(onVoiceResult ?: onQueryChange)
    val error = state.errorMessage
    // TV 端不能依赖软键盘：输入框只读 + 屏幕键盘 + 显式搜索键，与普通搜索页保持一致。
    val tvMode = !LocalTouchMode.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val inputFocus = rememberTvFocusRequest(
        key = Unit,
        enabled = tvMode,
        delayMs = 60L,
        retries = 3,
    )

    PageSurface { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(TvDimens.SectionGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI 找片",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onBack != null) {
                    AiActionButton(text = "返回", onClick = onBack)
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth()
                    .then(inputFocus.anchor)
                    .onFocusChanged { focusState ->
                        if (tvMode && focusState.isFocused) softwareKeyboardController?.hide()
                    },
                singleLine = true,
                readOnly = tvMode,
                label = { Text(if (tvMode) "用遥控器输入你想找的影片" else "描述你想找的影片") },
                placeholder = { Text("例如：科幻、关于时间旅行的电影") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(state.query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TvColors.SurfaceRaised,
                    unfocusedContainerColor = TvColors.Surface,
                    focusedTextColor = TvColors.TextPrimary,
                    unfocusedTextColor = TvColors.TextPrimary,
                    focusedBorderColor = TvColors.Accent,
                    unfocusedBorderColor = TvColors.Border,
                ),
                trailingIcon = {
                    if (state.voiceEnabled) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable { requestVoice(context) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "语音", color = TvColors.Accent)
                        }
                    }
                },
            )

            if (tvMode) {
                TvFocusableSurface(
                    shape = RoundedCornerShape(10.dp),
                    onClick = { onSubmit(state.query) },
                    focusedContainerColor = TvColors.Accent,
                    idleContainerColor = TvColors.SurfaceRaised,
                    focusedContentColor = TvColors.Background,
                    idleContentColor = TvColors.TextPrimary,
                    containerMinHeight = 48.dp,
                    descriptionText = "搜索",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("搜索", style = MaterialTheme.typography.labelLarge)
                }
                SearchKeyboard(
                    onKey = { key -> onQueryChange(state.query + key) },
                    onDelete = { onQueryChange(state.query.dropLast(1)) },
                    onClear = { onQueryChange("") },
                )
            }

            if (state.keySourceLabel.isNotBlank()) {
                Text(
                    text = state.keySourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextTertiary,
                )
            }

            if (state.suggestions.isNotEmpty()) {
                Text(
                    text = "你可以这样问",
                    style = MaterialTheme.typography.titleSmall,
                    color = TvColors.TextSecondary,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.suggestions, key = { it }) { suggestion ->
                        SuggestionChip(text = suggestion, onClick = { onSubmit(suggestion) })
                    }
                }
            }

            AiActionButton(
                text = if (state.loading) "请求中…" else "AI 找片",
                onClick = { onSubmit(state.query) },
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading && state.candidates.isEmpty() ->
                        LoadingState(text = "正在向 AI 请求候选片名…")

                    error != null && state.candidates.isEmpty() -> AiMessageState(
                        message = error,
                        title = "AI 找片暂不可用",
                        onRetry = { onSubmit(state.query) },
                        onGoSearch = onGoSearch,
                        onImportSource = onImportSource,
                    )

                    state.candidates.isEmpty() -> AiMessageState(
                        message = if (state.missBehavior == AiMissBehavior.Search) {
                            "没有找到匹配的候选片名，可以切换到普通搜索或导入视频源。"
                        } else {
                            "暂时没有匹配的候选片名，可以调整描述、去普通搜索或导入视频源。"
                        },
                        onGoSearch = onGoSearch,
                        onImportSource = onImportSource,
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 170.dp),
                        contentPadding = PaddingValues(bottom = TvDimens.SectionGap),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.candidates, key = { "${it.title}-${it.year.orEmpty()}" }) { candidate ->
                            AiCandidateCard(
                                candidate = candidate,
                                // 只回调片名，不在这里打开播放器。
                                onClick = { onCandidateClick(candidate.title) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 错误态/空态共用：始终提供「去搜索 / 导入源」，错误时额外提供「重试」。回调全部由外部传入。 */
@Composable
private fun AiMessageState(
    message: String,
    onGoSearch: () -> Unit,
    onImportSource: () -> Unit,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = TvColors.TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = TvColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onRetry != null) {
                AiActionButton(text = "重试", onClick = onRetry)
            }
            AiActionButton(text = "去搜索", onClick = onGoSearch)
            AiActionButton(text = "导入源", onClick = onImportSource)
        }
    }
}

@Composable
private fun AiCandidateCard(
    candidate: AiCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusableSurface(
        shape = RoundedCornerShape(TvDimens.CardRadius),
        onClick = onClick,
        focusedContainerColor = TvColors.AccentSoft,
        idleContainerColor = TvColors.SurfaceRaised,
        modifier = modifier.heightIn(min = 84.dp),
        contentAlignment = Alignment.CenterStart,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        descriptionText = "候选影片：${candidate.title}${candidate.year?.let { "，${it}年" } ?: ""}",
    ) {
        Column {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                color = TvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!candidate.year.isNullOrBlank()) {
                Text(
                    text = candidate.year,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    TvFocusableSurface(
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        focusedContainerColor = TvColors.Accent,
        idleContainerColor = TvColors.SurfaceRaised,
        focusedContentColor = TvColors.Background,
        idleContentColor = TvColors.TextSecondary,
        containerMinHeight = 44.dp,
        contentPadding = PaddingValues(horizontal = 14.dp),
        descriptionText = "使用建议：$text",
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun AiActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusableSurface(
        shape = RoundedCornerShape(50),
        onClick = onClick,
        focusedContainerColor = TvColors.Accent,
        idleContainerColor = TvColors.SurfaceRaised,
        focusedContentColor = TvColors.Background,
        idleContentColor = TvColors.TextPrimary,
        containerMinHeight = 48.dp,
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier,
        descriptionText = text,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * 记住一个系统语音页调用器：返回 true 表示已拉起系统语音页，false 表示设备无识别应用。
 * 不申请任何权限，识别结果由 [VoiceSearch.parseResult] 解析后回调。
 */
@Composable
fun rememberVoiceSearchRequest(onResult: (String) -> Unit): (Context) -> Boolean {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        VoiceSearch.parseResult(result.data)?.let { latest.value(it) }
    }
    return remember(launcher) { launchRequest@{ context: Context ->
        val intent = VoiceSearch.createIntent(context) ?: return@launchRequest false
        launcher.launch(intent)
        true
    } }
}
