package com.tvbox.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-03：跨协议 / 跨站跳转策略的 JVM 单测。 */
class RedirectPolicyTest {

    @Test
    fun `same origin needs no action`() {
        val hop = RedirectPolicy.Hop("https://a.example.com/v", "https://a.example.com/w?x=1")
        assertEquals(RedirectPolicy.Risk.SAME_ORIGIN, RedirectPolicy.classify(hop))
        assertFalse(RedirectPolicy.mustWarn(hop))
        assertFalse(RedirectPolicy.mustRecomputeUa(hop))
    }

    @Test
    fun `cross subdomain keeps registrable domain and ua`() {
        val hop = RedirectPolicy.Hop("https://www.example.com/a", "https://cdn.example.com/b")
        assertEquals(RedirectPolicy.Risk.CROSS_SUBDOMAIN, RedirectPolicy.classify(hop))
        assertFalse(RedirectPolicy.mustWarn(hop))
        assertTrue(RedirectPolicy.mustRecomputeUa(hop))
    }

    @Test
    fun `cross site is warned and recomputes ua`() {
        val hop = RedirectPolicy.Hop("https://a.example.com/a", "https://mirror.other.net/b")
        assertEquals(RedirectPolicy.Risk.CROSS_SITE, RedirectPolicy.classify(hop))
        assertTrue(RedirectPolicy.mustWarn(hop))
        assertTrue(RedirectPolicy.mustRecomputeUa(hop))
    }

    @Test
    fun `https to http is always a downgrade even to same host`() {
        val sameHost = RedirectPolicy.Hop("https://a.example.com/a", "http://a.example.com/b")
        val otherHost = RedirectPolicy.Hop("https://a.example.com/a", "http://cdn.other.net/b")
        assertEquals(RedirectPolicy.Risk.DOWNGRADE, RedirectPolicy.classify(sameHost))
        assertEquals(RedirectPolicy.Risk.DOWNGRADE, RedirectPolicy.classify(otherHost))
        assertTrue(RedirectPolicy.mustWarn(sameHost))
    }

    @Test
    fun `upgrade is not a downgrade`() {
        assertEquals(
            RedirectPolicy.Risk.CROSS_SITE,
            RedirectPolicy.classify(RedirectPolicy.Hop("http://a.example.com/a", "https://cdn.other.net/b")),
        )
    }

    @Test
    fun `unparsable url fails closed to cross site`() {
        assertEquals(
            RedirectPolicy.Risk.CROSS_SITE,
            RedirectPolicy.classify(RedirectPolicy.Hop("not a url", "https://a.example.com")),
        )
    }
}
