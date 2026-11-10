package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTaskPolicyTest {
    @Test
    fun firstFrameTasksHaveNoDeferral() {
        listOf(StartupTask.Settings, StartupTask.History, StartupTask.HomeFeed).forEach { task ->
            assertEquals(StartupTaskPhase.BeforeFirstFrame, startupPhaseOf(task))
            assertEquals(0L, startupDeferralMs(task))
        }
    }

    @Test
    fun otaCheckIsDeferredButNotToIdle() {
        // OTA 提示不能拖到用户已经操作很久之后，首帧后短延后即可。
        assertEquals(StartupTaskPhase.AfterFirstFrame, startupPhaseOf(StartupTask.OtaCheck))
        assertEquals(800L, startupDeferralMs(StartupTask.OtaCheck))
    }

    @Test
    fun liveEpgAndPlatformLiveInitAreIdleTasks() {
        assertEquals(StartupTaskPhase.Idle, startupPhaseOf(StartupTask.LiveEpg))
        assertEquals(StartupTaskPhase.Idle, startupPhaseOf(StartupTask.PlatformLiveInit))
        assertEquals(3_000L, startupDeferralMs(StartupTask.LiveEpg))
    }

    @Test
    fun deferralIsMonotonicAcrossPhases() {
        assertTrue(startupDeferralMs(StartupTask.HomeFeed) < startupDeferralMs(StartupTask.OtaCheck))
        assertTrue(startupDeferralMs(StartupTask.OtaCheck) < startupDeferralMs(StartupTask.LiveEpg))
    }

    @Test
    fun everyTaskHasAnExplicitPhase() {
        StartupTask.entries.forEach { task ->
            val phase = startupPhaseOf(task)
            assertTrue(startupDeferralMs(task) >= 0L)
            assertTrue(phase == StartupTaskPhase.BeforeFirstFrame || startupDeferralMs(task) > 0L)
        }
    }
}
