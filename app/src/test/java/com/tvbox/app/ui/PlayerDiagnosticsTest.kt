package com.tvbox.app.ui

import com.tvbox.app.domain.DecoderStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放信息面板的格式化与兜底：单位换算、未知值占位、解码器软硬判定。
 */
class PlayerDiagnosticsTest {

    @Test
    fun bitrateFormatsUnknownKbpsAndMbps() {
        assertEquals("—", formatBitrate(-1L))
        assertEquals("—", formatBitrate(0L))
        assertEquals("128 Kbps", formatBitrate(128_000L))
        assertEquals("2.4 Mbps", formatBitrate(2_400_000L))
        assertEquals("1.0 Mbps", formatBitrate(1_000_000L))
    }

    @Test
    fun durationFormatsSecondsAndMinutes() {
        assertEquals("—", formatDurationMs(-1L))
        assertEquals("0 s", formatDurationMs(0L))
        assertEquals("59 s", formatDurationMs(59_000L))
        assertEquals("1:01", formatDurationMs(61_000L))
        assertEquals("1:00", formatDurationMs(60_000L))
    }

    @Test
    fun frameCountFormatsUnknownAndGrouping() {
        assertEquals("—", formatFrameCount(-1L))
        assertEquals("0", formatFrameCount(0L))
        assertEquals("12,345", formatFrameCount(12_345L))
    }

    @Test
    fun resolutionAndFirstFrameHandleInvalidValues() {
        assertEquals("—", formatResolution(0, 1080))
        assertEquals("—", formatResolution(1920, -1))
        assertEquals("1920 × 1080", formatResolution(1920, 1080))
        assertEquals("—", formatFirstFrame(-1L))
        assertEquals("850 ms", formatFirstFrame(850L))
    }

    @Test
    fun decoderKindDetectsSoftwareAndHardwareNames() {
        assertEquals("未知", decoderKindLabel(null))
        assertEquals("未知", decoderKindLabel("  "))
        assertEquals("软解", decoderKindLabel("OMX.google.h264.decoder"))
        assertEquals("软解", decoderKindLabel("c2.android.avc.decoder"))
        assertEquals("软解", decoderKindLabel("OMX.ffmpeg.h264.decoder"))
        assertEquals("硬解", decoderKindLabel("OMX.amlogic.avc.decoder.awesome"))
        assertEquals("硬解", decoderKindLabel("c2.amlogic.avc.decoder"))
        assertEquals("硬解", decoderKindLabel("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun errorCodeFormatsNoErrorAndReadableReason() {
        assertEquals("无错误", formatErrorCode(0))
        val formatted = formatErrorCode(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertTrue(formatted.startsWith("0x"))
        assertTrue(formatted.contains("网络"))
    }

    @Test
    fun infoRowsUsePlaceholderWhenSnapshotMissing() {
        val rows = playerInfoRows(null)
        assertEquals(10, rows.size)
        assertEquals("自动", rows.first().value)
        assertTrue(rows.drop(1).all { it.value == "—" })
    }

    @Test
    fun infoRowsMarkHardwareOnlyFallback() {
        val info = PlayerPlaybackInfo(decoderName = "c2.android.avc.decoder")
        val rows = playerInfoRows(info, DecoderStrategy.HardwareOnly).associate { it.label to it.value }
        assertEquals("强制硬解", rows["解码策略"])
        assertEquals("c2.android.avc.decoder · 软解", rows["解码器"])
        assertEquals("强制硬解 · 实际软解（已回落）", rows["策略与实际"])
    }

    @Test
    fun middleEllipsisKeepsHeadAndTail() {
        val url = "https://example.com/live/playlist.m3u8?token=abcdef"
        assertEquals(url, middleEllipsis(url, maxChars = 100))

        val compact = middleEllipsis(url, maxChars = 40)
        assertEquals(40, compact.length)
        assertTrue(compact.startsWith("https://example.co"))
        assertTrue(compact.contains("…"))
        assertTrue(compact.endsWith("token=abcdef"))
    }

    @Test
    fun infoRowsFormatKnownSnapshot() {
        val info = PlayerPlaybackInfo(
            videoWidth = 1920,
            videoHeight = 1080,
            decodedFrames = 12_345L,
            droppedFrames = 3L,
            currentBitrateBps = 2_400_000L,
            averageBitrateBps = 128_000L,
            bufferedMs = 8_000L,
            firstFrameMs = 850L,
            decoderName = "c2.amlogic.avc.decoder",
            errorCode = 0,
        )
        val rows = playerInfoRows(info).associate { it.label to it.value }
        assertEquals("1920 × 1080", rows["分辨率"])
        assertEquals("c2.amlogic.avc.decoder · 硬解", rows["解码器"])
        assertEquals("2.4 Mbps", rows["当前码率"])
        assertEquals("128 Kbps", rows["平均码率"])
        assertEquals("8 s", rows["缓冲时长"])
        assertEquals("3", rows["丢帧"])
        assertEquals("12,345", rows["已解码帧"])
        assertEquals("850 ms", rows["首帧耗时"])
        assertEquals("无错误", rows["错误码"])
    }
}
