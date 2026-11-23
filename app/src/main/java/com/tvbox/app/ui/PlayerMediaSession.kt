package com.tvbox.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 把当前播放器接入系统 MediaSession，使系统媒体键、耳机线控、电视遥控器多媒体键
 * （播放/暂停、上一曲/下一曲、快进快退）在**应用未获焦时**也能控制播放。
 *
 * 设计要点：
 * - 会话与 ExoPlayer 同生命周期（remember(player)），切页自动 release，不留后台会话。
 * - 播放/暂停/快进快退由 MediaSession 直接转发给 player（Media3 默认行为）。
 * - 上一集/下一集属于业务动作，通过自定义 SessionCommand 回调给页面，
 *   避免 MediaSession 直接 seek 到「媒体项」而绕过选集/换台的业务逻辑。
 */
internal object PlayerMediaCommands {
    private val NO_ARGS = android.os.Bundle.EMPTY
    val PREVIOUS = SessionCommand("com.tvbox.app.media.PREVIOUS", NO_ARGS)
    val NEXT = SessionCommand("com.tvbox.app.media.NEXT", NO_ARGS)
}

/**
 * [onPrevious]/[onNext] 为空时不声明对应命令，系统媒体键不会误触发业务动作。
 */
@Composable
internal fun rememberPlayerMediaSession(
    player: Player?,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
): MediaSession? {
    val context = LocalContext.current
    val callbacks = remember(onPrevious, onNext) {
        MediaCallbacks(onPrevious, onNext)
    }
    val session = remember(context, player, callbacks) {
        player?.let { runCatching { buildSession(context, it, callbacks) }.getOrNull() }
    }
    DisposableEffect(session) {
        onDispose { session?.release() }
    }
    // 会话只负责把播放器暴露给系统以响应媒体键；标题由各页面在构建 MediaItem 时写入。
    // 这里不做运行时元数据替换——replaceMediaItem 会重置播放位置，代价远高于显示收益。
    return session
}

private fun buildSession(
    context: Context,
    player: Player,
    callbacks: MediaCallbacks,
): MediaSession = MediaSession.Builder(context, player)
    .setCallback(callbacks)
    .setId("tvbox_player_session")
    .build()

private class MediaCallbacks(
    private val onPrevious: (() -> Unit)?,
    private val onNext: (() -> Unit)?,
) : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        val commands = base.availableSessionCommands.buildUpon()
        if (onPrevious != null) commands.add(PlayerMediaCommands.PREVIOUS)
        if (onNext != null) commands.add(PlayerMediaCommands.NEXT)
        return MediaSession.ConnectionResult.accept(commands.build(), base.availablePlayerCommands)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: android.os.Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            PlayerMediaCommands.PREVIOUS.customAction -> onPrevious?.invoke()
            PlayerMediaCommands.NEXT.customAction -> onNext?.invoke()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}
