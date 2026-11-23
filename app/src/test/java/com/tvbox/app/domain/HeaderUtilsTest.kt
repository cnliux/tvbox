package com.tvbox.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HeaderUtilsTest {

    @Test
    fun `sanitizedForHttp 过滤空键空值与换行`() {
        val headers = mapOf(
            "User-Agent" to "ua",
            " " to "x",
            "Referer" to "",
            "Cookie" to "a\r\nb",
            "Origin" to "ok",
        )
        assertEquals(mapOf("User-Agent" to "ua", "Origin" to "ok"), headers.sanitizedForHttp())
    }

    @Test
    fun `partitionUserAgent 大小写不敏感且移除所有变体键`() {
        val (ua, rest) = mapOf("user-agent" to "ua1", "Referer" to "r").partitionUserAgent()
        assertEquals("ua1", ua)
        assertEquals(mapOf("Referer" to "r"), rest)
    }

    @Test
    fun `partitionUserAgent 无 UA 时原样返回`() {
        val headers = mapOf("Referer" to "r")
        val (ua, rest) = headers.partitionUserAgent()
        assertNull(ua)
        assertEquals(headers, rest)
    }

    @Test
    fun `空头部拆分安全`() {
        val (ua, rest) = emptyMap<String, String>().partitionUserAgent()
        assertNull(ua)
        assertTrue(rest.isEmpty())
    }
}

class SourceSpeedProbeTest {

    @Test
    fun `并发上限与结果完整性`() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val ids = (1..10).map { "line-$it" }
        val results = measureSourceLatencies(
            ids,
            parallelism = 2,
            test = { id ->
                val current = active.incrementAndGet()
                maxActive.updateAndGet { old -> maxOf(old, current) }
                delay(20)
                active.decrementAndGet()
                id.hashCode().toLong()
            },
        )
        assertEquals(ids.size, results.size)
        assertEquals(2, maxActive.get())
        ids.forEach { assertEquals(it.hashCode().toLong(), results[it]) }
    }

    @Test
    fun `测速失败记为 null 且计数照常推进`() = runBlocking {
        val results = measureSourceLatencies(
            listOf("a", "b"),
            test = { id -> if (id == "a") error("boom") else 5L },
        )
        assertNull(results["a"])
        assertEquals(5L, results["b"])
    }

    @Test
    fun `进度回调按完成数推进且不超过总数`() = runBlocking {
        val progress = mutableListOf<Int>()
        measureSourceLatencies(
            listOf("a", "b", "c"),
            test = { 1L },
            onResult = { _, _, completed, total ->
                assertEquals(3, total)
                progress.add(completed)
            },
        )
        assertEquals(listOf(1, 2, 3), progress.sorted())
    }

    @Test
    fun `取消异常立即透传不被吞成 null`() = runBlocking {
        try {
            withTimeout(50) {
                measureSourceLatencies(listOf("slow"), test = { delay(5_000); 1L })
            }
            org.junit.Assert.fail("应抛出取消")
        } catch (expected: CancellationException) {
            // withTimeout 触发的取消必须透传，而不是被 runCatching 吞成 null。
        }
        Unit
    }

    @Test
    fun `空列表直接返回空表`() = runBlocking {
        assertTrue(measureSourceLatencies(emptyList(), test = { 1L }).isEmpty())
    }
}
