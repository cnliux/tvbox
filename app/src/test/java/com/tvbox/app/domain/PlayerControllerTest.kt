package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第 313 轮 · 播放控制器接口契约单测
 * 通过 [MockPlayerController] 锁定 [PlayerController] 的关键不变量：
 * 快进/快退越界钳制、倍速范围钳制、播放/暂停翻转；
 * 并断言 [PlayerController.Companion] 的默认步长与倍速档位。
 */
class PlayerControllerTest {

    @Test
    fun seekForwardClampsToDuration() {
        val c = MockPlayerController(_duration = 1000L)
        c.seekRelative(+500L)
        assertEquals(500L, c.currentPosition)
        c.seekRelative(+900L) // 500 + 900 > 1000
        assertEquals(1000L, c.currentPosition)
    }

    @Test
    fun seekBackwardClampsToZero() {
        val c = MockPlayerController(_currentPosition = 200L, _duration = 1000L)
        c.seekRelative(-500L)
        assertEquals(0L, c.currentPosition)
    }

    @Test
    fun speedClampsToSupportedRange() {
        val c = MockPlayerController()
        c.setSpeed(99f)
        assertEquals(4.0f, c.currentSpeed)
        c.setSpeed(0.1f)
        assertEquals(0.5f, c.currentSpeed)
        c.setSpeed(1.5f)
        assertEquals(1.5f, c.currentSpeed)
    }

    @Test
    fun togglePlayPauseFlipsState() {
        val c = MockPlayerController()
        assertEquals(false, c.isPlaying)
        c.togglePlayPause()
        assertEquals(true, c.isPlaying)
        c.togglePlayPause()
        assertEquals(false, c.isPlaying)
    }

    @Test
    fun seekAndSpeedCallbacksFire() {
        val c = MockPlayerController()
        var seeked: Long? = null
        var speeded: Float? = null
        c.onSeekCallback = { seeked = it }
        c.onSpeedCallback = { speeded = it }
        c.seekRelative(10_000L)
        c.setSpeed(2.0f)
        assertEquals(10_000L, seeked)
        assertEquals(2.0f, speeded)
    }

    @Test
    fun defaultConstantsAreStable() {
        assertEquals(10_000L, PlayerController.DEFAULT_SEEK_STEP_MS)
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
            PlayerController.DEFAULT_SPEEDS,
        )
    }
}
