package com.tvbox.app.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 安全释放 ExoPlayer：先 pause + removeListener，再 release。
 * 防止 release() 后仍有 Listener 回调触发 NPE。
 */
@OptIn(UnstableApi::class)
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
@OptIn(UnstableApi::class)
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
