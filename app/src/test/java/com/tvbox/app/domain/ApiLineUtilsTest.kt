package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiLineUtilsTest {

    @Test
    fun orderApiLines_prefersSavedOrderAndAppendsUnknowns() {
        val all = listOf(line("a", "A"), line("b", "B"), line("c", "C"))
        val ordered = orderApiLines(all, preferredOrder = listOf("c", "a"))
        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun orderLinesByLatency_nullsLastStableByLatency() {
        val lines = listOf(line("slow", "S"), line("fast", "F"), line("fail", "X"))
        val latencies = mapOf("slow" to 500L, "fast" to 80L, "fail" to null)
        val ordered = orderLinesByLatency(lines, latencies)
        assertEquals(listOf("fast", "slow", "fail"), ordered.map { it.id })
    }

    @Test
    fun normalizeMacCmsApiUrl_trimsQueryFragmentAndEnsuresSlash() {
        assertEquals("https://example.com/api/", normalizeMacCmsApiUrl("  https://example.com/api?ac=list#x  "))
        assertEquals("https://example.com/", normalizeMacCmsApiUrl("https://example.com"))
        assertNull(normalizeMacCmsApiUrl("ftp://example.com/api"))
        assertNull(normalizeMacCmsApiUrl("http://"))
        assertNull(normalizeMacCmsApiUrl(""))
    }

    @Test
    fun customVideoApiLineId_isStableAndLowercaseNormalized() {
        val a = customVideoApiLineId("https://Example.com/Api/")
        val b = customVideoApiLineId("https://example.com/api/")
        assertEquals(a, b)
        assert(a.startsWith("custom_"))
    }
}

private fun line(id: String, name: String) = ApiLine(
    id = id,
    name = name,
    baseUrls = listOf("https://$id.test/"),
)
