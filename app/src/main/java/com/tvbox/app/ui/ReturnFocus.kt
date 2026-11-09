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
) {
    val lastKey: String? get() = savedKey.value
    val lastIndex: Int get() = savedIndex.value
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun reset() {
        savedKey.value = null
        savedIndex.value = 0
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
    return remember { ReturnFocus(key, index) }
}

@Composable
internal fun RestoreGridFocus(focus: ReturnFocus, grid: LazyGridState, items: List<Pair<String, Int>>) {
    // 记录「已成功恢复的 key」而不是一次性布尔：12 次请求全失败时不算已恢复，
    // 同页再次 mark() 换 key 后也能重新定位；配置变更后不会因旧布尔而永久跳过恢复。
    var restoredKey by rememberSaveable { mutableStateOf<String?>(null) }
    val targetKey = focus.lastKey
    LaunchedEffect(targetKey, items) {
        if (items.isEmpty() || targetKey == null || restoredKey == targetKey) return@LaunchedEffect
        val target = items.firstOrNull { it.first == targetKey }
            ?: items.minByOrNull { kotlin.math.abs(it.second - focus.lastIndex) }
            ?: return@LaunchedEffect
        withFrameNanos { }
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == target.second }) grid.scrollToItem(target.second)
        for (attempt in 0..12) {
            withFrameNanos { }
            if (focus.request(target.first)) {
                restoredKey = targetKey
                break
            }
        }
    }
}
