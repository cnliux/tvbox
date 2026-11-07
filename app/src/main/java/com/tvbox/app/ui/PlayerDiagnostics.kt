package com.tvbox.app.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 播放信息面板的数据快照。未知值统一用 -1/0 哨兵，由格式化函数转成「—」。
 */
internal data class PlayerPlaybackInfo(
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val decodedFrames: Long = -1L,
    val droppedFrames: Long = -1L,
    val currentBitrateBps: Long = -1L,
    val averageBitrateBps: Long = -1L,
    val bufferedMs: Long = -1L,
    val firstFrameMs: Long = -1L,
    val decoderName: String? = null,
    val errorCode: Int = 0,
)

/** 分辨率展示；宽高非法时给占位符。 */
internal fun formatResolution(width: Int, height: Int): String =
    if (width <= 0 || height <= 0) "—" else "$width × $height"

/** 码率展示：>=1Mbps 用 Mbps（一位小数），否则 Kbps。 */
internal fun formatBitrate(bps: Long): String = when {
    bps <= 0L -> "—"
    bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
    bps >= 1_000L -> String.format(Locale.US, "%d Kbps", bps / 1_000L)
    else -> "$bps bps"
}

/** 时长展示：不足 1 分钟用「N s」，否则 m:ss。 */
internal fun formatDurationMs(ms: Long): String {
    if (ms < 0L) return "—"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    } else {
        String.format(Locale.US, "%d s", totalSeconds)
    }
}

/** 帧数展示；负值表示采样未就绪。 */
internal fun formatFrameCount(count: Long): String =
    if (count < 0L) "—" else String.format(Locale.US, "%,d", count)

/** 首帧耗时展示；负值表示本次播放尚未出帧。 */
internal fun formatFirstFrame(ms: Long): String =
    if (ms < 0L) "—" else String.format(Locale.US, "%d ms", ms)

/**
 * 按解码器名称判断实际生效的软/硬解。
 * MediaCodec 软件解码器常见名：OMX.google.* / c2.android.* / *ffmpeg* / libvpx。
 */
internal fun decoderKindLabel(decoderName: String?): String {
    if (decoderName.isNullOrBlank()) return "未知"
    val lower = decoderName.lowercase(Locale.US)
    val software = lower.contains("google") ||
        lower.contains("c2.android") ||
        lower.contains("ffmpeg") ||
        lower.contains("libvpx")
    return if (software) "软解" else "硬解"
}

/** 错误码展示：0 表示当前无错误，其余带十六进制码与可读原因。 */
internal fun formatErrorCode(errorCode: Int): String =
    if (errorCode == 0) "无错误"
    else String.format(Locale.US, "0x%X · %s", errorCode, playbackErrorMessage(errorCode))

/**
 * 播放信息采集：AnalyticsListener 记录解码器/首帧/错误，PlaybackStats 提供丢帧与平均码率。
 * 只在面板打开时由 [rememberPlayerDiagnostics] 按秒刷新，关闭即停。
 */
@OptIn(UnstableApi::class)
internal class PlayerDiagnostics(private val player: ExoPlayer) {
    var info by mutableStateOf(PlayerPlaybackInfo())
        private set

    private val statsListener = PlaybackStatsListener(false, null)
    private var decoderCounters: DecoderCounters? = null
    private var decoderName: String? = null
    private var firstFrameStartMs: Long? = null
    private var firstFrameMs: Long = -1L
    private var errorCode: Int = 0

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long,
            initializedTimestampMs: Long,
        ) {
            this@PlayerDiagnostics.decoderName = decoderName
        }

        override fun onVideoEnabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters,
        ) {
            this@PlayerDiagnostics.decoderCounters = decoderCounters
        }

        override fun onMediaItemTransition(
            eventTime: AnalyticsListener.EventTime,
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            // 换集/换源后重新计时：从建源到首帧，与看门狗覆盖的是同一段。
            firstFrameStartMs = eventTime.realtimeMs
            firstFrameMs = -1L
            decoderName = null
            decoderCounters = null
            errorCode = 0
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            if (firstFrameMs < 0L) {
                val start = firstFrameStartMs ?: eventTime.realtimeMs
                firstFrameMs = (eventTime.realtimeMs - start).coerceAtLeast(0L)
            }
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: androidx.media3.common.PlaybackException,
        ) {
            errorCode = error.errorCode
        }
    }

    internal fun attach() {
        player.addAnalyticsListener(statsListener)
        player.addAnalyticsListener(analyticsListener)
        refresh()
    }

    internal fun detach() {
        player.removeAnalyticsListener(analyticsListener)
        player.removeAnalyticsListener(statsListener)
    }

    /** 读取当前值；只应在面板打开时按秒调用。 */
    internal fun refresh() {
        val stats = statsListener.playbackStats ?: PlaybackStats.EMPTY
        val size = player.videoSize
        val lastFormat = stats.videoFormatHistory.lastOrNull()?.format
        info = PlayerPlaybackInfo(
            videoWidth = size.width.coerceAtLeast(0),
            videoHeight = size.height.coerceAtLeast(0),
            decodedFrames = decoderCounters?.renderedOutputBufferCount?.toLong() ?: -1L,
            droppedFrames = stats.totalDroppedFrames,
            currentBitrateBps = lastFormat?.bitrate?.toLong() ?: -1L,
            averageBitrateBps = stats.meanVideoFormatBitrate.toLong(),
            bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
            firstFrameMs = firstFrameMs,
            decoderName = decoderName,
            errorCode = errorCode,
        )
    }
}

/**
 * 采集器跟随播放器实例；[active] 为 true（面板打开）时每秒刷新，关闭时协程取消即停。
 */
@Composable
internal fun rememberPlayerDiagnostics(player: ExoPlayer?, active: Boolean): PlayerDiagnostics? {
    val diagnostics = remember(player) { player?.let(::PlayerDiagnostics) }
    DisposableEffect(diagnostics) {
        diagnostics?.attach()
        onDispose { diagnostics?.detach() }
    }
    LaunchedEffect(diagnostics, active, player) {
        if (diagnostics == null || !active) return@LaunchedEffect
        while (true) {
            diagnostics.refresh()
            delay(1_000L)
        }
    }
    return diagnostics
}
