package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlPolicyTest {

    @Test
    fun horizontalSeekWinsOverBrightnessAndVolume() {
        val sample = GestureSample(GestureZone.Left, deltaX = 120f, deltaY = 40f, durationMs = 200L)
        assertEquals(GestureIntent.Seek, resolveGesture(sample))
    }

    @Test
    fun verticalGesturesRespectLeftRightZones() {
        assertEquals(
            GestureIntent.Brightness,
            resolveGesture(GestureSample(GestureZone.Left, 5f, -90f, 200L)),
        )
        assertEquals(
            GestureIntent.Volume,
            resolveGesture(GestureSample(GestureZone.Right, 5f, -90f, 200L)),
        )
        assertEquals(
            GestureIntent.None,
            resolveGesture(GestureSample(GestureZone.Center, 5f, -90f, 200L)),
        )
    }

    @Test
    fun smallMovementsAreTreatedAsAccidentalTouches() {
        assertEquals(
            GestureIntent.None,
            resolveGesture(GestureSample(GestureZone.Right, 8f, -9f, 80L)),
        )
    }

    @Test
    fun safeAreaPaddingAndSingleHandReachAreComputedCorrectly() {
        val insets = SafeAreaInsets(leftPx = 24, topPx = 12, rightPx = 16, bottomPx = 34)
        assertEquals(40 to 32, horizontalContentPadding(insets))
        assertEquals(50, bottomContentPadding(insets))
        assertTrue(isInSingleHandReach(controlTopPx = 850, screenHeightPx = 1000))
        assertFalse(isInSingleHandReach(controlTopPx = 400, screenHeightPx = 1000))
        assertTrue(PLAYER_CONTROL_LAYOUT[PlayerControlGroup.BottomProgress] == true)
    }

    @Test
    fun castStateHasVisibleLabels() {
        assertEquals("未连接", castStateLabel(CastState.Disconnected))
        assertEquals("搜索设备中", castStateLabel(CastState.Searching))
        assertEquals("已连接", castStateLabel(CastState.Connected))
    }
}
