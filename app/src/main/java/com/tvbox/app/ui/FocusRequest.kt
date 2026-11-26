package com.tvbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay

/**
 * 焦点请求状态：把各页面重复的
 * `remember { FocusRequester() } + LaunchedEffect { runCatching { requestFocus() } }`
 * 收敛到一处。
 *
 * [anchor] 只是一次普通的 `Modifier.focusRequester(...)`，必须放在原来的 modifier 位置；
 * [request] 只是一次容错的 `requestFocus()`，可在回调、生命周期监听或滚动之后调用。
 * 本类不添加 `focusable()`、不改 modifier 顺序、不读取设备模式，因此不改变现有焦点顺序。
 */
class TvFocusRequest internal constructor(val requester: FocusRequester) {
    /** 与原 `.focusRequester(requester)` 等价，请在原位置使用 `.then(anchor)`。 */
    val anchor: Modifier get() = Modifier.focusRequester(requester)

    /** 与原 `runCatching { requester.requestFocus() }` 等价。 */
    fun request(): Boolean = runCatching { requester.requestFocus() }.getOrDefault(false)
}

/**
 * @param key 传 `Unit`/`active`/`hasAction` 等时按原 effect key 自动请求；
 *           传 `null`（默认）时不产生 effect，由调用方在原有 LaunchedEffect 内调用 [TvFocusRequest.request]，
 *           用于「先 scrollToItem / withFrameNanos 再聚焦」这类必须保持顺序的场景。
 * @param enabled 对应原 effect 内的 `if` 条件。
 * @param delayMs 对应原 effect 内的 `delay(60)`。
 * @param clearFocusWhenDisabled 对应关闭面板时 `focusManager.clearFocus()`。
 * @param retries 首帧失败后的额外重试次数（F-04：低端机 60ms 后目标节点可能尚未进入组合，
 *                首焦静默失败；每帧重试一次，直到成功或耗尽次数）。
 */
@Composable
fun rememberTvFocusRequest(
    key: Any? = null,
    enabled: Boolean = true,
    delayMs: Long = 0L,
    clearFocusWhenDisabled: Boolean = false,
    retries: Int = 0,
): TvFocusRequest {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        if (enabled) {
            if (delayMs > 0L) delay(delayMs)
            var attempt = 0
            while (true) {
                val ok = runCatching { requester.requestFocus() }.getOrDefault(false)
                if (ok || attempt >= retries) break
                // 等待下一帧让目标节点进入组合后再试，避免 60ms 定时抢先于组合完成。
                withFrameNanos { }
                attempt++
            }
        } else if (clearFocusWhenDisabled) {
            focusManager.clearFocus()
        }
    }
    return remember(requester) { TvFocusRequest(requester) }
}
