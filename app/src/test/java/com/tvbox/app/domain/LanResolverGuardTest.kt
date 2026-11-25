package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanResolverGuardTest {

    @Test
    fun disabledByDefault() {
        assertFalse(LanResolverGuard.isAllowed(AppSettings()))
        assertNull(LanResolverGuard.blockReason(AppSettings()))
    }

    @Test
    fun enabledWithoutConfirmationReturnsExactStatus() {
        val settings = AppSettings(lanResolverEnabled = true, lanResolverConfirmed = false)
        assertEquals(LanResolverGuard.NEEDS_CONFIRMATION, LanResolverGuard.enableStatus(settings))
        assertEquals("需先确认安全提示", LanResolverGuard.blockReason(settings))
        assertFalse(LanResolverGuard.isAllowed(settings))
    }

    @Test
    fun enabledAndConfirmedIsAllowed() {
        val settings = AppSettings(lanResolverEnabled = true, lanResolverConfirmed = true)
        assertTrue(LanResolverGuard.isAllowed(settings))
        assertNull(LanResolverGuard.blockReason(settings))
    }
}
