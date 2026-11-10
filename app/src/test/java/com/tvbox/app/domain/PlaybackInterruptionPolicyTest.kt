package com.tvbox.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 310 轮 · 播放中断策略状态机单测
 * 锁定 [PlaybackInterruptionPolicy]：前台 + 已请求播放才允许播放；
 * 离开前台即中断；后台请求播放被拒；暂停不标记中断；中断后需重新请求。
 */
class PlaybackInterruptionPolicyTest {

    @Test
    fun initialStateAllowsPlayback() {
        val policy = PlaybackInterruptionPolicy()
        assertTrue(policy.playbackAllowed)
        assertFalse(policy.interrupted)
    }

    @Test
    fun leavingForegroundInterrupts() {
        val policy = PlaybackInterruptionPolicy()
        policy.leaveForeground()
        assertFalse(policy.playbackAllowed)
        assertTrue(policy.interrupted)
    }

    @Test
    fun requestPlayDeniedWhileInBackground() {
        val policy = PlaybackInterruptionPolicy()
        policy.leaveForeground()
        assertFalse(policy.requestPlay())
        assertFalse(policy.playbackAllowed)
    }

    @Test
    fun enteringForegroundAndRequestingPlayResumes() {
        val policy = PlaybackInterruptionPolicy()
        policy.leaveForeground()
        policy.enterForeground()
        assertTrue(policy.requestPlay())
        assertTrue(policy.playbackAllowed)
        assertFalse(policy.interrupted)
    }

    @Test
    fun pauseKeepsForegroundButBlocksPlaybackWithoutInterrupt() {
        val policy = PlaybackInterruptionPolicy()
        policy.pause()
        assertFalse(policy.playbackAllowed)
        assertFalse(policy.interrupted)
    }

    @Test
    fun interruptMarksInterruptedUntilRequestPlayClears() {
        val policy = PlaybackInterruptionPolicy()
        policy.interrupt()
        assertTrue(policy.interrupted)
        assertFalse(policy.playbackAllowed)
        policy.requestPlay()
        assertFalse(policy.interrupted)
    }
}
