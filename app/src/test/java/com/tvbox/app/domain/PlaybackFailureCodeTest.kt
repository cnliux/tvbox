package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3-22：结构化失败码的四处同源契约（JVM 单测，无 Android 依赖）。 */
class PlaybackFailureCodeTest {

    @Test
    fun `every code is unique and follows E digit pattern`() {
        val all = PlaybackFailureCode.entries
        assertEquals(all.size, all.map { it.code }.distinct().size)
        all.forEach { assertTrue(it.code.matches(Regex("E-[1-5][0-9]{2}"))) }
    }

    @Test
    fun `describeFailure is exhaustive for every declared code`() {
        PlaybackFailureCode.entries.forEach { code ->
            val presentation = describeFailure(code)
            assertEquals(code.code, presentation.code)
            assertEquals(code.title, presentation.title)
            assertEquals(code.origin, presentation.origin)
            assertEquals(code.remedy, presentation.remedy)
            assertTrue(presentation.userHint.isNotBlank())
        }
    }

    @Test
    fun `http status maps to documented codes`() {
        assertEquals(PlaybackFailureCode.ForbiddenOrGone, PlaybackFailureCode.fromHttpStatus(403))
        assertEquals(PlaybackFailureCode.ForbiddenOrGone, PlaybackFailureCode.fromHttpStatus(404))
        assertEquals(PlaybackFailureCode.RateLimited, PlaybackFailureCode.fromHttpStatus(429))
        assertEquals(PlaybackFailureCode.ConnectionRefused, PlaybackFailureCode.fromHttpStatus(503))
        assertNull(PlaybackFailureCode.fromHttpStatus(200))
    }

    @Test
    fun `share text is one line and carries no raw url or title`() {
        val report = PlaybackFailureReport(
            code = PlaybackFailureCode.ForbiddenOrGone,
            siteFingerprint = "https://secret.example/订阅?token=abc",
            lineLabel = "线路2\n第二行",
            deviceTag = "RK3368",
        )
        val text = report.shareText
        assertTrue(text.startsWith("E-202 · ▪"))
        assertTrue(text.contains("线路2 第二行"))
        assertTrue(text.contains("RK3368"))
        assertFalse(text.contains("secret.example"))
        assertFalse(text.contains("\n"))
        assertTrue(text.length < 80)
    }

    @Test
    fun `empty optional fields do not produce separators`() {
        assertEquals("E-101", PlaybackFailureReport(PlaybackFailureCode.ParseFailed).shareText)
        assertEquals("E-101 · ▪4f2a9c11", PlaybackFailureReport(
            PlaybackFailureCode.ParseFailed,
            siteFingerprint = "4F2A9C11",
        ).shareText)
    }

    @Test
    fun `rate limit remediation is cooldown and local failures are local origin`() {
        assertEquals(FailureRemedy.CooldownSource, PlaybackFailureCode.RateLimited.remedy)
        assertEquals(FailureRemedy.RetryWithUa, PlaybackFailureCode.ForbiddenOrGone.remedy)
        assertEquals(FailureOrigin.LOCAL, PlaybackFailureCode.LocalOom.origin)
        assertEquals(FailureOrigin.LOCAL, PlaybackFailureCode.LocalProxy.origin)
        assertEquals(FailureOrigin.REMOTE, PlaybackFailureCode.DrmLocked.origin)
    }
}
