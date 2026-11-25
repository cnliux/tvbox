package com.tvbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4-27 / T4-28 / T4-30 的构建期门禁 JVM 单测。
 * 直接从源文件读取「唯一真源」，避免文档与构建脚本漂移。
 */
class BuildConfigGovernanceTest {

    @Test
    fun `T4-27 versionCode has exactly one definition and one assignment`() {
        val script = TestProjectFiles.read("build.gradle.kts")
        assertEquals(1, Regex("""val\s+TVBOX_VERSION_CODE\s*=""").findAll(script).count())
        assertEquals(1, Regex("""versionCode\s*=""").findAll(script).count())
        assertEquals(0, Regex("""versionCode\s*=\s*\d+""").findAll(script).count())
        assertTrue("单包多端不应引入 flavor 版本分段", !Regex("""productFlavors\s*\{""").containsMatchIn(script))
    }

    @Test
    fun `T4-28 wrapper pins the official distribution sha256`() {
        val wrapper = TestProjectFiles.read("gradle/wrapper/gradle-wrapper.properties")
        val hash = Regex("""distributionSha256Sum\s*=\s*([0-9a-fA-F]+)""")
            .find(wrapper)?.groupValues?.get(1)
        assertTrue("distributionSha256Sum 缺失", hash != null)
        assertEquals(64, hash!!.length)
        assertTrue("distributionUrl 必须走 https 官方域名",
            Regex("""distributionUrl=https\\://services\.gradle\.org/distributions/gradle-""")
                .containsMatchIn(wrapper))
        assertTrue("必须校验 wrapper URL", Regex("""validateDistributionUrl=true""").containsMatchIn(wrapper))
    }

    @Test
    fun `T4-28 dependency resolution is locked and official repos come first`() {
        val settings = TestProjectFiles.read("settings.gradle.kts")
        assertTrue("必须禁止项目级仓库", "FAIL_ON_PROJECT_REPOS" in settings)
        val google = settings.indexOf("google()")
        val central = settings.indexOf("mavenCentral()")
        val mirror = settings.indexOf("aliyun", ignoreCase = true)
        assertTrue(google >= 0 && central > google)
        if (mirror >= 0) assertTrue("镜像源必须排在官方源之后", mirror > central)

        val build = TestProjectFiles.read("build.gradle.kts")
        assertTrue("必须锁定全部配置", Regex("""lockAllConfigurations\s*\(\s*\)""").containsMatchIn(build))
        assertTrue("gradle.lockfile 必须入库", TestProjectFiles.find("gradle.lockfile").isFile)
    }

    @Test
    fun `T4-30 apk budget is present and internally consistent`() {
        val raw = TestProjectFiles.read("tools/apk_budget.json")
        assertEquals(1, numberAfter("schema", raw))
        assertEquals(3L, numberAfter("tolerancePercent", raw))
        assertEquals(2L, numberAfter("consecutiveOverrunsBeforeFail", raw))

        val total = numberAfter("total", raw)
        val parts = listOf("dex", "native", "res", "assets").map { numberAfter(it, raw) }
        assertTrue("分项预算之和不得超过总预算", parts.sum() <= total)
        assertTrue("总预算必须在合理区间", total in 20_000_000L..40_000_000L)
        assertTrue("必须说明对比基线", "上一个 tag" in raw)
    }

    private fun numberAfter(key: String, text: String): Long =
        Regex(""""$key"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("JSON 缺少数字字段 $key")

    @Test
    fun `no flavor-specific apk id suffix can be hardcoded`() {
        // 单包多端：任何产品 flavor 的后缀硬编码都会让 OTA 推错包（T4-27）。
        val script = TestProjectFiles.read("build.gradle.kts")
        assertFalse(Regex("""applicationIdSuffix\s*=\s*"\.(tv|mobile|uni)"""").containsMatchIn(script))
    }
}
