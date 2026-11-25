package com.tvbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import com.tvbox.app.domain.DecoderStrategy
import com.tvbox.app.domain.PlaybackFailureCode
import kotlinx.coroutines.delay

/** 播放器可观察快照：四处播放页统一从这一份状态读进度、时长与播放意图。 */
internal data class PlaybackSnapshot(
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 当前位置之后已缓冲的时长（bufferedPosition - currentPosition，>=0）。 */
    val bufferedMs: Long = 0L,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
}

/** 纯函数归并播放器快照：负位置归零，未知/负时长归零，便于单测锁定。 */
internal fun playbackSnapshotOf(
    playbackState: Int,
    playWhenReady: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long = 0L,
): PlaybackSnapshot = PlaybackSnapshot(
    playbackState = playbackState,
    playWhenReady = playWhenReady,
    positionMs = positionMs.coerceAtLeast(0L),
    durationMs = durationMs.takeIf { it > 0L } ?: 0L,
    bufferedMs = bufferedMs.coerceAtLeast(0L),
)

/** 高频进度字段：仅进度条/时间显示需要，250ms 更新一次。 */
internal data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
)

/**
 * 低频播放状态：只在播放器回调时变化，直播页只读这里就不会被进度轮询唤醒。
 * 命名避开 UI 层的 PlaybackStatus（失败覆盖层密封类）。
 */
internal data class PlaybackStateSnapshot(
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
}

/**
 * 共享播放宿主：持有 ExoPlayer 实例，并在页面可见时按 250ms 刷新快照。
 *
 * 关键设计：进度与状态是**两个独立的 Compose State**。
 * 旧实现每 250ms 整体替换一个 snapshot 对象，导致只读 buffering/播放意图的
 * 直播页也跟着每秒重组 4 次；现在轮询只失效 [progress]，状态字段不变时
 * [status] 保持同一对象引用，下游 composable 完全不被通知。
 */
internal class PlaybackHost(
    val player: ExoPlayer,
) {
    /** 高频：只有显示进度/时间的 UI 读它。 */
    var progress by mutableStateOf(PlaybackProgress())
        private set

    /** 低频：只有状态真正变化时才产生新对象。 */
    var status by mutableStateOf(PlaybackStateSnapshot())
        private set

    val isBuffering: Boolean by derivedStateOf { status.playbackState == Player.STATE_BUFFERING }
    val wantsToPlay: Boolean by derivedStateOf { status.playWhenReady }

    /**
     * 全量快照视图，供诊断日志/一次性读全字段使用。
     * 注意：它读 [progress]，会随轮询失效——**不要在重组敏感的 UI 里读它**，
     * 页面应直接读 [progress] 或 [status] 的对应字段。
     */
    val snapshot: PlaybackSnapshot by derivedStateOf {
        playbackSnapshotOf(
            playbackState = status.playbackState,
            playWhenReady = status.playWhenReady,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            bufferedMs = progress.bufferedMs,
        )
    }

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
        val currentPosition = player.currentPosition
        progress = PlaybackProgress(
            positionMs = currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            bufferedMs = (player.bufferedPosition - currentPosition).coerceAtLeast(0L),
        )
        val nextState = PlaybackStateSnapshot(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
        )
        // 只有字段真的变化才赋值：同值赋值同样会使 Compose State 失效。
        if (nextState != status) status = nextState
    }
}

@Composable
internal fun rememberPlaybackHost(
    decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
    audioOutputMode: String = "auto",
): PlaybackHost {
    val player = rememberManagedExoPlayer(decoderStrategy, audioOutputMode = audioOutputMode)
    return rememberPlaybackHost(player)
}

/**
 * 平台直播在解析出可播地址前没有播放器实例；此重载让其余页面共用同一份快照逻辑。
 * [resetKey] 变化时重建快照（对应原页面按候选/地址重置 playerState 的语义）。
 */
@Composable
internal fun rememberPlaybackHostOrNull(
    player: ExoPlayer?,
    resetKey: Any? = null,
): PlaybackHost? {
    val host = remember(player, resetKey) { player?.let(::PlaybackHost) }
    if (host != null) {
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
    }
    return host
}

/**
 * 非空播放器重载：`rememberManagedExoPlayer` 总是返回非空实例，因此无需走 nullable 分支，
 * 调用方也不再用 `!!` 拆包。
 */
@Composable
internal fun rememberPlaybackHost(
    player: ExoPlayer,
    resetKey: Any? = null,
): PlaybackHost {
    val host = remember(player, resetKey) { PlaybackHost(player) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(host) {
        host.attach()
        onDispose { host.detach() }
    }
    LaunchedEffect(host, lifecycleOwner) {
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

/**
 * T3-22：把 ExoPlayer 错误码映射为结构化 [PlaybackFailureCode]。
 * 返回 null 表示没有更精确的归属，由调用方回退到 [playbackErrorMessage]。
 */
internal fun failureCodeFromExo(errorCode: Int): PlaybackFailureCode? = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailureCode.ConnectionRefused
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailureCode.ConnectionRefused
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailureCode.ForbiddenOrGone
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackFailureCode.ContainerUnsupported
    else -> null
}

internal const val PLAYBACK_SNAPSHOT_INTERVAL_MS = 250L
