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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.seekStepMsForHold
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ── 按键路由：页面注入 SeekMap / ChannelMap / RoomMap，共享层不判断“是点播还是直播” ──────

internal interface PlayerKeyMap {
    fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean
}

/** 多遥控器去重窗口（毫秒）：同一设备在同一 key 上 80ms 内重复上报只处理第一次。 */
private const val REMOTE_DEDUP_WINDOW_MS = 80L

/**
 * 遥控器事件来源缓存：按 (deviceId, keyCode, 物理动作) 记录最近一次处理时间，
 * 用于过滤多遥控器同时按下相同按键时的抖动。
 * 物理动作参与去重 key（ACTION_DOWN=0 / ACTION_UP=1）：一次按压的 DOWN 与 UP 是
 * 不同事件，快速按压（<80ms）的 KEYUP 不会被 KEYDOWN 的去重窗口吞掉；
 * 仅对同一动作类型事件的高频重复去重。
 * deviceId <= 0 视为系统/虚拟键无法区分真实设备，统一放行。
 *
 * 每个调用方应持有独立实例（通过 remember 创建），避免多播放器实例互相干扰去重窗口。
 */
internal class RemoteKeyCache {
    private val entries = LinkedHashMap<Triple<Int, Int, Int>, Long>(16, 0.75f, true)
    private val maxEntries = 64

    fun shouldDrop(deviceId: Int, keyCode: Int, action: Int, nowMs: Long): Boolean {
        if (deviceId <= 0) return false
        val key = Triple(deviceId, keyCode, action)
        val last = entries[key]
        if (last == null || nowMs - last >= REMOTE_DEDUP_WINDOW_MS) {
            // 放行分支也要刷新时间戳，否则首个去重窗口过后永远命中不了去重。
            entries[key] = nowMs
            return false
        }
        return true
    }

    fun trimIfNeeded() {
        // P2-10：极端连发（长按快进 30s+）会周期性撞上 maxEntries。原来用 clear() 全表清空，
        // 会让去重窗口失效一瞬、偶发一次双跳。这里改为 LRU 逐条淘汰（LinkedHashMap accessOrder=true，
        // 迭代首个即最久未用），只删最旧一条，保留去重窗口连续。
        while (entries.size >= maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }
}

@Composable
internal fun Modifier.playerKeyInput(map: PlayerKeyMap, chrome: PlayerChromeState): Modifier {
    // RemoteKeyCache 在组合期创建：每个调用点持有独立实例，多播放器场景去重窗口互不干扰，
    // 且保证跨按键复用去重窗口（80ms）。onPreviewKeyEvent 回调非组合上下文，不能在此处 remember。
    val cache = remember { RemoteKeyCache() }
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (cache.shouldDrop(native.deviceId, native.keyCode, native.action, android.os.SystemClock.elapsedRealtime())) {
            return@onPreviewKeyEvent false
        }
        cache.trimIfNeeded()
        map.handle(event, chrome)
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
    // ---- 以下为新增的逐项开关，全部给出与旧行为一致的默认值，旧调用点无需修改 ----
    /** 左侧上下滑调亮度。 */
    val leftBrightnessEnabled: Boolean = true,
    /** 右侧上下滑调音量。 */
    val rightVolumeEnabled: Boolean = true,
    /** 横向拖动进度。 */
    val horizontalSeekEnabled: Boolean = true,
    /** 左右两侧双击快进/快退。 */
    val doubleTapSideEnabled: Boolean = true,
    /** 中间双击播放/暂停。 */
    val doubleTapCenterEnabled: Boolean = true,
    /** 长按临时倍速。 */
    val longPressSpeedEnabled: Boolean = true,
    /** 双指捏合缩放画面。 */
    val pinchZoomEnabled: Boolean = true,
    /** 顶部下滑返回。 */
    val topSwipeBackEnabled: Boolean = false,
    /** 手势灵敏度：0=低 / 1=中 / 2=高。 */
    val sensitivity: Int = 1,
    /** 横屏握持时底部边缘不响应进度手势。 */
    val edgeGuardEnabled: Boolean = true,
    /** 手势锁开关：关闭时控制栏不提供锁定入口。 */
    val gestureLockEnabled: Boolean = false,
) {
    // 旧字段与新开关取交集，保证只设置旧字段的直播页行为完全不变。
    val effectiveSeek: Boolean get() = seekDrag && horizontalSeekEnabled
    val effectiveDoubleTap: Boolean get() = doubleTapSeek && (doubleTapSideEnabled || doubleTapCenterEnabled)
    val effectiveLongPress: Boolean get() = longPressSpeed && longPressSpeedEnabled
    val effectiveBrightness: Boolean get() = brightness && leftBrightnessEnabled
    val effectiveVolume: Boolean get() = volume && rightVolumeEnabled
}

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
    /** 捏合缩放：返回相对缩放系数（>1 放大）。默认空实现，旧调用点行为不变。 */
    val onPinchZoom: (scaleFactor: Float) -> Unit = {},
    /** 顶部下滑返回。默认空实现，旧调用点行为不变。 */
    val onTopSwipeBack: () -> Unit = {},
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

