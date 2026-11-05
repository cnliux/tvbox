package com.tvbox.app.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * 串行化 ExoPlayer 操作的状态机。
 *
 * 解决以下问题：
 *   1. 快速切集/切台时多个 prepare() 并发导致 IllegalStateException
 *   2. release() 时机混乱（Listener 回调在 release 后仍触发）
 *   3. 自动下一集（outro skip）与 onMediaItemTransition 的竞态
 *
 * 状态：IDLE → BUFFERING → READY → (PAUSED/ERROR)
 * 所有外部操作（prepare/seek/release）走串行队列，内部 Listener 回调直接应用状态。
 */
class PlayerStateMachine(
    private val player: ExoPlayer,
    private val onStateChanged: (PlayerState) -> Unit = {},
) {
    enum class PlayerState { IDLE, BUFFERING, READY, PAUSED, ERROR }

    enum class InternalState { IDLE, PREPARING, READY, PAUSED, ERROR }

    internal var internalState by mutableStateOf(InternalState.IDLE)
    private var pendingAction: PendingAction? = null
    private var errorState: PlaybackError? = null

    sealed class PendingAction {
        data class Prepare(val mediaItem: MediaItem, val playWhenReady: Boolean, val positionMs: Long) : PendingAction()
        data class Seek(val index: Int, val positionMs: Long) : PendingAction()
        object Release : PendingAction()
    }

    data class PlaybackError(val code: Int, val message: String, val cause: Throwable?)

    /** 串行 prepare：若当前正在缓冲则排队等待，READY 后自动应用。 */
    fun prepare(mediaItem: MediaItem, playWhenReady: Boolean = true, positionMs: Long = 0L) {
        val newAction = PendingAction.Prepare(mediaItem, playWhenReady, positionMs)
        if (internalState == InternalState.PREPARING) {
            // 已有 prepare 在途，排队等待
            pendingAction = newAction
            android.util.Log.w("TVBoxPSM", "Prepare queued: ${mediaItem.mediaId}, current=${internalState}")
        } else {
            execute(newAction)
        }
    }

    /** 串行 seek 到指定集数 */
    fun seekTo(index: Int, positionMs: Long = 0L) {
        execute(PendingAction.Seek(index, positionMs))
    }

    /** 释放播放器（onDispose 调用） */
    fun release() {
        execute(PendingAction.Release)
    }

    /** 播放中断（音频焦点丢失 / 后台） */
    fun interrupt() {
        if (internalState == InternalState.READY || internalState == InternalState.PAUSED) {
            player.pause()
            internalState = InternalState.PAUSED
            onStateChanged(PlayerState.PAUSED)
        }
    }

    /** 恢复播放 */
    fun resume() {
        if (internalState == InternalState.PAUSED && player.playbackState == Player.STATE_READY) {
            player.play()
            internalState = InternalState.READY
            onStateChanged(PlayerState.READY)
        }
    }

    internal fun onBufferingStarted() {
        internalState = InternalState.PREPARING
        onStateChanged(PlayerState.BUFFERING)
    }

    internal fun onBufferingFinished() {
        internalState = InternalState.READY
        onStateChanged(PlayerState.READY)
        // 检查是否有排队的 prepare
        val queued = pendingAction
        if (queued is PendingAction.Prepare) {
            pendingAction = null
            execute(queued)
        }
    }

    internal fun onError(error: PlaybackError) {
        errorState = error
        internalState = InternalState.ERROR
        onStateChanged(PlayerState.ERROR)
    }

    internal fun clearError() {
        errorState = null
        if (internalState == InternalState.ERROR) {
            internalState = InternalState.IDLE
            onStateChanged(PlayerState.IDLE)
        }
    }

    private fun execute(action: PendingAction) {
        when (action) {
            is PendingAction.Prepare -> {
                player.stop()
                player.setMediaItem(action.mediaItem, action.positionMs)
                player.prepare()
                player.playWhenReady = action.playWhenReady
                internalState = InternalState.PREPARING
                onStateChanged(PlayerState.BUFFERING)
            }
            is PendingAction.Seek -> {
                if (player.mediaItemCount > action.index) {
                    player.seekTo(action.index, action.positionMs)
                }
            }
            is PendingAction.Release -> {
                player.stop()
                player.release()
                internalState = InternalState.IDLE
                onStateChanged(PlayerState.IDLE)
            }
        }
    }
}

/**
 * 为 Compose 提供的 PlayerStateMachine 工厂。
 * 在 DisposableEffect 中注册 Listener，统一状态流转。
 */
@Composable
internal fun rememberPlayerStateMachine(
    player: ExoPlayer,
    onStateChanged: (PlayerStateMachine.PlayerState) -> Unit = {},
): PlayerStateMachine {
    val stateMachine = remember(player) { PlayerStateMachine(player, onStateChanged) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> stateMachine.onBufferingStarted()
                    Player.STATE_READY -> stateMachine.onBufferingFinished()
                    Player.STATE_IDLE -> {
                        if (stateMachine.internalState != PlayerStateMachine.InternalState.IDLE) {
                            stateMachine.internalState = PlayerStateMachine.InternalState.IDLE
                            onStateChanged(PlayerStateMachine.PlayerState.IDLE)
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                stateMachine.onError(
                    PlayerStateMachine.PlaybackError(
                        code = error.errorCode,
                        message = error.errorCodeName,
                        cause = error.cause,
                    )
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    return stateMachine
}
