package com.tvbox.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.LiveChannel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视直播页的浮层组件集合（顶部导航条 / 底部状态条按钮 / EPG 窄卡 / 频道浮层 / 线路浮层）。
 *
 * 与 [LiveScreen] 的分工：LiveScreen 负责播放器、遥控器按键与显隐状态机，
 * 本文件只画"叠在画面上的那层"皮，不含播放逻辑，也不触碰源解析。
 *
 * 关于毛玻璃：直播画面走 Media3 PlayerView（SurfaceView），Compose 无法对独立
 * surface 采样模糊，因此浮层用"高透明度底色 + 遮罩"模拟，视觉上等同 blur 后的效果，
 * 且不引入额外渲染成本。
 */

/** 电视直播强调色：与手机直播页、播放器控件共用同一套 #E50914。 */
internal val LiveTvAccent = Color(0xFFE50914)

/** 浮层主面板底色 rgba(14,14,18,0.94)。 */
internal val LiveTvPanelBg = Color(0xF00E0E12)

/** 焦点行底色 rgba(229,9,20,0.12)。 */
internal val LiveTvFocusBg = Color(0x1FE50914)

/** 当前播放行底色 rgba(229,9,20,0.05)。 */
internal val LiveTvPlayingBg = Color(0x0DE50914)

/** EPG 当前节目底色 rgba(229,9,20,0.1)。 */
internal val LiveTvEpgCurrentBg = Color(0x1AE50914)

/** 台标占位底色。 */
internal val LiveTvLogoPlaceholderBg = Color(0xFF1A1A22)

/** 进度条轨道底色。 */
internal val LiveTvTrackBg = Color(0x33FFFFFF)

/** 「全部」聚合分组：仅作为浏览入口，不是源里的二级分类。 */
internal const val LIVE_ALL_GROUP = "全部"

internal const val LIVE_LOG_TAG = "TVBoxLive"
internal const val LIVE_CONNECT_TIMEOUT_MS = 8_000
internal const val LIVE_READ_TIMEOUT_MS = 12_000
internal const val LIVE_CONTINUOUS_BUFFER_THRESHOLD_MS = 6_000L
internal const val LIVE_FREQUENT_BUFFER_WINDOW_MS = 60_000L
internal const val LIVE_FREQUENT_BUFFER_COUNT = 3
internal const val LIVE_CUMULATIVE_BUFFER_THRESHOLD_MS = 12_000L
internal const val LIVE_PROGRESS_SAMPLE_INTERVAL_MS = 1_000L

/** 顶/底信息条 3 秒无操作淡出。 */
internal const val LIVE_BARS_HIDE_DELAY_MS = 3_000L

/** 浮层 6 秒无操作自动收起。 */
internal const val CHANNEL_LIST_HIDE_DELAY_MS = 6_000L

/** 数字键直选：2 秒无输入自动跳转。 */
internal const val CHANNEL_NUMBER_COMMIT_DELAY_MS = 2_000L
internal const val MAX_CHANNEL_NUMBER_DIGITS = 4

/** 数字直选浮层最多展示的候选频道行数（超出滚动查看）。 */
internal const val LIVE_NUMBER_MATCH_ROWS = 6

/** 数字直选输入期间，下方候选列表的淡入淡出时长。 */
internal const val LIVE_NUMBER_MATCH_ANIM_MS = 120

internal const val PROMPT_HIDE_DELAY_MS = 1_500L

/** 顶栏时钟与 EPG 卡当前节目高亮的刷新节拍。 */
internal const val CLOCK_REFRESH_INTERVAL_MS = 20_000L

/** EPG 窄卡固定展示 4 条节目。 */
private const val LIVE_EPG_CARD_ROWS = 4

private val LIVE_CLOCK_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

internal fun formatClock(ms: Long): String = LIVE_CLOCK_FORMAT.format(Date(ms))

