package com.tvbox.app.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.VideoScaleMode
import kotlinx.coroutines.delay

/** 共享面板标识：TV 居中面板与手机内嵌面板共用同一套状态。 */
internal enum class PlayerPanel { None, Episodes, Sources, Speeds, Picture, Skip }

internal const val PLAYER_CHROME_AUTO_HIDE_MS = 5_000L
internal const val PLAYER_CHROME_LIVE_AUTO_HIDE_MS = 3_000L
internal const val PLAYER_CHROME_TOUCH_AUTO_HIDE_MS = 4_000L

/**
 * 共享控件状态：四个播放页不再各自维护 visible / panel / 自动隐藏 / 焦点恢复。
 * 页面只把根节点的 FocusRequester 接到 [rootFocus]。
 */
internal class PlayerChromeState(
    initialVisible: Boolean = false,
) {
    var visible by mutableStateOf(initialVisible)
    var panel by mutableStateOf(PlayerPanel.None)
    var interaction by mutableIntStateOf(0)
    var scrubbing by mutableStateOf(false)
    var locked by mutableStateOf(false)
    /** 页面级浮层（频道列表、线路浮层等）打开时暂停自动隐藏。 */
    var holdVisible by mutableStateOf(false)
    val rootFocus = FocusRequester()

    fun bump() {
        visible = true
        interaction++
    }

    fun toggle() {
        visible = !visible
        interaction++
    }

    fun openPanel(target: PlayerPanel) {
        panel = target
        bump()
    }

    fun closePanel() {
        panel = PlayerPanel.None
        interaction++
    }

    fun hide() {
        visible = false
        restoreFocus()
    }

    fun restoreFocus() {
        runCatching { rootFocus.requestFocus() }
    }
}

/** 纯函数：控件是否应当自动隐藏，供单测锁定四页归并后的显隐规则。 */
internal fun playerChromeShouldAutoHide(
    visible: Boolean,
    panel: PlayerPanel,
    locked: Boolean,
    scrubbing: Boolean,
    playing: Boolean,
    holdVisible: Boolean,
): Boolean = visible && panel == PlayerPanel.None && !locked && !scrubbing && playing && !holdVisible

/** 纯函数：BACK 一次只退回一层。 */
internal enum class PlayerChromeBackAction { LockedHint, ClosePanel, DismissExtra, HideControls, Exit }

internal fun playerChromeBackAction(
    locked: Boolean,
    panel: PlayerPanel,
    extraLayerOpen: Boolean,
    visible: Boolean,
    dismissControls: Boolean,
): PlayerChromeBackAction = when {
    extraLayerOpen -> PlayerChromeBackAction.DismissExtra
    locked && visible -> PlayerChromeBackAction.LockedHint
    panel != PlayerPanel.None -> PlayerChromeBackAction.ClosePanel
    dismissControls && visible -> PlayerChromeBackAction.HideControls
    else -> PlayerChromeBackAction.Exit
}

/** 自动隐藏：播放中 5 秒（或页面指定）无操作后收起；暂停/面板/锁定/浮层期间不隐藏。 */
@Composable
internal fun PlayerChromeAutoHide(
    state: PlayerChromeState,
    playing: Boolean,
    autoHideMs: Long = PLAYER_CHROME_AUTO_HIDE_MS,
    holdVisible: Boolean = false,
) {
    LaunchedEffect(
        state.visible, state.panel, state.interaction, state.locked,
        state.scrubbing, playing, state.holdVisible, holdVisible,
    ) {
        if (!playerChromeShouldAutoHide(
                visible = state.visible,
                panel = state.panel,
                locked = state.locked,
                scrubbing = state.scrubbing,
                playing = playing,
                holdVisible = holdVisible || state.holdVisible,
            )
        ) {
            return@LaunchedEffect
        }
        delay(autoHideMs)
        state.hide()
    }
}

/**
 * 共享 BACK 层级：页面浮层 → 锁屏提示 → 面板 → 控件 → 退出。
 * [extraDismiss] 按“最上层在前”的顺序返回 true 表示已收起该层。
 */
