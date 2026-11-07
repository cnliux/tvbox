package com.tvbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** 播放器可观察快照：四处播放页统一从这一份状态读进度、时长与播放意图。 */
internal data class PlaybackSnapshot(
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
}

/** 纯函数归并播放器快照：负位置归零，未知/负时长归零，便于单测锁定。 */
internal fun playbackSnapshotOf(
    playbackState: Int,
    playWhenReady: Boolean,
    positionMs: Long,
    durationMs: Long,
): PlaybackSnapshot = PlaybackSnapshot(
    playbackState = playbackState,
    playWhenReady = playWhenReady,
    positionMs = positionMs.coerceAtLeast(0L),
    durationMs = durationMs.takeIf { it > 0L } ?: 0L,
)

/**
 * 共享播放宿主：持有 ExoPlayer 实例，并在页面可见时按 250ms 刷新快照。
 * 页面只保留各自的业务语义（选集 / 换台 / 换房间），不再各写一套轮询与错误文案。
 */
internal class PlaybackHost(
    val player: ExoPlayer,
) {
    var snapshot by mutableStateOf(PlaybackSnapshot())
        private set

    private val listener = object : Player.Listener {
        // 状态切换需要立即反映到 UI（加载环不能等 250ms 轮询）。
        override fun onPlaybackStateChanged(playbackState: Int) = refreshSnapshot()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshSnapshot()

        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshSnapshot()
    }

    internal fun attach() {
        player.addListener(listener)
        refreshSnapshot()
    }

    internal fun detach() {
        player.removeListener(listener)
    }

    internal fun refreshSnapshot() {
        snapshot = playbackSnapshotOf(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition,
            durationMs = player.duration,
        )
    }
}

@Composable
internal fun rememberPlaybackHost(): PlaybackHost {
    val player = rememberManagedExoPlayer()
    val host = remember(player) { PlaybackHost(player) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    LaunchedEffect(host, lifecycleOwner) {
        // 退到后台后组合仍存活：不停止轮询会持续 4 次/秒唤醒主线程。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                host.refreshSnapshot()
                delay(PLAYBACK_SNAPSHOT_INTERVAL_MS)
            }
        }
    }
    return host
}

/** 不展示 ExoPlayer 原始异常文本，按错误码统一映射为可读提示。 */
internal fun playbackErrorMessage(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络超时，请稍后重试"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    -> "播放地址无效，正在尝试其他线路"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "当前线路格式不支持，正在尝试其他线路"
    else -> "播放失败，正在尝试其他线路"
}

internal const val PLAYBACK_SNAPSHOT_INTERVAL_MS = 250L
