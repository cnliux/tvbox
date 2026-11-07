package com.tvbox.app.ui

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
}