/** 无台标时的首字占位：字母 + 紧跟数字取两位（CCTV-1 → C1），其余取首字。 */
internal fun channelInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed[0]
    return if (first.isLetter() && trimmed.length > 1 && trimmed[1].isDigit()) {
        trimmed.substring(0, 2).uppercase()
    } else {
        first.uppercase()
    }
}

/** 遥控器数字键 → 0..9；非数字键返回 null。 */
internal fun liveNumberKeyOf(keyCode: Int): Int? = when (keyCode) {
    android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_NUMPAD_0 -> 0
    android.view.KeyEvent.KEYCODE_1, android.view.KeyEvent.KEYCODE_NUMPAD_1 -> 1
    android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_NUMPAD_2 -> 2
    android.view.KeyEvent.KEYCODE_3, android.view.KeyEvent.KEYCODE_NUMPAD_3 -> 3
    android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_NUMPAD_4 -> 4
    android.view.KeyEvent.KEYCODE_5, android.view.KeyEvent.KEYCODE_NUMPAD_5 -> 5
    android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_NUMPAD_6 -> 6
    android.view.KeyEvent.KEYCODE_7, android.view.KeyEvent.KEYCODE_NUMPAD_7 -> 7
    android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_NUMPAD_8 -> 8
    android.view.KeyEvent.KEYCODE_9, android.view.KeyEvent.KEYCODE_NUMPAD_9 -> 9
    else -> null
}

/** 只响应触点、不参与遥控器焦点（信息条按钮用，避免抢走方向键）。 */
private fun Modifier.liveTap(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) { detectTapGestures { onClick() } }

/**
 * 频道台标：底层先画首字占位，logo 非空时叠 Coil 图片；
 * 加载中/失败时 AsyncImage 不绘制，占位自然透出。
 *
 * 台标为长方形（宽 > 高），与频道 logo 常见比例一致。
 *
 * @param width 台标宽度
 * @param height 台标高度
 * @param stretch true=拉伸铺满（FillBounds），false=裁切填充（Crop，默认）。
 */
@Composable
internal fun LiveChannelLogo(
    logo: String?,
    name: String,
    width: Dp,
    height: Dp,
    initialFontSize: TextUnit,
    stretch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val contentScale = if (stretch) ContentScale.FillBounds else ContentScale.Crop
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(LiveTvLogoPlaceholderBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channelInitial(name),
            fontSize = initialFontSize,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
        )
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 顶部导航条：52dp，黑 0.75 → 透明渐变。
 * LOGO + 「电视直播｜平台直播」双 Tab（选中红色下划线 3dp）+ 右侧收藏/时间。
 */
@Composable
internal fun LiveTopBar(
    favorite: Boolean,
    clockText: String,
    onPlatformLive: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(LiveTvAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "TV",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(16.dp))
            LiveTopBarTab(label = "电视直播", selected = true, onClick = {})
            Spacer(Modifier.width(16.dp))
            LiveTopBarTab(label = "平台直播", selected = false, onClick = onPlatformLive)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (favorite) "♥" else "♡",
                color = if (favorite) LiveTvAccent else Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .liveTap(onToggleFavorite)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = clockText,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LiveTopBarTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .liveTap(onClick)
            .padding(horizontal = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) LiveTvAccent else Color.Transparent),
        )
    }
}

/** 底部状态条按钮：半透明胶囊，仅响应触点/鼠标，不抢占遥控器焦点。 */
@Composable
internal fun LiveBottomBarButton(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.88f),
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .liveTap(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 轻提示：半透明黑底居中偏下，1.5 秒自动消失。 */
@Composable
internal fun LivePrompt(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xB8000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 直播加载态：红色进度环 + 文案（规格要求"红色进度环 + 正在加载 xxx"）。
 * 与通用 LoadingState 的区别只在配色，让直播页的加载/缓冲观感和强调色统一。
 */
@Composable
internal fun LiveLoadingState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = LiveTvAccent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
