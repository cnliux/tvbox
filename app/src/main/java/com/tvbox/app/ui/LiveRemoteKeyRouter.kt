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
import androidx.compose.ui.platform.LocalFocusManager
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

        // BACK：任何状态都消费，按弹栈语义在 ACTION_UP 执行。
        if (action == AndroidKeyEvent.KEYCODE_BACK) {
            return if (up) onBack(state) else LiveRemoteResult(state)
        }

        // 状态隔离：面板打开时除 BACK 外全部放行，由面板焦点链处理。
        // 这一条是 Bug 2 的根治点。
        if (state.uiState != UIState.FULLSCREEN) return null

        // 数字输入态是 FULLSCREEN 上的模态：方向键/菜单键全部吞掉，防误换台。
        if (state.numberInput.isNotEmpty()) return handleNumberEntry(action, up, state)

        return when (action) {
            // ── 全屏：左右切源，上下换台 ──────────────────────────────
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) {
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
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
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
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                if (down) LiveRemoteResult(state, listOf(LiveRemoteEffect.PreviousChannel))
                else LiveRemoteResult(state)
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) LiveRemoteResult(state, listOf(LiveRemoteEffect.NextChannel))
                else LiveRemoteResult(state)
            }

            // ── 确认键：DOWN 只吞不执行，UP 才打开控制栏（Bug 1 修复） ──
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            -> {
                if (up) LiveRemoteResult(state.copy(uiState = UIState.CONTROLLER), listOf(LiveRemoteEffect.OpenController))
                else LiveRemoteResult(state)
            }

            // 菜单键同样两段式，避免面板刚出现就被 UP 误点。
            AndroidKeyEvent.KEYCODE_MENU -> {
                if (up) LiveRemoteResult(state.copy(uiState = UIState.CHANNEL_LIST), listOf(LiveRemoteEffect.OpenChannelList))
                else LiveRemoteResult(state)
            }

            // ── 数字选台 ────────────────────────────────────────────
            AndroidKeyEvent.KEYCODE_0,
            AndroidKeyEvent.KEYCODE_1,
            AndroidKeyEvent.KEYCODE_2,
            AndroidKeyEvent.KEYCODE_3,
            AndroidKeyEvent.KEYCODE_4,
            AndroidKeyEvent.KEYCODE_5,
            AndroidKeyEvent.KEYCODE_6,
            AndroidKeyEvent.KEYCODE_7,
            AndroidKeyEvent.KEYCODE_8,
            AndroidKeyEvent.KEYCODE_9,
            AndroidKeyEvent.KEYCODE_NUMPAD_0,
            AndroidKeyEvent.KEYCODE_NUMPAD_1,
            AndroidKeyEvent.KEYCODE_NUMPAD_2,
            AndroidKeyEvent.KEYCODE_NUMPAD_3,
            AndroidKeyEvent.KEYCODE_NUMPAD_4,
            AndroidKeyEvent.KEYCODE_NUMPAD_5,
            AndroidKeyEvent.KEYCODE_NUMPAD_6,
            AndroidKeyEvent.KEYCODE_NUMPAD_7,
            AndroidKeyEvent.KEYCODE_NUMPAD_8,
            AndroidKeyEvent.KEYCODE_NUMPAD_9,
            -> {
                if (!down) {
                    LiveRemoteResult(state)
                } else {
                    digitOf(action)?.let { appendDigit(state, it) } ?: LiveRemoteResult(state)
                }
            }

            else -> null
        }
    }

    private fun onBack(state: LiveRemoteState): LiveRemoteResult = when {
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
        exitLiveOnBackInFullscreen -> LiveRemoteResult(state, listOf(LiveRemoteEffect.ExitLive))
        else -> LiveRemoteResult(state)
    }

    private fun handleNumberEntry(action: Int, up: Boolean, state: LiveRemoteState): LiveRemoteResult {
        val digit = digitOf(action)
        if (digit != null) {
            return if (!up) appendDigit(state, digit) else LiveRemoteResult(state)
        }
        return when (action) {
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
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    val result = router.handle(
        action = event.nativeKeyEvent.keyCode,
        type = event.type,
        state = state,
        repeatCount = event.nativeKeyEvent.repeatCount,
    ) ?: return@onPreviewKeyEvent false

    if (result.state != state) onStateChange(result.state)
    result.effects.forEach(onEffect)
    true
}

/**
 * 面板焦点陷阱：active 时请求 firstFocusRequester，关闭时清空根焦点；
 * hasFocus 覆盖子控件，所以焦点在面板按钮之间移动时不会被抢回来。
 */
@Composable
fun rememberLivePanelFocusRequester(active: Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(active) {
        if (active) {
            runCatching { requester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }
    return requester
}

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
fun rememberLiveRootFocusRequester(restoreWhen: Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(restoreWhen) {
        if (restoreWhen) runCatching { requester.requestFocus() }
    }
    return requester
}

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
                    -> Unit // uiState / numberInput 已由 router 的 result.state 同步
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
        LiveRemoteNumberAutoCommit(state, ::commitNumber)
    }
}
