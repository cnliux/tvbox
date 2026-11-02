package com.tvbox.app.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.tvbox.app.domain.PlaySource
import com.tvbox.app.domain.VideoScaleMode
import com.tvbox.app.domain.correspondingEpisodeIndex

internal enum class PlayerPanel { None, Episodes, Sources, Speeds, Picture, Skip }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControls(
    title: String,
    sources: List<PlaySource>,
    sourceIndex: Int,
    episodeIndex: Int,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    speed: Float,
    panel: PlayerPanel,
    videoScaleMode: VideoScaleMode,
    onPanel: (PlayerPanel) -> Unit,
    onToggle: () -> Unit,
    onEpisode: (Int) -> Unit,
    onSource: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onVideoScale: (VideoScaleMode) -> Unit,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    onScrubbing: (Boolean) -> Unit,
    sourceLatencies: Map<String, Long?> = emptyMap(),
    introSkipSeconds: Int = 0,
    outroSkipSeconds: Int = 0,
    onIntroSkip: (Int) -> Unit = {},
    onOutroSkip: (Int) -> Unit = {},
    castLabel: String? = null,
    onCast: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val source = sources[sourceIndex]
    val compact = LocalConfiguration.current.screenWidthDp < 800
    val buttonScroll = rememberScrollState()
    var progressFocused by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    var preview by remember { mutableStateOf<Float?>(null) }
    var lastSeekTime by remember { mutableStateOf(Long.MIN_VALUE) }
    LaunchedEffect(panel) { if (panel == PlayerPanel.None) playFocus.requestFocus() }
    Box(modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .windowInsetsPadding(WindowInsets.safeDrawing).testTag("player_controls"),
            color = Color.Transparent, contentColor = Color.White,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$title · ${source.episodes.getOrNull(episodeIndex)?.title.orEmpty()}",
                    style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlayerTime(preview?.toLong() ?: positionMs), modifier = Modifier.width(66.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f))
                    Slider(
                        value = preview ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                        onValueChange = { preview = it; onScrubbing(true); onInteraction() },
                        onValueChangeFinished = {
                            preview?.let { onSeek(it.toLong()) }; preview = null; onScrubbing(false)
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(), enabled = durationMs > 0,
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
                            onInteraction()
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.nativeKeyEvent.repeatCount == 0 || lastSeekTime == Long.MIN_VALUE ||
                                    event.nativeKeyEvent.eventTime - lastSeekTime >= 150) {
                                    preview = ((preview?.toLong() ?: positionMs) + delta).coerceIn(0, durationMs.coerceAtLeast(0)).toFloat()
                                    lastSeekTime = event.nativeKeyEvent.eventTime
                                }
                                onScrubbing(true)
                            } else if (event.type == KeyEventType.KeyUp) {
                                preview?.let { onSeek(it.toLong()) }; preview = null; onScrubbing(false)
                                lastSeekTime = Long.MIN_VALUE
                            }
                            true
                        },
                    )
                    Text(if (durationMs > 0) formatPlayerTime(durationMs) else "--:--", modifier = Modifier.width(66.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Color.White.copy(alpha = 0.8f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.horizontalScroll(buttonScroll) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(if (playing) "暂停" else "播放", onToggle, Modifier.focusRequester(playFocus), primary = true)
                    ControlButton("上一集", { onEpisode(episodeIndex - 1) }, enabled = episodeIndex > 0)
                    ControlButton("下一集", { onEpisode(episodeIndex + 1) }, enabled = episodeIndex < source.episodes.lastIndex)
                    if (!compact) Spacer(Modifier.weight(1f))
                    if (castLabel != null) ControlButton(castLabel, onCast)
                    ControlButton("选集", { onPanel(PlayerPanel.Episodes) })
                    ControlButton("线路", { onPanel(PlayerPanel.Sources) })
                    ControlButton("跳过", { onPanel(PlayerPanel.Skip) })
                    ControlButton("${speed.toString().trimEnd('0').trimEnd('.')}x", { onPanel(PlayerPanel.Speeds) })
                    ControlButton("画面", { onPanel(PlayerPanel.Picture) })
                }
            }
        }
        if (panel == PlayerPanel.Skip) {
            SkipSettingsPanel(
                introSeconds = introSkipSeconds,
                outroSeconds = outroSkipSeconds,
                onIntroSkip = onIntroSkip,
                onOutroSkip = onOutroSkip,
                onClose = { onPanel(PlayerPanel.None) },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.8f),
            )
        } else if (panel != PlayerPanel.None) {
            val orderedSources = remember(sources, sourceLatencies) {
                orderSourcesByLatency(sources, sourceLatencies)
            }
            val labels: List<AnnotatedString> = when (panel) {
                PlayerPanel.Episodes -> source.episodes.map { AnnotatedString(it.title) }
                PlayerPanel.Sources -> orderedSources.map { sourceTabLabel(it.value, sourceLatencies) }
                PlayerPanel.Speeds -> playerSpeeds.map { AnnotatedString("${it}x") }
                PlayerPanel.Picture -> VideoScaleMode.entries.map { AnnotatedString(it.displayName) }
                else -> emptyList()
            }
            val selected = when (panel) {
                PlayerPanel.Episodes -> episodeIndex
                PlayerPanel.Sources -> orderedSources.indexOfFirst { it.index == sourceIndex }.coerceAtLeast(0)
                PlayerPanel.Speeds -> playerSpeeds.indexOf(speed).coerceAtLeast(0)
                PlayerPanel.Picture -> VideoScaleMode.entries.indexOf(videoScaleMode)
                else -> 0
            }
            val pickerFocus = remember(panel, labels.size) { List(labels.size) { FocusRequester() } }
            val grid = rememberLazyGridState()
            LaunchedEffect(panel, labels.size) {
                grid.scrollToItem(selected.coerceIn(0, labels.lastIndex.coerceAtLeast(0)))
                pickerFocus.getOrNull(selected)?.requestFocus()
            }
            Surface(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).fillMaxHeight(0.6f),
                color = Color(0xFA202020), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (panel) {
                            PlayerPanel.Episodes -> "选择集数"
                            PlayerPanel.Sources -> "选择线路 · 已按测速排序"
                            PlayerPanel.Picture -> "画面比例 · ${videoScaleMode.displayName}"
                            else -> "播放倍速"
                        }, Modifier.weight(1f))
                        ControlButton("关闭", { onPanel(PlayerPanel.None) })
                    }
                    if (panel == PlayerPanel.Picture) {
                        Text(videoScaleMode.description, style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (panel == PlayerPanel.Sources && orderedSources.none { sourceLatencies[it.value.lineId] != null }) {
                        Text("可先在详情页测速，线路会按延迟从低到高排列",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 12.dp))
                    }
                    LazyVerticalGrid(GridCells.Adaptive(130.dp), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(labels) { index, label ->
                            val realIndex = if (panel == PlayerPanel.Sources) orderedSources[index].index else index
                            val realSource = if (panel == PlayerPanel.Sources) orderedSources[index].value else source
                            val enabled = panel != PlayerPanel.Sources || correspondingEpisodeIndex(
                                realSource, source.episodes[episodeIndex].title, episodeIndex) != null
                            ControlButton(if (index == selected) label.withSuffix(" ✓") else label, {
                                onInteraction()
                                when (panel) {
                                    PlayerPanel.Episodes -> onEpisode(realIndex)
                                    PlayerPanel.Sources -> onSource(realIndex)
                                    PlayerPanel.Speeds -> onSpeed(playerSpeeds[realIndex])
                                    PlayerPanel.Picture -> onVideoScale(VideoScaleMode.entries[realIndex])
                                    else -> Unit
                                }
                                onPanel(PlayerPanel.None)
                            }, Modifier.focusRequester(pickerFocus[index]), enabled)
                        }
                    }
                }
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
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (primary) 24.dp else 10.dp)
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
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

