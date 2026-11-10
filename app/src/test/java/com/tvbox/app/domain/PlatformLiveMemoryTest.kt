package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformLiveMemoryTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val last = LastWatchedPlatform("huya", "game", "room-1", 1_000L)
        assertEquals(last, decodeLastWatchedPlatform(encodeLastWatchedPlatform(last)))
    }

    @Test
    fun nullEncodesAsVersionOnlyAndDecodesToNull() {
        assertEquals("v1", encodeLastWatchedPlatform(null))
        assertNull(decodeLastWatchedPlatform("v1"))
        assertNull(decodeLastWatchedPlatform(null))
        assertNull(decodeLastWatchedPlatform("  "))
    }

    @Test
    fun malformedOrFutureVersionIsRejected() {
        assertNull(decodeLastWatchedPlatform("v2|huya|game|room|1"))
        assertNull(decodeLastWatchedPlatform("v1|huya|game|room|not-a-number"))
        assertNull(decodeLastWatchedPlatform("v1|huya|game"))
    }

    @Test
    fun negativeTimestampIsClamped() {
        val decoded = decodeLastWatchedPlatform("v1|huya|game|room|-10")
        assertEquals(0L, decoded!!.atMs)
        assertEquals("huya", decoded.site)
    }

    @Test
    fun labelHandlesMissingMemory() {
        assertEquals("上次观看：无", lastWatchedLabel(null))
        assertEquals("上次观看：huya", lastWatchedLabel(LastWatchedPlatform("huya", "", "")))
    }

    @Test
    fun badgeUsesLocalTtlAndNeverMarksOfflineOnExpiry() {
        assertEquals(LiveBadge.Unknown, resolveLiveBadge(0L, 10_000L))
        assertEquals(LiveBadge.Live, resolveLiveBadge(9_000L, 10_000L))
        assertEquals(LiveBadge.Unknown, resolveLiveBadge(9_000L, 10_000L, ttlMs = 500L))
        assertEquals("正在直播", liveBadgeLabel(LiveBadge.Live))
        assertEquals("", liveBadgeLabel(LiveBadge.Unknown))
    }
}
