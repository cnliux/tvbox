package com.tvbox.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FactoryResetTest {

    private class RecordingDeps : FactoryResetDeps {
        val calls = mutableListOf<String>()
        override suspend fun clearPreferences() { calls += "preferences" }
        override suspend fun clearSources() { calls += "sources" }
        override suspend fun clearNetworkCaches() { calls += "network" }
        override suspend fun clearPlaybackHistory() { calls += "history" }
    }

    @Test
    fun preferencesOnlyTierClearsOnlyPreferences() = runBlocking {
        val deps = RecordingDeps()
        val result = FactoryReset.reset(ResetTier.PreferencesOnly, deps)
        assertEquals(listOf("preferences"), deps.calls)
        assertEquals(1, result.steps.size)
        assertTrue(result.retained.isEmpty())
    }

    @Test
    fun sourcesTierClearsPreferencesSourcesAndNetwork() = runBlocking {
        val deps = RecordingDeps()
        val result = FactoryReset.reset(ResetTier.PreferencesSourcesNetwork, deps)
        assertEquals(listOf("preferences", "sources", "network"), deps.calls)
        assertEquals(3, result.steps.size)
    }

    @Test
    fun allTierRequiresDeleteTokenBeforeAnyDependencyRuns() = runBlocking {
        val deps = RecordingDeps()
        try {
            FactoryReset.reset(ResetTier.All, deps, confirmationToken = "delete")
            fail("错误令牌应被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期：在任何依赖被调用前抛错
        }
        assertTrue(deps.calls.isEmpty())
        val result = FactoryReset.reset(ResetTier.All, deps, confirmationToken = "DELETE")
        assertEquals(listOf("preferences", "sources", "network", "history"), deps.calls)
        assertTrue(result.retained.contains("APK 安装包"))
        assertTrue(result.retained.contains("AAR 依赖包"))
        assertTrue(result.retained.contains("崩溃日志"))
        assertTrue(FactoryReset.confirmationText(ResetTier.All).contains("DELETE"))
    }
}
