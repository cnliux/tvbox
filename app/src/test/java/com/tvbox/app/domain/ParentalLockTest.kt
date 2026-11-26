package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6-42：家长锁 PIN 摘要/校验纯函数回归。 */
class ParentalLockTest {

    @Test
    fun `hashPin returns 16 hex chars for valid 4 digit pin`() {
        val hash = ParentalLock.hashPin("1234")
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        // 确定性：同 PIN 同摘要。
        assertEquals(hash, ParentalLock.hashPin("1234"))
    }

    @Test
    fun `hashPin rejects invalid input with empty string`() {
        assertEquals("", ParentalLock.hashPin(""))
        assertEquals("", ParentalLock.hashPin("12345"))
        assertEquals("", ParentalLock.hashPin("12a4"))
        assertEquals("", ParentalLock.hashPin(" 123"))
    }

    @Test
    fun `verifyPin accepts matching hash and rejects mismatches`() {
        val hash = ParentalLock.hashPin("4321")
        assertTrue(ParentalLock.verifyPin("4321", hash))
        assertFalse(ParentalLock.verifyPin("4322", hash))
        // 大小写不敏感：摘要存储大小写不影响校验。
        assertTrue(ParentalLock.verifyPin("4321", hash.uppercase()))
    }

    @Test
    fun `verifyPin never passes against missing or corrupt hash`() {
        assertFalse(ParentalLock.verifyPin("1234", ""))
        assertFalse(ParentalLock.verifyPin("1234", "zz"))
        // 长度不对（模拟被截断/篡改的摘要）：拒绝而不是静默放行。
        assertFalse(ParentalLock.verifyPin("1234", ParentalLock.hashPin("1234").drop(2)))
    }

    @Test
    fun `isConfigured mirrors a valid hash`() {
        assertFalse(ParentalLock.isConfigured(""))
        assertTrue(ParentalLock.isConfigured(ParentalLock.hashPin("0000")))
        assertFalse(ParentalLock.isConfigured("x"))
    }

    @Test
    fun `pin entry appends backspaces and resets to 4 digits`() {
        var entry = PinEntry("")
        entry = entry.append('7')
        entry = entry.append('7')
        entry = entry.append('8')
        assertFalse(entry.isComplete)
        entry = entry.append('9')
        assertTrue(entry.isComplete)
        // 输满后继续按数字不变。
        assertEquals("7789", entry.append('1').entered)
        assertEquals("778", entry.backspace().entered)
        assertEquals("", entry.backspace().backspace().backspace().backspace().reset().entered)
    }
}
