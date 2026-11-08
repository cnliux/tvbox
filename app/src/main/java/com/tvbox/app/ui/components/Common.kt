package com.tvbox.app.ui.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tvbox.app.R
import com.tvbox.app.data.DOUBAN_REFERER
import com.tvbox.app.data.DOUBAN_USER_AGENT
import com.tvbox.app.domain.DoubanHotItem
import com.tvbox.app.domain.Movie
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.ui.TvScreen
import com.tvbox.app.ui.formatPlayerTime
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
    showShortcutActions: Boolean = false,
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
                    style = if (showShortcutActions) {
                        MaterialTheme.typography.headlineLarge
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    fontWeight = if (showShortcutActions) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = subtitle,
                color = TvColors.TextSecondary,
                style = if (showShortcutActions) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showShortcutActions) {
                HeaderActionButton(text = "历史(1)", onClick = onHistory, legacyStyle = true, modifier = firstActionModifier)
                HeaderActionButton(text = "搜索(2)", onClick = onSearch, legacyStyle = true)
                HeaderActionButton(text = "电视(4)", onClick = onLive, legacyStyle = true)
                HeaderActionButton(text = "直播(5)", onClick = onPlatformLive, legacyStyle = true)
                HeaderActionButton(text = "设置(6)", onClick = onSettings, legacyStyle = true)
            } else {
                HeaderActionButton(text = "搜索", onClick = onSearch, modifier = firstActionModifier)
                HeaderActionButton(text = "历史", onClick = onHistory)
            }
        }
    }
}

@Composable
fun AppNavigationRail(
    screen: TvScreen,
    onHome: () -> Unit,
    onLive: () -> Unit,
    onPlatformLive: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(TvDimens.RailWidth),
        color = TvColors.Background,
        contentColor = TvColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = TvColors.Accent,
                contentColor = TvColors.Background,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("TV", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TvNavigationItem("首页", NavigationIcon.Home, screen.isHomeDestination(), onHome)
            TvNavigationItem("电视", NavigationIcon.Live, screen == TvScreen.Live, onLive)
            TvNavigationItem("直播", NavigationIcon.PlatformLive, screen == TvScreen.PlatformLive, onPlatformLive)
            Spacer(modifier = Modifier.weight(1f))
            TvNavigationItem("设置", NavigationIcon.Settings, screen == TvScreen.Settings, onSettings)
        }
    }
}

@Composable
private fun TvNavigationItem(
    label: String,
    icon: NavigationIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
                idleBorder = if (selected) TvColors.Accent.copy(alpha = 0.5f) else TvColors.Border,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = when {
            focused -> TvColors.SurfaceRaised
            selected -> TvColors.AccentSoft
            else -> Color.Transparent
        },
        contentColor = when {
            focused -> TvColors.TextPrimary
            selected -> TvColors.AccentStrong
            else -> TvColors.TextSecondary
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            NavigationGlyph(
                icon = icon,
                tint = when {
                    focused -> TvColors.TextPrimary
                    selected -> TvColors.AccentStrong
                    else -> TvColors.TextSecondary
                },
                label = label,
            )
        }
    }
}

private enum class NavigationIcon(val glyph: String) {
    Home("\uE608"),
    Live("\uEB34"),
    PlatformLive("\uE6E6"),
    Settings("\uE6F0"),
}

private val TvBoxIconFont = FontFamily(Font(R.font.tvbox_iconfont))

@Composable
private fun NavigationGlyph(
    icon: NavigationIcon,
    tint: Color,
    label: String,
) {
    val glyphSize = if (icon == NavigationIcon.Live) 22.sp else 26.sp
    Text(
        text = icon.glyph,
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = label },
        color = tint,
        fontFamily = TvBoxIconFont,
        fontSize = glyphSize,
        lineHeight = glyphSize,
    )
}

private fun TvScreen.isHomeDestination(): Boolean {
    return this == TvScreen.Home || this == TvScreen.Detail
}

