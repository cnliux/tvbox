package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tvbox.app.domain.LiveRemoteDirectionMapping
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import kotlinx.coroutines.delay

/**
 * 直播播放器遥控状态机。
 *
 * 设计要点（对应四个 Bug 和三个增强）：
 * 1. 确认键（DPAD_CENTER / ENTER）在 ACTION_DOWN 只「消费不执行」，在 ACTION_UP 才执行。
 *    DOWN 被消费后不会再传给刚获得焦点的播放/暂停按钮；UP 由根节点统一消费，
 *    从根上杜绝「面板刚弹出就顺手点了一个按钮」。
 * 2. 严格的 if / when 状态隔离：只有 FULLSCREEN 才解释为换台/切源；
 *    CONTROLLER / CHANNEL_LIST 除 BACK 外一律 return false，交给面板内部焦点链。
 * 3. 面板弹出时由 FocusRequester 强制抢焦点，背景容器 focusable(false)，
 *    onFocusChanged 用 hasFocus 做焦点陷阱（不会把焦点从面板子按钮上抢回来）。
 * 4. BACK 走弹栈：数字输入 > 控制栏 / 频道列表 > 退出播放页。
 * 5. 切源时由 isContinuitySource 判定虎牙/斗鱼 FLV，发 SourceSwitched(continuityHint=true)，
 *    UI 侧弹「已切换至备用线路（自动续流中）」。
 * 6. 数字选台：0-9 输入期间拦截方向键，2 秒无输入自动提交，BACK 清空。
 */

enum class UIState { FULLSCREEN, CONTROLLER, CHANNEL_LIST }

// 注：LiveRemoteDirectionMapping 已迁移到 com.tvbox.app.domain，见 domain/LiveRemoteDirectionMapping.kt。

/** numberInputNonce 每次输入变化都自增，用于取消上一次「2 秒自动提交」定时器。 */
data class LiveRemoteState(
    val uiState: UIState = UIState.FULLSCREEN,
    val numberInput: String = "",
    val numberInputNonce: Int = 0,
)

sealed interface LiveRemoteEffect {
    data object PreviousChannel : LiveRemoteEffect
    data object NextChannel : LiveRemoteEffect
    data object PreviousSource : LiveRemoteEffect
    data object NextSource : LiveRemoteEffect
    data object OpenController : LiveRemoteEffect
    data object OpenChannelList : LiveRemoteEffect
    data object ClosePanel : LiveRemoteEffect
    data object ExitLive : LiveRemoteEffect
    data object CommitNumber : LiveRemoteEffect
    data class NumberMove(val delta: Int) : LiveRemoteEffect
    data object TogglePlayback : LiveRemoteEffect
    data object OpenSettings : LiveRemoteEffect
    data object OpenInfo : LiveRemoteEffect
    data class NumberChanged(val value: String) : LiveRemoteEffect
    data class SourceSwitched(val continuityHint: Boolean) : LiveRemoteEffect
}

data class LiveRemoteResult(
    val state: LiveRemoteState,
    val effects: List<LiveRemoteEffect> = emptyList(),
)

