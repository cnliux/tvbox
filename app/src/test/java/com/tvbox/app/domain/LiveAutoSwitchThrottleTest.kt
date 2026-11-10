package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAutoSwitchThrottleTest {
    @Test
    fun firstAutoSwitchIsAlwaysAllowed() {
        val gate = gateAutoSwitch(AutoSwitchThrottleState(), nowMs = 10_000L)
        val allow = gate as AutoSwitchGate.Allow
        assertEquals(10_000L, allow.nextState.lastSwitchAtMs)
        assertEquals(1, allow.nextState.consecutiveSwitches)
    }

    @Test
    fun rapidConsecutiveAutoSwitchIsDeferred() {
        val state = AutoSwitchThrottleState(lastSwitchAtMs = 1_000L, consecutiveSwitches = 1)
        val gate = gateAutoSwitch(state, nowMs = 1_200L, baseBackoffMs = 800L)
        val defer = gate as AutoSwitchGate.Defer
        assertEquals(600L, defer.retryAfterMs)
    }

    @Test
    fun switchAfterBackoffWindowIsAllowedAndIncrementsCount() {
        val state = AutoSwitchThrottleState(lastSwitchAtMs = 1_000L, consecutiveSwitches = 1)
        val gate = gateAutoSwitch(state, nowMs = 1_900L, baseBackoffMs = 800L)
        val allow = gate as AutoSwitchGate.Allow
        assertEquals(1_900L, allow.nextState.lastSwitchAtMs)
        assertEquals(2, allow.nextState.consecutiveSwitches)
    }

    @Test
    fun backoffGrowsExponentiallyButIsCapped() {
        val state = AutoSwitchThrottleState(lastSwitchAtMs = 0L, consecutiveSwitches = 99)
        // 首次放行后连切到上限：shift 被 coerceIn(0, 6)，退避不会溢出 Long。
        val first = gateAutoSwitch(state, nowMs = 100L, baseBackoffMs = 800L, maxBackoffMs = 6_400L)
        assertTrue(first is AutoSwitchGate.Allow)
        val next = (first as AutoSwitchGate.Allow).nextState
        val deferred = gateAutoSwitch(next, nowMs = 101L, baseBackoffMs = 800L, maxBackoffMs = 6_400L)
        assertTrue(deferred is AutoSwitchGate.Defer)
        assertTrue((deferred as AutoSwitchGate.Defer).retryAfterMs <= 6_400L)
    }

    @Test
    fun resetClearsManualIntentAndClockRollbackIsAllowed() {
        val reset = resetAutoSwitchThrottle()
        assertEquals(0L, reset.lastSwitchAtMs)
        assertEquals(0, reset.consecutiveSwitches)

        val rollback = gateAutoSwitch(
            AutoSwitchThrottleState(lastSwitchAtMs = 5_000L, consecutiveSwitches = 4),
            nowMs = 1_000L,
        )
        assertTrue(rollback is AutoSwitchGate.Allow)
        assertEquals(1, (rollback as AutoSwitchGate.Allow).nextState.consecutiveSwitches)
    }
}
