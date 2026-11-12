package com.tvbox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent as NativeKeyEvent
import android.widget.Toast
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.VideoScaleMode
import kotlinx.coroutines.delay

/** 共享面板标识：TV 居中面板与手机内嵌面板共用同一套状态。 */
internal enum class PlayerPanel { None, Episodes, Sources, Speeds, Picture, Skip, Audio, Subtitle, Info, Url }

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

/** 播放器轨道选项（音轨/字幕）；[id] 由共享层解释，页面只提供展示名与选中态。 */
internal data class PlayerChromeTrack(
    val id: String,
    val label: String,
    val selected: Boolean = false,
)

/** 播放地址：页面区分「原始」与「实际」，共享层只负责展示与复制。 */
internal data class PlayerChromeUrl(
    val label: String,
    val url: String,
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
    val audioTracks: List<PlayerChromeTrack> = emptyList(),
    val subtitleTracks: List<PlayerChromeTrack> = emptyList(),
    /** 播放信息面板快照；为 null 表示数据尚未就绪，面板统一显示「—」。 */
    val playbackInfo: PlayerPlaybackInfo? = null,
    /** 当前播放地址（可含原始/实际两类），空列表表示该页没有可查看地址。 */
    val playbackUrls: List<PlayerChromeUrl> = emptyList(),
    /** 当前解码策略：信息面板同时展示策略与实际解码器，便于确认是否发生回落。 */
    val decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
    /** 本次播放器构建中实际发生回落的轨道；视频/音频分别标注，避免只看视频而漏掉音频回落。 */
    val decoderFallbackKinds: Set<DecoderTrackKind> = emptySet(),
)

/** 能力清单：这一页支持哪些控件；共享层不再判断“是点播还是直播”。 */
/**
 * 页面自定义按钮（线路、弹幕、调试监控等）。
 * 由共享层渲染成与其它功能按钮完全相同的一排、完全相同的样式，
 * 避免各页自己在角落里堆悬浮按钮导致位置/外观割裂。
 * [primary] 用于表达「开启态」高亮（如弹幕·开）。
 */
internal data class PlayerChromeAction(
    val label: String,
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

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
    val url: Boolean = false,
    /** 点播视频的音轨/字幕选择；面板为空时由共享层给出明确提示。 */
    val audioTracks: Boolean = false,
    val subtitleTracks: Boolean = false,
    /** 竖版精简：仅保留全屏与返回，其余控件不在竖版渲染。由 [forOrientation] 统一裁剪。 */
    val portraitMinimal: Boolean = false,
    /** 页面注入的自定义按钮，统一追加在内置功能按钮之后。 */
    val actions: List<PlayerChromeAction> = emptyList(),
    /** 调试：pacer 目标缓冲（毫秒），0 表示关闭；用于「一起看」续流缓冲实时调试。 */
    val pacerBufferMs: Long = 0,
    val onCyclePacerBuffer: (() -> Unit)? = null,
)

/**
 * 按屏幕方向裁剪控件能力：
 * - 竖版且本页提供全屏入口时，只保留全屏（返回由触摸布局固定提供），其余按钮关闭；
 * - 横屏与电视端保持页面注入的完整能力，避免键位/手势/按钮集合被竖版规则带偏。
 *
 * 页面仍注入完整能力，裁剪只发生在共享层，避免各页为竖版另写散装 if。
 */
