package com.tvbox.app.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ── 按键路由：页面注入 SeekMap / ChannelMap / RoomMap，共享层不判断“是点播还是直播” ──────

internal interface PlayerKeyMap {
    fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean
}

internal fun Modifier.playerKeyInput(map: PlayerKeyMap, chrome: PlayerChromeState): Modifier =
    onPreviewKeyEvent { event -> map.handle(event, chrome) }

/** 点播遥控：左右快进快退（长按连发）、OK 暂停、菜单切倍速、上一/下一集。 */
internal class PlayerSeekKeyMap(
    var callbacks: Callbacks,
) : PlayerKeyMap {
    data class Callbacks(
        val onSeekBy: (Long) -> Unit,
        val onFinishSeek: () -> Unit,
        val onToggle: () -> Unit,
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onChangeEpisode: (Int) -> Unit,
        val onCycleSpeed: () -> Unit,
    )

    private val remote = PlayerRemoteKeyState()

    override fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean {
        val native = event.nativeKeyEvent
        if (chrome.visible) chrome.interaction++
        if (chrome.visible && native.keyCode in listOf(
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            )
        ) {
            return false
        }
        return when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_BACK -> {
                if (chrome.visible || chrome.panel != PlayerPanel.None) {
                    if (event.type == KeyEventType.KeyUp) {
                        if (chrome.panel != PlayerPanel.None) chrome.closePanel() else chrome.hide()
                    }
                    true
                } else {
                    false
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                when (event.type) {
                    KeyEventType.KeyDown -> if (remote.shouldHandleSeek(native.eventTime, native.repeatCount)) {
                        remote.seekChanged = true
                        callbacks.onSeekBy(-REMOTE_SEEK_STEP_MS)
                    }
                    KeyEventType.KeyUp -> {
                        if (remote.seekChanged) callbacks.onFinishSeek()
                        remote.resetSeek()
                    }
                }
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                when (event.type) {
                    KeyEventType.KeyDown -> if (remote.shouldHandleSeek(native.eventTime, native.repeatCount)) {
                        remote.seekChanged = true
                        callbacks.onSeekBy(REMOTE_SEEK_STEP_MS)
                    }
                    KeyEventType.KeyUp -> {
                        if (remote.seekChanged) callbacks.onFinishSeek()
                        remote.resetSeek()
                    }
                }
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            -> {
                if (chrome.visible) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) chrome.bump()
                    true
                }
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onToggle()
                true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onPlay()
                true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onPause()
                true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
            AndroidKeyEvent.KEYCODE_1,
            AndroidKeyEvent.KEYCODE_NUMPAD_1,
            -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onChangeEpisode(-1)
                true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT,
            AndroidKeyEvent.KEYCODE_3,
            AndroidKeyEvent.KEYCODE_NUMPAD_3,
            -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onChangeEpisode(1)
                true
            }
            AndroidKeyEvent.KEYCODE_MENU -> {
                if (event.type == KeyEventType.KeyUp) callbacks.onCycleSpeed()
                true
            }
            else -> false
        }
    }
}

