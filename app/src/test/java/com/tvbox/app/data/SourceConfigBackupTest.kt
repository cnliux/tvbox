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
}
