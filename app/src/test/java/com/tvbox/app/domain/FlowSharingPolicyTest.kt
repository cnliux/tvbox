package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T5-37：TV 专属共享参数与焦点记忆的 JVM 单测。 */
class FlowSharingPolicyTest {

    @Test
    fun `tv keeps the flow alive far longer than mobile`() {
        assertEquals(15L * 60 * 1_000L, FlowSharingPolicy.stopTimeoutMs(isTv = true))
        assertEquals(5L * 1000L, FlowSharingPolicy.stopTimeoutMs(isTv = false))
        assertEquals(Long.MAX_VALUE, FlowSharingPolicy.REPLAY_EXPIRE_MS)
        assertEquals("9223372036854775807", FlowSharingPolicy.REPLAY_EXPIRE_MS.toString())
    }

    @Test
    fun `focus memory saves loads and overwrites per route`() {
        val memory = FlowSharingPolicy.FocusMemory()
        assertNull(memory.load("home"))
        memory.save("home", FlowSharingPolicy.FocusAnchor("row-3", 7, 240))
        assertEquals(FlowSharingPolicy.FocusAnchor("row-3", 7, 240), memory.load("home"))
        memory.save("home", FlowSharingPolicy.FocusAnchor("row-4", 1, 0))
        assertEquals(1, memory.size())
        assertEquals(1, memory.load("home")!!.index)
    }

    @Test
    fun `focus memory evicts oldest route and ignores blank route`() {
        val memory = FlowSharingPolicy.FocusMemory(limit = 2)
        memory.save("", FlowSharingPolicy.FocusAnchor("x", 0, 0))
        assertEquals(0, memory.size())
        memory.save("a", FlowSharingPolicy.FocusAnchor("row-a", 0, 0))
        memory.save("b", FlowSharingPolicy.FocusAnchor("row-b", 1, 10))
        memory.save("c", FlowSharingPolicy.FocusAnchor("row-c", 2, 20))
        assertEquals(2, memory.size())
        assertNull(memory.load("a"))
        assertEquals("row-b", memory.load("b")!!.rowId)
        assertEquals("row-c", memory.load("c")!!.rowId)
        memory.clear()
        assertEquals(0, memory.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `focus anchor rejects negative index`() {
        FlowSharingPolicy.FocusAnchor("row", -1, 0)
    }

    @Test
    fun `different routes do not overwrite each other`() {
        val memory = FlowSharingPolicy.FocusMemory()
        memory.save("home", FlowSharingPolicy.FocusAnchor("h", 1, 0))
        memory.save("history", FlowSharingPolicy.FocusAnchor("hist", 2, 100))
        assertNotEquals(memory.load("home")!!.rowId, memory.load("history")!!.rowId)
    }
}
