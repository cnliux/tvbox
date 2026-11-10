package com.tvbox.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.TvTheme

/**
 * TV 焦点视觉与动效的统一令牌：海报卡、按钮、列表项、导航图标与进度条共用一套数值，
 * 默认主题与影院主题只切换映射，调用点不感知主题差异。
 */
data class TvFocusTokens(
    val ringWidth: Dp,
    val ringColor: Color,
    val focusScale: Float,
    val elevation: Dp,
    val animationMs: Int,
)

val DefaultFocusTokens = TvFocusTokens(
    ringWidth = 3.dp,
    ringColor = Color(0xFF64B5F6),
    focusScale = 1.06f,
    elevation = 8.dp,
    animationMs = 150,
)

val CinemaFocusTokens = TvFocusTokens(
    ringWidth = 4.dp,
    ringColor = Color(0xFFFFC864),
    focusScale = 1.08f,
    elevation = 12.dp,
    animationMs = 180,
)

val LocalTvFocus = staticCompositionLocalOf { DefaultFocusTokens }

/** 系统开启「减少动效」时关闭缩放与过渡，但保留焦点描边以保证遥控器可见。 */
fun focusTokensFor(theme: TvTheme, reduceMotion: Boolean): TvFocusTokens {
    val base = if (theme == TvTheme.Cinema) CinemaFocusTokens else DefaultFocusTokens
    return if (reduceMotion) base.copy(focusScale = 1f, animationMs = 0) else base
}
