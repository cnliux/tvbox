package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UaScopeTest {

    @Test
    fun unifiedModeUsesGlobalCustomOrDefault() {
        val configured = AppSettings(customUserAgent = "MyTV/1.0")
        assertEquals("MyTV/1.0", UaScope.resolve("api", configured))
        assertEquals("MyTV/1.0", UaScope.resolve("webview", configured))
        assertEquals(UserAgentSetting.Default, UaScope.resolve("live", AppSettings()))
    }

    @Test
    fun scopedModeUsesPerScopeThenFallsBackToGlobalThenDefault() {
        val settings = AppSettings(
            uaScopeMode = "scoped",
            customUserAgent = "Global/1.0",
            uaForApi = "Api/2.0",
            uaForWebView = " WebView/3.0 \n ",
        )
        assertEquals("Api/2.0", UaScope.resolve("api", settings))
        assertEquals("WebView/3.0", UaScope.resolve("webview", settings))
        assertEquals("Global/1.0", UaScope.resolve("live", settings))
        assertEquals(UserAgentSetting.Default, UaScope.resolve("live", settings.copy(customUserAgent = "")))
    }

    @Test
    fun sanitizeRemovesControlCharactersAndUnknownScopeFallsBackToDefault() {
        val settings = AppSettings(customUserAgent = "Bad\r\nUA")
        assertEquals("BadUA", UaScope.resolve("api", settings))
        assertEquals(UserAgentSetting.Default, UaScope.resolve("unknown", settings))
    }

    @Test
    fun recomputeHeaderForHost_keepsGlobalExceptOnImmutableHosts() {
        assertEquals("Custom/1.0", UaScope.recomputeHeaderForHost("cdn.other.net", "Custom/1.0"))
        assertEquals("Custom/1.0", UaScope.recomputeHeaderForHost("a.example.com", "Custom/1.0"))
        // OTA 唯一更新通道：即使全局 UA 已设坏也必须落回内置默认。
        assertEquals(
            UserAgentSetting.Default,
            UaScope.recomputeHeaderForHost("raw.githubusercontent.com", "Custom/1.0"),
        )
    }

    @Test
    fun redactKeepsOnlyTailWithEllipsis() {
        val ua = "Mozilla/5.0 Chrome/122 TVBox/1.0.36"
        val result = UaScope.redact(ua)
        assertTrue(result.startsWith("…"))
        assertEquals(ua.takeLast(UaScope.RedactTailChars), result.removePrefix("…"))
        assertEquals("short", UaScope.redact("short"))
    }

    @Test
    fun probeScopeUsesDedicatedUaRegardlessOfModeAndDiffersFromPlayback() {
        val settings = AppSettings(
            uaScopeMode = "scoped",
            customUserAgent = "Global/1.0",
            uaForPlayback = "Playback/2.0",
        )
        assertTrue("probe" in UaScope.scopes)
        assertEquals("Playback/2.0", UaScope.resolve("playback", settings))
        assertEquals(UaScope.ProbeUserAgent, UaScope.resolve("probe", settings))
        assertNotEquals(UaScope.resolve("playback", settings), UaScope.resolve("probe", settings))
        // unified 模式下探针仍保持专用 UA，不复用用户自定义值。
        assertEquals(
            UaScope.ProbeUserAgent,
            UaScope.resolve("probe", settings.copy(uaScopeMode = "unified")),
        )
    }

    @Test
    fun exportIsRedactedByDefaultAndCanIncludeFullValues() {
        val settings = AppSettings(customUserAgent = "Secret/UA-123456", uaForApi = "ApiSecret/9")
        val redacted = UaScope.exportSensitive(settings)
        assertFalse(redacted.contains("Secret/UA-123456"))
        assertTrue(redacted.contains("<未设置>"))
        val full = UaScope.exportSensitive(settings, includeSensitive = true)
        assertTrue(full.contains("Secret/UA-123456"))
        assertTrue(full.contains("ApiSecret/9"))
    }
}
