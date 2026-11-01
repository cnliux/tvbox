package com.tvbox.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 手机触摸模式标记。
 *
 * 手机模式下界面主要由手指操作，遥控器焦点框（绿色描边 + 放大）在触摸后不会自动消失，
 * 会造成"点完一直高亮"的错觉。该标记为 true 时，通用焦点样式函数 `tvFocusScale`
 * 不再绘制焦点描边与缩放，同时页面也不再主动 requestFocus。
 */
val LocalTouchMode = staticCompositionLocalOf { false }
