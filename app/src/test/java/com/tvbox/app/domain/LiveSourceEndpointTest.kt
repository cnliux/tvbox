package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSourceEndpointTest {

    @Test
    fun `无参数地址原样返回`() {
        val endpoint = parseLiveSourceEndpoint("http://host/iptv.m3u")
        assertEquals("http://host/iptv.m3u", endpoint.url)
        assertTrue(endpoint.headers.isEmpty())
    }

    @Test
    fun `User-Agent 参数被解析并归一化键名`() {
        val endpoint = parseLiveSourceEndpoint("http://host/iptv.m3u|User-Agent=okhttp/4.9")
        assertEquals("http://host/iptv.m3u", endpoint.url)
        assertEquals(mapOf("User-Agent" to "okhttp/4.9"), endpoint.headers)
    }

    @Test
    fun `UA 简写与 User-Agent 等价`() {
        val endpoint = parseLiveSourceEndpoint("http://host/live.txt|UA=VLC/3.0.20")
        assertEquals("VLC/3.0.20", endpoint.headers["User-Agent"])
    }

    @Test
    fun `多参数与大小写不敏感`() {
        val endpoint = parseLiveSourceEndpoint(
            "http://host/iptv.m3u|user-agent=UA1|Referer=http://host/|Cookie=a=1",
        )
        assertEquals("http://host/iptv.m3u", endpoint.url)
        assertEquals(
            mapOf(
                "User-Agent" to "UA1",
                "Referer" to "http://host/",
                "Cookie" to "a=1",
            ),
            endpoint.headers,
        )
    }

    @Test
    fun `重复键取最后一次`() {
        val endpoint = parseLiveSourceEndpoint("http://host/x.m3u|ua=first|ua=second")
        assertEquals("second", endpoint.headers["User-Agent"])
    }

    @Test
    fun `含换行的参数值被丢弃`() {
        val endpoint = parseLiveSourceEndpoint("http://host/x.m3u|ua=a\rb|referer=ok")
        assertFalse(endpoint.headers.containsKey("User-Agent"))
        assertEquals("ok", endpoint.headers["Referer"])
    }

    @Test
    fun `非 http 前缀不按参数处理`() {
        val endpoint = parseLiveSourceEndpoint("rtsp://host/stream|ua=x")
        assertEquals("rtsp://host/stream|ua=x", endpoint.url)
        assertTrue(endpoint.headers.isEmpty())
    }

    @Test
    fun `空串与孤悬竖线安全返回`() {
        assertEquals("", parseLiveSourceEndpoint("").url)
        val endpoint = parseLiveSourceEndpoint("http://host/x.m3u|")
        // 没解析出合法参数时不截断真实地址（URL 可能自带未编码的 |）。
        assertEquals("http://host/x.m3u|", endpoint.url)
        assertTrue(endpoint.headers.isEmpty())
    }

    @Test
    fun `竖线后无合法参数时整串原样返回`() {
        val raw = "http://host/play?id=a|b&c=1"
        val endpoint = parseLiveSourceEndpoint(raw)
        assertEquals(raw, endpoint.url)
        assertTrue(endpoint.headers.isEmpty())
    }

    @Test
    fun `withFallbackUserAgent 已有 UA 时不覆盖`() {
        val headers = mapOf("user-agent" to "line-ua")
        val merged = headers.withFallbackUserAgent("global-ua")
        assertEquals("line-ua", merged["user-agent"])
        assertFalse(merged.containsKey("User-Agent"))
    }

    @Test
    fun `withFallbackUserAgent 缺 UA 时补齐`() {
        val merged = mapOf("Referer" to "http://h/").withFallbackUserAgent("global-ua")
        assertEquals("global-ua", merged["User-Agent"])
        assertEquals("http://h/", merged["Referer"])
    }
}

class UserAgentSettingTest {

    @Test
    fun `空配置回退内置默认`() {
        assertEquals(UserAgentSetting.Default, UserAgentSetting.resolve(""))
        assertEquals(UserAgentSetting.Default, UserAgentSetting.resolve("   "))
    }

    @Test
    fun `清洗剔除换行与首尾空白`() {
        assertEquals("okhttp/4.12", UserAgentSetting.sanitize(" okhttp/4.12\n"))
        assertEquals("abc", UserAgentSetting.sanitize("a\rb\nc"))
    }

    @Test
    fun `resolve 同样清洗换行`() {
        assertEquals("ua1", UserAgentSetting.resolve("ua1\n"))
    }

    @Test
    fun `预设包含内置默认且无换行`() {
        assertTrue(UserAgentSetting.presets.any { it.second == UserAgentSetting.Default })
        UserAgentSetting.presets.forEach { (_, ua) ->
            assertFalse(ua.contains('\n'))
            assertFalse(ua.contains('\r'))
        }
    }

    @Test
    fun `effectiveUserAgent 空配置取默认`() {
        assertEquals(UserAgentSetting.Default, AppSettings().effectiveUserAgent())
    }

    @Test
    fun `健康管理默认开启`() {
        assertTrue(AppSettings().sourceHealthCheckEnabled)
        assertEquals(90_000L, SourceHealthSetting.InitialDelayMs)
        assertEquals(30L * 60_000L, SourceHealthSetting.IntervalMs)
        assertEquals(3, SourceHealthSetting.Parallelism)
    }
}