/** 电视直播遥控：频道数字直选、浮层浏览、上下换线路、左右换台。 */
internal class PlayerChannelKeyMap(
    var callbacks: Callbacks,
) : PlayerKeyMap {
    data class Callbacks(
        val isChannelListVisible: () -> Boolean,
        val closeChannelList: () -> Unit,
        val isLineControlVisible: () -> Boolean,
        val closeLineControl: () -> Unit,
        val keepChannelListVisibleWhileBrowsing: () -> Unit,
        val isNumberInputActive: () -> Boolean,
        val onNumberKey: (Int) -> Unit,
        val onNumberMove: (Int) -> Unit,
        val onNumberCommit: () -> Unit,
        val onNumberDelete: () -> Unit,
        val onPreviousChannel: () -> Unit,
        val onNextChannel: () -> Unit,
        val onPreviousLine: () -> Unit,
        val onNextLine: () -> Unit,
        val onOpenChannelList: () -> Unit,
        val onConfirm: () -> Unit,
        /** 失败卡（重试/下一台）在场时，OK 必须交给当前聚焦的按钮，不在页面级改写为打开频道列表。 */
        val isFailureCardVisible: () -> Boolean = { false },
    )

    override fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean {
        if (event.type != KeyEventType.KeyUp) return false
        val key = event.nativeKeyEvent.keyCode
        // 任意按键唤出信息条并重置 3 秒淡出计时。
        chrome.bump()
        if (callbacks.isChannelListVisible()) {
            val digit = liveNumberKeyOf(key)
            if (digit != null) {
                callbacks.closeChannelList()
                callbacks.onNumberKey(digit)
                return true
            }
            return when (key) {
                AndroidKeyEvent.KEYCODE_MENU,
                AndroidKeyEvent.KEYCODE_INFO,
                -> {
                    callbacks.closeChannelList()
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP,
                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                -> {
                    callbacks.keepChannelListVisibleWhileBrowsing()
                    false
                }
                else -> false
            }
        }
        if (callbacks.isLineControlVisible()) {
            return when (key) {
                AndroidKeyEvent.KEYCODE_MENU,
                AndroidKeyEvent.KEYCODE_INFO,
                -> {
                    callbacks.closeLineControl()
                    true
                }
                else -> false
            }
        }
        if (callbacks.isNumberInputActive()) {
            return when (key) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    callbacks.onNumberMove(-1)
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    callbacks.onNumberMove(1)
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                -> {
                    callbacks.onNumberCommit()
                    true
                }
                AndroidKeyEvent.KEYCODE_DEL -> {
                    callbacks.onNumberDelete()
                    true
                }
                else -> false
            }
        }
        return when (key) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                callbacks.onPreviousChannel()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                callbacks.onNextChannel()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            -> {
                if (callbacks.isFailureCardVisible()) {
                    // 焦点在「重试/下一台」上：不消费，让按钮自己拿到 OK。
                    false
                } else {
                    callbacks.onConfirm()
                    true
                }
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                callbacks.onConfirm()
                true
            }
            AndroidKeyEvent.KEYCODE_MENU,
            AndroidKeyEvent.KEYCODE_INFO,
            -> {
                callbacks.onOpenChannelList()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                callbacks.onPreviousLine()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                callbacks.onNextLine()
                true
            }
            else -> {
                val digit = liveNumberKeyOf(key)
                if (digit != null) {
                    callbacks.onNumberKey(digit)
                    true
                } else {
                    false
                }
            }
        }
    }
}

/** 平台直播遥控：左右换房间、上下换候选、OK 打开线路、菜单强制重连。 */
internal class PlayerRoomKeyMap(
    var callbacks: Callbacks,
) : PlayerKeyMap {
    data class Callbacks(
        val isLineControlVisible: () -> Boolean,
        val onPreviousRoom: () -> Unit,
        val onNextRoom: () -> Unit,
        val onPreviousCandidate: () -> Unit,
        val onNextCandidate: () -> Unit,
        val onOpenLine: () -> Unit,
        val onReconnect: () -> Unit,
    )

    override fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean {
        if (event.type != KeyEventType.KeyUp) return false
        if (callbacks.isLineControlVisible()) return false
        return when (event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                callbacks.onPreviousRoom()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                callbacks.onNextRoom()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                callbacks.onPreviousCandidate()
                chrome.bump()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                callbacks.onNextCandidate()
                chrome.bump()
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                callbacks.onOpenLine()
                chrome.bump()
                true
            }
            AndroidKeyEvent.KEYCODE_MENU,
            AndroidKeyEvent.KEYCODE_INFO,
            -> {
                callbacks.onReconnect()
                chrome.bump()
                true
            }
            else -> false
        }
    }
}

// ── 触摸手势：点播与手机直播共用一套，能力开关决定双击/横滑/长按/上下滑 ──────────────

