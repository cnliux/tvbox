package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDiagnosticsTest {

    @Test
    fun classifiesDnsFailure() {
        assertEquals(
            SubscriptionErrorKind.Dns,
            classifySubscriptionError("Unable to resolve host: example.com").kind,
        )
    }

    @Test
    fun classifiesTimeout() {
        assertEquals(SubscriptionErrorKind.Timeout, classifySubscriptionError("connect timed out").kind)
    }

    @Test
    fun classifiesHttpFailure() {
        assertEquals(SubscriptionErrorKind.Http, classifySubscriptionError("bad response", httpStatus = 503).kind)
    }

    @Test
    fun classifiesHtmlAsNonJson() {
        assertEquals(
            SubscriptionErrorKind.NotJson,
            classifySubscriptionError(null, payload = "<html>404</html>").kind,
        )
    }

    @Test
    fun base64ShellIsNotTreatedAsHtml() {
        assertEquals(
            SubscriptionErrorKind.Unknown,
            classifySubscriptionError(null, payload = "XXXXXXXX**abcdef").kind,
        )
    }

    @Test
    fun detectsInvalidSchema() {
        assertEquals(
            SubscriptionErrorKind.InvalidSchema,
            classifySubscriptionError(null, payload = "{\"foo\":1}").kind,
        )
    }

    @Test
    fun failureKeepsPreviousConfig() {
        assertTrue(classifySubscriptionError("timeout").keepPreviousConfig)
    }

    @Test
    fun previewListsSkippedEntriesWithReasons() {
        val preview = validateSourceEntries(
            listOf(
                mapOf("name" to "源A", "type" to "maccms", "url" to "https://a.example"),
                mapOf("name" to "", "type" to "json", "url" to ""),
                mapOf("name" to "源C", "type" to "json", "url" to "ftp://c.example"),
            ),
        )
        assertEquals(1, preview.acceptedCount)
        assertEquals("源A", preview.accepted[0].name)
        assertEquals(2, preview.skipped.size)
        assertEquals("缺少名称", preview.skipped[0].reason)
        assertEquals("接口地址需为 http/https", preview.skipped[1].reason)
    }

    @Test
    fun unsupportedTypeIsRejected() {
        val preview = validateSourceEntries(
            listOf(mapOf("name" to "源", "type" to "weird", "url" to "https://a.example")),
        )
        assertEquals(0, preview.acceptedCount)
        assertTrue(preview.skipped[0].reason.contains("不支持的类型"))
    }
}
