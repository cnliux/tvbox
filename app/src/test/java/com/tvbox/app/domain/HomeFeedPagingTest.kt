package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedPagingTest {
    @Test
    fun resetAlwaysReturnsFirstPageAndClears() {
        val request = resolveHomeFeedPageRequest(
            HomeFeedPageState(page = 7, pageCount = 9, itemCount = 140),
            reset = true,
        )!!
        assertEquals(1, request.nextPage)
        assertTrue(request.clearExisting)
    }

    @Test
    fun loadMoreAdvancesOnePage() {
        val request = resolveHomeFeedPageRequest(
            HomeFeedPageState(page = 2, pageCount = 5),
            reset = false,
        )!!
        assertEquals(3, request.nextPage)
        assertFalse(request.clearExisting)
    }

    @Test
    fun reachingLastPageStopsFurtherRequests() {
        assertNull(resolveHomeFeedPageRequest(HomeFeedPageState(page = 5, pageCount = 5), reset = false))
        assertTrue(canLoadMoreHomeFeed(HomeFeedPageState(page = 4, pageCount = 5)))
        assertFalse(canLoadMoreHomeFeed(HomeFeedPageState(page = 5, pageCount = 5)))
    }

    @Test
    fun unknownPageCountStillAllowsProbing() {
        val state = HomeFeedPageState(page = 3, pageCount = 0)
        assertEquals(4, resolveHomeFeedPageRequest(state, reset = false)!!.nextPage)
        assertTrue(canLoadMoreHomeFeed(state))
    }

    @Test
    fun explicitRetryPageIsUsedAndInvalidPageRejected() {
        val state = HomeFeedPageState(page = 2, pageCount = 8)
        assertEquals(3, resolveHomeFeedPageRequest(state, reset = false, explicitPage = 3)!!.nextPage)
        assertNull(resolveHomeFeedPageRequest(state, reset = false, explicitPage = 9))
        assertNull(resolveHomeFeedPageRequest(state, reset = false, explicitPage = 0))
    }
}