internal data class PlayerTouchCapabilities(
    val seekDrag: Boolean = true,
    val doubleTapSeek: Boolean = true,
    val longPressSpeed: Boolean = true,
    val brightness: Boolean = true,
    val volume: Boolean = true,
    val lock: Boolean = true,
    val verticalSwipeEnabled: Boolean = true,
    /** 上下滑不再要求「垂直方向占优」；关闭上下滑时仍把这类拖动视为误触，不触发单击。 */
    val verticalSwipeLoose: Boolean = false,
)

internal data class PlayerTouchCallbacks(
    val isLocked: () -> Boolean,
    val currentPositionMs: () -> Long,
    val durationMs: () -> Long?,
    val onToggleControls: () -> Unit,
    val onLockedTap: () -> Unit,
    val onPlaybackToggle: () -> Unit,
    val onSeekBy: (deltaMs: Long, label: String) -> Unit,
    val onSeekDragPreview: (positionMs: Long, durationMs: Long) -> Unit,
    val onSeekDragCommit: (positionMs: Long) -> Unit,
    val onBrightnessChanged: (brightness: Float, percent: Int) -> Unit,
    val onVolumeChanged: (volume: Int, maxVolume: Int) -> Unit,
    val onLongPressStart: () -> Unit,
    val onLongPressEnd: () -> Unit,
)

/**
 * 共享触摸手势处理器。点播在 PlayerView 上挂载时开启双击/横滑/长按；
 * 手机直播只开启竖滑亮度/音量与单击唤出控件。
 */