class LiveRemoteKeyRouter(
    /** 当前源是否为虎牙/斗鱼 FLV；UI 侧可直接接 FlvContinuityProxy.shouldWrap(site, "flv")。 */
    private val isContinuitySource: () -> Boolean = { false },
    private val maxNumberDigits: Int = 4,
    /** 若项目已有 PlayerChromeBackHandler 负责退出，可传 false，由现有链路处理最后一层 BACK。 */
    private val exitLiveOnBackInFullscreen: Boolean = true,
    private val directionMapping: LiveRemoteDirectionMapping = LiveRemoteDirectionMapping.UP_DOWN_CHANNEL,
    /** 失败卡（重试/下一台）在场时确认键必须让给当前聚焦按钮。 */
    private val hasBlockingFailureCard: () -> Boolean = { false },
    /**
     * PlayerChrome 功能面板（设置/信息/播放地址等）打开时路由整体惰性：
     * 所有键（含 BACK）放行给面板焦点链与 PlayerChromeBackHandler，避免两套浮层互相干扰。
     */
    private val hasChromePanel: () -> Boolean = { false },
    /**
     * 统一遥控键位绑定（设置页可自定义）：用于「播放/暂停 / 设置 / 信息 / 菜单 / 上一台 / 下一台」等
     * 跨页通用动作的键码判定。方向键（换台/切源）与数字选台是直播特化语义，仍走 directionMapping
     * 与数字处理，不进绑定表；其余通用键码先经 [bindings.actionsFor] 翻译成 [LiveRemoteEffect]。
     */
    private val bindings: RemoteKeyBindings = RemoteKeyBindings.Default,
) {
    /**
     * 纯函数：输入物理键码 + Compose 事件类型，输出新状态 + 副作用。
     * 返回 null 表示「不消费」，交给面板内部子 Composable。
     */
    fun handle(
        action: Int,
        type: KeyEventType,
        state: LiveRemoteState,
        repeatCount: Int = 0,
    ): LiveRemoteResult? {
        val down = type == KeyEventType.KeyDown
        val up = type == KeyEventType.KeyUp
        if (!down && !up) return null

        // 长按连发一律吞掉但不重复执行，避免换台/切源像机关枪。
        if (repeatCount > 0) return LiveRemoteResult(state)

        // 功能面板打开时路由整体惰性：面板焦点链 + PlayerChromeBackHandler 接管全部按键。
        if (hasChromePanel()) return null

        // BACK：面板/数字输入态消费并按弹栈语义在 ACTION_UP 执行；
        // 全屏且无遮挡时若 exitLiveOnBackInFullscreen=false，则放行给已有的 BackHandler 链路。
        if (action == AndroidKeyEvent.KEYCODE_BACK) {
            return if (up) onBack(state) else LiveRemoteResult(state)
        }

        // 状态隔离：面板打开时除 BACK 外全部放行，由面板焦点链处理。
        // 这一条是 Bug 2 的根治点。
        if (state.uiState != UIState.FULLSCREEN) return null

        // 数字输入态是 FULLSCREEN 上的模态：方向键/菜单键全部吞掉，防误换台。
        if (state.numberInput.isNotEmpty()) return handleNumberEntry(action, up, state)

        val upDownChannel = directionMapping == LiveRemoteDirectionMapping.UP_DOWN_CHANNEL
        val channelUpKey = if (upDownChannel) AndroidKeyEvent.KEYCODE_DPAD_UP else AndroidKeyEvent.KEYCODE_DPAD_LEFT
        val channelDownKey = if (upDownChannel) AndroidKeyEvent.KEYCODE_DPAD_DOWN else AndroidKeyEvent.KEYCODE_DPAD_RIGHT
        val sourcePrevKey = if (upDownChannel) AndroidKeyEvent.KEYCODE_DPAD_LEFT else AndroidKeyEvent.KEYCODE_DPAD_UP
        val sourceNextKey = if (upDownChannel) AndroidKeyEvent.KEYCODE_DPAD_RIGHT else AndroidKeyEvent.KEYCODE_DPAD_DOWN

        // 数字键（含小键盘）：直播特化的选台语义，不走绑定表。
        if (action in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ||
            action in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9
        ) {
            return if (!down) {
                LiveRemoteResult(state)
            } else {
                digitOf(action)?.let { appendDigit(state, it) } ?: LiveRemoteResult(state)
            }
        }

        // 确认键（OK / ENTER）：两段式 + 失败卡让位，直播特化，不走绑定表。
        // 确认键 = 打开频道列表（节目浏览高频入口），与平台直播确认键的「节目列表」语义一致。
        // 换源/线路浮层改由设置键提供，不因确认键改开列表而丢失入口。
        if (action == AndroidKeyEvent.KEYCODE_DPAD_CENTER || action == AndroidKeyEvent.KEYCODE_ENTER) {
            if (hasBlockingFailureCard()) return null
            return if (!up) {
                LiveRemoteResult(state)
            } else if (state.uiState == UIState.CHANNEL_LIST) {
                // 列表已开：再按 OK 收起（面板顶部「确认键收起」提示的兑现）。
                // 焦点不在列表行项上时按键会落到路由，若这里仍只「打开」则列表永远关不掉。
                LiveRemoteResult(state, listOf(LiveRemoteEffect.ClosePanel))
            } else {
                LiveRemoteResult(
                    state.copy(uiState = UIState.CHANNEL_LIST),
                    listOf(LiveRemoteEffect.OpenChannelList),
                )
            }
        }

        // 方向键：按 directionMapping 解释为换台 / 切源，直播特化，不走绑定表。
        when (action) {
            sourcePrevKey -> {
                return if (down) {
                    LiveRemoteResult(
                        state,
                        listOf(
                            LiveRemoteEffect.PreviousSource,
                            LiveRemoteEffect.SourceSwitched(isContinuitySource()),
                        ),
                    )
                } else {
                    LiveRemoteResult(state)
                }
            }

            sourceNextKey -> {
                return if (down) {
                    LiveRemoteResult(
                        state,
                        listOf(
                            LiveRemoteEffect.NextSource,
                            LiveRemoteEffect.SourceSwitched(isContinuitySource()),
                        ),
                    )
                } else {
                    LiveRemoteResult(state)
                }
            }

            channelUpKey -> {
                return if (down) LiveRemoteResult(state, listOf(LiveRemoteEffect.PreviousChannel)) else LiveRemoteResult(state)
            }

            channelDownKey -> {
                return if (down) LiveRemoteResult(state, listOf(LiveRemoteEffect.NextChannel)) else LiveRemoteResult(state)
            }
        }

        // ── 通用动作：经统一绑定表翻译（用户自定义键位对直播页同样生效） ──
        val boundActions = bindings.actionsFor(action)
        if (boundActions.isEmpty()) return null
        if (!up) return LiveRemoteResult(state)
        val effects = mutableListOf<LiveRemoteEffect>()
        var nextState = state
        for (bound in boundActions) {
            when (bound) {
                RemoteKeyAction.PlayPause -> effects += LiveRemoteEffect.TogglePlayback
                RemoteKeyAction.Previous -> effects += LiveRemoteEffect.PreviousChannel
                RemoteKeyAction.Next -> effects += LiveRemoteEffect.NextChannel
                // 菜单键 = 打开频道列表（高频节目浏览入口），已开则收起；同步 uiState 供面板焦点陷阱使用。
                RemoteKeyAction.Menu -> {
                    if (nextState.uiState == UIState.CHANNEL_LIST) {
                        effects += LiveRemoteEffect.ClosePanel
                    } else {
                        effects += LiveRemoteEffect.OpenChannelList
                        nextState = nextState.copy(uiState = UIState.CHANNEL_LIST)
                    }
                }
                // 设置键 = 打开功能面板（线路入口收进面板内），与菜单键的列表语义区分。
                RemoteKeyAction.Settings -> effects += LiveRemoteEffect.OpenSettings
                // 信息键 = 打开节目信息面板（低频）。
                RemoteKeyAction.Info -> effects += LiveRemoteEffect.OpenInfo
                // 停止键 = 停止播放并回到列表（直播不可暂停，P1-06）。
                RemoteKeyAction.Stop -> effects += LiveRemoteEffect.ExitLive
                // ESC = 直接退出直播页。
                RemoteKeyAction.Exit -> effects += LiveRemoteEffect.ExitLive
                else -> Unit // 方向/快进快退/BACK 在直播页无对应语义
            }
        }
        return if (effects.isEmpty()) null else LiveRemoteResult(nextState, effects)
    }

    private fun onBack(state: LiveRemoteState): LiveRemoteResult? = when {
        state.numberInput.isNotEmpty() -> {
            val cleared = state.copy(
                numberInput = "",
                numberInputNonce = state.numberInputNonce + 1,
            )
            LiveRemoteResult(cleared, listOf(LiveRemoteEffect.NumberChanged("")))
        }
        state.uiState == UIState.CONTROLLER || state.uiState == UIState.CHANNEL_LIST -> {
            LiveRemoteResult(
                state.copy(uiState = UIState.FULLSCREEN),
                listOf(LiveRemoteEffect.ClosePanel),
            )
        }
        // 没有本地弹层可关时交还给已有的 PlayerChromeBackHandler，避免两处重复消费。
        exitLiveOnBackInFullscreen -> LiveRemoteResult(state, listOf(LiveRemoteEffect.ExitLive))
        else -> null
    }

    private fun handleNumberEntry(action: Int, up: Boolean, state: LiveRemoteState): LiveRemoteResult {
        val digit = digitOf(action)
        if (digit != null) {
            return if (!up) appendDigit(state, digit) else LiveRemoteResult(state)
        }
        return when (action) {
            // 输入台号时上下键只移动候选高亮，绝不触发背景换台。
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                if (up) LiveRemoteResult(state, listOf(LiveRemoteEffect.NumberMove(-1)))
                else LiveRemoteResult(state)
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (up) LiveRemoteResult(state, listOf(LiveRemoteEffect.NumberMove(1)))
                else LiveRemoteResult(state)
            }
            AndroidKeyEvent.KEYCODE_DEL -> {
                if (up) {
                    LiveRemoteResult(state)
                } else {
                    val next = state.numberInput.dropLast(1)
                    LiveRemoteResult(
                        state.copy(numberInput = next, numberInputNonce = state.numberInputNonce + 1),
                        listOf(LiveRemoteEffect.NumberChanged(next)),
                    )
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            -> {
                // 数字输入时确认键只用于立即提交，绝不再打开控制栏。
                if (up) LiveRemoteResult(state, listOf(LiveRemoteEffect.CommitNumber))
                else LiveRemoteResult(state)
            }
            // 方向键、菜单、信息键……输入台号期间一律吞掉，阻断背景换台。
            else -> LiveRemoteResult(state)
        }
    }

    private fun appendDigit(state: LiveRemoteState, digit: Int): LiveRemoteResult {
        val next = (state.numberInput + digit.toString()).takeLast(maxNumberDigits)
        return LiveRemoteResult(
            state.copy(numberInput = next, numberInputNonce = state.numberInputNonce + 1),
            listOf(LiveRemoteEffect.NumberChanged(next)),
        )
    }

    private fun digitOf(action: Int): Int? = when (action) {
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> action - AndroidKeyEvent.KEYCODE_0
        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> action - AndroidKeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

/** 把纯路由挂到根节点：必须在播放器内容之前（onPreviewKeyEvent 先于子控件拿到事件）。 */
@Composable
fun Modifier.liveRemoteKeyInput(
    router: LiveRemoteKeyRouter,
    state: LiveRemoteState,
    onStateChange: (LiveRemoteState) -> Unit,
    onEffect: (LiveRemoteEffect) -> Unit,
): Modifier = liveRemoteKeyInput(router, { state }, onStateChange, onEffect)

/**
 * [state] 以 lambda 提供：事件到达时读取当前值，而不是捕获组合时的快照。
 * 连续按键（同一帧内两次 DOWN）必须拿到上一次 effect 写入后的输入，否则会丢字。
 */
@Composable
fun Modifier.liveRemoteKeyInput(
    router: LiveRemoteKeyRouter,
    state: () -> LiveRemoteState,
    onStateChange: (LiveRemoteState) -> Unit,
    onEffect: (LiveRemoteEffect) -> Unit,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    val currentState = state()
    val result = router.handle(
        action = event.nativeKeyEvent.keyCode,
        type = event.type,
        state = currentState,
        repeatCount = event.nativeKeyEvent.repeatCount,
    ) ?: return@onPreviewKeyEvent false

    if (result.state != currentState) onStateChange(result.state)
    result.effects.forEach(onEffect)
    true
}

/**
 * 面板焦点陷阱：active 时请求 firstFocusRequester，关闭时清空根焦点；
 * hasFocus 覆盖子控件，所以焦点在面板按钮之间移动时不会被抢回来。
 */
@Composable
fun rememberLivePanelFocusRequester(active: Boolean): FocusRequester =
    rememberTvFocusRequest(
        key = active,
        enabled = active,
        clearFocusWhenDisabled = true,
    ).requester

fun Modifier.livePanelFocusTrap(
    active: Boolean,
    firstFocusRequester: FocusRequester,
): Modifier = this
    .focusable(enabled = active)
    .onFocusChanged { focusState ->
        if (active && !focusState.hasFocus) {
            runCatching { firstFocusRequester.requestFocus() }
        }
    }

/** 面板恢复全屏时把焦点还给播放器根节点。 */
@Composable
fun rememberLiveRootFocusRequester(restoreWhen: Boolean): FocusRequester =
    rememberTvFocusRequest(key = restoreWhen, enabled = restoreWhen).requester

fun Modifier.livePanelAnchor(requester: FocusRequester): Modifier = this.focusRequester(requester)

/** 数字选台 2 秒静默自动提交；nonce 变化会取消上一定时器。 */
@Composable
fun LiveRemoteNumberAutoCommit(
    state: LiveRemoteState,
    onCommit: () -> Unit,
    delayMs: Long = 2_000L,
) {
    LaunchedEffect(state.numberInputNonce) {
        if (state.numberInput.isEmpty()) return@LaunchedEffect
        delay(delayMs)
        onCommit()
    }
}

/**
 * 可直接接入页面的完整 Composable 骨架。
 *
 * - playerContent：播放器画面，调用方必须给它加 .focusable(enabled = state.uiState == FULLSCREEN)，
 *   或者直接把传进来的 modifier 用上（这里已带 focusable 门控）。
 * - controllerContent / channelListContent：把 firstFocusRequester 挂到面板第一个按钮上。
 *   频道列表里选台后调用 onClose()，内部会同步把 uiState 设回 FULLSCREEN，
 *   防止「播放 + 穿透到暂停」两个动作同时发生。
 * - 切源的轻提示由 onShowContinuityHint() 触发，接 showPrompt("已切换至备用线路（自动续流中）")。
 */
@Composable
fun LivePlayerRemoteShell(
    state: LiveRemoteState,
    onStateChange: (LiveRemoteState) -> Unit,
    isContinuitySource: () -> Boolean,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousSource: () -> Unit,
    onNextSource: () -> Unit,
    onNumberCommitted: (String) -> Unit,
    onExitLive: () -> Unit,
    onShowContinuityHint: () -> Unit,
    /** 数字选台静默自动提交超时（毫秒）；0=手动确认。见 [com.tvbox.app.domain.DirectTuneTimeout]。 */
    numberAutoCommitMs: Long = com.tvbox.app.domain.DirectTuneTimeout.DefaultMs,
    playerContent: @Composable (Modifier) -> Unit,
    controllerContent: @Composable (FocusRequester, onClose: () -> Unit) -> Unit,
    channelListContent: @Composable (FocusRequester, onPicked: () -> Unit, onClose: () -> Unit) -> Unit,
    numberEntryContent: @Composable (String) -> Unit,
) {
    val router = remember(isContinuitySource) { LiveRemoteKeyRouter(isContinuitySource) }
    val atFullscreen = state.uiState == UIState.FULLSCREEN && state.numberInput.isEmpty()
    val rootFocus = rememberLiveRootFocusRequester(atFullscreen)
    val controllerFocus = rememberLivePanelFocusRequester(state.uiState == UIState.CONTROLLER)
    val channelListFocus = rememberLivePanelFocusRequester(state.uiState == UIState.CHANNEL_LIST)

    fun closePanel() {
        onStateChange(state.copy(uiState = UIState.FULLSCREEN))
        runCatching { rootFocus.requestFocus() }
    }

    fun commitNumber() {
        val input = state.numberInput
        if (input.isEmpty()) return
        onStateChange(
            state.copy(
                numberInput = "",
                numberInputNonce = state.numberInputNonce + 1,
            ),
        )
        onNumberCommitted(input)
        runCatching { rootFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .livePanelAnchor(rootFocus)
            // 根节点始终保持可聚焦：面板/数字输入态下也要由它统一接住 BACK。
            // 真正需要禁用焦点的是背景播放器（见 playerContent 的 focusable 门控）。
            .focusable()
            .liveRemoteKeyInput(router, state, onStateChange) { effect ->
                when (effect) {
                    LiveRemoteEffect.PreviousChannel -> onPreviousChannel()
                    LiveRemoteEffect.NextChannel -> onNextChannel()
                    LiveRemoteEffect.PreviousSource -> onPreviousSource()
                    LiveRemoteEffect.NextSource -> onNextSource()
                    LiveRemoteEffect.OpenController,
                    LiveRemoteEffect.OpenChannelList,
                    LiveRemoteEffect.ClosePanel,
                    is LiveRemoteEffect.NumberChanged,
                    is LiveRemoteEffect.NumberMove,
                    LiveRemoteEffect.TogglePlayback,
                    LiveRemoteEffect.OpenSettings,
                    LiveRemoteEffect.OpenInfo,
                    -> Unit // 该骨架不承载这些能力；页面接入时映射到自己的 action。
                    is LiveRemoteEffect.SourceSwitched -> if (effect.continuityHint) onShowContinuityHint()
                    LiveRemoteEffect.CommitNumber -> commitNumber()
                    LiveRemoteEffect.ExitLive -> onExitLive()
                }
            },
    ) {
        playerContent(Modifier.matchParentSize().focusable(enabled = atFullscreen))

        when (state.uiState) {
            UIState.FULLSCREEN -> Unit
            UIState.CONTROLLER -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .livePanelFocusTrap(true, controllerFocus),
            ) {
                controllerContent(controllerFocus, ::closePanel)
            }
            UIState.CHANNEL_LIST -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .livePanelFocusTrap(true, channelListFocus),
            ) {
                // 选台成功后 externally 调 onPicked -> closePanel，状态回到 FULLSCREEN；
                // 面板自己的 onPreviewKeyEvent 必须 return true 消费 DPAD_CENTER，
                // 否则同一颗确认键的 UP 会穿透到播放器触发暂停。
                channelListContent(channelListFocus, ::closePanel, ::closePanel)
            }
        }

        if (state.numberInput.isNotEmpty()) numberEntryContent(state.numberInput)
        LiveRemoteNumberAutoCommit(state, ::commitNumber, delayMs = numberAutoCommitMs)
    }
}
