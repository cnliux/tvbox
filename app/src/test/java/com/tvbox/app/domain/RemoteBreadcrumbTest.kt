package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3-23：64 帧环形面包屑的 JVM 单测。 */
class RemoteBreadcrumbTest {

    @Test
    fun `ring keeps only the newest sixty four frames`() {
        val breadcrumb = RemoteBreadcrumb()
        repeat(70) { breadcrumb.record(it.toLong(), "KEY", "n=$it") }
        assertEquals(64, breadcrumb.size())
        val all = breadcrumb.snapshot()
        assertEquals(64, all.size)
        assertEquals("n=69", all.first().detail)
        assertEquals("n=6", all.last().detail)
    }

    @Test
    fun `render is newest first with relative timestamps`() {
        val breadcrumb = RemoteBreadcrumb()
        breadcrumb.record(1_000L, "LIVE", "ch=CCTV1 line=2")
        breadcrumb.record(2_100L, "KEY", "DPAD_RIGHT")
        breadcrumb.record(2_500L, "FAIL", "E-202")
        val text = breadcrumb.render()
        assertTrue(text.startsWith("t-0ms FAIL E-202"))
        assertTrue(text.contains("t-0.4s KEY DPAD_RIGHT"))
        assertTrue(text.contains("t-1.5s LIVE"))
        assertEquals(3, text.lines().size)
    }

    @Test
    fun `drain returns snapshot then clears so a failure is recorded once`() {
        val breadcrumb = RemoteBreadcrumb()
        breadcrumb.record(1L, "A")
        breadcrumb.record(2L, "B")
        assertEquals(2, breadcrumb.drainForFailure().size)
        assertEquals(0, breadcrumb.size())
        assertEquals("（无面包屑）", breadcrumb.render())
    }

    @Test
    fun `tags and details are single line and bounded`() {
        val breadcrumb = RemoteBreadcrumb()
        breadcrumb.record(0L, "A\nB", "x".repeat(200) + "\nsecret")
        val frame = breadcrumb.snapshot().first()
        assertTrue(frame.tag.length <= 16)
        assertTrue(frame.detail.length <= 48)
        assertTrue(frame.tag.isNotBlank())
        assertTrue(frame.tag.none { it == '\n' })
        assertTrue(frame.detail.none { it == '\n' })
    }
}