@Composable
private fun HeaderActionButton(
    text: String,
    onClick: () -> Unit,
    legacyStyle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = if (legacyStyle) RoundedCornerShape(50) else RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
                idleBorder = TvColors.Border,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable(),
        shape = shape,
        color = when {
            focused && legacyStyle -> TvColors.Accent
            focused -> TvColors.SurfaceRaised
            else -> TvColors.Surface
        },
        contentColor = if (focused && legacyStyle) TvColors.Background else TvColors.TextPrimary,
        tonalElevation = if (focused) 5.dp else 0.dp,
        shadowElevation = if (focused) 8.dp else 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (legacyStyle) 20.dp else 14.dp,
                vertical = if (legacyStyle) 10.dp else 8.dp,
            ),
            style = if (legacyStyle) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            fontWeight = if (focused || legacyStyle) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message.ifBlank { "加载失败，请稍后重试" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(text = message.ifBlank { "加载失败，请稍后重试" }, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) {
                Text("重试")
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(onClick = onSecondaryAction) {
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
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp).then(actionModifier),
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
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .tvFocusScale(
                shape = shape,
                focusedBorder = TvColors.FocusRing,
                idleBorder = if (selected) TvColors.Accent.copy(alpha = 0.7f) else TvColors.Border,
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .focusable(),
        shape = shape,
        color = when {
            focused -> TvColors.SurfaceRaised
            selected -> TvColors.AccentSoft
            else -> TvColors.Surface
        },
        contentColor = when {
            focused -> TvColors.TextPrimary
            selected -> TvColors.AccentStrong
            else -> TvColors.TextSecondary
        },
    ) {
        // 文字撑高约 46dp，触摸模式下不足 48dp 最小热区，统一抬到 48dp 并垂直居中。
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 点播海报卡与历史卡统一使用的封面宽高比。 */
private const val MediaCardAspectRatio = 2f / 3f

/** 角标与封面底部遮罩统一使用的半透明底色。 */
private val MediaCardScrim = Color(0xCC080B0B)

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
    Column(
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
                    Modifier.clickable(onClick = onClick)
                },
            )
            .then(if (touchMode) Modifier else Modifier.focusable()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(MediaCardAspectRatio)
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
                        .padding(8.dp),
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
        Text(
            text = title,
            modifier = Modifier.padding(
                start = 10.dp,
                top = 8.dp,
                end = 10.dp,
                bottom = if (subtitle.isNullOrBlank()) 10.dp else 2.dp,
            ),
            style = MaterialTheme.typography.titleSmall,
            // 手机版深色卡片上必须显式前景色，否则 edge-to-edge 下默认内容色偏暗、片名几乎不可见。
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    )
}

@Composable
fun DoubanHotPosterCard(
    item: DoubanHotItem,
    resolving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var posterFailed by remember(item.doubanId, item.posterUrl) { mutableStateOf(item.posterUrl.isBlank()) }
    val imageRequest = remember(item.posterUrl) {
        ImageRequest.Builder(context)
            .data(item.posterUrl)
            .apply {
                val host = Uri.parse(item.posterUrl).host.orEmpty()
                if (host.endsWith(".doubanio.com", ignoreCase = true)) {
                    addHeader("Referer", DOUBAN_REFERER)
                    addHeader("User-Agent", DOUBAN_USER_AGENT)
                }
            }
            .build()
    }

    MediaCard(
        title = item.title,
        onClick = onClick,
        modifier = modifier,
        badge = if (item.score > 0.0) "豆瓣 ${"%.1f".format(item.score)}" else null,
        cover = {
            if (!posterFailed) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
                    imageLoader = rememberTvImageLoader(),
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
        subtitle = listOf(item.sourceName, progressText)
            .filter { it.isNotBlank() }
            .joinToString(" / "),
        badge = "继续观看",
        progress = item.progressPercent / 100f,
        bottomOverlay = {
            Text(
                text = item.episodeTitle,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        cover = {
            if (item.posterUrl.isNotBlank() && !posterFailed) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.movieName,
                    contentScale = ContentScale.Crop,
                    onError = { posterFailed = true },
                    modifier = Modifier.fillMaxSize(),
                    imageLoader = rememberTvImageLoader(),
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
            val imageLoader = rememberTvImageLoader()
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(movie.posterUrl)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build(),
                contentDescription = movie.name,
                contentScale = ContentScale.Crop,
                onError = { posterFailed = true },
                modifier = Modifier.fillMaxSize(),
                imageLoader = imageLoader,
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

