package com.tvbox.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCollectionsTest {

    @Test
    fun retryAttemptsFirstPlusMaxRetries() = runBlocking {
        var attempts = 0
        val result = exponentialRetry(maxRetries = 2, baseDelayMs = 1L, maxDelayMs = 1L) {
            attempts++
            if (attempts < 3) error("boom")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun retryGivesUpAfterMaxRetries() = runBlocking {
        var attempts = 0
        var retried = 0
        val error = runCatching {
            exponentialRetry(
                maxRetries = 2,
                baseDelayMs = 1L,
                maxDelayMs = 1L,
                onRetry = { _, _, _ -> retried++ },
            ) {
                attempts++
                error("boom")
            }
            Unit
        }.exceptionOrNull()
        assertEquals(3, attempts)
        assertEquals(2, retried)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun retryDoesNotSwallowCancellation() = runBlocking {
        var attempts = 0
        val job = launch {
            exponentialRetry(maxRetries = 3, baseDelayMs = 1L, maxDelayMs = 1L) {
                attempts++
                delay(1_000L)
                attempts
            }
        }
        delay(20L)
        job.cancel()
        job.join()
        assertEquals(1, attempts)
        assertTrue(job.isCancelled)
    }

    @Test
    fun synchronizedListIsActuallyLocked() {
        val list = ConcurrentCollections.synchronizedList(listOf("a"))
        val threads = (0 until 4).map { index ->
            Thread { repeat(100) { list.add("x$index") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(401, list.size)
    }
}
