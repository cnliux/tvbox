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

    // ── 首启契约：未落盘 / 落盘 / 条款变更三种状态 ──────────────────────

    @Test
    fun `no persisted ack requires acknowledgement`() {
        assertTrue(LegalTerms.requiresAcknowledgement(loadAck(emptyMap())))
    }

    @Test
    fun `persisted current ack does not prompt again`() {
        // 模拟 FirstRunScreen.saveLegalAck() → legalAck() 的落盘往返。
        val store = mutableMapOf<String, Any?>()
        persistAck(store, LegalTerms.Ack(LegalTerms.TERMS_VERSION, LegalTerms.CURRENT_HASH, 0L))

        val restored = loadAck(store)
        assertTrue(LegalTerms.isCurrent(restored))
        assertFalse(LegalTerms.requiresAcknowledgement(restored))
    }

    @Test
    fun `changed terms hash prompts again until new hash is persisted`() {
        // 用户确认过旧版条款：hash 变化后必须重新提示。
        val oldHash = LegalTerms.hash("旧版条款文本")
        val store = mutableMapOf<String, Any?>()
        persistAck(store, LegalTerms.Ack(LegalTerms.TERMS_VERSION, oldHash, 0L))
        assertTrue(LegalTerms.requiresAcknowledgement(loadAck(store)))

        // 重新确认并落盘新 hash 后不再提示。
        persistAck(store, LegalTerms.Ack(LegalTerms.TERMS_VERSION, LegalTerms.CURRENT_HASH, 0L))
        assertFalse(LegalTerms.requiresAcknowledgement(loadAck(store)))
    }

    @Test
    fun `version only mismatch also prompts again after re-persist`() {
        val store = mutableMapOf<String, Any?>()
        persistAck(store, LegalTerms.Ack(LegalTerms.TERMS_VERSION - 1, LegalTerms.CURRENT_HASH, 0L))
        assertTrue(LegalTerms.requiresAcknowledgement(loadAck(store)))

        persistAck(store, LegalTerms.Ack(LegalTerms.TERMS_VERSION, LegalTerms.CURRENT_HASH, 0L))
        assertFalse(LegalTerms.requiresAcknowledgement(loadAck(store)))
    }

    /** 与 FirstRunScreen.legalAck() 同口径：版本 < 0 或 hash 空白视为未确认。 */
    private fun loadAck(store: Map<String, Any?>): LegalTerms.Ack? {
        val version = store[LegalTerms.VERSION_KEY] as? Int ?: return null
        val hash = store[LegalTerms.HASH_KEY] as? String ?: return null
        return if (version >= 0 && hash.isNotBlank()) LegalTerms.Ack(version, hash, 0L) else null
    }

    private fun persistAck(store: MutableMap<String, Any?>, ack: LegalTerms.Ack) {
        store[LegalTerms.VERSION_KEY] = ack.termsVersion
        store[LegalTerms.HASH_KEY] = ack.termsHash
    }
}
