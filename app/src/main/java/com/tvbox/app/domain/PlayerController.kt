package com.tvbox.app.domain

/**
 * 播放器控制抽象接口，将遥控器快捷键与手机手势逻辑与 Media3 ExoPlayer 解耦。
 *
 * 设计目标：
 * 1. 便于单元测试——可注入 MockPlayerController 验证快捷键/手势的业务逻辑。
 * 2. 便于替换播放内核——未来若更换播放引擎，只需提供新实现。
 * 3. TV 遥控器与手机手势代码完全独立于 ExoPlayer API。
 *
 * 当前实现（ExoPlayerController）直接封装 ExoPlayer；
 * 测试时可用 [MockPlayerController] 替换。
 *
 * 使用方式：
 * ```kotlin
 * val controller = ExoPlayerController(player)
 * // 遥控器快捷键：
 * controller.seekRelative(+10_000)   // 快进 10s
 * controller.seekRelative(-10_000)  // 快退 10s
 * controller.setSpeed(2.0f)
 * controller.togglePlayPause()
 * // 手机手势由 PlayerScreen 内部处理，通过 controller 回调驱动 UI 状态。
 * ```
 */
interface PlayerController {
    /** 快进/快退指定毫秒数（正数前进，负数后退）。 */
    fun seekRelative(ms: Long)

    /** 设置播放速度，0.5f ~ 4.0f。 */
    fun setSpeed(speed: Float)

    /** 切换播放/暂停状态。 */
    fun togglePlayPause()

    /** 跳转到上一集（点播场景）。 */
    fun playPreviousEpisode()

    /** 跳转到下一集（点播场景）。 */
    fun playNextEpisode()

    /** 当前播放位置（毫秒）。 */
    val currentPosition: Long

    /** 媒体总时长（毫秒），未知时返回 0L。 */
    val duration: Long

    /** 是否正在播放。 */
    val isPlaying: Boolean

    /** 是否正在缓冲。 */
    val isBuffering: Boolean

    /** 当前倍速。 */
    val currentSpeed: Float

    companion object {
        /** 快进/快退默认步长（10 秒）。 */
        const val DEFAULT_SEEK_STEP_MS = 10_000L
        /** 倍速默认步长列表。 */
        val DEFAULT_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}

/**
 * 基于 Media3 ExoPlayer 的 [PlayerController] 实现。
 *
 * @param player Media3 ExoPlayer 实例；调用方负责生命周期管理。
 */
class ExoPlayerController(private val player: androidx.media3.exoplayer.ExoPlayer) : PlayerController {
    override fun seekRelative(ms: Long) {
        val target = (player.currentPosition + ms).coerceAtLeast(0L)
            .coerceAtMost(player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
    }

    override fun setSpeed(speed: Float) {
        val clamped = PlayerSpeedSetting.clamp(speed)
        player.setPlaybackSpeed(clamped)
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override val currentPosition: Long get() = player.currentPosition
    override val duration: Long get() = player.duration
    override val isPlaying: Boolean get() = player.isPlaying
    override val isBuffering: Boolean get() = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
    override val currentSpeed: Float get() = player.playbackParameters.speed

    override fun playPreviousEpisode() {
        if (player.currentPosition > 0L) player.seekToDefaultPosition()
    }

    override fun playNextEpisode() {
        // No-op: actual episode switching is handled by ViewModel.
    }
}

/** 测试用的 Mock [PlayerController]，不依赖真实播放器。 */
class MockPlayerController(
    private var _currentPosition: Long = 0L,
    private var _duration: Long = 0L,
    private var _isPlaying: Boolean = false,
    private var _isBuffering: Boolean = false,
    private var _speed: Float = 1.0f,
) : PlayerController {
    var onSeekCallback: ((Long) -> Unit)? = null
    var onSpeedCallback: ((Float) -> Unit)? = null
    var onToggleCallback: (() -> Unit)? = null

    override fun seekRelative(ms: Long) {
        _currentPosition = (_currentPosition + ms).coerceAtLeast(0L).coerceAtMost(_duration)
        onSeekCallback?.invoke(ms)
    }

    override fun setSpeed(speed: Float) {
        _speed = speed.coerceIn(0.5f, 4.0f)
        onSpeedCallback?.invoke(_speed)
    }

    override fun togglePlayPause() {
        _isPlaying = !_isPlaying
        onToggleCallback?.invoke()
    }

    override val currentPosition: Long get() = _currentPosition
    override val duration: Long get() = _duration
    override val isPlaying: Boolean get() = _isPlaying
    override val isBuffering: Boolean get() = _isBuffering
    override val currentSpeed: Float get() = _speed

    override fun playPreviousEpisode() {}
    override fun playNextEpisode() {}
}
