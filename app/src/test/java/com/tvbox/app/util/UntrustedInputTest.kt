package com.tvbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1-05：外部输入契约单测（弹幕 / DLNA）。 */
class UntrustedInputTest {
    @Test
    fun `sanitizeDanmaku truncates and strips control characters`() {
        val raw = "　" + "x".repeat(200) + "\n\u0001尾部"
        val out = UntrustedInput.sanitizeDanmaku(raw)
        assertEquals(80, out.length)
        assertFalse(out.contains('\n'))
        assertFalse(out.contains('\u0001'))
        assertTrue(out.startsWith("x"))
    }

    @Test
    fun `requireHttpUrl rejects non http schemes and local addresses`() {
        assertNull(UntrustedInput.requireHttpUrl("file:///data/data/com.tvbox.app/x"))
        assertNull(UntrustedInput.requireHttpUrl("content://media/1"))
        assertNull(UntrustedInput.requireHttpUrl("http://127.0.0.1:9978/live.flv"))
        assertNull(UntrustedInput.requireHttpUrl("http://169.254.169.254/latest/meta-data"))
        assertNull(UntrustedInput.requireHttpUrl("http://10.0.0.1/"))
    }

    @Test
    fun `requireHttpUrl accepts public http and https`() {
        assertEquals(
            "https://cdn.example.com/live.flv",
            UntrustedInput.requireHttpUrl("https://cdn.example.com/live.flv"),
        )
        assertEquals(
            "http://8.8.8.8/flv",
            UntrustedInput.requireHttpUrl("http://8.8.8.8/flv"),
        )
    }

    @Test
    fun `requireHttpUrl can explicitly allow loopback`() {
        assertTrue(UntrustedInput.requireHttpUrl("http://127.0.0.1:8080/", allowLoopback = true) != null)
    }

    private fun assertFalse(value: Boolean) {
        org.junit.Assert.assertFalse(value)
    }
}
