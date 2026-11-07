package com.tvbox.app.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHostTest {

    @Test
    fun snapshotClampsNegativePositionAndUnknownDuration() {
        val snapshot = playbackSnapshotOf(
            playbackState = Player.STATE_READY,
            playWhenReady = true,
            positionMs = -120L,
            durationMs = -1L,
        )
        assertEquals(0L, snapshot.positionMs)
        assertEquals(0L, snapshot.durationMs)
        assertTrue(snapshot.playWhenReady)
    }

    @Test
    fun snapshotKeepsPositivePositionAndDuration() {
        val snapshot = playbackSnapshotOf(
            playbackState = Player.STATE_READY,
            playWhenReady = false,
            positionMs = 12_000L,
            durationMs = 3_600_000L,
        )
        assertEquals(12_000L, snapshot.positionMs)
        assertEquals(3_600_000L, snapshot.durationMs)
        assertFalse(snapshot.isBuffering)
    }

    @Test
    fun onlyBufferingStateCountsAsBuffering() {
        assertTrue(
            playbackSnapshotOf(Player.STATE_BUFFERING, true, 0L, 0L).isBuffering,
        )
        assertFalse(
            playbackSnapshotOf(Player.STATE_READY, true, 0L, 0L).isBuffering,
        )
        assertFalse(
            playbackSnapshotOf(Player.STATE_ENDED, true, 0L, 0L).isBuffering,
        )
    }

    @Test
    fun networkErrorsMapToReadableMessages() {
        assertEquals(
            "网络超时，请稍后重试",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertEquals(
            "网络连接失败，请检查网络",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            "播放地址无效，正在尝试其他线路",
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            "当前线路格式不支持，正在尝试其他线路",
            playbackErrorMessage(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals(
            "播放失败，正在尝试其他线路",
            playbackErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
    }
}
