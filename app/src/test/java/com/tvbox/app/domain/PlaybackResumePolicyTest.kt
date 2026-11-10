package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun unpreparedPlayerKeepsSavedPosition() {
        val result = resolveResumePosition(
            savedPositionMs = 120_000L,
            playerPositionMs = 0L,
            durationMs = 600_000L,
            isPrepared = false,
            sourceChanged = true,
        )
        assertEquals(120_000L, result.positionMs)
        assertFalse(result.clampedToDuration)
    }

    @Test
    fun preparedPlayerPrefersItsOwnPosition() {
        val result = resolveResumePosition(
            savedPositionMs = 120_000L,
            playerPositionMs = 130_000L,
            durationMs = 600_000L,
            isPrepared = true,
            sourceChanged = false,
        )
        assertEquals(130_000L, result.positionMs)
    }

    @Test
    fun switchingToShorterSourceClampsBeforeTail() {
        val result = resolveResumePosition(
            savedPositionMs = 500_000L,
            playerPositionMs = 0L,
            durationMs = 100_000L,
            isPrepared = false,
            sourceChanged = true,
        )
        assertEquals(98_000L, result.positionMs)
        assertTrue(result.clampedToDuration)
    }

    @Test
    fun unknownDurationDoesNotClamp() {
        val result = resolveResumePosition(
            savedPositionMs = 500_000L,
            playerPositionMs = 0L,
            durationMs = 0L,
            isPrepared = false,
            sourceChanged = true,
        )
        assertEquals(500_000L, result.positionMs)
        assertFalse(result.clampedToDuration)
    }

    @Test
    fun progressPersistenceUsesDeltaAndIntervalThrottle() {
        assertTrue(
            shouldPersistProgress(
                lastPersistedMs = 100_000L,
                candidateMs = 106_000L,
                nowMs = 101_000L,
                lastPersistAtMs = 100_000L,
            ),
        )
        assertFalse(
            shouldPersistProgress(
                lastPersistedMs = 100_000L,
                candidateMs = 101_000L,
                nowMs = 102_000L,
                lastPersistAtMs = 100_000L,
            ),
        )
        assertTrue(
            shouldPersistProgress(
                lastPersistedMs = 100_000L,
                candidateMs = 101_000L,
                nowMs = 120_000L,
                lastPersistAtMs = 100_000L,
            ),
        )
        assertFalse(
            shouldPersistProgress(
                lastPersistedMs = 100_000L,
                candidateMs = 100_000L,
                nowMs = 200_000L,
                lastPersistAtMs = 100_000L,
            ),
        )
    }

    @Test
    fun retryKeepsTheLargerValidPosition() {
        assertEquals(90_000L, positionForRetry(savedPositionMs = 90_000L, playerPositionMs = 0L))
        assertEquals(95_000L, positionForRetry(savedPositionMs = 90_000L, playerPositionMs = 95_000L))
        assertEquals(0L, positionForRetry(savedPositionMs = -1L, playerPositionMs = 0L))
    }
}
