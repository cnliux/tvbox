package com.tvbox.app.data.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T1-02：明文按域策略与出口守卫的 JVM 单测（不发起真实网络请求）。 */
class CleartextGuardTest {

    private fun guardedClient(guard: CleartextGuard): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(guard)
            // 终止拦截器：直接返回假响应，保证测试不触网。
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()

    private fun call(client: OkHttpClient, url: String): Response =
        client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun builtinHostsAreMatchedWithSubdomains() {
        assertTrue(CleartextPolicy.isBuiltinHost("douban.com"))
        assertTrue(CleartextPolicy.isBuiltinHost("img9.doubanio.com."))
        assertTrue(CleartextPolicy.isBuiltinHost("raw.githubusercontent.com"))
        assertTrue(CleartextPolicy.isBuiltinHost("GH-PROXY.ORG"))
        assertFalse(CleartextPolicy.isBuiltinHost("douban.com.evil.test"))
        assertFalse(CleartextPolicy.isBuiltinHost("my.iptv.example"))
        assertFalse(CleartextPolicy.isBuiltinHost(""))
    }

    @Test
    fun requiresHttpsOnlyForBuiltinHosts() {
        assertTrue(CleartextPolicy.requiresHttps("http://github.com/cnliux/tvbox/update.json"))
        assertTrue(CleartextPolicy.requiresHttps("https://douban.com/j/search_subjects"))
        assertFalse(CleartextPolicy.requiresHttps("http://192.168.1.1/live.m3u"))
        assertFalse(CleartextPolicy.requiresHttps("http://my-source.example/api.php"))
        assertFalse(CleartextPolicy.isBuiltinHost("") || CleartextPolicy.requiresHttps(":::"))
    }

    @Test
    fun guardBlocksCleartextToBuiltinDomain() {
        val guard = CleartextGuard()
        val client = guardedClient(guard)
        try {
            call(client, "http://douban.com/j/search_subjects")
            fail("内置域名明文请求应被拒绝")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("douban.com"))
        }
        assertEquals(0, guard.snapshot().cleartext)
        assertEquals(1, guard.snapshot().blocked)
        assertEquals(0, guard.snapshot().tls)
    }

    @Test
    fun guardAllowsCleartextForUserSourcesAndCountsTls() {
        val guard = CleartextGuard()
        val client = guardedClient(guard)
        call(client, "http://192.168.1.37:8899/live.m3u").close()
        call(client, "https://my-source.example/api.php").close()
        val stats = guard.snapshot()
        assertEquals(1, stats.cleartext)
        assertEquals(1, stats.tls)
        assertEquals(0, stats.blocked)
    }
}
