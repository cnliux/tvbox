package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S2-11：保留域免疫自定义 UA 的契约。 */
class UaScopeImmutableHostTest {
    @Test
    fun `github and ota hosts are immutable`() {
        listOf(
            "github.com", "API.GitHub.COM", "objects.githubusercontent.com:443",
            "raw.githubusercontent.com", "codeload.github.com.", "sub.githubusercontent.com",
        ).forEach { assertTrue(it, UaScope.isImmutableHost(it)) }
    }

    @Test
    fun `ordinary hosts are not immutable`() {
        listOf("douban.com", "example.com", "notgithub.com", "127.0.0.1").forEach {
            assertFalse(it, UaScope.isImmutableHost(it))
        }
    }

    @Test
    fun `immutable host forces builtin ua even when custom is set`() {
        val settings = AppSettings(
            uaScopeMode = "unified",
            customUserAgent = "EvilCrawler/1.0",
        )
        assertEquals(UserAgentSetting.Default, UaScope.effectiveForHost("github.com", "api", settings))
        assertEquals("EvilCrawler/1.0", UaScope.effectiveForHost("douban.com", "api", settings))
    }
}