    /** 双击快进步长（毫秒）；由播放页按 fastSeekStepSeconds 注入。 */
    var doubleTapSeekMs: Long = DOUBLE_TAP_SEEK_MS

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val width = view.width
        val height = view.height
        val density = view.context.resources.displayMetrics.density
        // 灵敏度：低档需要更大位移，高档更小位移；仅影响手势，不影响播放器倍速。
        val factor = when (capabilities.sensitivity) {
            0 -> 0.6f
            2 -> 1.6f
            else -> 1f
        }
        val gestureSlop = (touchSlop(view) / factor).toInt().coerceAtLeast(1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.pinching = false
                state.pinchDistance = 0f
                state.topSwipeBackTriggered = false
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
                if (capabilities.effectiveLongPress && !callbacks.isLocked()) scheduleLongPressSpeed(view)
            }
            MotionEvent.ACTION_MOVE -> {
                // 捏合缩放优先于所有单指手势；只上报相对系数，是否应用由页面决定。
                if (event.pointerCount >= 2) {
                    if (capabilities.pinchZoomEnabled && !callbacks.isLocked()) {
                        val distance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                        if (state.pinching && state.pinchDistance > 0f) {
                            callbacks.onPinchZoom(distance / state.pinchDistance)
                        }
                        state.pinchDistance = distance
                        state.pinching = true
                        cancelLongPressSpeed()
                        cancelPendingSingleTap()
                    }
                    return true
                }
                if (state.pinching) return true
                val totalDx = event.x - state.downX
                val totalDy = event.y - state.downY
                val durationMs = callbacks.durationMs()
                if (state.longPressActive) return true
                if (!callbacks.isLocked() && state.swipeMode == PlayerSwipeMode.None) {
                    // 顶部下滑返回：仅在顶部边缘内触发，且同一次按下只触发一次。
                    if (
                        capabilities.topSwipeBackEnabled && !state.topSwipeBackTriggered &&
                        state.downY <= TOP_SWIPE_EDGE_DP * density && totalDy > gestureSlop &&
                        abs(totalDy) > abs(totalDx)
                    ) {
                        state.topSwipeBackTriggered = true
                        state.dragWithoutAction = true
                        cancelLongPressSpeed()
                        cancelPendingSingleTap()
                        callbacks.onTopSwipeBack()
                        return true
                    }
                    when {
                        capabilities.effectiveSeek && durationMs != null &&
                            abs(totalDx) > gestureSlop && abs(totalDx) > abs(totalDy) &&
                            // 边缘保护：横屏握持时底部不响应进度手势，避免误触。
                            !(capabilities.edgeGuardEnabled &&
                                state.downY >= height - SEEK_EDGE_GUARD_DP * density) -> {
                            state.swipeMode = PlayerSwipeMode.Seek
                        }
                        capabilities.verticalSwipeEnabled && abs(totalDy) > gestureSlop &&
                            (capabilities.verticalSwipeLoose || abs(totalDy) > abs(totalDx)) -> {
                            val leftHalf = state.downX < width / 2f
                            state.swipeMode = when {
                                leftHalf && capabilities.effectiveBrightness -> PlayerSwipeMode.Brightness
                                !leftHalf && capabilities.effectiveVolume -> PlayerSwipeMode.Volume
                                capabilities.effectiveBrightness && !capabilities.effectiveVolume ->
                                    PlayerSwipeMode.Brightness
                                capabilities.effectiveVolume && !capabilities.effectiveBrightness ->
                                    PlayerSwipeMode.Volume
                                else -> PlayerSwipeMode.None
                            }
                        }
                        capabilities.verticalSwipeLoose && abs(totalDy) > gestureSlop -> {
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
                    // height==0 且 totalDy==0 时 totalDy/height 得 NaN，经 coerceIn 后仍是 NaN，
                    // setScreenBrightness(NaN) 写入非法亮度；高度未布局则跳过。
                    if (height <= 0f) return true
                    val brightness = (state.startBrightness - totalDy * factor / height.toFloat())
                        .coerceIn(MIN_SCREEN_BRIGHTNESS, 1f)
                    window?.setScreenBrightness(brightness)
                    callbacks.onBrightnessChanged(brightness, (brightness * 100).roundToInt())
                } else if (state.swipeMode == PlayerSwipeMode.Volume && state.maxVolume > 0) {
                    cancelLongPressSpeed()
                    cancelPendingSingleTap()
                    // height<=0 已在上面短路；此处 float division 不会得 NaN。
                    val volume = (state.startVolume -
                        (totalDy * factor / height.toFloat() * state.maxVolume).roundToInt())
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
                val wasPinching = state.pinching
                state.pinching = false
                state.pinchDistance = 0f
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
                if (wasLongPressActive || dragWithoutAction || wasPinching) {
                    cancelPendingSingleTap()
                    return true
                }
                val distance = squaredDistance(event.x, event.y, state.lastTapX, state.lastTapY)
                val canDoubleTapSide = capabilities.doubleTapSeek && capabilities.doubleTapSideEnabled
                val canDoubleTapCenter = capabilities.doubleTapSeek && capabilities.doubleTapCenterEnabled
                val doubleTapPossible = canDoubleTapSide || canDoubleTapCenter
                val isDoubleTap = doubleTapPossible &&
                    event.eventTime - state.lastTapUpTimeMs <= ViewConfiguration.getDoubleTapTimeout().toLong() &&
                    distance <= gestureSlop * gestureSlop
                if (isDoubleTap) {
                    state.lastTapUpTimeMs = 0L
                    val stepSeconds = (doubleTapSeekMs / 1_000L).coerceAtLeast(1L)
                    when {
                        canDoubleTapSide && event.x < width / 3f ->
                            callbacks.onSeekBy(-doubleTapSeekMs, "快退 $stepSeconds 秒")
                        canDoubleTapSide && event.x > width * 2f / 3f ->
                            callbacks.onSeekBy(doubleTapSeekMs, "快进 $stepSeconds 秒")
                        canDoubleTapCenter -> callbacks.onPlaybackToggle()
                    }
                } else {
                    state.lastTapUpTimeMs = event.eventTime
                    state.lastTapX = event.x
                    state.lastTapY = event.y
                    cancelPendingSingleTap()
                    if (doubleTapPossible) {
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
    var pinching: Boolean = false
    var pinchDistance: Float = 0f
    var topSwipeBackTriggered: Boolean = false
}

internal class PlayerRemoteKeyState {
    var lastSeekEventTimeMs: Long = Long.MIN_VALUE
    var seekChanged: Boolean = false

    /** 本轮长按的起始时刻；每次按下（repeatCount == 0）重新计时。 */
    private var seekStartEventTimeMs: Long = Long.MIN_VALUE

    fun shouldHandleSeek(eventTimeMs: Long, repeatCount: Int): Boolean {
        if (repeatCount == 0) {
            seekStartEventTimeMs = eventTimeMs
        }
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

    /**
     * 按住越久步进越大（10s → 30s → 60s）。单次按下时 held≈0，仍是原有 10 秒语义；
     * 档位判定在 domain 层的纯函数里，可被 JVM 单测覆盖。
     */
    fun currentSeekStepMs(eventTimeMs: Long, stepSeconds: Int = FastSeekSetting.DefaultStepSeconds): Long {
        val held = if (seekStartEventTimeMs == Long.MIN_VALUE) {
            0L
        } else {
            (eventTimeMs - seekStartEventTimeMs).coerceAtLeast(0L)
        }
        return seekStepMsForHold(held, baseStepSeconds = stepSeconds)
    }

    fun resetSeek() {
        lastSeekEventTimeMs = Long.MIN_VALUE
        seekStartEventTimeMs = Long.MIN_VALUE
        seekChanged = false
    }

    // ── 无长短按遥控器（6 键：↑↓←→ OK MENU）的单击分段快进 ─────────────────
    // 痛点：这类遥控器不产生系统连发（repeatCount 恒为 0），按住 ≠ 快进，单击一次只走一步。
    // 方案：把「单击次数」当节拍器——超时窗口（SEEK_TAP_WINDOW_MS）内每次点击步长升一档
    // （10s → 20s → 30s …，封顶 10 档），停点 1 秒后由 UI 侧提交跳转。

    private var tapCount: Int = 0
    private var lastTapEventTimeMs: Long = Long.MIN_VALUE

    /**
     * 单击快进：返回本次点击应走的毫秒数。超时窗口外的首击重置计数（回到 baseStepSeconds）。
     * 与长按连发 [shouldHandleSeek] 独立，两套语义不混用。
     */
    fun tapSeekStepMs(eventTimeMs: Long, stepSeconds: Int): Long {
        tapCount = if (
            lastTapEventTimeMs == Long.MIN_VALUE ||
            eventTimeMs - lastTapEventTimeMs >= SEEK_TAP_WINDOW_MS
        ) {
            1
        } else {
            (tapCount + 1).coerceAtMost(MAX_TAP_SEGMENTS)
        }
        lastTapEventTimeMs = eventTimeMs
        seekChanged = true
        return seekStepMsForHold((tapCount - 1) * SEGMENT_HOLD_STEP_MS, baseStepSeconds = stepSeconds)
    }

    fun resetTapSeek() {
        tapCount = 0
        lastTapEventTimeMs = Long.MIN_VALUE
        seekChanged = false
    }

    companion object {
        /** 单击与长按的分界：按住不足此时长视为单击，达到视为长按连发。 */
        const val LONG_PRESS_MS = 450L
        /** 单击分段快进的超时窗口：停点超过此时长视为一次新意图，UI 侧在此时长后提交跳转。 */
        const val SEEK_TAP_WINDOW_MS = 900L
        /** 分段步长升档的等效按住毫秒间隔（tapCount-1) * 此值喂给 seekStepMsForHold）。 */
        private const val SEGMENT_HOLD_STEP_MS = 350L
        private const val MAX_TAP_SEGMENTS = 10
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
/** 边缘保护：横屏握持时底部该高度内不响应进度手势（dp）。 */
private const val SEEK_EDGE_GUARD_DP = 24f
/** 顶部下滑返回的触发边缘高度（dp）。 */
private const val TOP_SWIPE_EDGE_DP = 24f
// 遥控器首档步进 10 秒由 domain 的 DEFAULT_SEEK_TIERS 定义，
// 长按加速度换算见 PlayerRemoteKeyState.currentSeekStepMs，避免两处常量漂移。
private const val REMOTE_SEEK_REPEAT_INTERVAL_MS = 150L
internal const val MIN_SCREEN_BRIGHTNESS = 0.01f
internal const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
private const val MAX_SYSTEM_BRIGHTNESS = 255f
