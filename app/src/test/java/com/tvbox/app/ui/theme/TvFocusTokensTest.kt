package com.tvbox.app.ui.theme

import com.tvbox.app.domain.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TvFocusTokensTest {

    @Test
    fun bothThemesMapToTheirOwnFocusTokens() {
        val defaultTokens = focusTokensFor(TvTheme.Default, reduceMotion = false)
        val cinemaTokens = focusTokensFor(TvTheme.Cinema, reduceMotion = false)
        assertEquals(DefaultFocusTokens, defaultTokens)
        assertEquals(CinemaFocusTokens, cinemaTokens)
        assertNotEquals(defaultTokens.ringWidth, cinemaTokens.ringWidth)
    }

    @Test
    fun reduceMotionDisablesScaleAndAnimationButKeepsFocusRing() {
        val reduced = focusTokensFor(TvTheme.Cinema, reduceMotion = true)
        assertEquals(1f, reduced.focusScale, 0f)
        assertEquals(0, reduced.animationMs)
        assertEquals(CinemaFocusTokens.ringWidth, reduced.ringWidth)
        assertEquals(CinemaFocusTokens.ringColor, reduced.ringColor)
    }
}
