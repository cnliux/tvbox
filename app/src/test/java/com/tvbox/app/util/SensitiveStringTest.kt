package com.tvbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveStringTest {

    // ---------- 委派任务新增：包装类型契约 ----------

    @Test
    fun `toString 不含原始值`() {
        val rawValue = "我的秘密片名全称"
        val rendered = sensitive(rawValue)!!.toString()
        assertFalse("toString 泄漏原始值: $rendered", rendered.contains(rawValue))
    }

    @Test
    fun `toString 含长度标记`() {
        val rawValue = "我的秘密片名全称"
        val rendered = sensitive(rawValue)!!.toString()
        assertTrue("toString 缺少长度标记: $rendered", rendered.contains("[len=${rawValue.length}]"))
    }

    @Test
    fun `reveal 返回原始值`() {
        val rawValue = "https://example.com/path?token=abc"
        assertEquals(rawValue, sensitive(rawValue)!!.reveal())
    }

    // ---------- 委派任务新增：StructuredLog 结构化脱敏 ----------

    @Test
    fun `StructuredLog MediaName 不含完整原始片名`() {
        val rawName = "漫长的季节完整片名"
        // 直接断言 event 的返回值，不依赖 stdout 捕获（单测环境 Log 可能不可用）。
        val line = StructuredLog.event(LogField.MediaName, sensitive(rawName), "开始播放")
        assertFalse("日志泄漏完整片名: $line", line.contains(rawName))
        assertTrue("日志应保留前 4 字符: $line", line.contains(rawName.take(4)))
        assertTrue("日志应含字段名: $line", line.contains("field=MediaName"))
    }

    @Test
    fun `StructuredLog AccessToken 完全遮蔽`() {
        val token = "sk-abcdef1234567890"
        val line = StructuredLog.event(LogField.AccessToken, sensitive(token), "请求鉴权")
        assertFalse("日志泄漏 token 前缀: $line", line.contains(token.take(4)))
        assertFalse("日志泄漏完整 token: $line", line.contains(token))
        assertTrue("日志应标记长度: $line", line.contains("[len=${token.length}]"))
    }

    @Test
    fun `StructuredLog ConfigUrl 完全遮蔽`() {
        val url = "https://config.example.com/box.json"
        val line = StructuredLog.event(LogField.ConfigUrl, sensitive(url), "拉取订阅")
        assertFalse("日志泄漏 config 前缀: $line", line.contains(url.take(4)))
        assertFalse("日志泄漏完整 config 地址: $line", line.contains(url))
        assertTrue("日志应标记长度: $line", line.contains("[len=${url.length}]"))
    }

    @Test
    fun `StructuredLog null value 不抛异常`() {
        val line = StructuredLog.event(LogField.MediaUrl, null, "地址缺失")
        assertTrue("null 应渲染为占位符: $line", line.contains("value=<空>"))
    }

    @Test
    fun `LogField 枚举覆盖方案点名字段`() {
        val expected = setOf(
            LogField.MediaName,
            LogField.MediaUrl,
            LogField.UserAgent,
            LogField.AccessToken,
            LogField.ConfigUrl,
        )
        assertTrue("LogField 缺少方案点名字段: ${LogField.values().toList()}",
            expected.all { it in LogField.values().toSet() })
        assertNotNull(LogField.valueOf("MediaName"))
        assertNotNull(LogField.valueOf("MediaUrl"))
        assertNotNull(LogField.valueOf("UserAgent"))
        assertNotNull(LogField.valueOf("AccessToken"))
        assertNotNull(LogField.valueOf("ConfigUrl"))
    }

    // ---------- 原有测试（保留向后兼容） ----------

    @Test
    fun `toString 脱敏后不含 query 中的 token 原文`() {
        val value = sensitive("https://example.com/path?token=abc")!!
        val rendered = value.toString()
        assertFalse("toString 泄漏 token=abc: $rendered", rendered.contains("token=abc"))
    }

    @Test
    fun `sensitive 工厂对 null 与空白返回 null`() {
        assertNull(sensitive(null))
        assertNull(sensitive(""))
        assertNull(sensitive("   "))
    }

    @Test
    fun `logSafe 等价于 logSafeUrl`() {
        val url = "http://user:pwd@host.example:8080/a/b?token=xyz"
        val expected = logSafeUrl(url)
        val actual = sensitive(url)!!.logSafe()
        assertEquals(expected, actual)
        assertFalse(actual.contains("token=xyz"))
    }

    @Test
    fun `logSafeText 等价于 logSafeText 函数`() {
        val text = "播放失败 msg=http://a.example/x?token=zzz, code=1"
        val expected = logSafeText(text)
        val actual = sensitive(text)!!.logSafeText()
        assertEquals(expected, actual)
        assertFalse(actual.contains("token=zzz"))
    }

    @Test
    fun `orEmptyForLog 空值返回占位符`() {
        assertEquals("<空>", sensitive(null).orEmptyForLog())
        assertEquals("<空>", sensitive("   ").orEmptyForLog())
        val shown = sensitive("https://ok.example/v?token=1")!!.orEmptyForLog()
        assertTrue(shown.contains("ok.example"))
        assertFalse(shown.contains("token=1"))
    }

    @Test
    fun `SensitiveLog event 是 smoke test 不抛异常`() {
        SensitiveLog.event(SensitiveField.SourceUrl, sensitive("http://x/y"))
        SensitiveLog.event(SensitiveField.Authorization, null)
        SensitiveLog.event(SensitiveField.Custom, sensitive("   "))
    }

    @Test
    fun `SensitiveField 枚举保留向后兼容字段名`() {
        val expected = setOf(
            SensitiveField.Authorization,
            SensitiveField.Cookie,
            SensitiveField.Token,
            SensitiveField.ApiKey,
            SensitiveField.SourceUrl,
            SensitiveField.Ua,
            SensitiveField.Phone,
            SensitiveField.RoomId,
            SensitiveField.Custom
        )
        assertEquals(expected, SensitiveField.values().toSet())
        assertNotNull(SensitiveField.valueOf("SourceUrl"))
    }

    /** 捕获 StructuredLog 在 JVM 测试环境下通过 println 输出的那一行日志。 */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        val printer = java.io.PrintStream(buffer, true, Charsets.UTF_8)
        return try {
            System.setOut(printer)
            block()
            printer.flush()
            buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}