internal class PlayerTouchGestureHandler(
    activity: Activity?,
    private val audioManager: AudioManager?,
    var capabilities: PlayerTouchCapabilities,
    var callbacks: PlayerTouchCallbacks,
) : View.OnTouchListener {
    private val context: Context? = activity
    private val window: Window? = activity?.window
    private val handler = Handler(Looper.getMainLooper())
    private var state = PlayerTouchGestureState()

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val width = view.width
        val height = view.height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.downX = event.x
                state.downY = event.y
                state.downPositionMs = callbacks.currentPositionMs().coerceAtLeast(0L)
                state.seekTargetMs = state.downPositionMs
                state.seeking = false
                state.swipeMode = PlayerSwipeMode.None
                state.dragWithoutAction = false
                state.startBrightness = context?.let { window?.currentScreenBrightness(it) } ?: DEFAULT_SCREEN_BRIGHTNESS
                state.startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                state.maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                if (capabilities.longPressSpeed && !callbacks.isLocked()) scheduleLongPressSpeed(view)
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.x - state.downX
                val totalDy = event.y - state.downY
                val durationMs = callbacks.durationMs()
                if (state.longPressActive) return true
                if (!callbacks.isLocked() && state.swipeMode == PlayerSwipeMode.None) {
                    when {
                        capabilities.seekDrag && durationMs != null &&
                            abs(totalDx) > touchSlop(view) && abs(totalDx) > abs(totalDy) -> {
                            state.swipeMode = PlayerSwipeMode.Seek
                        }
                        capabilities.verticalSwipeEnabled && abs(totalDy) > touchSlop(view) &&
                            (capabilities.verticalSwipeLoose || abs(totalDy) > abs(totalDx)) -> {
                            state.swipeMode = when {
                                !capabilities.brightness && !capabilities.volume -> PlayerSwipeMode.None
                                !capabilities.brightness -> PlayerSwipeMode.Volume
                                !capabilities.volume -> PlayerSwipeMode.Brightness
                                state.downX < width / 2f -> PlayerSwipeMode.Brightness
                                else -> PlayerSwipeMode.Volume
                            }
                        }
                        capabilities.verticalSwipeLoose && abs(totalDy) > touchSlop(view) -> {
                            // 关闭上下滑时（如手机直播横屏）仍视为拖动，抬手不触发单击。
                            state.dragWithoutAction = true
                        }
                    }
                }
                if (callbacks.isLocked()) {
                    cancelLongPressSpeed()
                } else if (state.swipeMode == PlayerSwipeMode.Seek && durationMs != null) {
                    cancelLongPressSpeed()
                    cancelPendingSingleTap()
                    state.seeking = true
                    val target = calculateDragSeekPosition(
                        startPositionMs = state.downPositionMs,
                        dragPx = totalDx,
                        viewWidthPx = width,
                        durationMs = durationMs,
                    )
                    state.seekTargetMs = target
                    callbacks.onSeekDragPreview(target, durationMs)
                } else if (state.swipeMode == PlayerSwipeMode.Brightness) {
                    cancelLongPressSpeed()
                    cancelPendingSingleTap()
                    val brightness = (state.startBrightness - totalDy / height.toFloat())
                        .coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
                    window?.setScreenBrightness(brightness)
                    callbacks.onBrightnessChanged(brightness, (brightness * 100).roundToInt())
                } else if (state.swipeMode == PlayerSwipeMode.Volume && state.maxVolume > 0) {
                    cancelLongPressSpeed()
                    cancelPendingSingleTap()
                    val volume = (state.startVolume -
                        (totalDy / height.toFloat() * state.maxVolume).roundToInt())
                        .coerceIn(0, state.maxVolume)
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                    callbacks.onVolumeChanged(volume, state.maxVolume)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val swipeMode = state.swipeMode
                state.swipeMode = PlayerSwipeMode.None
                val wasLongPressActive = state.longPressActive
                val dragWithoutAction = state.dragWithoutAction
                state.dragWithoutAction = false
                cancelLongPressSpeed()
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    state.seeking = false
                    cancelPendingSingleTap()
                    return true
                }
                if (callbacks.isLocked()) {
                    state.seeking = false
                    cancelPendingSingleTap()
                    if (swipeMode == PlayerSwipeMode.None && !wasLongPressActive) {
                        callbacks.onLockedTap()
                    }
                    return true
                }
                if (state.seeking) {
                    state.seeking = false
                    callbacks.onSeekDragCommit(state.seekTargetMs)
                    return true
                }
                if (swipeMode == PlayerSwipeMode.Brightness || swipeMode == PlayerSwipeMode.Volume) {
                    cancelPendingSingleTap()
                    return true
                }
                if (wasLongPressActive || dragWithoutAction) {
                    cancelPendingSingleTap()
                    return true
                }
                val slop = touchSlop(view)
                val distance = squaredDistance(event.x, event.y, state.lastTapX, state.lastTapY)
                val isDoubleTap = capabilities.doubleTapSeek &&
                    event.eventTime - state.lastTapUpTimeMs <= ViewConfiguration.getDoubleTapTimeout().toLong() &&
                    distance <= slop * slop
                if (isDoubleTap) {
                    state.lastTapUpTimeMs = 0L
                    when {
                        event.x < width / 3f -> callbacks.onSeekBy(-DOUBLE_TAP_SEEK_MS, "快退 10 秒")
                        event.x > width * 2f / 3f -> callbacks.onSeekBy(DOUBLE_TAP_SEEK_MS, "快进 10 秒")
                        else -> callbacks.onPlaybackToggle()
                    }
                } else {
                    state.lastTapUpTimeMs = event.eventTime
                    state.lastTapX = event.x
                    state.lastTapY = event.y
                    cancelPendingSingleTap()
                    if (capabilities.doubleTapSeek) {
                        val runnable = Runnable {
                            state.singleTapRunnable = null
                            callbacks.onToggleControls()
                        }
                        state.singleTapRunnable = runnable
                        handler.postDelayed(runnable, ViewConfiguration.getDoubleTapTimeout().toLong())
                    } else {
                        callbacks.onToggleControls()
                    }
                }
            }
        }
        return true
    }

    fun release() {
        // 页面销毁时若长按仍在进行，必须回调 onLongPressEnd 恢复原倍速，
        // 否则 2x 会一直保留，甚至被写进用户偏好。
        cancelPendingGestures()
        handler.removeCallbacksAndMessages(null)
        state.longPressRunnable = null
        state.singleTapRunnable = null
    }

    /** 锁定态下取消未决的长按/单击，避免锁定后仍触发倍速或控件切换。 */
    fun cancelPendingGestures() {
        cancelLongPressSpeed()
        cancelPendingSingleTap()
    }

    private fun scheduleLongPressSpeed(view: View) {
        state.originalSpeed = 1f
        state.longPressRunnable?.let(handler::removeCallbacks)
        state.longPressActive = false
        val runnable = Runnable {
            state.longPressActive = true
            callbacks.onLongPressStart()
        }
        state.longPressRunnable = runnable
        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPressSpeed() {
        state.longPressRunnable?.let(handler::removeCallbacks)
        state.longPressRunnable = null
        if (state.longPressActive) {
            state.longPressActive = false
            callbacks.onLongPressEnd()
        }
    }

    private fun cancelPendingSingleTap() {
        state.singleTapRunnable?.let(handler::removeCallbacks)
        state.singleTapRunnable = null
    }

    private fun touchSlop(view: View): Int = ViewConfiguration.get(view.context).scaledTouchSlop
}

