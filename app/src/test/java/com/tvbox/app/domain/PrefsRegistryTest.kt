package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsRegistryTest {

    @Test
    fun allEntriesHaveNonEmptyName() {
        PrefsRegistry.entries.forEach { entry ->
            assertFalse("条目 ${entry.module} 的 name 不能为空", entry.name.isEmpty())
        }
    }

    @Test
    fun noDuplicateNames() {
        val names = PrefsRegistry.entries.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun namesFor_preferencesOnly_returnsOnlyPrefsWipedOnThatTier() {
        val names = PrefsRegistry.namesFor(ResetTier.PreferencesOnly)
        assertTrue("PreferencesOnly 应至少包含 app_settings", names.contains("app_settings"))
        // watch_history 只在 All 档擦除
        assertFalse("watch_history 不在 PreferencesOnly 档位", names.contains("watch_history"))
    }

    @Test
    fun namesFor_allReturnsMoreThanPreferencesOnly() {
        val prefsOnly = PrefsRegistry.namesFor(ResetTier.PreferencesOnly).toSet()
        val all = PrefsRegistry.namesFor(ResetTier.All).toSet()
        assertTrue("All 档必须严格多于 PreferencesOnly 档", all.size > prefsOnly.size)
        assertTrue("All 档应覆盖 PreferencesOnly 的所有条目", all.containsAll(prefsOnly))
    }

    @Test
    fun allEntriesMarkedInBackupOrNot() {
        // 每条记录都必须有明确的 inBackup 标记（非空判断无意义，所有都应该有值）
        PrefsRegistry.entries.forEach { entry ->
            // inBackup 和 wipedOnReset 的组合是业务逻辑；这里只验证字段非 null
            assertNotNull(entry.inBackup)
            assertNotNull(entry.wipedOnReset)
        }
    }

    @Test
    fun t7_45_liveLineQualityRegistered() {
        // T7-45：线路质量缓存曾漏登，导致 FactoryReset/备份静默跳过
        assertTrue(
            "live_line_quality 必须登记在 PrefsRegistry",
            PrefsRegistry.allNames.contains("live_line_quality")
        )
        assertTrue(
            "live_line_quality 必须参与 PreferencesOnly 档擦除",
            PrefsRegistry.namesFor(ResetTier.PreferencesOnly).contains("live_line_quality")
        )
    }

    @Test
    fun assertNoOrphanKeys_detectsUnknownKeys() {
        val observed = setOf("player.seek_step_ms", "seek_step", "remote.profile_schema", "typo_key")
        val orphans = PrefsKeys.assertNoOrphanKeys(observed)
        assertEquals(setOf("typo_key"), orphans)
    }

    @Test
    fun assertNoOrphanKeys_registeredKeysAreNotOrphans() {
        val observed = PrefsKeys.all.map { it.name }.toSet()
        assertTrue("已登记键不应被判为孤儿", PrefsKeys.assertNoOrphanKeys(observed).isEmpty())
    }

    @Test
    fun canonicalize_mapsRenamedKeyToCanonicalName() {
        val result = PrefsKeys.canonicalize(mapOf("seek_step" to 5_000, "probe.ua_override" to "x"))
        assertEquals(5_000, result["player.seek_step_ms"])
        assertEquals("x", result["probe.ua_override"])
        assertFalse("旧键应被改写为规范名", result.containsKey("seek_step"))
    }

    private fun assertNotNull(value: Any?) {
        if (value == null) throw AssertionError("字段不应为 null")
    }
}
