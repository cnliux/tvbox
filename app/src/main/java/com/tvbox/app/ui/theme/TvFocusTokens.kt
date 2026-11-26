package com.tvbox.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.domain.ThemeAccent

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

/** 各主题的焦点环宽度：默认 3dp，影院主题加粗到 4dp（对应 CinemaFocusTokens）。 */
private fun baseFocusTokens(themeAccent: ThemeAccent): TvFocusTokens = when (themeAccent) {
    ThemeAccent.Cinema -> CinemaFocusTokens
    else -> DefaultFocusTokens
}

/**
 * 按主题强调色与「减少动效」偏好解析最终焦点令牌。
 *
 * F-01 契约：ringColor 由主题的 [TvColorTokens.focusRing] 派生（调用方传入），
 * 保证「切主题后焦点环随主题换色」；ringWidth/elevation 由主题 base（影院更粗更高）决定。
 * 「减少动效」时关闭缩放与过渡，但保留焦点描边以保证遥控器可见。
 */
fun focusTokensFor(themeAccent: ThemeAccent, ringColor: Color, reduceMotion: Boolean): TvFocusTokens {
    val base = baseFocusTokens(themeAccent).copy(ringColor = ringColor)
    return if (reduceMotion) base.copy(focusScale = 1f, animationMs = 0) else base
}
