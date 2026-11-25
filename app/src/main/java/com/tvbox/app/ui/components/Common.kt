package com.tvbox.app.ui.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tvbox.app.data.DOUBAN_REFERER
import com.tvbox.app.data.DOUBAN_USER_AGENT
import com.tvbox.app.domain.DoubanHotItem
import com.tvbox.app.domain.Movie
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.domain.NewEpisodeHint
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.ui.formatPlayerTime
import com.tvbox.app.ui.rememberTvFocusRequest
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens

@Composable
fun AppHeader(
    title: String,
    subtitle: String,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onLive: () -> Unit = {},
    onPlatformLive: () -> Unit = {},
    onSettings: () -> Unit = {},
    firstActionModifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle,
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderActionButton(text = "历史(1)", onClick = onHistory, legacyStyle = true, modifier = firstActionModifier)
            HeaderActionButton(text = "搜索(2)", onClick = onSearch, legacyStyle = true)
            HeaderActionButton(text = "电视(4)", onClick = onLive, legacyStyle = true)
            HeaderActionButton(text = "直播(5)", onClick = onPlatformLive, legacyStyle = true)
            HeaderActionButton(text = "设置(6)", onClick = onSettings, legacyStyle = true)
        }
    }
}

@Composable
private fun HeaderActionButton(
    text: String,
    onClick: () -> Unit,
    legacyStyle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TvFocusableSurface(
        shape = if (legacyStyle) RoundedCornerShape(50) else RoundedCornerShape(10.dp),
        onClick = onClick,
        focusedContainerColor = if (legacyStyle) TvColors.Accent else TvColors.SurfaceRaised,
        idleContainerColor = TvColors.Surface,
        modifier = modifier,
        focusedContentColor = if (legacyStyle) TvColors.Background else TvColors.TextPrimary,
        idleContentColor = TvColors.TextPrimary,
        focusedTonalElevation = 5.dp,
        focusedShadowElevation = 8.dp,
        contentMinHeight = 48.dp,
        // S6-32：顶栏按钮此前只有 Text 标签，读屏只会念「按钮」；补中文语义标签。
        descriptionText = TvA11y.action(text, "打开"),
    ) { focused ->
        // 顶栏动作按钮的文字高度约 32dp，触摸模式下低于 48dp 最小热区；外层补足并垂直居中。
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = if (legacyStyle) 20.dp else 14.dp),
            style = if (legacyStyle) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            fontWeight = if (focused || legacyStyle) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(
        // #142/加载态：状态文本挂 LiveRegion(Polite)，加载开始/完成被 TalkBack 播报；
        // #147：整块标为遍历组，读屏按视觉顺序一次性读完，不与背景列表跳读交错。
        modifier = modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(14.dp))
        // 同上：不依赖 colorScheme，避免在纯黑背景页面上文字变成深色而看不见。
        // #142：liveRegion 让"正在加载/加载完成"变化被朗读，无需 UI 主动 announce。
        Text(
            text = text,
            color = TvColors.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    // #236：错误页默认焦点落在"重试"，避免 TV 遥控器进入后焦点悬空在背景。
    val retryFocus = rememberTvFocusRequest(key = Unit)
    // 删除诊断日志：已确认 message 正常传入，问题在于文字颜色继承了外层 LocalContentColor
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TvColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message.ifBlank { "加载失败，请稍后重试" },
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.TextSecondary,
                textAlign = TextAlign.Center,
                // #142：错误原因变化时通过 LiveRegion 朗读，不依赖 Toast（TV 会被遮挡）。
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        } else {
            // 显式指定颜色：不指定时会继承 LocalContentColor，在深色页面（如手机搜索页）
            // 可能拿到接近纯黑的 contentColor，表现为「只有重试按钮，看不到任何错误原因」。
            Text(
                text = message.ifBlank { "加载失败，请稍后重试" },
                style = MaterialTheme.typography.titleMedium,
                color = TvColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(retryFocus.anchor)
                    .semantics { traversalIndex = 0f },
            ) {
                Text("重试")
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { traversalIndex = 1f },
                ) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** 动作按钮附加修饰符，供遥控端把初始焦点落在空态动作上。 */
    actionModifier: Modifier = Modifier,
) {
    // #235：无外部 focusRequester 时，空态动作按钮自身即为默认焦点。
    val hasAction = actionLabel != null && onAction != null
    val fallbackFocus = rememberTvFocusRequest(key = hasAction, enabled = hasAction)
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TvColors.TextPrimary,
            textAlign = TextAlign.Center,
            // #142：空态标题（"暂无数据/去导入"）变化被朗读。
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(fallbackFocus.anchor)
                    .semantics { traversalIndex = 0f }
                    .then(actionModifier),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusableSurface(
        shape = RoundedCornerShape(50),
        onClick = onClick,
        focusedContainerColor = TvColors.SurfaceRaised,
        idleContainerColor = TvColors.Surface,
        modifier = modifier,
        focusedContentColor = TvColors.TextPrimary,
        idleContentColor = TvColors.TextSecondary,
        selected = selected,
        selectedContainerColor = TvColors.AccentSoft,
        selectedContentColor = TvColors.AccentStrong,
        selectedIdleBorder = TvColors.Accent.copy(alpha = 0.7f),
        contentMinHeight = 48.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        // S6-32：分类胶囊此前只有选中态、没有名称标签，TalkBack 无法读出是哪一类。
        descriptionText = TvA11y.label(label),
        stateText = if (selected) "已选中" else "未选中",
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 点播海报卡与历史卡统一使用的封面宽高比。 */
private const val MediaCardAspectRatio = 2f / 3f

/** 手机竖屏模式下的更紧凑宽高比，让同屏显示更多行。 */
private const val MediaCardAspectRatioMobile = 0.7f

/** 角标与封面底部遮罩统一使用的半透明底色。 */
private val MediaCardScrim = Color(0xCC080B0B)

/**
 * 取样式可用于布局换算的行高：优先用显式 lineHeight；未指定时按字号推导 ~1.25 倍，
 * 保证结果是合法的 Sp（TextUnit.toDp() 只接受 Sp，Unspecified 会直接抛异常导致启动崩溃）。
 */
private fun TextStyle.effectiveLineHeight(): TextUnit = when {
    lineHeight.isSpecified -> lineHeight
    fontSize.isSpecified -> fontSize * 1.25f
    else -> TextUnit(16f, TextUnitType.Sp)
}

/**
 * 点播海报卡与历史卡的共用底座。
 *
 * 统一圆角、封面宽高比与裁切、标题/副标题字号与间距、角标样式、焦点缩放与描边。
 * 各卡片只通过 [cover]、[bottomOverlay]、[badge]、[progress]、[subtitle] 等插槽声明差异项。
 */
@Composable
fun MediaCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    bottomOverlay: (@Composable BoxScope.() -> Unit)? = null,
    coverOverlay: @Composable BoxScope.() -> Unit = {},
    cover: @Composable BoxScope.() -> Unit,
    /** P2-15 无障碍：非空时整体替换派生标签，供 TalkBack 一次读出更完整信息。 */
    accessibilityLabel: String? = null,
) {
    val shape = RoundedCornerShape(TvDimens.CardRadius)
    val touchMode = LocalTouchMode.current
    var focused by remember { mutableStateOf(false) }
    // 触摸模式走 pointerInput 消费点击（避免与焦点逻辑竞争），因此拿不到 clickable 的涟漪反馈；
    // 这里用按下时整体压暗作为最基本的点击反馈，松开或手势被滚动接管后立即恢复。
    var pressed by remember { mutableStateOf(false) }
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.7f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "media-card-press",
    )
    // 卡片内封面、标题、副标题、角标、进度各自是独立语义节点，读屏会逐条朗读；
    // 合并成「片名 + 副标题 + 角标」，整卡作为一个按钮播报。
    val cardLabel = accessibilityLabel ?: buildString {
        append(title)
        if (!subtitle.isNullOrBlank()) append("，").append(subtitle)
        if (!badge.isNullOrBlank()) append("，").append(badge)
        if (progress != null) append("，已观看 ").append((progress.coerceIn(0f, 1f) * 100).toInt()).append("%")
    }
    // 用局部变量避免在语义块里与 androidx.compose.ui.semantics.onClick 同名。
    val activateCard = onClick
    BoxWithConstraints(
        modifier = modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
                idleBorder = TvColors.Border,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .background(if (focused) TvColors.SurfaceRaised else TvColors.Surface)
            .alpha(pressAlpha)
            .then(
                if (touchMode) {
                    Modifier.pointerInput(onClick) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    pressed = false
                                }
                            },
                            onTap = { onClick() },
                        )
                    }
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = cardLabel
                role = Role.Button
                // 触摸模式走 pointerInput，没有 clickable 的语义点击动作；补一个动作，
                // 让 TalkBack 双击卡片能真正打开详情。
                if (touchMode) {
                    onClick {
                        activateCard()
                        true
                    }
                }
            }
            .then(if (touchMode) Modifier else Modifier.focusable()),
    ) {
        // 触摸模式按实测卡宽选字号档位：手机竖屏网格卡（约 108–130dp）与续看卡（140dp）
        // 都走小字两行档，宽平板卡用较大档。全部取自 typography token，
        // 因此仍随设置里的字号缩放。
        // 手机模式下根据卡片宽度自适应选择更紧凑的宽高比
        val narrowCard = maxWidth < 150.dp
        val isMobilePortrait = touchMode && maxWidth < 400.dp
        val aspectRatio = if (isMobilePortrait) MediaCardAspectRatioMobile else MediaCardAspectRatio
        val cardTitleStyle = when {
            !touchMode -> MaterialTheme.typography.titleSmall
            narrowCard -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.titleMedium
        }
        val cardTitleLines = if (touchMode && narrowCard) 2 else 1
        val cardSubtitleStyle = when {
            !touchMode -> MaterialTheme.typography.labelMedium
            narrowCard -> MaterialTheme.typography.labelMedium
            else -> MaterialTheme.typography.bodySmall
        }
        val cardSubtitleLines = if (touchMode && narrowCard) 2 else 1
        // 窄卡（手机网格 / 续看卡）文字区固定高度：标题与副标题各按「行高 × 最大行数」预留空间，
        // 这样无论片名是 1 行还是 2 行，整卡总高都一致，网格同行卡片不再因文字长短而参差变形。
        // 高度随字号设置（typography 已含缩放）用 density 换算，避免在「超大」字号下截断。
        // 防御：lineHeight 未指定（Unspecified）时禁止进入 toDp()——TextUnit.toDp() 对非 Sp
        // 类型会直接抛 IllegalStateException（历史上一次启动崩溃的根因），必须先回退到字号推导行高。
        val density = LocalDensity.current
        val titleTextHeight = with(density) {
            (cardTitleStyle.effectiveLineHeight() * cardTitleLines).toDp()
        }
        val subtitleTextHeight = with(density) {
            (cardSubtitleStyle.effectiveLineHeight() * cardSubtitleLines).toDp()
        }
        val fixTextBlock = touchMode && narrowCard
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .background(TvColors.SurfaceRaised),
            ) {
                cover()
                bottomOverlay?.let { overlay ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MediaCardScrim),
                                ),
                            )
                            .padding(8.dp),
                    ) {
                        overlay()
                    }
                }
                if (!badge.isNullOrBlank()) {
                    MediaCardBadge(
                        text = badge,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
                coverOverlay()
            }
            progress?.let { rawProgress ->
                val progressValue = rawProgress.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = TvColors.SurfaceRaised,
                )
            }
            if (fixTextBlock) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(titleTextHeight + 6.dp)
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp),
                ) {
                    Text(
                        text = title,
                        style = cardTitleStyle,
                        // 手机版深色卡片上必须显式前景色，否则 edge-to-edge 下默认内容色偏暗、片名几乎不可见。
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = cardTitleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = title,
                    modifier = Modifier.padding(
                        start = 8.dp,
                        top = 6.dp,
                        end = 8.dp,
                        bottom = if (subtitle.isNullOrBlank()) 8.dp else 1.dp,
                    ),
                    style = cardTitleStyle,
                    // 手机版深色卡片上必须显式前景色，否则 edge-to-edge 下默认内容色偏暗、片名几乎不可见。
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = cardTitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                if (fixTextBlock) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(subtitleTextHeight + 8.dp)
                            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            text = subtitle,
                            color = TvColors.TextSecondary,
                            style = cardSubtitleStyle,
                            maxLines = cardSubtitleLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        color = TvColors.TextSecondary,
                        style = cardSubtitleStyle,
                        maxLines = cardSubtitleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 统一角标：顶右对齐、半透明底、白字单行，供豆瓣分、集数、继续观看等复用。 */
@Composable
private fun MediaCardBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MediaCardScrim)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 新剧集提示统一色板：已确认更新（橙）与仅本机未看（绿），高对比、三处复用。 */
val NewEpisodeConfirmedColor = Color(0xFFFF9E4D)
val NewEpisodePendingColor = Color(0xFF5DBB8A)

/**
 * 新剧集醒目文字标签：历史记录 / 追剧列表 / 详情页三处共用。
 * [large] 用于 TV 端（3 米外可读，焦点态下同样清晰），默认手机端紧凑档。
 * [onNavigate] 当详情页有动作时传入：点击或遥控 OK 后跳转至新增剧集位置；null 表示纯展示。
 */
@Composable
fun NewEpisodeBadge(
    hint: NewEpisodeHint,
    large: Boolean = false,
    modifier: Modifier = Modifier,
    onNavigate: (() -> Unit)? = null,
) {
    val color = if (hint.confirmed) NewEpisodeConfirmedColor else NewEpisodePendingColor
    // TV 端（large）更新提示必须 3 米可读：用实心高对比背景 + 深色文字 + 描边，
    // 而不是此前的 0.18 alpha 弱背景（远看几乎融进封面）。手机端保持轻量弱背景。
    val background = if (large) color else color.copy(alpha = 0.18f)
    val contentColor = if (large) Color(0xFF101010) else color
    Surface(
        shape = RoundedCornerShape(if (large) 6.dp else 4.dp),
        color = background,
        border = if (large) BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)) else null,
        modifier = if (onNavigate != null) {
            modifier
                .then(Modifier.clickable(role = Role.Button) { onNavigate() })
                .focusable()
                .semantics {
                    // S6-32：可点击的更新提示此前只有 stateDescription，缺内容标签。
                    contentDescription = TvA11y.label(
                        "新剧集提示",
                        hint.label,
                        if (hint.confirmed) "已确认更新" else "仅本机未看",
                    )
                    stateDescription = "跳转到新剧集：${hint.label}"
                }
        } else modifier,
    ) {
        Text(
            // #145/#361：已确认/仅本机未看此前只靠橙/绿颜色区分，补形状标识（★/○），色盲用户也可辨认。
            text = "${if (hint.confirmed) "★" else "○"} ${hint.label}",
            modifier = Modifier.padding(
                horizontal = if (large) 10.dp else 6.dp,
                vertical = if (large) 4.dp else 2.dp,
            ),
            color = contentColor,
            style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun MoviePosterCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 封面 URL 可能因防盗链/过期而加载失败，失败后与「无海报」走同一兜底，避免留白。
    var posterFailed by remember(movie.posterUrl) { mutableStateOf(false) }
    MediaCard(
        title = movie.name,
        onClick = onClick,
        modifier = modifier,
        badge = movie.remarks.takeIf { it.isNotBlank() },
        // P2-15 无障碍：海报整卡一次读出「片名 + 年份 + 备注（评分/更新状态）」，避免 TalkBack 只念「按钮」。
        accessibilityLabel = buildString {
            append(movie.name)
            if (movie.year.isNotBlank()) append("，").append(movie.year)
            if (movie.remarks.isNotBlank()) append("，").append(movie.remarks)
        },
        cover = {
            if (movie.posterUrl.isNotBlank() && !posterFailed) {
                PosterImage(
                    url = movie.posterUrl,
                    kind = PosterKind.CARD,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
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
    )
}

@Composable
fun DoubanHotPosterCard(
    item: DoubanHotItem,
    resolving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var posterFailed by remember(item.doubanId, item.posterUrl) { mutableStateOf(item.posterUrl.isBlank()) }

    MediaCard(
        title = item.title,
        onClick = onClick,
        modifier = modifier,
        badge = if (item.score > 0.0) "豆瓣 ${"%.1f".format(item.score)}" else null,
        cover = {
            if (!posterFailed) {
                PosterImage(
                    url = item.posterUrl,
                    kind = PosterKind.CARD,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
                    configure = {
                        val host = Uri.parse(item.posterUrl).host.orEmpty()
                        if (host.endsWith(".doubanio.com", ignoreCase = true)) {
                            addHeader("Referer", DOUBAN_REFERER)
                            addHeader("User-Agent", DOUBAN_USER_AGENT)
                        }
                    },
                )
            } else {
                Text(
                    text = item.title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        coverOverlay = {
            if (resolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB3080B0B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在查找资源...",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun HistoryItemCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 批量删除多选态：勾选标记占用 badge 位，让遥控器用户明确看到选中状态。 */
    selected: Boolean = false,
) {
    // 封面加载失败（防盗链/地址失效）时同样显示「无封面」，避免历史卡出现空白海报。
    var posterFailed by remember(item.posterUrl) { mutableStateOf(false) }
    val progressText = if (item.durationMs > 0) {
        "${formatPlayerTime(item.positionMs)} / ${item.progressPercent}%"
    } else {
        "已看到 ${formatPlayerTime(item.positionMs)}"
    }
    MediaCard(
        title = item.movieName,
        onClick = onClick,
        modifier = modifier,
        // 导入/迁移的历史可能没有来源名，过滤空段避免副标题以 " / " 开头。
        // 更新提示改由封面底部 overlay 的 NewEpisodeBadge 承担（大字号、高对比、3 米可读），
        // 副标题只保留来源与进度，避免普通灰字弱提示再次被忽略。
        subtitle = listOf(item.sourceName, progressText)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" / "),
        badge = if (selected) "✓ 已选" else "继续观看",
        progress = item.progressPercent / 100f,
        bottomOverlay = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.episodeTitle.isNotBlank()) {
                    Text(
                        text = item.episodeTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                item.newEpisodeHint?.let { hint ->
                    NewEpisodeBadge(hint = hint, large = true)
                }
            }
        },
        cover = {
            if (item.posterUrl.isNotBlank() && !posterFailed) {
                PosterImage(
                    url = item.posterUrl,
                    kind = PosterKind.CARD,
                    contentDescription = item.movieName,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "无封面",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

@Composable
fun PosterImage(movie: Movie, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    // 海报可能因防盗链/过期加载失败：失败后与"无海报"同一兜底，避免详情页留下空白方块。
    var posterFailed by remember(movie.posterUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (movie.posterUrl.isNotBlank() && !posterFailed) {
            PosterImage(
                url = movie.posterUrl,
                kind = PosterKind.HERO,
                contentDescription = movie.name,
                contentScale = ContentScale.Crop,
                onError = { posterFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("无海报", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PageSurface(
    contentPadding: PaddingValues = PaddingValues(
        horizontal = TvDimens.PageHorizontalPadding,
        vertical = TvDimens.PageVerticalPadding,
    ),
    content: @Composable (PaddingValues) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TvColors.Background,
        contentColor = TvColors.TextPrimary,
    ) {
        content(contentPadding)
    }
}

@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.width(64.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SmallMeta(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 可折叠的文字介绍：默认只显示 [collapsedLines] 行，点「展开」后完整显示。
 *
 * 长简介会把下方的选集挤出首屏，所以默认折叠；展开按钮用 TextButton 而不是让整段文字
 * 可点击——电视端只有可聚焦控件才能用遥控选中，纯文字点击在遥控上完全做不到。
 */
@Composable
fun CollapsibleDescription(
    text: String,
    collapsedLines: Int,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(if (expanded) "收起介绍" else "展开介绍")
        }
    }
}

