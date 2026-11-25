package com.tvbox.app.data.network

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-03：验证 [RedirectTrackingInterceptor] 链路真正调用了 mustWarn / mustRecomputeUa，
 * 且跨站/降级时按目标 host 重算请求头 UA。用假网络拦截器返回 3xx，不发起真实请求。
 */
class RedirectTrackingTest {

    private fun fakeResponse(request: Request, code: Int, location: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Found")
            .apply { if (location != null) header("Location", location) }
            .body("".toResponseBody())
            .build()

    /** 假网络：第一次请求返回 302 到 [redirectTo]，后续请求返回 200，并记录每次请求的 UA。 */
    private fun clientWithRedirect(
        tracker: RedirectTracker,
        redirectTo: String,
        uaForHost: (String) -> String = { "RECOMPUTED" },
        seenUas: MutableList<String>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            RedirectTrackingInterceptor(
                onRedirect = tracker::recordHop,
                uaForHost = uaForHost,
            ),
        )
        .addInterceptor(object : Interceptor {
            private var first = true

            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                seenUas.add(request.header("User-Agent").orEmpty())
                return if (first) {
                    first = false
                    fakeResponse(request, 302, redirectTo)
                } else {
                    fakeResponse(request, 200)
                }
            }
        })
        .build()

    @Test
    fun `跨站跳转累计告警与重算并改写下一跳UA`() {
        val tracker = RedirectTracker()
        val seenUas = mutableListOf<String>()
        val client = clientWithRedirect(tracker, "https://cdn.other.net/b", seenUas = seenUas)

        val response = client.newCall(
            Request.Builder()
                .url("https://a.example.com/a")
                .header("User-Agent", "ORIGINAL")
                .build(),
        ).execute()

        assertEquals(200, response.code)
        response.close()
        assertEquals(listOf("ORIGINAL", "RECOMPUTED"), seenUas)
        val stats = tracker.snapshot()
        assertEquals(1, stats.totalHops)
        assertEquals(1, stats.crossSiteHops)
        assertEquals(1, stats.warningCount)
        assertEquals(1, stats.recomputeUaCount)
    }

    @Test
    fun `同源跳转既不告警也不重算UA`() {
        val tracker = RedirectTracker()
        val seenUas = mutableListOf<String>()
        val recomputeCalls = AtomicInteger(0)
        val client = clientWithRedirect(
            tracker,
            "https://a.example.com/b",
            uaForHost = { host -> recomputeCalls.incrementAndGet(); "RECOMPUTED-$host" },
            seenUas = seenUas,
        )

        val response = client.newCall(
            Request.Builder().url("https://a.example.com/a").header("User-Agent", "ORIGINAL").build(),
        ).execute()

        assertEquals(200, response.code)
        response.close()
        assertEquals(listOf("ORIGINAL", "ORIGINAL"), seenUas)
        assertEquals(0, recomputeCalls.get())
        val stats = tracker.snapshot()
        assertEquals(1, stats.totalHops)
        assertEquals(0, stats.warningCount)
        assertEquals(0, stats.recomputeUaCount)
    }

    @Test
    fun `https降级到http计入降级告警与UA重算`() {
        val tracker = RedirectTracker()
        val seenUas = mutableListOf<String>()
        val client = clientWithRedirect(tracker, "http://a.example.com/b", seenUas = seenUas)

        val response = client.newCall(
            Request.Builder().url("https://a.example.com/a").header("User-Agent", "ORIGINAL").build(),
        ).execute()

        assertEquals(200, response.code)
        response.close()
        val stats = tracker.snapshot()
        assertEquals(1, stats.downgradeHops)
        assertEquals(1, stats.warningCount)
        assertEquals(1, stats.recomputeUaCount)
        assertEquals("RECOMPUTED", seenUas.last())
    }

    @Test
    fun `RedirectTracker按策略累计多跳告警`() {
        val tracker = RedirectTracker()
        tracker.recordHop(RedirectPolicy.Hop("https://a.example.com/a", "https://www.example.com/b"))
        tracker.recordHop(RedirectPolicy.Hop("https://www.example.com/b", "https://mirror.other.net/c"))
        tracker.recordHop(RedirectPolicy.Hop("https://mirror.other.net/c", "http://mirror.other.net/d"))
        val stats = tracker.snapshot()

        assertEquals(3, stats.totalHops)
        assertEquals(2, stats.warningCount) // 跨站 + 降级共两次，跨子域不告警
        assertEquals(3, stats.recomputeUaCount)
        assertEquals(1, stats.downgradeHops)
        assertEquals(1, stats.crossSiteHops)
        assertFalse(stats.warningCount == 3)
        assertTrue(stats.recomputeUaCount == 3)
    }
}
