package com.tvbox.app.data.reset

import com.tvbox.app.domain.ResetTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ResetPathPolicy] 的纯 JVM 单测：不依赖 Android Context，用临时目录构造假文件。
 *
 * 验证白名单安全边界：
 * a) 白名单只含 baseDir 之下的路径（不含根/上级）；
 * b) All 档待删集合覆盖 PreferencesOnly 的子集；
 * c) auditJson 输出可被简单解析且含 tier 与删除条数；
 * d) 越界路径（如 ../outside）被拒绝。
 */
class AndroidFactoryResetDepsTest {

    private fun tempDir(): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "reset_policy_test_${System.nanoTime()}",
        )
        assertTrue(dir.mkdirs())
        return dir
    }

    private fun teardown(dir: File) {
        dir.deleteRecursively()
    }

    @Test
    fun `whitelist only contains paths under baseDir`() {
        val base = tempDir()
        val cache = File(base, "cache").apply { mkdirs() }
        try {
            val tier = ResetTier.All
            val paths = ResetPathPolicy.resolveTier(tier, base, cache)
            assertTrue("All 档应解析出白名单路径", paths.isNotEmpty())
            val roots = listOf(base, cache)
            paths.forEach { path ->
                assertTrue(
                    "路径 ${path.absolutePath} 必须位于根之下",
                    ResetPathPolicy.isUnder(path, roots),
                )
                // 不能等于根，也不能指向根上级。
                assertFalse(
                    "路径 ${path.absolutePath} 不能等于根",
                    path.absolutePath == base.absolutePath || path.absolutePath == cache.absolutePath,
                )
                assertFalse(
                    "路径 ${path.absolutePath} 不能位于根之上",
                    path.absolutePath.startsWith(base.parentFile.absolutePath + File.separator) &&
                        !path.absolutePath.startsWith(base.absolutePath + File.separator),
                )
            }
        } finally {
            teardown(base)
        }
    }

    @Test
    fun `all tier covers preferences only tier`() {
        val base = tempDir()
        val cache = File(base, "cache").apply { mkdirs() }
        try {
            val prefsOnly = ResetPathPolicy.resolveTier(ResetTier.PreferencesOnly, base, cache)
            val all = ResetPathPolicy.resolveTier(ResetTier.All, base, cache)
            val prefsOnlySet = prefsOnly.map { it.absolutePath }.toSet()
            val allSet = all.map { it.absolutePath }.toSet()
            assertTrue(
                "All 档应覆盖 PreferencesOnly 的待删集合",
                allSet.containsAll(prefsOnlySet),
            )
        } finally {
            teardown(base)
        }
    }

    @Test
    fun `preferences only includes datastore and shared prefs`() {
        val base = tempDir()
        val cache = File(base, "cache").apply { mkdirs() }
        try {
            val paths = ResetPathPolicy.resolveTier(ResetTier.PreferencesOnly, base, cache)
            val abs = paths.map { it.absolutePath }.toSet()
            assertTrue(
                "应包含 DataStore 偏好文件",
                abs.contains(File(base, ResetPathPolicy.DATASTORE_PREFS_RELATIVE).absolutePath),
            )
            ResetPathPolicy.SHARED_PREFS_NAMES.forEach { name ->
                assertTrue(
                    "应包含共享偏好 $name",
                    abs.contains(File(base, "shared_prefs/$name.xml").absolutePath),
                )
            }
        } finally {
            teardown(base)
        }
    }

    @Test
    fun `audit json is parseable and contains tier and count`() {
        val json = ResetPathPolicy.auditJson(
            tier = ResetTier.PreferencesOnly,
            deleted = listOf("/tmp/a.xml", "/tmp/b.xml"),
            failed = listOf("/tmp/c.xml"),
        )
        assertTrue("审计应包含 tier", json.contains("\"tier\""))
        assertTrue("审计应包含 PreferencesOnly", json.contains("PreferencesOnly"))
        assertTrue("审计应包含删除条数", json.contains("\"deletedCount\": 2"))
        assertTrue("审计应包含失败条数", json.contains("\"failedCount\": 1"))
        assertTrue("审计应包含 timestamp", json.contains("\"timestamp\""))
        // 简单的可解析性：成对大括号、数组括号闭合。
        assertEquals(1, json.count { it == '{' })
        assertEquals(1, json.count { it == '}' })
    }

    @Test
    fun `out of scope path is rejected`() {
        val base = tempDir()
        val cache = File(base, "cache").apply { mkdirs() }
        try {
            val outside = File(base.parentFile, "outside")
            assertFalse(
                "越界路径 ../outside 必须被拒绝",
                ResetPathPolicy.isUnder(outside, listOf(base, cache)),
            )
            // 根目录本身也不允许。
            assertFalse(
                "根目录本身必须被拒绝",
                ResetPathPolicy.isUnder(base, listOf(base, cache)),
            )
            // 根之下允许。
            val inside = File(base, "shared_prefs/a.xml")
            assertTrue(
                "根之下的路径应被允许",
                ResetPathPolicy.isUnder(inside, listOf(base, cache)),
            )
        } finally {
            teardown(base)
        }
    }

    @Test
    fun `source tier adds source config and cache paths`() {
        val base = tempDir()
        val cache = File(base, "cache").apply { mkdirs() }
        try {
            val sourcePaths = ResetPathPolicy.resolveTier(ResetTier.PreferencesSourcesNetwork, base, cache)
            val abs = sourcePaths.map { it.absolutePath }.toSet()
            assertTrue(
                "源档应包含 filesDir/exports",
                abs.contains(File(base, "exports").absolutePath),
            )
            assertTrue(
                "源档应包含 cacheDir/source_config",
                abs.contains(File(cache, "source_config").absolutePath),
            )
        } finally {
            teardown(base)
        }
    }
}
