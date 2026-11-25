package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEnvelopeTest {

    private fun envelope(vararg sections: Pair<String, DiagnosticSection>) =
        DiagnosticEnvelope(
            generatedAtMs = 1_700_000_000_000L,
            appVersion = "1.2.3",
            appVersionCode = 123,
            deviceModel = "Xiaomi TV",
            androidVersion = "11",
            sdkInt = 30,
            sections = sections.toMap(),
        )

    @Test
    fun `schemaVersion 默认等于 1`() {
        assertEquals(1, envelope().schemaVersion)
    }

    @Test
    fun `redactSection 将未脱敏 section 标记为已脱敏`() {
        val src = DiagnosticSection("崩溃日志", false, listOf("ok"))
        val out = DiagnosticRedactor.redactSection(src)
        assertTrue(out.redacted)
        assertFalse(src.redacted) // 原对象未被改
        assertEquals(listOf("ok"), out.lines)
    }

    @Test
    fun `redactLine 对含 token 的 URL 脱敏`() {
        val raw = "播放失败 url=https://cdn.example.com/vod.m3u8?token=SECRET123&u=abc"
        val safe = DiagnosticRedactor.redactLine(raw)
        assertFalse(safe.contains("SECRET123"))
        assertFalse(safe.contains("cdn.example.com/vod"))
    }

    @Test
    fun `redactLine 保留普通无敏感文本`() {
        assertEquals("解码失败", DiagnosticRedactor.redactLine("解码失败"))
    }

    @Test
    fun `preview 包含各 section 标题`() {
        val p = DiagnosticPreview.preview(
            envelope("crash" to DiagnosticSection("崩溃日志", true, listOf("...")))
        )
        assertTrue(p.any { it.contains("崩溃日志") })
    }

    @Test
    fun `preview 标注已脱敏 section`() {
        val p = DiagnosticPreview.preview(
            envelope("crash" to DiagnosticSection("崩溃日志", true, emptyList()))
        )
        assertTrue(p.any { it.contains("【已脱敏】") && it.contains("崩溃日志") })
    }

    @Test
    fun `toJson 含 schemaVersion 与 appVersion 字段名`() {
        val json = envelope().toJson()
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"appVersion\":\"1.2.3\""))
    }

    @Test
    fun `toJson 中含 token 的 URL 已脱敏`() {
        val e = envelope(
            "crash" to DiagnosticSection(
                "崩溃", true,
                listOf("url=https://x.example.com/a.m3u8?token=TOPSECRET"),
            ),
        )
        val json = e.toJson()
        assertFalse(json.contains("TOPSECRET"))
        assertFalse(json.contains("x.example.com/a"))
    }

    @Test
    fun `空 sections 时 preview 与 toJson 不抛异常`() {
        val e = envelope()
        assertTrue(DiagnosticPreview.preview(e).isNotEmpty())
        val json = e.toJson()
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun `generatedAtMs 可自定义且可确定性断言`() {
        assertEquals(1_700_000_000_000L, envelope().generatedAtMs)
    }

    @Test
    fun `envelope 是不可变 data class（copy 验证）`() {
        val a = envelope("k" to DiagnosticSection("s", false, listOf("x")))
        val b = a.copy(appVersion = "9.9.9")
        assertEquals("1.2.3", a.appVersion)
        assertEquals("9.9.9", b.appVersion)
        assertNotEquals(a, b)
    }
}