/** 线路面板：延迟可测的按延迟升序，超时与未测速保持原顺序排在后面。 */
private fun orderSourcesByLatency(
    sources: List<PlaySource>,
    latencies: Map<String, Long?>,
): List<IndexedValue<PlaySource>> {
    return sources.withIndex().sortedWith(
        compareBy<IndexedValue<PlaySource>> { item ->
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

private val introSkipOptions = listOf(0, 10, 20, 30, 60)
private val outroSkipOptions = listOf(0, 15, 30, 60, 90)

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

internal fun formatPlayerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
private val playerSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private fun sourceTabLabel(source: PlaySource, latencies: Map<String, Long?>): AnnotatedString {
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

private val LatencyGreen = Color(0xFF4ADE80)

private fun AnnotatedString.withSuffix(suffix: String): AnnotatedString = buildAnnotatedString {
    append(this@withSuffix)
    append(suffix)
}

// ── 手机模式播放器控件 ─────────────────────────────────────────────────────────

/** 手机播放页一次只展开一个面板，避免多个面板同时出现把画面挤没。 */
private enum class MobilePlayerSection { Episodes, Sources, Speeds, Picture }

/**
 * 手机模式播放控件。
 *
 * 顶部：返回 + 片名/集数；底部：进度条 + 大号播放键，再下一行是等宽功能入口（选集/线路/倍速/画面/投屏）。
 * 之前 6 个按钮塞在一行 SpaceEvenly 里，窄屏会被折叠成一字宽；现在竖屏每个入口按权重均分，
 * 横屏（≥600dp）允许整行横滑，按钮不会再被压缩。
 * 顶部与底部都吸收 safeDrawing：系统导航栏临时出现时也不会压住控件。
 * TV 端遥控器快捷键逻辑仍在 PlayerScreen 的 onPreviewKeyEvent 中处理，不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControlsMobile(
    title: String,
    sources: List<PlaySource>,
    sourceIndex: Int,
    episodeIndex: Int,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    speed: Float,
    videoScaleMode: VideoScaleMode,
    onToggle: () -> Unit,
    onEpisode: (Int) -> Unit,
    onSource: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onVideoScale: (VideoScaleMode) -> Unit,
    onSeek: (Long) -> Unit,
    castLabel: String? = null,
    onCast: () -> Unit = {},
    onBack: () -> Unit = {},
    // 通知外层“面板是否展开”：面板展开时不能自动隐藏控件，否则来不及选集。
    onMorePanelChange: (Boolean) -> Unit = {},
) {
    val source = sources[sourceIndex]
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var openSection by remember { mutableStateOf<MobilePlayerSection?>(null) }
    LaunchedEffect(openSection) { onMorePanelChange(openSection != null) }
    val episodeTitle = source.episodes.getOrNull(episodeIndex)?.title.orEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部栏（手机没有遥控器，必须给出可见的返回入口）
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent),
                    ),
                ),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("返回", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (episodeTitle.isNotBlank()) {
                        Text(
                            text = episodeTitle,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        // 底部控制区
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
                ),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                openSection?.let { section ->
                    MobileSectionPanel(
                        section = section,
                        sources = sources,
                        sourceIndex = sourceIndex,
                        episodeIndex = episodeIndex,
                        speed = speed,
                        videoScaleMode = videoScaleMode,
                        onEpisode = onEpisode,
                        onSource = onSource,
                        onSpeed = onSpeed,
                        onVideoScale = onVideoScale,
                        onClose = { openSection = null },
                    )
                }
                MobileProgressRow(positionMs = positionMs, durationMs = durationMs, onSeek = onSeek)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MobileChip(
                        text = "上一集",
                        modifier = Modifier.weight(1f),
                        enabled = episodeIndex > 0,
                        onClick = { onEpisode(episodeIndex - 1) },
                    )
                    MobilePlayPauseButton(playing = playing, onClick = onToggle)
                    MobileChip(
                        text = "下一集",
                        modifier = Modifier.weight(1f),
                        enabled = episodeIndex < source.episodes.lastIndex,
                        onClick = { onEpisode(episodeIndex + 1) },
                    )
                }
                val features: @Composable RowScope.() -> Unit = {
                    val itemModifier = if (wide) Modifier else Modifier.weight(1f)
                    MobileChip(
                        text = "选集",
                        modifier = itemModifier,
                        selected = openSection == MobilePlayerSection.Episodes,
                        onClick = { openSection = openSection.toggle(MobilePlayerSection.Episodes) },
                    )
                    MobileChip(
                        text = "线路",
                        modifier = itemModifier,
                        selected = openSection == MobilePlayerSection.Sources,
                        onClick = { openSection = openSection.toggle(MobilePlayerSection.Sources) },
                    )
                    MobileChip(
                        text = "${formatSpeedLabel(speed)}x",
                        modifier = itemModifier,
                        selected = openSection == MobilePlayerSection.Speeds,
                        onClick = { openSection = openSection.toggle(MobilePlayerSection.Speeds) },
                    )
                    MobileChip(
                        text = "画面",
                        modifier = itemModifier,
                        selected = openSection == MobilePlayerSection.Picture,
                        onClick = { openSection = openSection.toggle(MobilePlayerSection.Picture) },
                    )
                    if (castLabel != null) {
                        MobileChip(text = castLabel, modifier = itemModifier, onClick = onCast)
                    }
                }
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { features() }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { features() }
                }
            }
        }
    }
}

private fun MobilePlayerSection?.toggle(section: MobilePlayerSection): MobilePlayerSection? =
    if (this == section) null else section

private fun formatSpeedLabel(speed: Float): String = speed.toString().trimEnd('0').trimEnd('.')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileProgressRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    // 拖动期间只更新本地预览，松手才真正 seek，避免拖动过程中连续 seek 造成卡顿。
    var preview by remember { mutableStateOf<Float?>(null) }
    val maxValue = durationMs.coerceAtLeast(1L).toFloat()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatPlayerTime(preview?.toLong() ?: positionMs),
            modifier = Modifier.width(52.dp),
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
                Box(
                    modifier = Modifier.size(16.dp).background(Color.White, CircleShape),
                )
            },
            track = { slider ->
                val fraction = ((slider.value - slider.valueRange.start) /
                    (slider.valueRange.endInclusive - slider.valueRange.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val center = size.height / 2
                    drawLine(
                        Color.White.copy(alpha = 0.25f),
                        Offset(0f, center),
                        Offset(size.width, center),
                        size.height,
                        StrokeCap.Round,
                    )
                    drawLine(
                        Color(0xFF7AE2BA),
                        Offset(0f, center),
                        Offset(size.width * fraction, center),
                        size.height,
                        StrokeCap.Round,
                    )
                }
            },
            modifier = Modifier.weight(1f).height(40.dp),
        )
        Text(
            text = if (durationMs > 0) formatPlayerTime(durationMs) else "--:--",
            modifier = Modifier.width(52.dp),
            style = timeStyle,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

/** 大号播放/暂停圆钮：用 Canvas 画三角与双竖条，避免依赖额外的图标库。 */
@Composable
private fun MobilePlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            if (playing) {
                val barWidth = size.width * 0.26f
                drawRect(
                    Color(0xFF101614),
                    topLeft = Offset(0f, 0f),
                    size = Size(barWidth, size.height),
                )
                drawRect(
                    Color(0xFF101614),
                    topLeft = Offset(size.width - barWidth, 0f),
                    size = Size(barWidth, size.height),
                )
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
            .heightIn(min = 44.dp)
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

@Composable
private fun MobileSectionPanel(
    section: MobilePlayerSection,
    sources: List<PlaySource>,
    sourceIndex: Int,
    episodeIndex: Int,
    speed: Float,
    videoScaleMode: VideoScaleMode,
    onEpisode: (Int) -> Unit,
    onSource: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onVideoScale: (VideoScaleMode) -> Unit,
    onClose: () -> Unit,
) {
    val episodes = sources[sourceIndex].episodes
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
                        MobilePlayerSection.Episodes -> "选集（共 ${episodes.size} 集）"
                        MobilePlayerSection.Sources -> "线路（共 ${sources.size} 条）"
                        MobilePlayerSection.Speeds -> "播放倍速"
                        MobilePlayerSection.Picture -> "画面比例 · ${videoScaleMode.displayName}"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
                TextButton(onClick = onClose) {
                    Text(
                        "收起",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            when (section) {
                MobilePlayerSection.Episodes -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodes.size) { index ->
                        MobileChip(
                            text = "${index + 1}. ${episodes[index].title}",
                            selected = index == episodeIndex,
                            onClick = { onEpisode(index); onClose() },
                        )
                    }
                }
                MobilePlayerSection.Sources -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources.size) { index ->
                        MobileChip(
                            text = sources[index].name,
                            selected = index == sourceIndex,
                            onClick = { onSource(index); onClose() },
                        )
                    }
                }
                MobilePlayerSection.Speeds -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    playerSpeeds.forEach { item ->
                        MobileChip(
                            text = "${formatSpeedLabel(item)}x",
                            selected = kotlin.math.abs(item - speed) < 0.01f,
                            onClick = { onSpeed(item); onClose() },
                        )
                    }
                }
                MobilePlayerSection.Picture -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoScaleMode.entries.forEach { mode ->
                        MobileChip(
                            text = mode.displayName,
                            selected = mode == videoScaleMode,
                            onClick = { onVideoScale(mode); onClose() },
                        )
                    }
                }
            }
        }
    }
}
