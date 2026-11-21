package com.tvbox.app.ui

import android.util.TypedValue
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvbox.app.domain.PlaybackFailureKind
import com.tvbox.app.domain.VideoScaleMode

/**
 * 共享播放画面：四个播放页只传播放器与画面比例，不再各写一份 PlayerView 配置。
 *
 * resize 只改 `PlayerView.resizeMode`，不重建播放器；SurfaceView 由 Media3 内部管理，
 * 切换 Fit/Zoom/Fill 不会触发重新建源。
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSurface(
    player: ExoPlayer?,
    resizeMode: VideoScaleMode,
    showBuffering: Boolean = true,
    posterUrl: String? = null,
    configure: (PlayerView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var firstFrameRendered by remember(player) { mutableStateOf(false) }
    // #149/#364：读取字幕样式状态；变更时 AndroidView 的 update 会重新应用到 SubtitleView。
    val subtitleStyle = SubtitleStyleController.style
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                this.player = player
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isFocusable = false
                isFocusableInTouchMode = false
                useController = false
                setShowBuffering(
                    if (showBuffering) PlayerView.SHOW_BUFFERING_ALWAYS
                    else PlayerView.SHOW_BUFFERING_NEVER,
                )
                applyVideoScaleMode(resizeMode)
                // #149/#364：字幕样式只作用于 SubtitleView，不重建 ExoPlayer、不打断播放。
                applySubtitleStyle(subtitleStyle)
                configure(this)
            }
        },
        update = { view ->
            view.player = player
            view.applyVideoScaleMode(resizeMode)
            view.applySubtitleStyle(subtitleStyle)
            view.setShowBuffering(
                if (showBuffering) PlayerView.SHOW_BUFFERING_ALWAYS
                else PlayerView.SHOW_BUFFERING_NEVER,
            )
        },
            modifier = Modifier.fillMaxSize(),
        )
        if (posterUrl != null && !firstFrameRendered) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * 画面比例即时生效：只改媒体视图的缩放模式与比例框，不重建播放器、不打断播放。
 * 固定比例档位由 [VideoScaleMode.aspectRatio] 把画面框约束到目标比例（居中留黑边），
 * 配合 FIT 在框内保持视频原始比例；其余档位清空比例（跟随容器与 resizeMode）。
 * 每次应用都打印生效的模式与比例框，便于确认“画面比例设置”确实生效。
 */
@OptIn(UnstableApi::class)
private fun PlayerView.applyVideoScaleMode(mode: VideoScaleMode) {
    resizeMode = mode.toPlayerResizeMode()
    val ratio = mode.aspectRatio ?: 0f
    findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?.setAspectRatio(ratio)
    PlayerLog.i(
        "TVBoxVideoScale",
        "applyVideoScaleMode mode=${mode.storageKey}, resizeMode=$resizeMode, frameAspectRatio=$ratio",
    )
}

/**
 * #149/#364：把字号/背景档位应用到 Media3 字幕视图。
 * 内嵌字幕与外挂文本轨都由 SubtitleView 绘制，因此这一处设置对两条路径都生效；
 * 用固定 sp 字号乘以倍率，避免依赖 Media3 内部默认 fraction 常量导致版本兼容问题。
 */
@OptIn(UnstableApi::class)
private fun PlayerView.applySubtitleStyle(style: SubtitleViewStyle) {
    val subtitleView = subtitleView ?: return
    val default = CaptionStyleCompat.DEFAULT
    val backgroundColor = when (style.background) {
        SubtitleBackground.Black -> android.graphics.Color.BLACK
        SubtitleBackground.None -> android.graphics.Color.TRANSPARENT
        SubtitleBackground.Default -> default.backgroundColor
    }
    subtitleView.setStyle(
        CaptionStyleCompat(
            default.foregroundColor,
            backgroundColor,
            default.windowColor,
            default.edgeType,
            default.edgeColor,
            default.typeface,
        ),
    )
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * style.fontSizeScale)
}

/** 播放状态表达：加载中 / 三类失败（取流失败、建源后未出帧、出帧后卡死）。 */
internal sealed interface PlaybackStatus {
    data object Hidden : PlaybackStatus

    data class Loading(val text: String) : PlaybackStatus

    data class Failed(
        val kind: PlaybackFailureKind,
        val title: String,
        val message: String,
        val retryEnabled: Boolean = true,
        val nextLabel: String? = null,
    ) : PlaybackStatus

    /** 点播无下一集且播放自然结束：提示"已播完"，可返回详情。 */
    data class Finished(
        val title: String,
        val message: String,
    ) : PlaybackStatus
}

internal fun playbackFailureTitle(kind: PlaybackFailureKind): String = when (kind) {
    PlaybackFailureKind.SourceFailed -> "取流失败"
    PlaybackFailureKind.FirstFrameTimeout -> "已连接但迟迟没有画面"
    // 软卡顿只用于页内轻提示，不会进入失败浮层；给兜底文案即可。
    PlaybackFailureKind.StallSoft -> "正在重连"
    PlaybackFailureKind.Stalled -> "播放已中断"
}

internal fun playbackFailureMessage(kind: PlaybackFailureKind): String = when (kind) {
    PlaybackFailureKind.SourceFailed -> "当前线路取流失败，可重试当前线路或切换到下一台。"
    PlaybackFailureKind.FirstFrameTimeout -> "已建立连接但始终未出画面，可重试当前线路或切换到下一台。"
    PlaybackFailureKind.StallSoft -> "连接短暂中断，正在自动恢复，请稍候。"
    PlaybackFailureKind.Stalled -> "画面已中断，可重试当前线路或切换到下一台。"
}

/**
 * 共享状态浮层：加载环与失败卡片走同一套语义；
 * [onNext] 为空时只显示重试（点播没有"下一台"，由页面传 null）。
 */
@Composable
internal fun PlaybackStatusOverlay(
    status: PlaybackStatus,
    onRetry: () -> Unit = {},
    onNext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    retryFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** 点播"已播完"时的返回详情出口；为空时按钮只关闭提示。 */
    onFinishedBack: (() -> Unit)? = null,
) {
    when (status) {
        PlaybackStatus.Hidden -> Unit
        is PlaybackStatus.Loading -> {
            Column(
                modifier = modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.64f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = status.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        is PlaybackStatus.Failed -> {
            Column(
                modifier = modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = status.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = status.message,
                    color = Color.White.copy(alpha = 0.76f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlButton(
                        "重试",
                        onRetry,
                        modifier = if (retryFocusRequester != null) {
                            Modifier.focusRequester(retryFocusRequester)
                        } else {
                            Modifier
                        },
                        primary = true,
                        enabled = status.retryEnabled,
                    )
                    val nextLabel = status.nextLabel
                    if (nextLabel != null && onNext != null) {
                        ControlButton(nextLabel, onNext)
                    }
                }
            }
        }
        is PlaybackStatus.Finished -> {
            Column(
                modifier = modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = status.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = status.message,
                    color = Color.White.copy(alpha = 0.76f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                ControlButton("返回详情", onFinishedBack ?: {}, primary = true)
            }
        }
    }
}