internal fun PlayerChromeCapabilities.forOrientation(portrait: Boolean): PlayerChromeCapabilities =
    if (portrait && orientation) {
        copy(
            seek = false,
            episodes = false,
            sources = false,
            speed = false,
            resize = false,
            skip = false,
            cast = false,
            castLabel = null,
            lock = false,
            favorite = false,
            favoriteLabel = null,
            favoriteSelected = false,
            mute = false,
            settings = false,
            channel = false,
            room = false,
            lines = false,
            info = false,
            url = false,
            audioTracks = false,
            subtitleTracks = false,
            portraitMinimal = true,
            actions = emptyList(),
            // 调试按钮（pacer 缓冲）在竖屏也保留，方便手机端实时调缓冲。
            pacerBufferMs = this.pacerBufferMs,
            onCyclePacerBuffer = this.onCyclePacerBuffer,
        )
    } else {
        this
    }

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
    val onBack: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onOrientation: () -> Unit = {},
    val onMute: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onLockChange: (Boolean) -> Unit = {},
    val onInteraction: () -> Unit = {},
    /** 空字符串表示关闭字幕。 */
    val onSelectAudioTrack: (String) -> Unit = {},
    val onSelectSubtitleTrack: (String) -> Unit = {},
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
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val effectiveCapabilities = remember(capabilities, portrait) {
        capabilities.forOrientation(portrait)
    }
    AnimatedVisibility(
        visible = state.visible,
        enter = if (fade) fadeIn(tween(150)) else EnterTransition.None,
        exit = if (fade) fadeOut(tween(250)) else ExitTransition.None,
    ) {
        if (touch) {
            PlayerChromeTouch(
                state = state,
                data = data,
                capabilities = effectiveCapabilities,
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
        } else {
            PlayerChromeTv(
                state = state,
                data = data,
                capabilities = effectiveCapabilities,
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
                        Text(formatPlayerTime(preview?.toLong() ?: data.positionMs), modifier = Modifier.widthIn(min = 66.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1, softWrap = false)
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
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp)
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
                        Text(if (data.durationMs > 0) formatPlayerTime(data.durationMs) else "--:--", modifier = Modifier.widthIn(min = 66.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1, softWrap = false)
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
                    if (capabilities.audioTracks) ControlButton("音轨", { state.openPanel(PlayerPanel.Audio); actions.onInteraction() })
                    if (capabilities.subtitleTracks) ControlButton("字幕", { state.openPanel(PlayerPanel.Subtitle); actions.onInteraction() })
                    if (capabilities.resize) ControlButton("画面", { state.openPanel(PlayerPanel.Picture); actions.onInteraction() })
                    if (capabilities.skip) ControlButton("跳过", { state.openPanel(PlayerPanel.Skip); actions.onInteraction() })
                    if (capabilities.info) ControlButton("信息", { state.openPanel(PlayerPanel.Info) })
                    if (capabilities.url) ControlButton("地址", { state.openPanel(PlayerPanel.Url) })
                    if (capabilities.settings) ControlButton("设置", { actions.onSettings(); state.interaction++ })
                    // 页面按钮（线路、弹幕、调试监控等）与内置按钮同排同样式。
                    capabilities.actions.forEach { action ->
                        ControlButton(
                            text = action.label,
                            onClick = { action.onClick(); actions.onInteraction() },
                            primary = action.primary,
                        )
                    }
                    // 调试：pacer 目标缓冲循环按钮（「一起看」续流缓冲实时调试）。
                    capabilities.onCyclePacerBuffer?.let { cb ->
                        ControlButton(pacerBufferLabel(capabilities.pacerBufferMs), cb)
                    }
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
    statusContent: (@Composable RowScope.() -> Unit)?,
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
        if (capabilities.portraitMinimal) {
            // 竖版只保留顶部返回/全屏；底部进度、播放与面板入口全部隐藏，避免小画面里挤占全屏按钮。
            return@Box
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
                if (statusContent != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = statusContent,
                    )
                }
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
                    MobileProgressRow(
                        positionMs = data.positionMs,
                        durationMs = data.durationMs,
                        onScrubbing = { scrubbing -> state.scrubbing = scrubbing; state.interaction++ },
                        onSeek = { actions.onSeek(it); state.interaction++ },
                    )
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
                    if (capabilities.audioTracks) add(ChromeFeature("音轨", state.panel == PlayerPanel.Audio) {
                        state.openPanel(PlayerPanel.Audio)
                    })
                    if (capabilities.subtitleTracks) add(ChromeFeature("字幕", state.panel == PlayerPanel.Subtitle) {
                        state.openPanel(PlayerPanel.Subtitle)
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
                    if (capabilities.info) add(ChromeFeature("信息", state.panel == PlayerPanel.Info) {
                        state.openPanel(PlayerPanel.Info)
                    })
                    if (capabilities.url) add(ChromeFeature("地址", state.panel == PlayerPanel.Url) {
                        state.openPanel(PlayerPanel.Url)
                    })
                    if (capabilities.settings) add(ChromeFeature("设置") {
                        actions.onSettings(); state.interaction++
                    })
                    if (capabilities.cast) add(ChromeFeature(capabilities.castLabel ?: "投屏") {
                        actions.onCast(); state.interaction++
                    })
                    capabilities.actions.forEach { action ->
                        add(ChromeFeature(action.label, action.primary) {
                            action.onClick(); actions.onInteraction()
                        })
                    }
                    // 调试：pacer 目标缓冲循环按钮（手机端与 TV 同语义）。
                    capabilities.onCyclePacerBuffer?.let { cb ->
                        add(ChromeFeature(pacerBufferLabel(capabilities.pacerBufferMs)) {
                            cb(); actions.onInteraction()
                        })
                    }
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

/** pacer 目标缓冲按钮文案：0 表示关闭（严格实时），否则显示秒数。 */
internal fun pacerBufferLabel(ms: Long): String =
    if (ms <= 0L) "缓冲·关" else "缓冲${ms / 1000}s"

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
    if (panel == PlayerPanel.Info) {
        PlayerInfoPanel(
            info = data.playbackInfo,
            decoderStrategy = data.decoderStrategy,
            fallbackKinds = data.decoderFallbackKinds,
            onClose = { state.closePanel() },
            modifier = modifier,
        )
        return
    }
    if (panel == PlayerPanel.Url) {
        PlayerUrlPanel(
            urls = data.playbackUrls,
            onClose = { state.closePanel() },
            modifier = modifier,
        )
        return
    }
    val orderedSources = remember(data.sources, data.sourceLatencies) {
        orderSourcesByLatency(data.sources, data.sourceLatencies)
    }
    val subtitleOptions = remember(data.subtitleTracks) {
        listOf(
            PlayerChromeTrack(
                id = "",
                label = "关闭字幕",
                selected = data.subtitleTracks.none { it.selected },
            ),
        ) + data.subtitleTracks
    }
    val labels: List<AnnotatedString> = when (panel) {
        PlayerPanel.Episodes -> data.episodes.map { AnnotatedString(it) }
        PlayerPanel.Sources -> orderedSources.map { sourceTabLabel(it.value, data.sourceLatencies) }
        PlayerPanel.Speeds -> playerSpeeds.map { AnnotatedString("${it}x") }
        PlayerPanel.Picture -> VideoScaleMode.entries.map { AnnotatedString(it.displayName) }
        PlayerPanel.Audio -> data.audioTracks.map { AnnotatedString(it.label) }
        PlayerPanel.Subtitle -> subtitleOptions.map { AnnotatedString(it.label) }
        else -> emptyList()
    }
    val selected = when (panel) {
        PlayerPanel.Episodes -> data.episodeIndex
        PlayerPanel.Sources -> orderedSources.indexOfFirst { it.index == data.sourceIndex }.coerceAtLeast(0)
        // 偏好里可能存了非标准倍速（如 1.1f）：严格 indexOf 返回 -1 会被 coerceAtLeast(0)
        // 误高亮到 0.75x。改为与手机版一致的容差匹配，找不到就返回 -1（不高亮任何档位）。
        PlayerPanel.Speeds -> playerSpeeds.indexOfFirst { kotlin.math.abs(it - data.speed) < 0.01f }
        PlayerPanel.Picture -> VideoScaleMode.entries.indexOf(data.resizeMode)
        PlayerPanel.Audio -> data.audioTracks.indexOfFirst { it.selected }.coerceAtLeast(0)
        PlayerPanel.Subtitle -> subtitleOptions.indexOfFirst { it.selected }.coerceAtLeast(0)
        else -> 0
    }
    // 轨道面板为空时给出明确提示，而不是留下一个空面板。
    val emptyTrackHint = when (panel) {
        PlayerPanel.Audio -> "当前视频没有可用音轨".takeIf { data.audioTracks.isEmpty() }
        PlayerPanel.Subtitle -> "当前视频没有字幕轨道".takeIf { data.subtitleTracks.isEmpty() }
        else -> null
    }
    val pickerFocus = remember(panel, labels.size) { List(labels.size) { FocusRequester() } }
    val grid = rememberLazyGridState()
    LaunchedEffect(panel, labels.size) {
        grid.scrollToItem(selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
        runCatching { pickerFocus.getOrNull(selected)?.requestFocus() }
    }
    Surface(modifier.fillMaxHeight(0.6f),
        color = Color(0xFA202020), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (panel) {
                    PlayerPanel.Episodes -> "选择集数${if (episodeSortReverse) "（倒序）" else ""}"
                    PlayerPanel.Sources -> "选择线路 · 已按测速排序"
                    PlayerPanel.Picture -> "画面比例 · ${data.resizeMode.displayName}"
                    PlayerPanel.Audio -> "选择音轨 · 共 ${data.audioTracks.size} 条"
                    PlayerPanel.Subtitle -> "选择字幕 · 共 ${data.subtitleTracks.size} 条"
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
            if (emptyTrackHint != null) {
                Text(
                    emptyTrackHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyVerticalGrid(GridCells.Adaptive(130.dp), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(labels, key = { index, label -> "$index-$label" }) { index, label ->
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
                                PlayerPanel.Audio -> data.audioTracks.getOrNull(realIndex)
                                    ?.let { actions.onSelectAudioTrack(it.id) }
                                PlayerPanel.Subtitle -> subtitleOptions.getOrNull(realIndex)
                                    ?.let { actions.onSelectSubtitleTrack(it.id) }
                                else -> Unit
                            }
                            state.closePanel()
                        }, Modifier.focusRequester(pickerFocus[index]), enabled)
                    }
                }
            }
        }
    }
}

/** 播放信息面板的一行：只读展示，不承载任何播放操作。 */
internal data class PlayerInfoRow(val label: String, val value: String)

/** 构造播放信息行：数据缺失一律显示「—」，不把哨兵值 0/-1 暴露给用户。 */
internal fun playerInfoRows(
    info: PlayerPlaybackInfo?,
    decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
    fallbackKinds: Set<DecoderTrackKind> = emptySet(),
): List<PlayerInfoRow> {
    val decoder = info?.decoderName?.takeIf { it.isNotBlank() }
    val actualKind = decoder?.let(::decoderKindLabel)
    // 强制硬解时按回落轨道分别标注：视频与音频都可能独立回落到软解。
    val videoFallback = decoderStrategy == DecoderStrategy.HardwareOnly &&
        DecoderTrackKind.Video in fallbackKinds
    val audioFallback = decoderStrategy == DecoderStrategy.HardwareOnly &&
        DecoderTrackKind.Audio in fallbackKinds
    return buildList {
        add(PlayerInfoRow("解码策略", decoderStrategy.displayName))
        add(PlayerInfoRow("分辨率", formatResolution(info?.videoWidth ?: 0, info?.videoHeight ?: 0)))
        add(PlayerInfoRow("解码器", decoder?.let { "$it · $actualKind" } ?: "—"))
        if (videoFallback) {
            add(PlayerInfoRow("视频回落", "强制硬解 · 实际软解（已回落）"))
        }
        if (audioFallback) {
            add(PlayerInfoRow("音频回落", "强制硬解 · 实际软解（已回落）"))
        }
        add(PlayerInfoRow("当前码率", formatBitrate(info?.currentBitrateBps ?: -1L)))
        add(PlayerInfoRow("平均码率", formatBitrate(info?.averageBitrateBps ?: -1L)))
        add(PlayerInfoRow("缓冲时长", formatDurationMs(info?.bufferedMs ?: -1L)))
        add(PlayerInfoRow("丢帧", formatFrameCount(info?.droppedFrames ?: -1L)))
        add(PlayerInfoRow("已解码帧", formatFrameCount(info?.decodedFrames ?: -1L)))
        add(PlayerInfoRow("首帧耗时", formatFirstFrame(info?.firstFrameMs ?: -1L)))
        add(PlayerInfoRow("错误码", info?.let { formatErrorCode(it.errorCode) } ?: "—"))
    }
}

/** TV 端播放信息面板：每行可聚焦，方向键逐项移动，列表随焦点自动滚动。 */
@Composable
private fun PlayerInfoPanel(
    info: PlayerPlaybackInfo?,
    decoderStrategy: DecoderStrategy,
    fallbackKinds: Set<DecoderTrackKind>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(info, decoderStrategy, fallbackKinds) {
        playerInfoRows(info, decoderStrategy, fallbackKinds)
    }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    Surface(
        modifier.fillMaxHeight(0.6f),
        color = Color(0xFA202020),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放信息", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ControlButton("关闭", onClose)
            }
            Text(
                "面板打开时每秒刷新，关闭后停止采样",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    item(key = index) {
                        if (index == 0) {
                            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
                        }
                        PlayerInfoRowItem(
                            row = row,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerInfoRowItem(row: PlayerInfoRow, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = 2.dp,
                color = if (focused) Color(0xFF7AE2BA) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            text = row.value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 地址过长时保留头尾、中间省略；展开后展示完整内容。 */
internal fun middleEllipsis(text: String, maxChars: Int = 72): String {
    if (text.length <= maxChars || maxChars < 8) return text
    val head = maxChars / 2
    val tail = maxChars - head - 1
    return text.take(head) + "…" + text.takeLast(tail)
}

private fun copyPlaybackUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("playback_url", url))
}

/** TV 端播放地址面板：逐行可聚焦，聚焦即展开完整地址，复制按钮给出面板内反馈。 */
@Composable
private fun PlayerUrlPanel(
    urls: List<PlayerChromeUrl>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expandedIndex by remember { mutableIntStateOf(-1) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(3_000L)
            feedback = null
        }
    }
    Surface(
        modifier.fillMaxHeight(0.6f),
        color = Color(0xFA202020),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放地址", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ControlButton("关闭", onClose)
            }
            Text(
                "地址包含请求参数，仅在本机查看；日志仍按既定规则脱敏",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (urls.isEmpty()) {
                Text(
                    text = "暂无可查看的播放地址",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f).padding(top = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    urls.forEachIndexed { index, item ->
                        item(key = index) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 2.dp,
                                        color = if (expandedIndex == index) Color(0xFF7AE2BA) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                    ControlButton("复制", {
                                        copyPlaybackUrl(context, item.url)
                                        feedback = "已复制：${item.label}"
                                    })
                                }
                                Text(
                                    text = if (expandedIndex == index) item.url else middleEllipsis(item.url),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 聚焦即展开完整地址：方向键逐行移动即可查看，不依赖触摸。
                                        .onFocusChanged { if (it.isFocused) expandedIndex = index }
                                        .focusable()
                                        .padding(top = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = feedback ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7AE2BA),
                modifier = Modifier.padding(top = 8.dp),
            )
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
                        PlayerPanel.Audio -> "音轨（共 ${data.audioTracks.size} 条）"
                        PlayerPanel.Subtitle -> "字幕（共 ${data.subtitleTracks.size} 条）"
                        PlayerPanel.Skip -> "跳过片头/片尾"
                        PlayerPanel.Info -> "播放信息"
                        PlayerPanel.Url -> "播放地址"
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
                    // 当前集在「可能已倒序」的列表里的下标。
                    val currentListIndex = if (episodeSortReverse) {
                        data.episodes.lastIndex - data.episodeIndex
                    } else {
                        data.episodeIndex
                    }
                    val epRowState = rememberLazyListState()
                    // 打开选集面板时直接定位到当前集（左边留两格上下文）；
                    // 否则百集剧要从第 1 集横滑几十次才能找到正在看的那集。
                    LaunchedEffect(section, currentListIndex, epList.size) {
                        if (currentListIndex in epList.indices) {
                            epRowState.scrollToItem((currentListIndex - 2).coerceAtLeast(0))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = onSortReverse) {
                                Text(if (episodeSortReverse) "正序" else "倒序", color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                        LazyRow(
                            state = epRowState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(epList.size, key = { it }) { index ->
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
                    items(data.sources.size, key = { it }) { index ->
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
                PlayerPanel.Picture -> Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
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
                PlayerPanel.Audio -> if (data.audioTracks.isEmpty()) {
                    Text("当前视频没有可用音轨，将使用默认音轨。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        data.audioTracks.forEach { track ->
                            MobileChip(
                                text = track.label,
                                selected = track.selected,
                                onClick = { actions.onSelectAudioTrack(track.id); onClose() },
                            )
                        }
                    }
                }
                PlayerPanel.Subtitle -> if (data.subtitleTracks.isEmpty()) {
                    Text("当前视频没有字幕轨道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MobileChip(
                            text = "关闭字幕",
                            selected = data.subtitleTracks.none { it.selected },
                            onClick = { actions.onSelectSubtitleTrack(""); onClose() },
                        )
                        data.subtitleTracks.forEach { track ->
                            MobileChip(
                                text = track.label,
                                selected = track.selected,
                                onClick = { actions.onSelectSubtitleTrack(track.id); onClose() },
                            )
                        }
                    }
                }
                PlayerPanel.Info -> {
                    val infoRows = remember(
                        data.playbackInfo,
                        data.decoderStrategy,
                        data.decoderFallbackKinds,
                    ) {
                        playerInfoRows(
                            data.playbackInfo,
                            data.decoderStrategy,
                            data.decoderFallbackKinds,
                        )
                    }
                    // 信息行可能超出一屏：限制高度并允许滚动，不挤压上方的进度与功能行。
                    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        infoRows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = row.label,
                                    modifier = Modifier.width(88.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                )
                                Text(
                                    text = row.value,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.92f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = "面板打开时每秒刷新，关闭后停止采样。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
                PlayerPanel.Url -> {
                    val context = LocalContext.current
                    // 同一时间只展开一条地址，避免长地址把面板撑满。
                    var expandedLabel by remember { mutableStateOf<String?>(null) }
                    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (data.playbackUrls.isEmpty()) {
                            Text(
                                text = "暂无可查看的播放地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                        data.playbackUrls.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .padding(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                    TextButton(
                                        onClick = {
                                            copyPlaybackUrl(context, item.url)
                                            Toast.makeText(context, "已复制：${item.label}", Toast.LENGTH_SHORT).show()
                                        },
                                    ) {
                                        Text("复制", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(
                                    text = if (expandedLabel == item.label) {
                                        item.url
                                    } else {
                                        middleEllipsis(item.url, maxChars = 64)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 地址本身就是可点击区域，原 top 4dp 内边距只有约 24dp 高，
                                        // 补足到 48dp 热区并给读屏一个按钮角色与展开状态。
                                        .clickable(role = Role.Button) {
                                            expandedLabel = if (expandedLabel == item.label) null else item.label
                                        }
                                        .semantics {
                                            stateDescription = if (expandedLabel == item.label) "已展开" else "已收起"
                                        }
                                        .padding(vertical = 14.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.88f),
                                )
                            }
                        }
                        Text(
                            text = "点击地址可展开/收起完整内容",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
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
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
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
    onScrubbing: (Boolean) -> Unit,
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
            onValueChange = { preview = it; onScrubbing(true) },
            onValueChangeFinished = {
                preview?.let { onSeek(it.toLong()) }
                preview = null
                // 与 TV 进度条一致：拖动期间暂停控件自动隐藏，松手才恢复计时。
                onScrubbing(false)
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
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" },
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
