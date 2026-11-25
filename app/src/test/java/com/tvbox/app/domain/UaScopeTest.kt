package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun redactKeepsOnlyTailWithEllipsis() {
        val ua = "Mozilla/5.0 Chrome/122 TVBox/1.0.36"
        val result = UaScope.redact(ua)
        assertTrue(result.startsWith("…"))
        assertEquals(ua.takeLast(UaScope.RedactTailChars), result.removePrefix("…"))
        assertEquals("short", UaScope.redact("short"))
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
