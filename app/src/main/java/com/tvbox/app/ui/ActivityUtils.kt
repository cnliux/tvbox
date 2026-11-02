package com.tvbox.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/** 从 Context 链中递归提取 Activity，避免在播放/直播页面重复实现。 */
internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 统一的时间格式化：毫秒 → "HH:MM:SS" 或 "MM:SS"。 */
internal fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * 提示消息自动消失定时器。
 * 调用方只需追踪一个 nonce，nonce 归零时隐藏提示，达到指定毫秒后自动重置为 null。
 */
@Composable
internal fun PromptTimer(nonce: Int, hideDelayMs: Long, onTimeout: () -> Unit) {
    LaunchedEffect(nonce) {
        if (nonce == 0) return@LaunchedEffect
        delay(hideDelayMs)
        onTimeout()
    }
}

