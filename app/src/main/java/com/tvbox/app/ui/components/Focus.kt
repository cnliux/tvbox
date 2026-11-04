package com.tvbox.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.ui.theme.TvColors
import com.tvbox.app.ui.theme.TvDimens

@Composable
fun Modifier.tvFocusScale(
    shape: Shape,
    focusedBorder: Color? = null,
    idleBorder: Color? = null,
): Modifier = composed {
    // 手机触摸模式不再"隐藏"焦点：触摸点击不会主动获取焦点，而外接蓝牙遥控/键盘
    // 仍然需要可见的焦点反馈。触摸模式只用 2dp 描边、不做放大，避免误以为卡片被选中。
    val touchMode = LocalTouchMode.current
    val resolvedFocusedBorder = focusedBorder ?: TvColors.Accent
    val resolvedIdleBorder = idleBorder ?: TvColors.Border
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && !touchMode) TvDimens.FocusScale else 1f,
        label = "tv-focus-scale",
    )
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = when {
                focused && !touchMode -> 3.dp
                focused -> 2.dp
                else -> 1.dp
            },
            color = if (focused) resolvedFocusedBorder else resolvedIdleBorder,
            shape = shape,
        )
}
