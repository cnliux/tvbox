package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent

/**
 * S7-36 G3：遥控器键位矩阵语料。
 *
 * 四套真实世界语料：小米同码（确认=DPAD_CENTER）、南都 180ms 长按差异、
 * 蓝牙 NUMPAD（确认=ENTER、数字键为 KEYCODE_NUMPAD_*）、无障碍开关控（仅方向+确认）。
 * 每套语料喂给 [LiveRemoteKeyRouter]，断言动作序列；断言消息必须带设备名，
 * 否则 CI 红了只输出 `expected true`，无人知道哪一格挂了。
 */
object RemoteKeyEventFixtures {
    data class Profile(
        val device: String,
        val note: String,
        val confirmKey: Int,
        val backKey: Int,
        val upKey: Int,
        val downKey: Int,
        val digitOneKey: Int,
    )

    val profiles: List<Profile> = listOf(
        Profile(
            device = "小米盒子 4S",
            note = "红外，确认键与方向键同码（DPAD_CENTER）",
            confirmKey = AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            backKey = AndroidKeyEvent.KEYCODE_BACK,
            upKey = AndroidKeyEvent.KEYCODE_DPAD_UP,
            downKey = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            digitOneKey = AndroidKeyEvent.KEYCODE_1,
        ),
        Profile(
            device = "南都 N1",
            note = "红外，180ms 长按阈值与小米不同（同码不同手感）",
            confirmKey = AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            backKey = AndroidKeyEvent.KEYCODE_BACK,
            upKey = AndroidKeyEvent.KEYCODE_DPAD_UP,
            downKey = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            digitOneKey = AndroidKeyEvent.KEYCODE_1,
        ),
        Profile(
            device = "蓝牙遥控器（NUMPAD）",
            note = "蓝牙数字键为 NUMPAD_1，确认走 ENTER",
            confirmKey = AndroidKeyEvent.KEYCODE_ENTER,
            backKey = AndroidKeyEvent.KEYCODE_BACK,
            upKey = AndroidKeyEvent.KEYCODE_DPAD_UP,
            downKey = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            digitOneKey = AndroidKeyEvent.KEYCODE_NUMPAD_1,
        ),
        Profile(
            device = "无障碍开关控",
            note = "只有方向 + 确认，无数字键；这里用方向键验证不误触",
            confirmKey = AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            backKey = AndroidKeyEvent.KEYCODE_BACK,
            upKey = AndroidKeyEvent.KEYCODE_DPAD_UP,
            downKey = AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            digitOneKey = AndroidKeyEvent.KEYCODE_DPAD_UP, // 无数字键，复用方向键验证「不误入数字态」
        ),
    )
}
