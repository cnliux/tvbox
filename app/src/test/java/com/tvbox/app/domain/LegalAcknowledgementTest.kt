package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6-39：免责条款版本/hash/ack 证据链的 JVM 单测。 */
class LegalAcknowledgementTest {

    @Test
    fun `hash is stable short and changes with text`() {
        assertEquals(16, LegalTerms.CURRENT_HASH.length)
        assertTrue(LegalTerms.CURRENT_HASH.matches(Regex("[0-9a-f]{16}")))
        assertEquals(LegalTerms.hash(LegalTerms.TEXT), LegalTerms.hash(LegalTerms.TEXT))
        assertNotEquals(LegalTerms.hash(LegalTerms.TEXT), LegalTerms.hash(LegalTerms.TEXT + " "))
    }

    @Test
    fun `only current version and hash count as acknowledged`() {
        val current = LegalTerms.Ack(LegalTerms.TERMS_VERSION, LegalTerms.CURRENT_HASH, 1_000L)
        assertTrue(LegalTerms.isCurrent(current))
        assertFalse(LegalTerms.requiresAcknowledgement(current))

        assertFalse(LegalTerms.isCurrent(null))
        assertTrue(LegalTerms.requiresAcknowledgement(null))
        assertFalse(LegalTerms.isCurrent(current.copy(termsVersion = LegalTerms.TERMS_VERSION - 1)))
        assertFalse(LegalTerms.isCurrent(current.copy(termsHash = "0000000000000000")))
        assertTrue(LegalTerms.isCurrent(current.copy(termsHash = LegalTerms.CURRENT_HASH.uppercase())))
    }

    @Test
    fun `terms text contains the three missing legal clauses`() {
        assertTrue("不规避技术措施" in LegalTerms.TEXT)
        assertTrue("数据处理说明" in LegalTerms.TEXT)
        assertTrue("下游分发约束" in LegalTerms.TEXT)
        assertTrue("不内置、不托管、不推荐任何公开订阅地址" in LegalTerms.TEXT)
        assertTrue("合法授权" in LegalTerms.TEXT)
    }

    @Test
    fun `persisted keys are stable`() {
        assertEquals("legal.ack_version", LegalTerms.VERSION_KEY)
        assertEquals("legal.ack_hash", LegalTerms.HASH_KEY)
    }
}
