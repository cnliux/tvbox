package com.tvbox.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.ui.theme.LocalTvFocus
import com.tvbox.app.ui.theme.TvColors

@Composable
fun Modifier.tvFocusScale(
    shape: Shape,
    focusedBorder: Color? = null,
    idleBorder: Color? = null,
): Modifier = composed {
    // 手机触摸模式不再"隐藏"焦点：触摸点击不会主动获取焦点，而外接蓝牙遥控/键盘
    // 仍然需要可见的焦点反馈。触摸模式只用 2dp 描边、不做放大，避免误以为卡片被选中。
    val touchMode = LocalTouchMode.current
    // #363：消费主题层 focusTokensFor(reduceMotion) 的产出。系统开启"减少动画"时
    // focusScale=1f、animationMs=0，此处不再缩放且动效时长归零，尊重系统无障碍偏好。
    // F-01：ringWidth / ringColor / elevation 一并从 token 消费，改主题后焦点框随主题换色。
    val focusTokens = LocalTvFocus.current
    val resolvedFocusedBorder = focusedBorder ?: TvColors.FocusRing
    val resolvedIdleBorder = idleBorder ?: TvColors.Border
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && !touchMode) focusTokens.focusScale else 1f,
        animationSpec = tween(durationMillis = focusTokens.animationMs),
        label = "tv-focus-scale",
    )
    val ringWidth = when {
        focused && !touchMode -> focusTokens.ringWidth
        focused -> 2.dp
        else -> 1.dp
    }
    val elevation = if (focused) focusTokens.elevation else 0.dp
    this
        .shadow(elevation = elevation, shape = shape, clip = false)
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = ringWidth,
            color = if (focused) resolvedFocusedBorder else resolvedIdleBorder,
            shape = shape,
        )
}
