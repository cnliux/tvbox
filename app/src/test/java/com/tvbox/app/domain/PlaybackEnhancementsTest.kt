package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 播放器增强纯逻辑的 JVM 单测：步进曲线、片头片尾判定、错误分级。 */
class PlaybackEnhancementsTest {

    private val creditsConfig = SkipCreditsConfig(
        enabled = true,
        introSeconds = 90,
        outroSeconds = 60,
    )

    @Test
    fun seekStepStartsAtTenSeconds() {
        val state = resolveSeekStep(0L)
        assertEquals(10, state.stepSeconds)
        assertEquals(1_500L, requireNotNull(state.nextTierInMs))
        assertEquals(30, requireNotNull(state.nextStepSeconds))
    }

    @Test
    fun seekStepAcceleratesWithHoldDuration() {
        assertEquals(10, resolveSeekStep(1_499L).stepSeconds)
        assertEquals(30, resolveSeekStep(1_500L).stepSeconds)
        assertEquals(30, resolveSeekStep(3_499L).stepSeconds)
        assertEquals(60, resolveSeekStep(3_500L).stepSeconds)
        assertEquals(60, resolveSeekStep(60_000L).stepSeconds)
    }

    @Test
    fun seekStepTopTierHasNoNextHint() {
        val state = resolveSeekStep(5_000L)
        assertEquals(60, state.stepSeconds)
        assertNull(state.nextTierInMs)
        assertNull(state.nextStepSeconds)
    }

    @Test
    fun seekStepClampsNegativeHoldDuration() {
        assertEquals(10, resolveSeekStep(-100L).stepSeconds)
        assertEquals(1_500L, requireNotNull(resolveSeekStep(-100L).nextTierInMs))
    }

    @Test(expected = IllegalArgumentException::class)
    fun seekStepRejectsUnsortedTiers() {
        resolveSeekStep(0L, listOf(SeekTier(0L, 10), SeekTier(1_000L, 30), SeekTier(500L, 60)))
    }

    @Test
    fun skipCreditsIsDisabledByDefault() {
        assertNull(resolveSkipCredits(0L, 2_400_000L, SkipCreditsConfig()))
    }

    @Test
    fun skipCreditsSeeksPastIntro() {
        val decision = resolveSkipCredits(5_000L, 2_400_000L, creditsConfig)
        assertEquals(SkipCreditsAction.SeekToIntro, decision?.action)
        assertEquals(90_000L, requireNotNull(decision).targetPositionMs)
    }

    @Test
    fun skipCreditsPlaysNextEpisodeAtOutro() {
        val decision = resolveSkipCredits(2_400_000L - 30_000L, 2_400_000L, creditsConfig)
        assertEquals(SkipCreditsAction.PlayNextEpisode, decision?.action)
    }

    @Test
    fun skipCreditsLeavesMainContentUntouched() {
        assertNull(resolveSkipCredits(600_000L, 2_400_000L, creditsConfig))
    }

    @Test
    fun skipCreditsRejectsOverlappingRanges() {
        assertNull(resolveSkipCredits(1_000L, 120_000L, creditsConfig))
    }

    @Test
    fun skipCreditsRejectsUnknownDuration() {
        assertNull(resolveSkipCredits(0L, 0L, creditsConfig))
        assertNull(resolveSkipCredits(0L, -1L, creditsConfig))
    }

    @Test
    fun classifyHttpFailureAsSourceInvalid() {
        val decision = classifyPlaybackError("HTTP 404 not found", httpStatus = 404)
        assertEquals(PlaybackErrorKind.SourceInvalid, decision.kind)
        assertEquals(PlaybackErrorAction.SwitchSource, decision.action)
    }

    @Test
    fun classifyChineseSourceFailureAsSourceInvalid() {
        val decision = classifyPlaybackError("解析失败：无播放地址")
        assertEquals(PlaybackErrorKind.SourceInvalid, decision.kind)
    }

    @Test
    fun classifyTimeoutAsNetworkRetry() {
        val decision = classifyPlaybackError("java.net.SocketTimeoutException: timeout")
        assertEquals(PlaybackErrorKind.Network, decision.kind)
        assertEquals(PlaybackErrorAction.Retry, decision.action)
    }

    @Test
    fun classifyCodecFailureAsDecoderSwitchLine() {
        val decision = classifyPlaybackError("MediaCodec decoder init failed")
        assertEquals(PlaybackErrorKind.Decoder, decision.kind)
        assertEquals(PlaybackErrorAction.SwitchLine, decision.action)
    }

    @Test
    fun classifyUnknownErrorAsShowMessage() {
        val decision = classifyPlaybackError(null)
        assertEquals(PlaybackErrorKind.Unknown, decision.kind)
        assertEquals(PlaybackErrorAction.ShowMessage, decision.action)
    }

    @Test
    fun singleRemotePressKeepsOriginalTenSecondStep() {
        assertEquals(10_000L, seekStepMsForHold(0L))
        assertEquals(10_000L, seekStepMsForHold(200L))
        assertEquals(10_000L, seekStepMsForHold(1_499L))
    }

    @Test
    fun longPressStepAcceleratesWithHoldDuration() {
        assertEquals(30_000L, seekStepMsForHold(1_500L))
        assertEquals(30_000L, seekStepMsForHold(3_499L))
        assertEquals(60_000L, seekStepMsForHold(3_500L))
    }

    @Test
    fun highestTierStaysCappedAndNegativeHoldIsTreatedAsZero() {
        assertEquals(60_000L, seekStepMsForHold(60_000L))
        assertEquals(10_000L, seekStepMsForHold(-500L))
    }
}
