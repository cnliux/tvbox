package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6-42：少儿锁 PIN 摘要与三项人因开关的纯函数判定。 */
class KidsLockPolicyTest {

    @Test
    fun `pin hash is stable sha256 prefix and never stores plain pin`() {
        // SHA-256("123456") = 8d969eef6ecad3c29a3a629280e686cf...
        assertEquals("8d969eef6ecad3c2", KidsLockPolicy.hashPin("123456"))
        assertEquals(KidsLockPolicy.PIN_HASH_HEX_LENGTH, KidsLockPolicy.hashPin("123456").length)
        assertTrue(KidsLockPolicy.hashPin("123456").matches(Regex("[0-9a-f]{16}")))
        assertNotEquals("123456", KidsLockPolicy.hashPin("123456"))
        assertNotEquals(KidsLockPolicy.hashPin("123456"), KidsLockPolicy.hashPin("123457"))
    }

    @Test
    fun `pin format accepts only four to six digits`() {
        assertTrue(KidsLockPolicy.isValidPin("1234"))
        assertTrue(KidsLockPolicy.isValidPin("123456"))
        assertFalse(KidsLockPolicy.isValidPin("123"))
        assertFalse(KidsLockPolicy.isValidPin("1234567"))
        assertFalse(KidsLockPolicy.isValidPin("12ab"))
        assertFalse(KidsLockPolicy.isValidPin("12 4"))
        assertFalse(KidsLockPolicy.isValidPin(""))
    }

    @Test
    fun `matches accepts case insensitive correct hash only`() {
        val hash = KidsLockPolicy.hashPin("246810")
        assertTrue(KidsLockPolicy.matches("246810", hash))
        assertTrue(KidsLockPolicy.matches("246810", hash.uppercase()))
        assertFalse(KidsLockPolicy.matches("246811", hash))
        assertFalse(KidsLockPolicy.matches("123", hash))
        assertFalse(KidsLockPolicy.isValidStoredHash("oops"))
        assertFalse(KidsLockPolicy.isValidStoredHash(hash.dropLast(1)))
    }

    @Test
    fun `blank or malformed stored hash never unlocks`() {
        assertFalse(KidsLockPolicy.matches("123456", ""))
        // 合法格式但不是该 PIN 的摘要，同样不能解锁。
        assertFalse(KidsLockPolicy.matches("123456", "0000000000000000"))
        assertFalse(KidsLockPolicy.matches("123456", "not-a-hash"))
        // 摘要损坏时锁仍视为开启，避免改坏偏好即可绕过。
        assertTrue(KidsLockPolicy.isEnabled("not-a-hash"))
    }

    @Test
    fun `screen time and driving mode decisions are pure and default off`() {
        assertFalse(KidsLockPolicy.isScreenTimeLimitActive(0))
        assertFalse(KidsLockPolicy.shouldLockForScreenTime(10_000L, 0))
        assertFalse(KidsLockPolicy.shouldLockForScreenTime(59L, 60))
        assertTrue(KidsLockPolicy.shouldLockForScreenTime(60L, 60))
        assertTrue(KidsLockPolicy.shouldLockForScreenTime(121L, 60))

        assertFalse(KidsLockPolicy.shouldRestrictInteraction(false, true))
        assertFalse(KidsLockPolicy.shouldRestrictInteraction(true, false))
        assertTrue(KidsLockPolicy.shouldRestrictInteraction(true, true))
    }

    @Test
    fun `default app settings keep all human factor features off`() {
        val settings = AppSettings()
        assertFalse(KidsLockPolicy.isEnabled(settings.kidsLockPinHash))
        assertFalse(KidsLockPolicy.isScreenTimeLimitActive(settings.screenTimeLimitMinutes))
        assertFalse(KidsLockPolicy.shouldRestrictInteraction(settings.drivingMode, true))
    }
}
