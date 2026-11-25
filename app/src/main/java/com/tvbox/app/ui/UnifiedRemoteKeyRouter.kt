package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.resolveHardware
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.seekStepMsForHold

/**
 * 统一遥控键位路由器（对应「遥控器快捷键统一 + 支持自定义」需求）。
 *
 * 替代旧的 PlayerSeekKeyMap / PlayerChannelKeyMap / PlayerRoomKeyMap 三套 map：
 * - 三个播放页共用 [UnifiedRemoteKeyRouter]，各页只注入自己关心的 [Action] 处理器；
 * - 键码→语义动作的映射由 domain 层 [RemoteKeyBindings] 决定（用户可自定义），
 *   路由器只把「键码」翻译成「动作」，不判断「是点播还是直播」。
 * - 默认绑定 = 旧硬编码行为，用户改绑只**追加**新键、不改默认键，避免常用键丢失。
 *
 * 各页仍保留页面特有语义（电视直播数字选台、平台直播房间候选上下），通过 [onUnhandledKey]
 * 回传给页面自行处理，或由页面在 [PlayerMode.Channel] / [PlayerMode.Room] 特化处理。
 */
internal class UnifiedRemoteKeyRouter(
    val mode: PlayerMode,
    var callbacks: Callbacks,
    /** 用户自定义键位绑定（默认 [RemoteKeyBindings.Default]，从 AppSettings 注入）。 */
    var bindings: RemoteKeyBindings = RemoteKeyBindings.Default,
    /** 点播快进步长（秒）：由页面按 FastSeekSetting 注入。 */
    var seekStepSeconds: Int = FastSeekSetting.DefaultStepSeconds,
    /** 方向键在控制栏可见时交给焦点系统（点播），避免吃掉焦点移动。 */
    val onUnhandledKey: (KeyEvent) -> Boolean = { false },
) : PlayerKeyMap {
    /** 播放模式：点播 / 电视直播 / 平台直播。 */
    sealed interface PlayerMode {
        data object Seek : PlayerMode
        data object Channel : PlayerMode
        data object Room : PlayerMode
    }

    /** 统一语义动作：键码翻译结果，各页只处理自己关心的动作。 */
    sealed interface Action {
        data class SeekBy(val deltaMs: Long) : Action
        data object FinishSeek : Action
        data object TogglePlayback : Action
        data object Play : Action
        data object Pause : Action
        data class ChangeEpisode(val delta: Int) : Action
        data object CycleSpeed : Action
        data object OpenSettings : Action
        data object OpenInfo : Action
        data object OpenMenu : Action
        /** 直播专属：打开「线路 / 切源」浮层（设置键的高频语义，区别于 MENU 的频道/节目列表）。 */
        data object OpenLineOverlay : Action

        /** 通用停止：点播=暂停并保持控制栏，直播=停止并退出到列表语义。 */
        data object Stop : Action

        // 直播/平台直播专属动作
        data object PreviousChannel : Action
        data object NextChannel : Action
        data object PreviousLine : Action
        data object NextLine : Action
        data object OpenChannelList : Action
        data object Confirm : Action
        data object PreviousRoom : Action
        data object NextRoom : Action
        data object PreviousCandidate : Action
        data object NextCandidate : Action
        data object OpenRoomList : Action
        data object Reconnect : Action
    }

    data class Callbacks(
        // ── 通用动作 ──────────────────────────────────────────────────────
        val onAction: (Action) -> Boolean,
        /** BACK 键不在此消费：交给 PlayerChromeBackHandler 统一处理层级。 */
        val chrome: PlayerChromeState,
        /** 可选：方向键是否交给焦点系统（点播控制栏可见时用）。默认 null 表示路由器自己处理。 */
        val isOverlayVisible: () -> Boolean = { false },
        /** 直播/电视页特有：失败卡在场时 OK 不消费，让焦点按钮自己拿到。 */
        val isBlockingCardVisible: () -> Boolean = { false },
    )

    /** 长按连发状态（点播快进快退用）。 */
    private val remote = PlayerRemoteKeyState()

    /**
     * 探测当前遥控器是否支持系统级连发（repeatCount > 0）。6 键无长短按遥控器恒为 false，
     * 此时 ←/→ 走「单击分段累加」语义（超时窗口内连点，步长逐档升级）；
     * 支持连发的遥控器保留原长按加速语义。首次 DOWN 视为潜在连发，超时未收到 repeat 即判定为单击。
     */
    private var repeatCapable: Boolean = true
    private var firstDownTimeMs: Long = Long.MIN_VALUE

    /** P1-07：OK/PlayPause 跨键防抖。部分盒子把一次按压同时上报 DPAD_CENTER 与
     *  MEDIA_PLAY_PAUSE（不同 keyCode，同一次物理按压）；300ms 内只处理第一个。 */
    private var lastConfirmOrPlayEventTimeMs: Long = Long.MIN_VALUE
    private var lastConfirmOrPlayAction: RemoteKeyAction? = null

    override fun handle(event: KeyEvent, chrome: PlayerChromeState): Boolean {
        val native = event.nativeKeyEvent
        if (chrome.visible) chrome.interaction++
        val isKeyDown = event.type == KeyEventType.KeyDown
        val isKeyUp = event.type == KeyEventType.KeyUp
        val keyCode = native.keyCode

        // BACK 不在此消费（统一由 PlayerChromeBackHandler 处理）。
        if (keyCode == AndroidKeyEvent.KEYCODE_BACK) return false

        // 方向键：点播模式下控制栏可见时交给焦点系统（旧 PlayerSeekKeyMap 行为）。
        if (mode == PlayerMode.Seek && chrome.visible && keyCode in setOf(
                AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_UP,
                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            )
        ) {
            return false
        }

        // P1-09：按键学习过的遥控器按 scanCode > deviceId+keyCode > keyCode 裁决；
        // 未学习时 resolveHardware 返回 null，行为与改动前完全一致。
        val preferred = bindings.resolveHardware(native.deviceId.toString(), keyCode, native.scanCode)
        val actions = preferred?.let(::listOf) ?: bindings.actionsFor(keyCode)
        if (actions.isEmpty()) {
            // 未绑定键：交给页面的 onUnhandledKey（数字选台等）。
            return onUnhandledKey(event)
        }

        // 按动作优先级执行；返回 true 表示已消费。
        return actions.any { action ->
            dispatch(action, event, chrome, isKeyDown, isKeyUp, native.eventTime, native.repeatCount)
        }
    }

    private fun dispatch(
        action: RemoteKeyAction,
        event: KeyEvent,
        chrome: PlayerChromeState,
        isKeyDown: Boolean,
        isKeyUp: Boolean,
        eventTime: Long,
        repeatCount: Int,
    ): Boolean {
        // P1-07：OK/PlayPause 跨键防抖。仅对「确认/播放暂停」这对逻辑动作生效；
        // 若 300ms 内已处理过其中另一个，则本次抑制，避免一次按压双触发。
        if (isConfirmOrPlayPause(action) && suppressConfirmOrPlay(isKeyDown, action, eventTime)) {
            return true // 已消费但不重复触发
        }
        return when (mode) {
            PlayerMode.Seek -> dispatchSeek(action, isKeyDown, isKeyUp, eventTime, repeatCount, chrome)
            PlayerMode.Channel -> dispatchChannel(action, isKeyUp, chrome)
            PlayerMode.Room -> dispatchRoom(action, isKeyUp, chrome)
        }
    }

    /** 该动作是否属于「确认/播放暂停」防抖组。 */
    private fun isConfirmOrPlayPause(action: RemoteKeyAction): Boolean =
        action == RemoteKeyAction.Confirm || action == RemoteKeyAction.PlayPause

    /**
     * 跨键防抖裁决：同一物理按压若被上报成 Confirm 与 PlayPause 两个 keyCode，
     * 只处理先到的那一个；[DEBOUNCE_MS] 内的另一个被抑制。返回 true 表示应抑制。
     */
    private fun suppressConfirmOrPlay(isKeyDown: Boolean, action: RemoteKeyAction, eventTime: Long): Boolean {
        if (!isKeyDown) return false
        val now = eventTime
        val suppressed = lastConfirmOrPlayAction != null &&
            lastConfirmOrPlayAction != action &&
            now - lastConfirmOrPlayEventTimeMs in 0..CONFIRM_PLAY_DEBOUNCE_MS
        // 记录本次（无论是否被抑制，都刷新时间戳，防止连续三次同键互相穿透）。
        lastConfirmOrPlayAction = action
        lastConfirmOrPlayEventTimeMs = now
        return suppressed
    }

    /**
     * 点播 ←/→ 统一处理：自适应「有无长短按」。
     * - 按住 < LONG_PRESS_MS 视为单击：走单击分段累加（连点步长逐档升级），停点由 UI 提交。
     * - 按住 ≥ LONG_PRESS_MS 视为长按：走原有长按加速连发，UP 时提交。
     * 判定在 DOWN 记时、UP 时裁决，同一颗键两种语义自动切换，6 键遥控器与全键遥控器共用。
     */
    private fun handleSeekKey(
        direction: Int,
        isKeyDown: Boolean,
        isKeyUp: Boolean,
        eventTime: Long,
        repeatCount: Int,
        chrome: PlayerChromeState,
    ) {
        when {
            isKeyDown -> {
                if (repeatCount == 0) {
                    firstDownTimeMs = eventTime
                    remote.shouldHandleSeek(eventTime, repeatCount)
                } else {
                    // 系统连发（长按）：走长按加速语义。
                    remote.seekChanged = true
                    callbacks.onAction(Action.SeekBy(direction * remote.currentSeekStepMs(eventTime, seekStepSeconds)))
                }
            }

            isKeyUp -> {
                val held = if (firstDownTimeMs == Long.MIN_VALUE) 0L else (eventTime - firstDownTimeMs).coerceAtLeast(0L)
                if (held < PlayerRemoteKeyState.LONG_PRESS_MS) {
                    // 单击：分段累加。
                    val stepMs = remote.tapSeekStepMs(eventTime, seekStepSeconds)
                    callbacks.onAction(Action.SeekBy(direction * stepMs))
                } else {
                    // 长按结束：提交本轮累计跳转。
                    if (remote.seekChanged) callbacks.onAction(Action.FinishSeek)
                    remote.resetSeek()
                }
                firstDownTimeMs = Long.MIN_VALUE
            }
        }
    }

    private fun dispatchSeek(
        action: RemoteKeyAction,
        isKeyDown: Boolean,
        isKeyUp: Boolean,
        eventTime: Long,
        repeatCount: Int,
        chrome: PlayerChromeState,
    ): Boolean = when (action) {
        RemoteKeyAction.DirectionLeft, RemoteKeyAction.SeekBack -> {
            handleSeekKey(direction = -1, isKeyDown, isKeyUp, eventTime, repeatCount, chrome)
            true
        }

        RemoteKeyAction.DirectionRight, RemoteKeyAction.SeekForward -> {
            handleSeekKey(direction = +1, isKeyDown, isKeyUp, eventTime, repeatCount, chrome)
            true
        }

        RemoteKeyAction.Confirm -> {
            if (chrome.visible) {
                false
            } else {
                if (isKeyUp) chrome.bump()
                true
            }
        }

        RemoteKeyAction.PlayPause -> {
            if (isKeyUp) callbacks.onAction(Action.TogglePlayback)
            true
        }

        RemoteKeyAction.Play -> {
            // P2-12：定向播放键，执行前由页面比对 isPlaying，同态 no-op。
            if (isKeyUp) callbacks.onAction(Action.Play)
            true
        }

        RemoteKeyAction.Pause -> {
            // P2-12：定向暂停键，执行前由页面比对 isPlaying，同态 no-op。
            if (isKeyUp) callbacks.onAction(Action.Pause)
            true
        }

        RemoteKeyAction.Previous -> {
            if (isKeyUp) callbacks.onAction(Action.ChangeEpisode(-1))
            true
        }

        RemoteKeyAction.Next -> {
            if (isKeyUp) callbacks.onAction(Action.ChangeEpisode(1))
            true
        }

        RemoteKeyAction.Menu -> {
            if (isKeyUp) callbacks.onAction(Action.CycleSpeed)
            true
        }

        RemoteKeyAction.Settings -> {
            if (isKeyUp) callbacks.onAction(Action.OpenSettings)
            true
        }

        RemoteKeyAction.Info -> {
            if (isKeyUp) callbacks.onAction(Action.OpenInfo)
            true
        }

        RemoteKeyAction.Stop -> {
            // 点播：按停止 = 暂停并保持控制栏。
            if (isKeyUp) callbacks.onAction(Action.Stop)
            true
        }

        else -> false // 点播页不处理其他动作
    }

    private fun dispatchChannel(
        action: RemoteKeyAction,
        isKeyUp: Boolean,
        chrome: PlayerChromeState,
    ): Boolean {
        if (!isKeyUp) return false
        chrome.bump()
        return when (action) {
            RemoteKeyAction.DirectionLeft -> {
                callbacks.onAction(Action.PreviousChannel)
                true
            }

            RemoteKeyAction.DirectionRight -> {
                callbacks.onAction(Action.NextChannel)
                true
            }

            RemoteKeyAction.Confirm, RemoteKeyAction.PlayPause -> {
                if (callbacks.isBlockingCardVisible()) {
                    false // 失败卡在场，让焦点按钮拿到 OK。
                } else {
                    callbacks.onAction(Action.Confirm)
                    true
                }
            }

            RemoteKeyAction.Menu -> {
                callbacks.onAction(Action.OpenChannelList)
                true
            }

            RemoteKeyAction.Info -> {
                callbacks.onAction(Action.OpenInfo)
                true
            }

            RemoteKeyAction.Settings -> {
                // 电视直播：设置键 = 打开线路浮层（切源/线路的高频入口），与 MENU 的频道列表区分。
                callbacks.onAction(Action.OpenLineOverlay)
                true
            }

            RemoteKeyAction.Stop -> {
                // 直播：按停止 = 停止并退出到列表语义。
                callbacks.onAction(Action.Stop)
                true
            }

            else -> false
        }
    }

    private fun dispatchRoom(
        action: RemoteKeyAction,
        isKeyUp: Boolean,
        chrome: PlayerChromeState,
    ): Boolean {
        if (!isKeyUp) return false
        if (callbacks.isOverlayVisible()) return false
        chrome.bump()
        return when (action) {
            RemoteKeyAction.DirectionLeft -> {
                callbacks.onAction(Action.PreviousRoom)
                true
            }

            RemoteKeyAction.DirectionRight -> {
                callbacks.onAction(Action.NextRoom)
                true
            }

            RemoteKeyAction.DirectionUp -> {
                callbacks.onAction(Action.PreviousCandidate)
                true
            }

            RemoteKeyAction.DirectionDown -> {
                callbacks.onAction(Action.NextCandidate)
                true
            }

            RemoteKeyAction.Confirm, RemoteKeyAction.PlayPause -> {
                callbacks.onAction(Action.OpenRoomList)
                true
            }

            RemoteKeyAction.Menu -> {
                callbacks.onAction(Action.Reconnect)
                true
            }

            RemoteKeyAction.Info -> {
                callbacks.onAction(Action.OpenInfo)
                true
            }

            RemoteKeyAction.Settings -> {
                // 平台直播：设置键 = 打开线路浮层（切源/候选线路的高频入口），与 MENU 的重连区分。
                callbacks.onAction(Action.OpenLineOverlay)
                true
            }

            RemoteKeyAction.Stop -> {
                // 直播：按停止 = 停止并退出到列表语义。
                callbacks.onAction(Action.Stop)
                true
            }

            else -> false
        }
    }
}

/**
 * P1-07：OK 与 PlayPause 跨键防抖窗口。
 * 部分盒子（小米/天猫魔盒）把一次物理按压同时上报 DPAD_CENTER 与 MEDIA_PLAY_PAUSE
 * 两个不同 keyCode；300ms 内只处理先到的一个，避免「一次按压双触发」。
 */
private const val CONFIRM_PLAY_DEBOUNCE_MS: Long = 300L
