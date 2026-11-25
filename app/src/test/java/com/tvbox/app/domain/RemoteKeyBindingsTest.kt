package com.tvbox.app.domain

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥控键位绑定表的「覆盖语义」回归锁定：
 * - 用户为某动作改绑后整体替换默认（不再追加，避免设置页"说谎"）
 * - 默认键表补全方向键 / 确认 / 返回等抽象动作
 * - 空自定义集合回退默认
 */
class RemoteKeyBindingsTest {

    @Test
    fun customBindingOverridesDefaultNotAppends() {
        // 用户把 Previous 改绑到 MENU：整体替换默认，而不是追加在默认键之后。
        val bindings = RemoteKeyBindings(
            custom = mapOf(RemoteKeyAction.Previous.id to setOf(KeyEvent.KEYCODE_MENU)),
        )

        // keysFor：改绑后只有 MENU，不含默认 MEDIA_PREVIOUS。
        assertEquals(setOf(KeyEvent.KEYCODE_MENU), bindings.keysFor(RemoteKeyAction.Previous))
        assertFalse(bindings.keysFor(RemoteKeyAction.Previous).contains(KeyEvent.KEYCODE_MEDIA_PREVIOUS))

        // actionsFor 反查：MENU 现在触发 Previous（改绑后 Previous 认领了 MENU）。
        // 注：Menu 动作自身的默认键仍含 MENU，因此反查会同时出现两者；语义裁决由页面按
        // 优先级负责（本绑定表设计允许同一键映射多个动作）。这里只验证「改绑生效」这一核心事实。
        val menuActions = bindings.actionsFor(KeyEvent.KEYCODE_MENU)
        assertTrue(menuActions.contains(RemoteKeyAction.Previous))
    }

    @Test
    fun defaultKeysPresent() {
        val bindings = RemoteKeyBindings.Default

        assertTrue(bindings.keysFor(RemoteKeyAction.DirectionLeft).contains(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(bindings.keysFor(RemoteKeyAction.DirectionRight).contains(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(bindings.keysFor(RemoteKeyAction.DirectionUp).contains(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(bindings.keysFor(RemoteKeyAction.DirectionDown).contains(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(bindings.keysFor(RemoteKeyAction.Confirm).contains(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(bindings.keysFor(RemoteKeyAction.Back).contains(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun emptyCustomFallsBackToDefault() {
        // 某动作的 custom 为空集时，回退默认绑定（而不是返回空集或追加）。
        val bindings = RemoteKeyBindings(
            custom = mapOf(RemoteKeyAction.Previous.id to emptySet()),
        )

        assertEquals(RemoteKeyBindings.defaultBindings[RemoteKeyAction.Previous], bindings.keysFor(RemoteKeyAction.Previous))
        assertTrue(bindings.keysFor(RemoteKeyAction.Previous).contains(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun directionalPlayPauseSeparatedFromToggle() {
        // P2-12：MEDIA_PLAY 与 MEDIA_PAUSE 是「定向」键，拆出独立动作，
        // PlayPause 只保留 MEDIA_PLAY_PAUSE，避免把明确要播/要停的键误当 toggle。
        val bindings = RemoteKeyBindings.Default

        assertEquals(setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), bindings.keysFor(RemoteKeyAction.PlayPause))
        assertEquals(setOf(KeyEvent.KEYCODE_MEDIA_PLAY), bindings.keysFor(RemoteKeyAction.Play))
        assertEquals(setOf(KeyEvent.KEYCODE_MEDIA_PAUSE), bindings.keysFor(RemoteKeyAction.Pause))

        // 反查：PLAY 只触发 Play（不再触发 PlayPause），PAUSE 只触发 Pause。
        assertTrue(bindings.actionsFor(KeyEvent.KEYCODE_MEDIA_PLAY).contains(RemoteKeyAction.Play))
        assertFalse(bindings.actionsFor(KeyEvent.KEYCODE_MEDIA_PLAY).contains(RemoteKeyAction.PlayPause))
        assertTrue(bindings.actionsFor(KeyEvent.KEYCODE_MEDIA_PAUSE).contains(RemoteKeyAction.Pause))
        assertFalse(bindings.actionsFor(KeyEvent.KEYCODE_MEDIA_PAUSE).contains(RemoteKeyAction.PlayPause))
    }
}
