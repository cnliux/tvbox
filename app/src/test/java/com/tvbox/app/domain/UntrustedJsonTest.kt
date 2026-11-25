package com.tvbox.app.domain

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5-34：24 个畸形响应语料的最小集，断言零抛出 + 默认值 + 无 `!!`。 */
class UntrustedJsonTest {

    @Serializable
    data class MovieDto(val name: String = "", val url: String = "")

    @Serializable
    data class VodDto(val list: List<MovieDto> = emptyList(), val page: Int = 1)

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/malformed/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun `valid payload with unknown future field still decodes`() {
        val result = UntrustedJson.decode(
            """{"list":[{"name":"演示","url":"https://e.example/1","future":123}],"page":2,"futureTop":true}""",
            VodDto.serializer(),
        )
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().list.size)
        assertEquals(2, result.getOrThrow().page)
    }

    @Test
    fun `null fields fall back to defaults instead of throwing`() {
        val dto = UntrustedJson.decodeOrDefault(resource("null_fields.json"), VodDto.serializer(), VodDto())
        assertTrue(dto.list.isEmpty())
        assertEquals(1, dto.page)
    }

    @Test
    fun `type drift fails as Result instead of crashing`() {
        val result = UntrustedJson.decode(resource("type_drift.json"), VodDto.serializer())
        assertTrue(result.isFailure)
        assertEquals(VodDto(), UntrustedJson.decodeOrDefault(resource("type_drift.json"), VodDto.serializer(), VodDto()))
    }

    @Test
    fun `html error page is rejected before decoding`() {
        val raw = resource("html_error.json")
        assertFalse(UntrustedJson.looksLikeJson(raw))
        assertTrue(UntrustedJson.decode(raw, VodDto.serializer()).isFailure)
    }

    @Test
    fun `bom prefixed json is accepted`() {
        val raw = resource("bom.json")
        assertTrue(UntrustedJson.looksLikeJson(raw))
        assertTrue(UntrustedJson.decode(raw, VodDto.serializer()).isSuccess)
    }

    @Test
    fun `empty and array payloads never throw`() {
        assertTrue(UntrustedJson.decode("", VodDto.serializer()).isFailure)
        assertTrue(UntrustedJson.decode("[]", VodDto.serializer()).isFailure)
        assertEquals(VodDto(), UntrustedJson.decodeOrDefault("", VodDto.serializer(), VodDto()))
    }
}
