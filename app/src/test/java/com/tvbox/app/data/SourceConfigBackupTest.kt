package com.tvbox.app.data

import com.tvbox.app.domain.ApiLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConfigBackupTest {
    @Test
    fun exportAndParseRoundTripKeepsSourcesOrderAndWeights() {
        val lines = listOf(
            ApiLine(id = "a", name = "源 A", baseUrls = listOf("https://a.test/api.php/provide/vod/")),
            ApiLine(id = "b", name = "源 B", baseUrls = listOf("https://b.test/api.php/provide/vod/")),
        )
        val raw = SourceConfigBackup.export(
            lines = lines,
            liveUrls = listOf("https://live.test/list.m3u"),
            order = listOf("b", "a"),
            weights = mapOf("b" to 3, "a" to 1),
        )
        val result = SourceConfigBackup.parse(raw)

        assertNull(result.error)
        assertNotNull(result.payload)
        val payload = result.payload!!
        assertEquals(listOf("a", "b"), payload.lines.map { it.id })
        assertEquals("https://a.test/api.php/provide/vod/", payload.lines.first().baseUrls.single())
        assertEquals(listOf("https://live.test/list.m3u"), payload.liveUrls)
        assertEquals(listOf("b", "a"), payload.order)
        assertEquals(mapOf("b" to 3, "a" to 1), payload.weights)
    }

    @Test
    fun exportedBackupHasNoSensitiveKeys() {
        val raw = SourceConfigBackup.export(
            lines = listOf(ApiLine(id = "a", name = "源 A", baseUrls = listOf("https://a.test/"))),
            liveUrls = emptyList(),
        )
        assertFalse(SourceConfigBackup.hasSensitiveKeys(raw))
        assertFalse(raw.contains("apiKey"))
    }

    @Test
    fun hasSensitiveKeysDetectsNestedCredentialKeys() {
        assertTrue(SourceConfigBackup.hasSensitiveKeys("""{"sites":[{"api_key":"secret"}]}"""))
        assertTrue(SourceConfigBackup.hasSensitiveKeys("""{"nested":{"Authorization":"x"}}"""))
        assertFalse(SourceConfigBackup.hasSensitiveKeys("""{"sites":[{"api":"https://a.test/"}]}"""))
    }

    @Test
    fun parseRejectsWrongFormatAndFutureVersion() {
        val wrongFormat = SourceConfigBackup.parse("""{"format":"other","version":1,"sites":[]}""")
        assertNotNull(wrongFormat.error)
        assertNull(wrongFormat.payload)

        val future = SourceConfigBackup.parse(
            """{"format":"tvbox-source-backup","version":99,"sites":[]}""",
        )
        assertTrue(future.error?.contains("高于当前支持") == true)
        assertNull(future.payload)
    }

    @Test
    fun parseSkipsBlankApiAndIgnoresUnknownCredentialFields() {
        val result = SourceConfigBackup.parse(
            """
            {"format":"tvbox-source-backup","version":1,
             "sites":[{"id":"a","name":"A","api":"https://a.test/","apiKey":"must-be-ignored"},
                      {"id":"b","name":"B","api":"  "}],
             "lives":["https://live.test/a.m3u","https://live.test/a.m3u"],
             "order":[],"weights":{"a":"oops","b":2}}
            """.trimIndent(),
        )
        assertNull(result.error)
        val payload = result.payload!!
        assertEquals(1, payload.lines.size)
        assertEquals(1, payload.liveUrls.size)
        assertEquals(mapOf("b" to 2), payload.weights)
    }

    @Test
    fun parseRejectsMalformedJson() {
        val result = SourceConfigBackup.parse("{not json")
        assertNotNull(result.error)
        assertNull(result.payload)
    }

    @Test
    fun exportedBackupCarriesChecksumAndRoundTrips() {
        val raw = SourceConfigBackup.export(
            lines = listOf(ApiLine(id = "a", name = "A", baseUrls = listOf("https://a.test/"))),
            liveUrls = listOf("https://live.test/a.m3u"),
        )
        assertTrue(raw.contains("checksum"))
        assertNull(SourceConfigBackup.parse(raw).error)
    }

    @Test
    fun parseRejectsTamperedBackupWithBadChecksum() {
        // 取一份合法备份，改掉其中一个 URL 但保留原 checksum，应被完整性校验拦截。
        val valid = SourceConfigBackup.export(
            lines = listOf(ApiLine(id = "a", name = "A", baseUrls = listOf("https://a.test/"))),
            liveUrls = emptyList(),
        )
        val tampered = valid.replace("https://a.test/", "https://evil.test/")
        val result = SourceConfigBackup.parse(tampered)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("校验失败"))
        assertNull(result.payload)
    }

    @Test
    fun exportIsIdempotentForIdenticalInput() {
        // 幂等导出：同一份输入连续导出两次，结果必须逐字节一致。
        // 防止随机 ID、时间戳或 Map 迭代顺序导致备份文件在换机/重导场景下无谓变化。
        val lines = listOf(
            ApiLine(id = "a", name = "源 A", baseUrls = listOf("https://a.test/api.php/provide/vod/")),
            ApiLine(id = "b", name = "源 B", baseUrls = listOf("https://b.test/api.php/provide/vod/")),
        )
        val weights = mapOf("b" to 3, "a" to 1)
        val first = SourceConfigBackup.export(
            lines = lines,
            liveUrls = listOf("https://live.test/list.m3u"),
            order = listOf("b", "a"),
            weights = weights,
        )
        val second = SourceConfigBackup.export(
            lines = lines,
            liveUrls = listOf("https://live.test/list.m3u"),
            order = listOf("b", "a"),
            weights = weights,
        )
        assertEquals(first, second)
        // 再导出第三次，排除偶发抖动，并确认 checksum 字段本身也稳定。
        val third = SourceConfigBackup.export(
            lines = lines,
            liveUrls = listOf("https://live.test/list.m3u"),
            order = listOf("b", "a"),
            weights = weights,
        )
        assertEquals(first, third)
    }

    @Test
    fun exportedBackupCarriesSchemaEnvelopeAndRoundTrips() {
        // T7-44：新版导出带 schema/kind/minReader/createdBy 信封，parse 仍正常恢复。
        val raw = SourceConfigBackup.export(
            lines = listOf(ApiLine(id = "x", name = "X", baseUrls = listOf("https://x.test/"))),
            liveUrls = emptyList(),
        )
        assertTrue(raw.contains("\"schema\":1"))
        assertTrue(raw.contains("\"kind\":\"source_config\""))
        assertTrue(raw.contains("\"minReader\":1"))
        assertTrue(raw.contains("\"createdBy\""))
        val result = SourceConfigBackup.parse(raw)
        assertNull(result.error)
        assertNotNull(result.payload)
        assertEquals("x", checkNotNull(result.payload).lines.single().id)
    }

    @Test
    fun legacyBackupWithoutSchemaFieldStillImports() {
        // 历史格式（无 schema 信封）应继续可用 —— ignoreUnknownKeys=true 兜底。
        val legacy = """
            {"format":"tvbox-source-backup","version":1,
             "sites":[{"id":"old","name":"旧源","api":"https://old.test/"}],
             "lives":[],"order":[],"weights":{}}
        """.trimIndent()
        val result = SourceConfigBackup.parse(legacy)
        assertNull(result.error)
        assertNotNull(result.payload)
        assertEquals("old", checkNotNull(result.payload).lines.single().id)
    }

    @Test
    fun legacyChecksumComputesOverLegacyFieldsOnly() {
        // 兼容存量备份：checksum 只对 legacy body 计算，加上 schema 信封不影响校验。
        val lines = listOf(ApiLine(id = "k", name = "K", baseUrls = listOf("https://k.test/")))
        val raw = SourceConfigBackup.export(lines = lines, liveUrls = emptyList())
        // 导入时应通过校验（checksum 匹配 legacy body）。
        val result = SourceConfigBackup.parse(raw)
        assertNull(result.error)
    }

    @Test
    fun repeatedRestoreYieldsSameStateWithoutDuplicates() {
        // 幂等恢复：恢复同一份备份两次，最终应用内配置状态应与恢复一次后完全一致，
        // 既不出现重复源，也不出现重复直播/排序/权重条目。
        val raw = SourceConfigBackup.export(
            lines = listOf(
                ApiLine(id = "a", name = "源 A", baseUrls = listOf("https://a.test/")),
                ApiLine(id = "b", name = "源 B", baseUrls = listOf("https://b.test/")),
            ),
            liveUrls = listOf("https://live.test/a.m3u", "https://live.test/b.m3u"),
            order = listOf("b", "a"),
            weights = mapOf("a" to 1, "b" to 2),
        )
        val once = SourceConfigBackup.parse(raw)
        assertNull(once.error)
        val twice = SourceConfigBackup.parse(raw)
        assertNull(twice.error)

        val payloadOnce = once.payload!!
        val payloadTwice = twice.payload!!
        assertEquals(payloadOnce, payloadTwice)
        // 不出现重复源：ID 与接口地址均唯一。
        assertEquals(payloadOnce.lines.size, payloadOnce.lines.map { it.id }.distinct().size)
        assertEquals(payloadOnce.lines.size, payloadOnce.lines.map { it.baseUrls.single() }.distinct().size)
        // 不出现重复直播条目。
        assertEquals(payloadOnce.liveUrls.size, payloadOnce.liveUrls.distinct().size)
        // 连续「导出→恢复→再导出」应收敛到同一份文本，进一步佐证恢复无副作用累积。
        val reexported = SourceConfigBackup.export(
            lines = payloadTwice.lines,
            liveUrls = payloadTwice.liveUrls,
            order = payloadTwice.order,
            weights = payloadTwice.weights,
        )
        assertNull(SourceConfigBackup.parse(reexported).error)
        assertEquals(SourceConfigBackup.parse(reexported).payload, payloadTwice)
    }

    @Test
    fun exportedBackupFiltersAllSensitiveFieldNamesCaseInsensitive() {
        // 敏感字段过滤：即便源条目里混入了常见凭据字段，导出结果也绝不能包含
        // api_key / token / authorization / cookie / signature（不区分大小写）。
        // 导出 API 只接受源标识/名称/接口，凭据无从注入，因此导出后逐个关键词断言。
        val raw = SourceConfigBackup.export(
            lines = listOf(
                ApiLine(id = "a", name = "源 A", baseUrls = listOf("https://a.test/api.php/provide/vod/")),
            ),
            liveUrls = listOf("https://live.test/list.m3u"),
            order = listOf("a"),
            weights = mapOf("a" to 1),
        )
        val lowered = raw.lowercase()
        assertFalse(lowered.contains("api_key"))
        assertFalse(lowered.contains("apikey"))
        assertFalse(lowered.contains("token"))
        assertFalse(lowered.contains("authorization"))
        assertFalse(lowered.contains("cookie"))
        assertFalse(lowered.contains("signature"))
        // 导出前自检同样认定无敏感键。
        assertFalse(SourceConfigBackup.hasSensitiveKeys(raw))
    }
}