// ── 共享纯函数与状态 ────────────────────────────────────────────────────────────

private enum class PlayerSwipeMode { None, Seek, Brightness, Volume }

private class PlayerTouchGestureState {
    var downX: Float = 0f
    var downY: Float = 0f
    var downPositionMs: Long = 0L
    var seekTargetMs: Long = 0L
    var seeking: Boolean = false
    var swipeMode: PlayerSwipeMode = PlayerSwipeMode.None
    var dragWithoutAction: Boolean = false
    var startBrightness: Float = DEFAULT_SCREEN_BRIGHTNESS
    var startVolume: Int = 0
    var maxVolume: Int = 0
    var longPressActive: Boolean = false
    var longPressRunnable: Runnable? = null
    var singleTapRunnable: Runnable? = null
    var lastTapUpTimeMs: Long = 0L
    var lastTapX: Float = 0f
    var lastTapY: Float = 0f
    var originalSpeed: Float = 1f
}

private class PlayerRemoteKeyState {
    var lastSeekEventTimeMs: Long = Long.MIN_VALUE
    var seekChanged: Boolean = false

    fun shouldHandleSeek(eventTimeMs: Long, repeatCount: Int): Boolean {
        if (
            repeatCount == 0 ||
            lastSeekEventTimeMs == Long.MIN_VALUE ||
            eventTimeMs - lastSeekEventTimeMs >= REMOTE_SEEK_REPEAT_INTERVAL_MS
        ) {
            lastSeekEventTimeMs = eventTimeMs
            return true
        }
        return false
    }

    fun resetSeek() {
        lastSeekEventTimeMs = Long.MIN_VALUE
        seekChanged = false
    }
}

internal fun calculateDragSeekPosition(
    startPositionMs: Long,
    dragPx: Float,
    viewWidthPx: Int,
    durationMs: Long,
): Long {
    if (viewWidthPx <= 0) return startPositionMs.coerceIn(0L, durationMs)
    val offsetMs = (dragPx / viewWidthPx.toFloat() * durationMs).roundToLong()
    return (startPositionMs + offsetMs).coerceIn(0L, durationMs)
}

private fun squaredDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return dx * dx + dy * dy
}

internal fun Window.currentScreenBrightness(context: Context): Float {
    val current = attributes.screenBrightness
    if (current >= 0f) return current.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
    val systemBrightness = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(DEFAULT_SYSTEM_BRIGHTNESS)
    return (systemBrightness / MAX_SYSTEM_BRIGHTNESS).coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
}

internal fun Window.setScreenBrightness(brightness: Float) {
    attributes = attributes.apply { screenBrightness = brightness.coerceIn(MIN_SCREEN_BRIGHTNESS, 1f) }
}

internal fun Window.restoreScreenBrightness(brightness: Float) {
    attributes = attributes.apply { screenBrightness = brightness }
}

internal const val DOUBLE_TAP_SEEK_MS = 10_000L
internal const val REMOTE_SEEK_STEP_MS = 10_000L
private const val REMOTE_SEEK_REPEAT_INTERVAL_MS = 150L
internal const val MIN_SCREEN_BRIGHTNESS = 0.01f
internal const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
private const val MAX_SYSTEM_BRIGHTNESS = 255f
