package com.tvbox.app.ai

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiSearchRepositoryTest {

    private class FakeCompletion(
        private val content: String,
        var calls: Int = 0,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            calls++
            val body = """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    @Test
    fun missingApiKey_hasExplicitMessage() {
        val repo = AiSearchRepository(apiKey = "", client = fakeClient())
        try {
            runBlocking { repo.search("科幻") }
            fail("缺少 Key 时应失败")
        } catch (error: AiSearchException.MissingApiKey) {
            assertTrue(error.message.orEmpty().contains("构建期未注入 API Key"))
        }
        assertFalse(repo.apiKeyConfigured)
        assertTrue(repo.apiKeySourceLabel().contains("未注入"))
    }

    @Test
    fun disabledSetting_isRejected() {
        val repo = AiSearchRepository(apiKey = "test-key", client = fakeClient())
        try {
            runBlocking {
                repo.search("科幻", AiSearchConfig(enabled = false))
            }
            fail("开关关闭时应失败")
        } catch (_: AiSearchException.Disabled) {
            // 预期
        }
    }

    @Test
    fun parsesJsonCandidatesWithYear() {
        val fake = FakeCompletion(
            """[{\"title\":\"流浪地球\",\"year\":\"2019\"},{\"title\":\"星际穿越\",\"year\":\"2014\"}]""",
        )
        val repo = AiSearchRepository(apiKey = "test-key", client = fakeClient(fake))

        val result = runBlocking { repo.search("关于时间旅行的科幻片") }

        assertEquals(listOf("流浪地球", "星际穿越"), result.candidates.map { it.title })
        assertEquals(listOf("2019", "2014"), result.candidates.map { it.year })
        assertEquals(AiMissBehavior.Stay, result.missBehavior)
        assertFalse(result.fromCache)
    }

    @Test
    fun fallsBackToPlainTextLines() {
        // JSON 字符串内的换行必须是转义序列 \\n，模型返回后才会变成真正的两行。
        val fake = FakeCompletion("1. 流浪地球（2019）\\n2. 星际穿越 2014")
        val repo = AiSearchRepository(apiKey = "test-key", client = fakeClient(fake))

        val result = runBlocking { repo.search("科幻") }

        assertEquals(listOf("流浪地球", "星际穿越"), result.candidates.map { it.title })
        assertEquals("2014", result.candidates[1].year)
    }

    @Test
    fun cachesSuccessForConfiguredMinutes() {
        var now = 1_000L
        val fake = FakeCompletion("""[{\"title\":\"流浪地球\",\"year\":\"2019\"}]""")
        val repo = AiSearchRepository(
            apiKey = "test-key",
            client = fakeClient(fake),
            nowMs = { now },
        )
        val config = AiSearchConfig(cacheMinutes = 30)

        val first = runBlocking { repo.search("科幻", config) }
        val second = runBlocking { repo.search("科幻", config) }

        assertEquals(1, fake.calls)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        now += 31 * 60_000L
        runBlocking { repo.search("科幻", config) }
        assertEquals(2, fake.calls)
    }

    @Test
    fun zeroCacheMinutes_disablesCache() {
        val fake = FakeCompletion("""[{\"title\":\"流浪地球\",\"year\":\"2019\"}]""")
        val repo = AiSearchRepository(apiKey = "test-key", client = fakeClient(fake))

        runBlocking { repo.search("科幻", AiSearchConfig(cacheMinutes = 0)) }
        runBlocking { repo.search("科幻", AiSearchConfig(cacheMinutes = 0)) }

        assertEquals(2, fake.calls)
    }

    @Test
    fun emptyCandidates_keepMissBehaviorWithoutException() {
        val fake = FakeCompletion("[]")
        val repo = AiSearchRepository(apiKey = "test-key", client = fakeClient(fake))

        val result = runBlocking {
            repo.search("不存在的片名", AiSearchConfig(missBehavior = "search"))
        }

        assertTrue(result.candidates.isEmpty())
        assertEquals(AiMissBehavior.Search, result.missBehavior)
    }

    @Test
    fun redactionRemovesPersonalInfoButKeepsTitle() {
        val raw = "我之前看过流浪地球 电话 13800138000 邮箱 me@example.com https://a.b/c 主演：张三"
        val redacted = redactUserText(raw, enabled = true)

        assertTrue(redacted.contains("流浪地球"))
        assertFalse(redacted.contains("13800138000"))
        assertFalse(redacted.contains("me@example.com"))
        assertFalse(redacted.contains("https://"))
        assertFalse(redacted.contains("张三"))
        assertEquals(raw.trim(), redactUserText(raw, enabled = false))
    }

    private fun fakeClient(interceptor: Interceptor = FakeCompletion("[]")): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()
}