@Composable
internal fun PlayerChromeBackHandler(
    state: PlayerChromeState,
    onExit: () -> Unit,
    dismissControls: Boolean = true,
    onLockedHint: () -> Unit = {},
    extraDismiss: List<() -> Boolean> = emptyList(),
) {
    BackHandler {
        val action = playerChromeBackAction(
            locked = state.locked,
            panel = state.panel,
            extraLayerOpen = false,
            visible = state.visible,
            dismissControls = dismissControls,
        )
        var handled = false
        for (dismiss in extraDismiss) {
            if (dismiss()) {
                handled = true
                break
            }
        }
        if (!handled) {
            when (action) {
                PlayerChromeBackAction.LockedHint -> onLockedHint()
                PlayerChromeBackAction.ClosePanel -> state.closePanel()
                PlayerChromeBackAction.DismissExtra -> Unit
                PlayerChromeBackAction.HideControls -> state.hide()
                PlayerChromeBackAction.Exit -> onExit()
            }
        }
    }
}

/** 页面传入的数据语义：标题、集/线路列表、进度与播放状态。 */
internal data class PlayerChromeSource(
    val name: String,
    val lineId: String,
)

internal data class PlayerChromeData(
    val title: String,
    val subtitle: String? = null,
    val episodes: List<String> = emptyList(),
    val sources: List<PlayerChromeSource> = emptyList(),
    val episodeIndex: Int = 0,
    val sourceIndex: Int = 0,
    val sourceEnabled: List<Boolean> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val resizeMode: VideoScaleMode = VideoScaleMode.Fit,
    val sourceLatencies: Map<String, Long?> = emptyMap(),
    val buffering: Boolean = false,
)

/** 能力清单：这一页支持哪些控件；共享层不再判断“是点播还是直播”。 */
internal data class PlayerChromeCapabilities(
    val seek: Boolean = true,
    val episodes: Boolean = false,
    val sources: Boolean = false,
    val speed: Boolean = false,
    val resize: Boolean = false,
    val skip: Boolean = false,
    val cast: Boolean = false,
    val castLabel: String? = null,
    val lock: Boolean = false,
    val favorite: Boolean = false,
    val favoriteLabel: String? = null,
    val favoriteSelected: Boolean = false,
    val orientation: Boolean = false,
    val mute: Boolean = false,
    val settings: Boolean = false,
    val channel: Boolean = false,
    val room: Boolean = false,
    val lines: Boolean = false,
    val info: Boolean = false,
)

/** 页面注入的语义动作：共享层只负责按键/触摸到这些回调的映射。 */
internal data class PlayerChromeActions(
    val onToggle: () -> Unit,
    val onSeek: (Long) -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onSelectEpisode: (Int) -> Unit = {},
    val onSelectSource: (Int) -> Unit = {},
    val onSelectSpeed: (Float) -> Unit = { _ -> },
    val onSelectResize: (VideoScaleMode) -> Unit = {},
    val onOpenLinePanel: () -> Unit = {},
    val onOpenChannels: () -> Unit = {},
    val onOpenRoomPanel: () -> Unit = {},
    val onOpenInfo: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onMute: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onLockChange: (Boolean) -> Unit = {},
    val onInteraction: () -> Unit = {},
)

private val playerSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val introSkipOptions = listOf(0, 10, 20, 30, 60)
private val outroSkipOptions = listOf(0, 15, 30, 60, 90)
private val LatencyGreen = Color(0xFF4ADE80)

