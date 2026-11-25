package com.tvbox.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class TvFocusTokensTest {

    @Test
    fun defaultThemeUsesDefaultFocusTokens() {
        assertEquals(DefaultFocusTokens, focusTokensFor(reduceMotion = false))
    }

    @Test
    fun reduceMotionDisablesScaleAndAnimationButKeepsFocusRing() {
        val reduced = focusTokensFor(reduceMotion = true)
        assertEquals(1f, reduced.focusScale, 0f)
        assertEquals(0, reduced.animationMs)
        assertEquals(DefaultFocusTokens.ringWidth, reduced.ringWidth)
        assertEquals(DefaultFocusTokens.ringColor, reduced.ringColor)
    }
}
