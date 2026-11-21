package com.tvbox.app.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * #149/#364：字幕样式（字号倍率 + 背景档位）。
 * 网络流内嵌字幕与外挂字幕都由 Media3 的 SubtitleView 渲染，样式统一在这里持有，
 * 播放器画面层读取同一份 Compose 状态并即时应用，不重建播放器。
 */
internal enum class SubtitleBackground { Default, Black, None }

internal data class SubtitleViewStyle(
    val fontSizeScale: Float = 1f,
    val background: SubtitleBackground = SubtitleBackground.Default,
) {
    companion object {
        val Default = SubtitleViewStyle()
        /** 字幕字号档位：小 / 标准 / 大 / 更大 / 最大。 */
        val FONT_SCALE_STEPS = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)
    }
}

/** #149/#364：字幕样式的进程内持久化状态，跨切集/换源保持，播放页共用。 */
internal object SubtitleStyleController {
    var style by mutableStateOf(SubtitleViewStyle.Default)
        private set

    fun setFontSizeScale(scale: Float) {
        style = style.copy(fontSizeScale = scale.coerceIn(0.6f, 2.5f))
    }

    fun setBackground(background: SubtitleBackground) {
        style = style.copy(background = background)
    }
}

internal fun subtitleScaleLabel(scale: Float): String = when (scale) {
    0.8f -> "小"
    1f -> "标准"
    1.25f -> "大"
    1.5f -> "更大"
    else -> "最大"
}

internal fun subtitleBackgroundLabel(background: SubtitleBackground): String = when (background) {
    SubtitleBackground.Default -> "默认背景"
    SubtitleBackground.Black -> "黑底"
    SubtitleBackground.None -> "透明"
}

/**
 * 音轨/字幕轨道状态：跟随播放器实例重新读取，轨道变化时刷新。
 * 播放器重建（切换解码策略等）后由新的 player 重新挂监听，避免沿用旧实例的轨道。
 */
internal class PlayerTracksState {
    var audioTracks by mutableStateOf<List<PlayerChromeTrack>>(emptyList())
    var subtitleTracks by mutableStateOf<List<PlayerChromeTrack>>(emptyList())
}

@Composable
internal fun rememberPlayerTracks(player: Player): PlayerTracksState {
    val state = remember(player) { PlayerTracksState() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                state.audioTracks = buildTrackOptions(tracks, C.TRACK_TYPE_AUDIO, "音轨")
                state.subtitleTracks = buildTrackOptions(tracks, C.TRACK_TYPE_TEXT, "字幕")
            }
        }
        player.addListener(listener)
        listener.onTracksChanged(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }
    return state
}

private fun buildTrackOptions(
    tracks: Tracks,
    trackType: Int,
    fallbackPrefix: String,
): List<PlayerChromeTrack> {
    val result = mutableListOf<PlayerChromeTrack>()
    var order = 0
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != trackType) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            order++
            val format = group.getTrackFormat(trackIndex)
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: format.language?.takeIf { it.isNotBlank() }
                ?: "$fallbackPrefix $order"
            result += PlayerChromeTrack(
                id = "$groupIndex:$trackIndex",
                label = label,
                selected = group.isTrackSelected(trackIndex),
            )
        }
    }
    return result
}

/** 选中音轨；[trackId] 解析失败（轨道已随重建失效）时保持当前选择不变。 */
@OptIn(UnstableApi::class)
internal fun Player.selectAudioTrack(trackId: String) {
    applyTrackOverride(C.TRACK_TYPE_AUDIO, trackId, disableWhenEmpty = false)
}

/** 选中字幕；空 [trackId] 表示关闭字幕。 */
@OptIn(UnstableApi::class)
internal fun Player.selectSubtitleTrack(trackId: String) {
    applyTrackOverride(C.TRACK_TYPE_TEXT, trackId, disableWhenEmpty = true)
}

@OptIn(UnstableApi::class)
private fun Player.applyTrackOverride(trackType: Int, trackId: String, disableWhenEmpty: Boolean) {
    val builder = trackSelectionParameters.buildUpon()
    if (trackId.isEmpty()) {
        if (disableWhenEmpty) builder.setTrackTypeDisabled(trackType, true)
        trackSelectionParameters = builder.build()
        return
    }
    val parts = trackId.split(':')
    val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return
    val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return
    val group = currentTracks.groups.getOrNull(groupIndex) ?: return
    if (group.type != trackType || trackIndex !in 0 until group.length) return
    builder.setTrackTypeDisabled(trackType, false)
    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
    trackSelectionParameters = builder.build()
}
