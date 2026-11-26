package com.tvbox.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.tvbox.app.domain.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * F-01 契约：焦点令牌的 ringWidth / ringColor / elevation 必须被真实消费——
 * 否则调整令牌不会产生任何视觉变化（旧测试断言的恰是死字段，形成「测试已覆盖」假象）。
 * 这里以「主题 × reduceMotion 矩阵」验证 focusTokensFor 的解析结果。
 */
class TvFocusTokensTest {

    private val themeRing: Color = Color(0xFF1ED760)
    private val cinemaRing: Color = Color(0xFFF7FAF8)

    @Test
    fun defaultThemeUsesDefaultBaseWithProvidedRingColor() {
        val tokens = focusTokensFor(ThemeAccent.Default, themeRing, reduceMotion = false)
        assertEquals(DefaultFocusTokens.ringWidth, tokens.ringWidth)
        assertEquals(themeRing, tokens.ringColor)
        assertEquals(DefaultFocusTokens.elevation, tokens.elevation)
        assertEquals(DefaultFocusTokens.focusScale, tokens.focusScale)
        assertEquals(DefaultFocusTokens.animationMs, tokens.animationMs)
    }

    @Test
    fun cinemaThemeUsesCinemaBase() {
        val tokens = focusTokensFor(ThemeAccent.Cinema, cinemaRing, reduceMotion = false)
        assertEquals(CinemaFocusTokens.ringWidth, tokens.ringWidth)
        assertEquals(cinemaRing, tokens.ringColor)
        assertEquals(CinemaFocusTokens.elevation, tokens.elevation)
        assertEquals(CinemaFocusTokens.focusScale, tokens.focusScale)
        assertEquals(CinemaFocusTokens.animationMs, tokens.animationMs)
    }

    @Test
    fun nonCinemaThemesUseDefaultBaseButThemeRingColor() {
        val oceanRing = Color(0xFF64B5F6)
        val tokens = focusTokensFor(ThemeAccent.Ocean, oceanRing, reduceMotion = false)
        assertEquals(DefaultFocusTokens.ringWidth, tokens.ringWidth)
        assertEquals(oceanRing, tokens.ringColor)
        assertEquals(DefaultFocusTokens.elevation, tokens.elevation)
    }

    @Test
    fun reduceMotionDisablesScaleAndAnimationButKeepsRingWidthAndColor() {
        val reduced = focusTokensFor(ThemeAccent.Default, themeRing, reduceMotion = true)
        assertEquals(1f, reduced.focusScale, 0f)
        assertEquals(0, reduced.animationMs)
        assertEquals(DefaultFocusTokens.ringWidth, reduced.ringWidth)
        assertEquals(themeRing, reduced.ringColor)
        assertEquals(DefaultFocusTokens.elevation, reduced.elevation)
    }

    @Test
    fun cinemaBaseDiffersFromDefaultBase() {
        assertNotEquals(DefaultFocusTokens.ringWidth, CinemaFocusTokens.ringWidth)
        assertNotEquals(DefaultFocusTokens.elevation, CinemaFocusTokens.elevation)
        assertNotEquals(DefaultFocusTokens.focusScale, CinemaFocusTokens.focusScale)
    }
}
