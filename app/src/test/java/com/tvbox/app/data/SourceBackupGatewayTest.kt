package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBackupGatewayTest {

    private val lines = listOf(
        ApiLine("a", "源A", listOf("https://a.test/api.php/provide/vod/")),
        ApiLine("b", "源B", listOf("https://b.test/api.php/provide/vod/")),
    )

    @Test
    fun exportProducesSanitizedPayload() {
        val result = SourceBackupGateway.export(lines, listOf("https://live.test/a.m3u"))
        assertTrue(result.allowed)
        assertNotNull(result.payload)
        assertEquals("备份不含密钥", result.message)
        assertFalse(SourceConfigBackup.hasSensitiveKeys(result.payload!!))
    }

    @Test
    fun parseRoundTripRestoresLinesAndLiveUrls() {
        val exported = SourceBackupGateway.export(
            lines, listOf("https://live.test/a.m3u"), order = listOf("b", "a"),
        ).payload!!
        val parsed = SourceBackupGateway.parse(exported)
        val payload = checkNotNull(parsed.payload)
        assertEquals(2, payload.lines.size)
        assertEquals(1, payload.liveUrls.size)
        assertEquals(listOf("b", "a"), payload.order)
        assertTrue(parsed.message.contains("预览"))
    }

    @Test
    fun malformedBackupReturnsReadableError() {
        val result = SourceBackupGateway.parse("{not json")
        assertNull(result.payload)
        assertTrue(result.message.contains("解析失败") || result.message.contains("格式"))
        val wrongFormat = SourceBackupGateway.parse("{\"format\":\"other\",\"version\":1}")
        assertNull(wrongFormat.payload)
        assertEquals("不是本应用的源配置备份文件", wrongFormat.message)
    }

    @Test
    fun emptyBackupIsRejected() {
        val raw = SourceConfigBackup.export(emptyList(), emptyList())
        val result = SourceBackupGateway.parse(raw)
        assertNull(result.payload)
        assertEquals("备份中没有可恢复的源", result.message)
    }

    @Test
    fun unknownSensitiveFieldsAreNotBroughtBack() {
        // 外部/旧格式备份（无 checksum，走兼容路径）携带敏感字段，解析时应被忽略。
        // 不使用 export() 再追加字段：那会使 checksum 失效并触发完整性拦截，与本用例意图不符。
        val raw = """
            {"format":"tvbox-source-backup","version":1,
             "sites":[{"id":"a","name":"源A","api":"https://a.test/api.php/provide/vod/","apiKey":"should-not-be-returned"},
                      {"id":"b","name":"源B","api":"https://b.test/api.php/provide/vod/"}],
             "lives":[],"order":[],"weights":{}}
        """.trimIndent()
        val parsed = SourceBackupGateway.parse(raw)
        assertNotNull(parsed.payload)
        assertEquals(2, parsed.payload!!.lines.size)
        assertTrue(parsed.message.contains("预览"))
    }

    @Test
    fun restoreOrderKeepsUnknownLinesAtTail() {
        val restored = SourceBackupGateway.restoreOrder(lines, listOf("b"))
        assertEquals(listOf("b", "a"), restored.map { it.id })
        assertEquals(listOf("a", "b"), SourceBackupGateway.restoreOrder(lines, emptyList()).map { it.id })
    }
}
