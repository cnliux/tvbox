package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-04：HEAD/Range 探测产出的时长可信度，对齐文档三条黄金响应。 */
class DurationConfidenceTest {

    @Test
    fun `partial content with content range is high confidence`() {
        val shape = ResponseShape(
            status = 206,
            contentRange = "bytes 0-65535/10485760",
            contentLength = 65_536L,
        )
        assertEquals(DurationConfidence.HIGH, durationConfidence(shape))
        assertTrue(contributesToScore(DurationConfidence.HIGH))
    }

    @Test
    fun `chunked response has no confidence`() {
        val shape = ResponseShape(
            status = 200,
            transferEncoding = "chunked",
        )
        assertEquals(DurationConfidence.NONE, durationConfidence(shape))
        assertFalse(contributesToScore(DurationConfidence.NONE))
    }

    @Test
    fun `negative or missing content length has no confidence`() {
        assertEquals(
            DurationConfidence.NONE,
            durationConfidence(ResponseShape(200, contentLength = -1L)),
        )
        assertEquals(DurationConfidence.NONE, durationConfidence(ResponseShape(200)))
    }

    @Test
    fun `plain content length is low confidence and only for estimation`() {
        val confidence = durationConfidence(ResponseShape(200, contentLength = 1_024L))
        assertEquals(DurationConfidence.LOW, confidence)
        assertTrue(contributesToScore(confidence))
    }

    @Test
    fun `malformed content range never guesses`() {
        assertEquals(
            DurationConfidence.NONE,
            durationConfidence(ResponseShape(206, contentRange = "bytes 0-65535/*")),
        )
        assertEquals(
            DurationConfidence.NONE,
            durationConfidence(ResponseShape(206, contentRange = "bytes")),
        )
    }
}
