package com.tvbox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定横滑进度换算：从页面内联搬移到共享手势层后，边界与比例语义不能变。
 */
class PlayerInputTest {

    private val durationMs = 6_000_000L

    @Test
    fun zeroDragKeepsStartPosition() {
        assertEquals(
            1_200_000L,
            calculateDragSeekPosition(
                startPositionMs = 1_200_000L,
                dragPx = 0f,
                viewWidthPx = 1_000,
                durationMs = durationMs,
            ),
        )
    }

    @Test
    fun halfWidthDragMovesHalfDuration() {
        assertEquals(
            4_200_000L,
            calculateDragSeekPosition(
                startPositionMs = 1_200_000L,
                dragPx = 500f,
                viewWidthPx = 1_000,
                durationMs = durationMs,
            ),
        )
    }

    @Test
    fun dragPastEndClampsToDuration() {
        assertEquals(
            durationMs,
            calculateDragSeekPosition(
                startPositionMs = 1_200_000L,
                dragPx = 4_000f,
                viewWidthPx = 1_000,
                durationMs = durationMs,
            ),
        )
    }

    @Test
    fun dragBeforeStartClampsToZero() {
        assertEquals(
            0L,
            calculateDragSeekPosition(
                startPositionMs = 1_200_000L,
                dragPx = -4_000f,
                viewWidthPx = 1_000,
                durationMs = durationMs,
            ),
        )
    }

    @Test
    fun nonPositiveWidthOnlyClampsStart() {
        assertEquals(
            durationMs,
            calculateDragSeekPosition(
                startPositionMs = durationMs * 2,
                dragPx = 0f,
                viewWidthPx = 0,
                durationMs = durationMs,
            ),
        )
    }
}
