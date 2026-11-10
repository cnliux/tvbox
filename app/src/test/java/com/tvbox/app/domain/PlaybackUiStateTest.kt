package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiStateTest {

    @Test
    fun skipCreditsPreferencesDefaultToDisabledAndRoundTrip() {
        val default = decodeSkipCreditsPreferences(null)
        assertFalse(default.enabled)
        assertFalse(default.toConfig().enabled)
        val configured = SkipCreditsPreferences(enabled = true, introSeconds = 90, outroSeconds = 60)
        assertEquals(configured, decodeSkipCreditsPreferences(encodeSkipCreditsPreferences(configured)))
        assertTrue(configured.toConfig().enabled)
    }

    @Test
    fun corruptedSkipCreditsPreferencesFallBackToSafeDefaults() {
        assertEquals(SkipCreditsPreferences(), decodeSkipCreditsPreferences("v2|1|90|60"))
        assertEquals(SkipCreditsPreferences(), decodeSkipCreditsPreferences("v1|1|bad|60"))
        assertEquals(SkipCreditsPreferences(), decodeSkipCreditsPreferences("v1|1|90"))
        assertEquals(
            600,
            decodeSkipCreditsPreferences("v1|1|99999|60").introSeconds,
        )
    }

    @Test
    fun seekTimelineHintScalesWithHoldDurationAndClampsAtEdges() {
        val first = seekTimelineHint(30_000L, 600_000L, heldMs = 0L, forward = true)
        assertEquals(10, first.stepSeconds)
        assertEquals(40, first.targetSeconds)
        assertFalse(first.accelerated)

        val accelerated = seekTimelineHint(30_000L, 600_000L, heldMs = 4_000L, forward = true)
        assertEquals(60, accelerated.stepSeconds)
        assertTrue(accelerated.accelerated)

        val backward = seekTimelineHint(3_000L, 600_000L, heldMs = 0L, forward = false)
        assertEquals(0, backward.targetSeconds)
    }

    @Test
    fun playbackErrorPresentationMapsKindsToActionableHints() {
        val network = presentPlaybackError("连接超时")
        assertEquals(PlaybackErrorKind.Network, network.kind)
        assertEquals("重试", network.actionLabel)

        val invalid = presentPlaybackError("404")
        assertEquals(PlaybackErrorKind.SourceInvalid, invalid.kind)
        assertEquals(PlaybackErrorAction.SwitchSource, invalid.action)

        val unknown = presentPlaybackError(null)
        assertEquals(PlaybackErrorKind.Unknown, unknown.kind)
        assertNull(unknown.actionLabel)
    }
}