/**
 * 共享控件层：TV 与手机共用同一套状态、面板与能力注入。
 * 页面只传数据/能力/动作；[topSlot] 与 [statusSlot] 承接各页独有信息（时钟、频道状态等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerChrome(
    state: PlayerChromeState,
    data: PlayerChromeData,
    capabilities: PlayerChromeCapabilities,
    actions: PlayerChromeActions,
    touch: Boolean,
    autoHideMs: Long = PLAYER_CHROME_AUTO_HIDE_MS,
    episodeSortReverse: Boolean = false,
    onSortReverse: () -> Unit = {},
    introSkipSeconds: Int = 0,
    outroSkipSeconds: Int = 0,
    onIntroSkip: (Int) -> Unit = {},
    onOutroSkip: (Int) -> Unit = {},
    statusContent: (@Composable RowScope.() -> Unit)? = null,
    fade: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = if (fade) fadeIn(tween(150)) else EnterTransition.None,
        exit = if (fade) fadeOut(tween(250)) else ExitTransition.None,
    ) {
        if (touch) {
            PlayerChromeTouch(
                state = state,
                data = data,
                capabilities = capabilities,
                actions = actions,
                episodeSortReverse = episodeSortReverse,
                onSortReverse = onSortReverse,
                introSkipSeconds = introSkipSeconds,
                outroSkipSeconds = outroSkipSeconds,
                onIntroSkip = onIntroSkip,
                onOutroSkip = onOutroSkip,
                modifier = modifier,
            )
        } else {
            PlayerChromeTv(
                state = state,
                data = data,
                capabilities = capabilities,
                actions = actions,
                episodeSortReverse = episodeSortReverse,
                onSortReverse = onSortReverse,
                introSkipSeconds = introSkipSeconds,
                outroSkipSeconds = outroSkipSeconds,
                onIntroSkip = onIntroSkip,
                onOutroSkip = onOutroSkip,
                statusContent = statusContent,
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChromeTv(
    state: PlayerChromeState,
    data: PlayerChromeData,
    capabilities: PlayerChromeCapabilities,
    actions: PlayerChromeActions,
    episodeSortReverse: Boolean,
    onSortReverse: () -> Unit,
    introSkipSeconds: Int,
    outroSkipSeconds: Int,
    onIntroSkip: (Int) -> Unit,
    onOutroSkip: (Int) -> Unit,
    statusContent: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier,
) {
    val buttonScroll = rememberScrollState()
    var progressFocused by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    var preview by remember { mutableStateOf<Float?>(null) }
    var lastSeekTime by remember { mutableStateOf(Long.MIN_VALUE) }
    val compact = LocalConfiguration.current.screenWidthDp < 800
    LaunchedEffect(state.panel) { if (state.panel == PlayerPanel.None) runCatching { playFocus.requestFocus() } }
    Box(modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .windowInsetsPadding(WindowInsets.safeDrawing).testTag("player_controls"),
            color = Color.Transparent, contentColor = Color.White,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildString {
                        append(data.title)
                        data.subtitle?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (statusContent != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = statusContent,
                    )
                }
                if (capabilities.seek) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPlayerTime(preview?.toLong() ?: data.positionMs), modifier = Modifier.width(66.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f))
                        Slider(
                            value = preview ?: data.positionMs.toFloat().coerceIn(0f, data.durationMs.coerceAtLeast(1).toFloat()),
                            onValueChange = {
                                preview = it; state.scrubbing = true; actions.onInteraction()
                            },
                            onValueChangeFinished = {
                                preview?.let { actions.onSeek(it.toLong()) }; preview = null; state.scrubbing = false
                            },
                            valueRange = 0f..data.durationMs.coerceAtLeast(1).toFloat(), enabled = data.durationMs > 0,
                            thumb = {
                                Box(Modifier.size(if (progressFocused) 18.dp else 12.dp)
                                    .background(Color.White, CircleShape))
                            },
                            track = { slider ->
                                val fraction = ((slider.value - slider.valueRange.start) /
                                    (slider.valueRange.endInclusive - slider.valueRange.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                Canvas(Modifier.fillMaxWidth().height(if (progressFocused) 5.dp else 3.dp)) {
                                    val center = size.height / 2
                                    drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, center), Offset(size.width, center), size.height, StrokeCap.Round)
                                    drawLine(Color(0xFF7AE2BA), Offset(0f, center), Offset(size.width * fraction, center), size.height, StrokeCap.Round)
                                }
                            },
                            modifier = Modifier.weight(1f).height(36.dp).padding(horizontal = 8.dp)
                                .onFocusChanged { progressFocused = it.isFocused }
                                .testTag("player_progress").onPreviewKeyEvent { event ->
                                val delta = when (event.nativeKeyEvent.keyCode) {
                                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> -10_000L
                                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> 10_000L
                                    else -> return@onPreviewKeyEvent false
                                }
                                actions.onInteraction()
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.nativeKeyEvent.repeatCount == 0 || lastSeekTime == Long.MIN_VALUE ||
                                        event.nativeKeyEvent.eventTime - lastSeekTime >= 150) {
                                        preview = ((preview?.toLong() ?: data.positionMs) + delta)
                                            .coerceIn(0, data.durationMs.coerceAtLeast(0)).toFloat()
                                        lastSeekTime = event.nativeKeyEvent.eventTime
                                    }
                                    state.scrubbing = true
                                } else if (event.type == KeyEventType.KeyUp) {
                                    preview?.let { actions.onSeek(it.toLong()) }; preview = null; state.scrubbing = false
                                    lastSeekTime = Long.MIN_VALUE
                                }
                                true
                            },
                        )
                        Text(if (data.durationMs > 0) formatPlayerTime(data.durationMs) else "--:--", modifier = Modifier.width(66.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.horizontalScroll(buttonScroll) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(if (data.playing) "暂停" else "播放", {
                        actions.onToggle(); state.interaction++
                    }, Modifier.focusRequester(playFocus), primary = true)
                    if (capabilities.episodes) {
                        ControlButton("上一集", { actions.onPrevious(); state.interaction++ }, enabled = data.episodeIndex > 0)
                        ControlButton("下一集", { actions.onNext(); state.interaction++ }, enabled = data.episodeIndex < data.episodes.lastIndex)
                    }
                    if (capabilities.channel) {
                        ControlButton("上一台", { actions.onPrevious(); state.interaction++ })
                        ControlButton("下一台", { actions.onNext(); state.interaction++ })
                    }
                    if (capabilities.room) {
                        ControlButton("上一个", { actions.onPrevious(); state.interaction++ })
                        ControlButton("下一个", { actions.onNext(); state.interaction++ })
                    }
                    if (!compact) Spacer(Modifier.weight(1f))
                    if (capabilities.cast) ControlButton(capabilities.castLabel ?: "投屏", { actions.onCast(); state.interaction++ })
                    if (capabilities.episodes) ControlButton("选集", { state.openPanel(PlayerPanel.Episodes) })
                    if (capabilities.sources) ControlButton("线路", { state.openPanel(PlayerPanel.Sources) })
                    if (capabilities.lines) ControlButton("线路", { actions.onOpenLinePanel(); state.interaction++ })
                    if (capabilities.favorite) ControlButton(capabilities.favoriteLabel ?: "收藏", { actions.onFavorite(); state.interaction++ })
                    if (capabilities.speed) ControlButton("${formatSpeedLabel(data.speed)}x", { state.openPanel(PlayerPanel.Speeds); actions.onInteraction() })
                    if (capabilities.resize) ControlButton("画面", { state.openPanel(PlayerPanel.Picture); actions.onInteraction() })
                    if (capabilities.skip) ControlButton("跳过", { state.openPanel(PlayerPanel.Skip); actions.onInteraction() })
                    if (capabilities.info) ControlButton("信息", { actions.onOpenInfo(); state.interaction++ })
                    if (capabilities.settings) ControlButton("设置", { actions.onSettings(); state.interaction++ })
                }
            }
        }
        PlayerChromePanel(
            state = state,
            data = data,
            capabilities = capabilities,
            actions = actions,
            episodeSortReverse = episodeSortReverse,
            onSortReverse = onSortReverse,
            introSkipSeconds = introSkipSeconds,
            outroSkipSeconds = outroSkipSeconds,
            onIntroSkip = onIntroSkip,
            onOutroSkip = onOutroSkip,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.8f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChromeTouch(
    state: PlayerChromeState,
    data: PlayerChromeData,
    capabilities: PlayerChromeCapabilities,
    actions: PlayerChromeActions,
    episodeSortReverse: Boolean,
    onSortReverse: () -> Unit,
    introSkipSeconds: Int,
    outroSkipSeconds: Int,
    onIntroSkip: (Int) -> Unit,
    onOutroSkip: (Int) -> Unit,
    modifier: Modifier,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val episodeTitle = data.subtitle.orEmpty()
    Box(modifier = modifier.fillMaxSize()) {
        if (state.locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            ) {
                MobileChip(
                    text = "解锁",
                    modifier = Modifier.align(Alignment.CenterEnd).width(92.dp),
                    selected = true,
                    onClick = { state.locked = false; actions.onLockChange(false); state.interaction++ },
                )
                Text(
                    text = "播放已锁定，点击“解锁”恢复控制",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            return@Box
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent))),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { actions.onBack(); state.interaction++ },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("返回", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (episodeTitle.isNotBlank()) {
                            Text(
                                text = episodeTitle,
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (data.buffering) {
                            Text("缓冲中", color = Color(0xFF7AE2BA), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (capabilities.favorite) {
                    MobileChip(
                        text = capabilities.favoriteLabel ?: "★",
                        modifier = Modifier.width(48.dp),
                        selected = capabilities.favoriteSelected,
                        onClick = { actions.onFavorite(); state.interaction++ },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (capabilities.orientation) {
                    MobileChip(
                        text = "⛶",
                        modifier = Modifier.width(48.dp),
                        onClick = { actions.onOrientation(); state.interaction++ },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (capabilities.lock) {
                    TextButton(onClick = { state.locked = true; actions.onLockChange(true); state.interaction++ }) {
                        Text("锁定", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
                ),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.panel != PlayerPanel.None) {
                    PlayerChromeMobilePanel(
                        section = state.panel,
                        data = data,
                        actions = actions,
                        onClose = { state.closePanel() },
                        episodeSortReverse = episodeSortReverse,
                        onSortReverse = onSortReverse,
                        introSkipSeconds = introSkipSeconds,
                        outroSkipSeconds = outroSkipSeconds,
                        onIntroSkip = onIntroSkip,
                        onOutroSkip = onOutroSkip,
                    )
                }
                if (capabilities.seek) {
                    MobileProgressRow(positionMs = data.positionMs, durationMs = data.durationMs, onSeek = {
                        actions.onSeek(it); state.interaction++
                    })
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MobileChip(
                            text = "-10s",
                            modifier = Modifier.weight(0.8f),
                            enabled = data.durationMs > 0,
                            onClick = { actions.onSeekBy(-10_000L); state.interaction++ },
                        )
                        MobileChip(
                            text = "上一集",
                            modifier = Modifier.weight(1f),
                            enabled = data.episodeIndex > 0,
                            onClick = { actions.onPrevious(); state.interaction++ },
                        )
                        MobilePlayPauseButton(playing = data.playing, onClick = { actions.onToggle(); state.interaction++ })
                        MobileChip(
                            text = "下一集",
                            modifier = Modifier.weight(1f),
                            enabled = data.episodeIndex < data.episodes.lastIndex,
                            onClick = { actions.onNext(); state.interaction++ },
                        )
                        MobileChip(
                            text = "+10s",
                            modifier = Modifier.weight(0.8f),
                            enabled = data.durationMs > 0,
                            onClick = { actions.onSeekBy(10_000L); state.interaction++ },
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (capabilities.channel || capabilities.room) {
                            MobileChip(
                                text = if (capabilities.channel) "上一台" else "上一个",
                                modifier = Modifier.weight(1f),
                                onClick = { actions.onPrevious(); state.interaction++ },
                            )
                            MobilePlayPauseButton(playing = data.playing, onClick = { actions.onToggle(); state.interaction++ })
                            MobileChip(
                                text = if (capabilities.channel) "下一台" else "下一个",
                                modifier = Modifier.weight(1f),
                                onClick = { actions.onNext(); state.interaction++ },
                            )
                        } else {
                            MobilePlayPauseButton(playing = data.playing, onClick = { actions.onToggle(); state.interaction++ })
                        }
                    }
                }
                val features = buildList {
                    if (capabilities.episodes) add(ChromeFeature("选集", state.panel == PlayerPanel.Episodes) {
                        state.openPanel(PlayerPanel.Episodes)
                    })
                    if (capabilities.sources) add(ChromeFeature("线路", state.panel == PlayerPanel.Sources) {
                        state.openPanel(PlayerPanel.Sources)
                    })
                    if (capabilities.lines) add(ChromeFeature("线路") {
                        actions.onOpenLinePanel(); state.interaction++
                    })
                    if (capabilities.speed) add(ChromeFeature("${formatSpeedLabel(data.speed)}x", state.panel == PlayerPanel.Speeds) {
                        state.openPanel(PlayerPanel.Speeds)
                    })
                    if (capabilities.resize) add(ChromeFeature("画面", state.panel == PlayerPanel.Picture) {
                        state.openPanel(PlayerPanel.Picture)
                    })
                    if (capabilities.skip) add(ChromeFeature("跳过", state.panel == PlayerPanel.Skip) {
                        state.openPanel(PlayerPanel.Skip)
                    })
                    if (capabilities.mute) add(ChromeFeature("音量") {
                        actions.onMute(); state.interaction++
                    })
                    if (capabilities.info) add(ChromeFeature("信息") {
                        actions.onOpenInfo(); state.interaction++
                    })
                    if (capabilities.settings) add(ChromeFeature("设置") {
                        actions.onSettings(); state.interaction++
                    })
                    if (capabilities.cast) add(ChromeFeature(capabilities.castLabel ?: "投屏") {
                        actions.onCast(); state.interaction++
                    })
                }
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        features.forEach { feature ->
                            MobileChip(text = feature.label, selected = feature.selected, onClick = feature.onClick)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        features.chunked(3).forEach { rowItems ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { feature ->
                                    MobileChip(
                                        text = feature.label,
                                        modifier = Modifier.weight(1f),
                                        selected = feature.selected,
                                        onClick = feature.onClick,
                                    )
                                }
                                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ChromeFeature(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChromePanel(
    state: PlayerChromeState,
    data: PlayerChromeData,
    capabilities: PlayerChromeCapabilities,
    actions: PlayerChromeActions,
    episodeSortReverse: Boolean,
    onSortReverse: () -> Unit,
    introSkipSeconds: Int,
    outroSkipSeconds: Int,
    onIntroSkip: (Int) -> Unit,
    onOutroSkip: (Int) -> Unit,
    modifier: Modifier,
) {
    val panel = state.panel
    if (panel == PlayerPanel.None) return
    if (panel == PlayerPanel.Skip) {
        SkipSettingsPanel(
            introSeconds = introSkipSeconds,
            outroSeconds = outroSkipSeconds,
            onIntroSkip = { onIntroSkip(it); state.interaction++ },
            onOutroSkip = { onOutroSkip(it); state.interaction++ },
            onClose = { state.closePanel() },
            modifier = modifier,
        )
        return
    }
    val orderedSources = remember(data.sources, data.sourceLatencies) {
        orderSourcesByLatency(data.sources, data.sourceLatencies)
    }
    val labels: List<AnnotatedString> = when (panel) {
        PlayerPanel.Episodes -> data.episodes.map { AnnotatedString(it) }
        PlayerPanel.Sources -> orderedSources.map { sourceTabLabel(it.value, data.sourceLatencies) }
        PlayerPanel.Speeds -> playerSpeeds.map { AnnotatedString("${it}x") }
        PlayerPanel.Picture -> VideoScaleMode.entries.map { AnnotatedString(it.displayName) }
        else -> emptyList()
    }
    val selected = when (panel) {
        PlayerPanel.Episodes -> data.episodeIndex
        PlayerPanel.Sources -> orderedSources.indexOfFirst { it.index == data.sourceIndex }.coerceAtLeast(0)
        PlayerPanel.Speeds -> playerSpeeds.indexOf(data.speed).coerceAtLeast(0)
        PlayerPanel.Picture -> VideoScaleMode.entries.indexOf(data.resizeMode)
        else -> 0
    }
    val pickerFocus = remember(panel, labels.size) { List(labels.size) { FocusRequester() } }
    val grid = rememberLazyGridState()
    LaunchedEffect(panel, labels.size) {
        grid.scrollToItem(selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
        pickerFocus.getOrNull(selected)?.requestFocus()
    }
    Surface(modifier.fillMaxHeight(0.6f),
        color = Color(0xFA202020), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (panel) {
                    PlayerPanel.Episodes -> "选择集数${if (episodeSortReverse) "（倒序）" else ""}"
                    PlayerPanel.Sources -> "选择线路 · 已按测速排序"
                    PlayerPanel.Picture -> "画面比例 · ${data.resizeMode.displayName}"
                    else -> "播放倍速"
                }, Modifier.weight(1f))
                if (panel == PlayerPanel.Episodes) {
                    ControlButton("↕ 倒序", { onSortReverse(); state.interaction++ })
                }
                ControlButton("关闭", { state.closePanel() })
            }
            if (panel == PlayerPanel.Picture) {
                Text(data.resizeMode.description, style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 12.dp))
            }
            if (panel == PlayerPanel.Sources && orderedSources.none { data.sourceLatencies[it.value.lineId] != null }) {
                Text("可先在详情页测速，线路会按延迟从低到高排列",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 12.dp))
            }
            LazyVerticalGrid(GridCells.Adaptive(130.dp), state = grid,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(labels) { index, label ->
                    val realIndex = if (panel == PlayerPanel.Sources) orderedSources[index].index else index
                    val enabled = when (panel) {
                        PlayerPanel.Sources -> data.sourceEnabled.getOrNull(realIndex) ?: true
                        else -> true
                    }
                    ControlButton(if (index == selected) label.withSuffix(" ✓") else label, {
                        actions.onInteraction()
                        when (panel) {
                            PlayerPanel.Episodes -> actions.onSelectEpisode(realIndex)
                            PlayerPanel.Sources -> actions.onSelectSource(realIndex)
                            PlayerPanel.Speeds -> actions.onSelectSpeed(playerSpeeds[realIndex])
                            PlayerPanel.Picture -> actions.onSelectResize(VideoScaleMode.entries[realIndex])
                            else -> Unit
                        }
                        state.closePanel()
                    }, Modifier.focusRequester(pickerFocus[index]), enabled)
                }
            }
        }
    }
}

@Composable
private fun PlayerChromeMobilePanel(
    section: PlayerPanel,
    data: PlayerChromeData,
    actions: PlayerChromeActions,
    onClose: () -> Unit,
    episodeSortReverse: Boolean,
    onSortReverse: () -> Unit,
    introSkipSeconds: Int,
    outroSkipSeconds: Int,
    onIntroSkip: (Int) -> Unit,
    onOutroSkip: (Int) -> Unit,
) {
    Surface(
        color = Color(0xE61A1A1A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (section) {
                        PlayerPanel.Episodes -> "选集（共 ${data.episodes.size} 集）"
                        PlayerPanel.Sources -> "线路（共 ${data.sources.size} 条）"
                        PlayerPanel.Speeds -> "播放倍速"
                        PlayerPanel.Picture -> "画面比例 · ${data.resizeMode.displayName}"
                        PlayerPanel.Skip -> "跳过片头/片尾"
                        PlayerPanel.None -> ""
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
                TextButton(onClick = onClose) {
                    Text("收起", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                }
            }
            when (section) {
                PlayerPanel.Episodes -> {
                    val epList = if (episodeSortReverse) data.episodes.asReversed() else data.episodes
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = onSortReverse) {
                                Text(if (episodeSortReverse) "正序" else "倒序", color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(epList.size) { index ->
                                val realIndex = if (episodeSortReverse) data.episodes.lastIndex - index else index
                                MobileChip(
                                    text = "${realIndex + 1}. ${epList[index]}",
                                    selected = realIndex == data.episodeIndex,
                                    onClick = { actions.onSelectEpisode(realIndex); onClose() },
                                )
                            }
                        }
                    }
                }
                PlayerPanel.Sources -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(data.sources.size) { index ->
                        MobileChip(
                            text = data.sources[index].name,
                            selected = index == data.sourceIndex,
                            onClick = { actions.onSelectSource(index); onClose() },
                        )
                    }
                }
                PlayerPanel.Speeds -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    playerSpeeds.forEach { item ->
                        MobileChip(
                            text = "${formatSpeedLabel(item)}x",
                            selected = kotlin.math.abs(item - data.speed) < 0.01f,
                            onClick = { actions.onSelectSpeed(item); onClose() },
                        )
                    }
                }
                PlayerPanel.Picture -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoScaleMode.entries.forEach { mode ->
                        MobileChip(
                            text = mode.displayName,
                            selected = mode == data.resizeMode,
                            onClick = { actions.onSelectResize(mode); onClose() },
                        )
                    }
                }
                PlayerPanel.Skip -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开始跳过：每次开播自动跳过片头", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f))
                    MobileSkipOptionRow(introSkipOptions, introSkipSeconds, onIntroSkip)
                    Text("结束跳过：距离片尾还剩指定秒数时自动下一集", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f))
                    MobileSkipOptionRow(outroSkipOptions, outroSkipSeconds, onOutroSkip)
                    Text("设置立即保存，对之后播放生效。", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f))
                }
                PlayerPanel.None -> Unit
            }
        }
    }
}

@Composable
internal fun ControlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    ControlButton(AnnotatedString(text), onClick, modifier, enabled, primary)
}

@Composable
internal fun ControlButton(
    text: AnnotatedString,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    minHeight: Dp = 48.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (primary) 24.dp else 10.dp)
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = minHeight).onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused && enabled) Color(0xFF7AE2BA) else Color.Transparent, shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = when { primary -> Color.White; focused -> Color.White.copy(alpha = 0.18f); else -> Color.Transparent },
            contentColor = if (primary) Color(0xFF101614) else Color.White.copy(alpha = 0.9f),
            disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

private fun orderSourcesByLatency(
    sources: List<PlayerChromeSource>,
    latencies: Map<String, Long?>,
): List<IndexedValue<PlayerChromeSource>> {
    return sources.withIndex().sortedWith(
        compareBy<IndexedValue<PlayerChromeSource>> { item ->
            val latency = latencies[item.value.lineId]
            when {
                latency != null -> 0
                item.value.lineId in latencies -> 1
                else -> 2
            }
        }.thenBy { item -> latencies[item.value.lineId] ?: Long.MAX_VALUE }
            .thenBy { it.index },
    )
}

@Composable
private fun SkipSettingsPanel(
    introSeconds: Int,
    outroSeconds: Int,
    onIntroSkip: (Int) -> Unit,
    onOutroSkip: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    Surface(modifier, color = Color(0xFA202020), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("跳过设置", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ControlButton("关闭", onClose)
            }
            Text("开始跳过：每次开播自动跳过片头", style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f))
            SkipOptionRow(introSkipOptions, introSeconds, onIntroSkip, firstFocus)
            Text("结束跳过：距离片尾还剩这么多秒时自动下一集", style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f))
            SkipOptionRow(outroSkipOptions, outroSeconds, onOutroSkip)
            Text("设置会立即保存，对之后播放的集数生效。", style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun SkipOptionRow(
    options: List<Int>,
    current: Int,
    onSelect: (Int) -> Unit,
    firstFocus: FocusRequester? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, seconds ->
            val label = if (seconds == 0) "关闭" else "${seconds}秒"
            ControlButton(
                text = if (current == seconds) "$label ✓" else label,
                onClick = { onSelect(seconds) },
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun MobileSkipOptionRow(
    options: List<Int>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { seconds ->
            val label = if (seconds == 0) "关闭" else "${seconds}秒"
            MobileChip(
                text = if (current == seconds) "$label ✓" else label,
                selected = current == seconds,
                onClick = { onSelect(seconds) },
            )
        }
    }
}

private fun formatSpeedLabel(speed: Float): String = speed.toString().trimEnd('0').trimEnd('.')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileProgressRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var preview by remember { mutableStateOf<Float?>(null) }
    val maxValue = durationMs.coerceAtLeast(1L).toFloat()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatPlayerTime(preview?.toLong() ?: positionMs),
            modifier = Modifier.widthIn(min = 52.dp),
            style = timeStyle,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
        Slider(
            value = (preview ?: positionMs.toFloat()).coerceIn(0f, maxValue),
            onValueChange = { preview = it },
            onValueChangeFinished = {
                preview?.let { onSeek(it.toLong()) }
                preview = null
            },
            valueRange = 0f..maxValue,
            enabled = durationMs > 0,
            thumb = {
                Box(modifier = Modifier.size(16.dp).background(Color.White, CircleShape))
            },
            track = { slider ->
                val fraction = ((slider.value - slider.valueRange.start) /
                    (slider.valueRange.endInclusive - slider.valueRange.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val center = size.height / 2
                    drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, center), Offset(size.width, center), size.height, StrokeCap.Round)
                    drawLine(Color(0xFF7AE2BA), Offset(0f, center), Offset(size.width * fraction, center), size.height, StrokeCap.Round)
                }
            },
            modifier = Modifier.weight(1f).height(40.dp),
        )
        Text(
            text = if (durationMs > 0) formatPlayerTime(durationMs) else "--:--",
            modifier = Modifier.widthIn(min = 52.dp),
            style = timeStyle,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

@Composable
private fun MobilePlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Color.White)
            .semantics { contentDescription = if (playing) "暂停" else "播放" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            if (playing) {
                val barWidth = size.width * 0.26f
                drawRect(Color(0xFF101614), topLeft = Offset(0f, 0f), size = Size(barWidth, size.height))
                drawRect(Color(0xFF101614), topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height))
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.12f, 0f)
                    lineTo(size.width * 0.95f, size.height / 2f)
                    lineTo(size.width * 0.12f, size.height)
                    close()
                }
                drawPath(path, Color(0xFF101614))
            }
        }
    }
}

@Composable
private fun MobileChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> Color(0xFF1ED760)
                    enabled -> Color.White.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.32f)
                selected -> Color(0xFF101614)
                else -> Color.White.copy(alpha = 0.92f)
            },
        )
    }
}

private fun sourceTabLabel(source: PlayerChromeSource, latencies: Map<String, Long?>): AnnotatedString {
    val tested = source.lineId.isNotBlank() && latencies.containsKey(source.lineId)
    val latency = latencies[source.lineId]
    return buildAnnotatedString {
        append(source.name)
        if (tested) {
            append(" · ")
            withStyle(SpanStyle(color = if (latency != null) LatencyGreen else Color.White.copy(alpha = 0.6f))) {
                append(latency?.let { "${it}ms" } ?: "测速失败")
            }
        }
    }
}

private fun AnnotatedString.withSuffix(suffix: String): AnnotatedString = buildAnnotatedString {
    append(this@withSuffix)
    append(suffix)
}
