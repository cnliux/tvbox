package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2-13：同名源不丢弃、显式胜出标记的 JVM 单测。 */
class ResolvedSiteTest {

    private fun site(name: String, sub: String, key: String, order: Int) =
        SiteCandidate(name, sub, key, order)

    @Test
    fun `same name keeps both sources and marks the first as winner`() {
        val a = site("月光", "subA", "moon", 0)
        val b = site("月光", "subB", "moon", 1)
        val resolved = SiteResolver.resolve(listOf(a, b))
        assertEquals(1, resolved.size)
        assertEquals(a.fingerprint, resolved[0].winner.fingerprint)
        assertEquals(listOf(b), resolved[0].shadowed)
        assertTrue(resolved[0].hasConflict)
    }

    @Test
    fun `winner can be overridden by fingerprint`() {
        val a = site("月光", "subA", "moon", 0)
        val b = site("月光", "subB", "moon", 1)
        val resolved = SiteResolver.resolve(listOf(a, b), setOf(b.fingerprint))
        assertEquals(b.fingerprint, resolved[0].winner.fingerprint)
        assertEquals(listOf(a), resolved[0].shadowed)
    }

    @Test
    fun `different names are never merged and order is stable`() {
        val first = site("甲", "subA", "a", 0)
        val second = site("乙", "subA", "b", 1)
        val third = site("甲", "subB", "c", 2)
        val resolved = SiteResolver.resolve(listOf(first, second, third))
        assertEquals(2, resolved.size)
        assertEquals("甲", resolved[0].winner.name)
        assertEquals("乙", resolved[1].winner.name)
        assertEquals(listOf(third), resolved[0].shadowed)
        assertFalse(resolved[1].hasConflict)
    }

    @Test
    fun `fingerprint is stable eight hex chars and depends on subscription`() {
        val a = site("月光", "subA", "moon", 0)
        val b = site("月光", "subB", "moon", 0)
        assertEquals(8, a.fingerprint.length)
        assertTrue(a.fingerprint.matches(Regex("[0-9a-f]{8}")))
        assertTrue(a.fingerprint != b.fingerprint)
        assertEquals(a.fingerprint, site("月光", "subA", "moon", 99).fingerprint)
    }

    @Test
    fun `summary exposes conflict count`() {
        val resolved = SiteResolver.resolve(
            listOf(
                site("月光", "subA", "moon", 0),
                site("月光", "subB", "moon", 1),
                site("cctv", "subA", "c", 2),
            ),
        )
        assertEquals("本次合并 3 个源，1 个同名冲突（已按你上次的选择）", SiteResolver.summarize(resolved))
    }
}
