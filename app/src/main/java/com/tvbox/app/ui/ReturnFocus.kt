package com.tvbox.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

internal class ReturnFocus(
    private val savedKey: MutableState<String?>,
    private val savedIndex: MutableState<Int>,
    private val restoredKeyState: MutableState<String?>,
) {
    val lastKey: String? get() = savedKey.value
    val lastIndex: Int get() = savedIndex.value
    /** 已成功恢复过的 key：必须在 reset() 时清空，否则换词搜回同一卡时恢复会被永久跳过。 */
    var restoredKey: String?
        get() = restoredKeyState.value
        set(value) { restoredKeyState.value = value }
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun reset() {
        savedKey.value = null
        savedIndex.value = 0
        restoredKeyState.value = null
        // 换关键词后旧卡片的 FocusRequester 已无意义，避免长期累积。
        requesters.clear()
    }

    fun mark(key: String, index: Int) {
        savedKey.value = key
        savedIndex.value = index
    }

    fun modifier(key: String, index: Int): Modifier {
        val requester = requesters.getOrPut(key) { FocusRequester() }
        return Modifier.focusRequester(requester).onFocusChanged {
            if (it.isFocused) mark(key, index)
        }
    }

    fun request(key: String): Boolean = runCatching { requesters[key]?.requestFocus() ?: false }.getOrDefault(false)
}

@Composable
internal fun rememberReturnFocus(): ReturnFocus {
    val key = rememberSaveable { mutableStateOf<String?>(null) }
    val index = rememberSaveable { mutableIntStateOf(0) }
    val restored = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { ReturnFocus(key, index, restored) }
}

@Composable
internal fun RestoreGridFocus(focus: ReturnFocus, grid: LazyGridState, items: List<Pair<String, Int>>) {
    // 记录「已成功恢复的 key」而不是一次性布尔：12 次请求全失败时不算已恢复，
    // 同页再次 mark() 换 key 后也能重新定位；配置变更后不会因旧布尔而永久跳过恢复。
    val targetKey = focus.lastKey
    // key 用「内容签名」而非 items 列表实例：每次重组都会生成新的 List 实例，直接作 key 会
    // 在 restoredKey != targetKey 时每轮重建 effect、重启 12 次恢复循环（焦点抖动/卡死）。
    // 也不能只用 size：搜索结果换一批、历史刷新后可能条数恰好相同，size 不变 effect 不重建，
    // 焦点仍落在旧列表坐标系上。首/尾 key + 条数构成稳定内容签名，换列表即变、同列表重组不变。
    val contentSig = items.firstOrNull()?.first + "|" + items.lastOrNull()?.first + "|" + items.size
    LaunchedEffect(targetKey, contentSig) {
        if (items.isEmpty() || targetKey == null || focus.restoredKey == targetKey) return@LaunchedEffect
        val target = items.firstOrNull { it.first == targetKey }
            ?: items.minByOrNull { kotlin.math.abs(it.second - focus.lastIndex) }
            ?: return@LaunchedEffect
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == target.second }) grid.scrollToItem(target.second)
        for (attempt in 0..12) {
            withFrameNanos { }
            if (focus.request(target.first)) {
                focus.restoredKey = targetKey
                break
            }
        }
    }
}
