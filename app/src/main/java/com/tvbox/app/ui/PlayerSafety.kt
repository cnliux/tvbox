package com.tvbox.app.ui

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tvbox.app.domain.DecoderStrategy

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
 * 共享播放器工厂：四个播放页只通过这里配置 Renderers，避免各页各建一套 factory。
 *
 * 始终开启 [DefaultRenderersFactory.setEnableDecoderFallback]（默认「自动」档的解码失败回落）；
 * [DecoderStrategy.HardwareOnly] 时再用 [MediaCodecSelector] 只保留硬件解码器。
 * 工程未集成 FFmpeg 扩展解码器，因此不存在「强制软解」。
 */
@OptIn(UnstableApi::class)
internal fun buildManagedExoPlayer(
    context: Context,
    decoderStrategy: DecoderStrategy,
    configure: (ExoPlayer.Builder) -> ExoPlayer.Builder = { it },
): ExoPlayer {
    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .apply {
            if (decoderStrategy == DecoderStrategy.HardwareOnly) {
                setMediaCodecSelector(hardwareOnlyMediaCodecSelector())
            }
        }
    return configure(ExoPlayer.Builder(context, renderersFactory))
        .build()
        .apply { playWhenReady = true }
}

/**
 * 强制硬解：只返回 `hardwareAccelerated` 的解码器。
 * 设备确实没有对应硬解时回退默认候选，避免选择器返回空列表导致取流直接失败。
 */
@OptIn(UnstableApi::class)
private fun hardwareOnlyMediaCodecSelector(): MediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneling)
        val hardware = all.filter { it.hardwareAccelerated }
        if (hardware.isNotEmpty()) {
            hardware
        } else {
            android.util.Log.w("TVBoxPlayer", "强制硬解：$mimeType 无可用硬件解码器，已回退默认解码器")
            all
        }
    }

/**
 * 统一的播放器持有：进入组合时创建，离开组合时安全释放。
 * [decoderStrategy] 变化会重建播放器（解码器只在构建时选择），页面需按重建语义续播/重连。
 */
@Composable
internal fun rememberManagedExoPlayer(
    decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
): ExoPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember(decoderStrategy) {
        buildManagedExoPlayer(context, decoderStrategy)
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
