package com.tvbox.app.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 安全释放 ExoPlayer：先 pause + removeListener，再 release。
 * 防止 release() 后仍有 Listener 回调触发 NPE。
 */
internal fun safeReleasePlayer(player: ExoPlayer) {
    try {
        if (player.isPlaying) player.pause()
        // ExoPlayer 不支持并发 release，确保在调用线程执行
        player.release()
    } catch (e: Exception) {
        android.util.Log.w("TVBoxPlayer", "safeReleasePlayer failed: ${e.message}")
    }
}

/**
 * 安全 prepare：若 player 已被 release 则静默忽略。
 * 返回 false 表示 prepare 失败（已打印日志），调用方应降级到错误页。
 */
internal fun safePrepare(
    player: ExoPlayer,
    mediaItem: MediaItem,
    playWhenReady: Boolean = true,
): Boolean {
    return try {
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
        true
    } catch (e: Exception) {
        android.util.Log.e("TVBoxPlayer", "safePrepare failed: ${e.message}", e)
        false
    }
}

/**
 * 统一的播放器持有：进入组合时创建，离开组合时安全释放。
 * 页面只负责播放逻辑，不再各自写 `remember { ExoPlayer... }` + `onDispose { release() }`。
 */
@Composable
internal fun rememberManagedExoPlayer(): ExoPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        onDispose { safeReleasePlayer(player) }
    }
    return player
}

/**
 * 同 [rememberManagedExoPlayer]，但允许当前没有可播放内容（工厂返回 null 时不创建播放器）。
 * [keys] 变化时旧播放器会先被安全释放，再创建新实例。
 */
@Composable
internal fun rememberManagedExoPlayerOrNull(
    vararg keys: Any?,
    factory: (android.content.Context) -> ExoPlayer?,
): ExoPlayer? {
    val context = LocalContext.current.applicationContext
    val player = remember(*keys) { factory(context) }
    DisposableEffect(player) {
        onDispose { player?.let(::safeReleasePlayer) }
    }
    return player
}
