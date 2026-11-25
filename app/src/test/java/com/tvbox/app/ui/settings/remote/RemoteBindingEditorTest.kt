package com.tvbox.app.ui.settings.remote

import android.view.KeyEvent
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBindingEditorTest {

    private val default = RemoteKeyBindings.Default

    @Test
    fun `rows cover every action in binding order`() {
        val rows = remoteBindingRows(default)
        assertEquals(RemoteKeyAction.entries.size, rows.size)
        assertEquals(RemoteKeyAction.entries.map { it.id }, rows.map { it.action.id })
        assertEquals(RemoteKeyAction.entries.map { it.displayName }, rows.map { it.displayName })
    }

    @Test
    fun `default binding summary renders sorted hex key codes`() {
        val rows = remoteBindingRows(default).associateBy { it.action }
        // Confirm 绑定 DPAD_CENTER / ENTER / NUMPAD_ENTER → 排序后的十六进制。
        val expected = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        ).sorted().joinToString(" / ") { "0x%03X".format(it) }
        assertEquals(expected, rows.getValue(RemoteKeyAction.Confirm).boundKeysSummary)
        // 无自定义键 → 不显示清除入口。
        assertFalse(rows.getValue(RemoteKeyAction.Confirm).hasCustomBinding)
    }

    @Test
    fun `custom binding replaces default and flags clear entry`() {
        val custom = default.withBinding(RemoteKeyAction.Menu, KeyEvent.KEYCODE_PROG_RED)
        val row = remoteBindingRows(custom).first { it.action == RemoteKeyAction.Menu }
        assertEquals("0x%03X".format(KeyEvent.KEYCODE_PROG_RED), row.boundKeysSummary)
        assertTrue(row.hasCustomBinding)
    }

    @Test
    fun `conflict rows flag a key owned by two actions for both layouts`() {
        val conflicted = default.withBinding(RemoteKeyAction.Previous, KeyEvent.KEYCODE_MENU)
        val rows = remoteBindingRows(conflicted).associateBy { it.action }

        val menuRow = rows.getValue(RemoteKeyAction.Menu)
        assertEquals(1, menuRow.conflicts.size)
        assertEquals(KeyEvent.KEYCODE_MENU, menuRow.conflicts.first().keyCode)
        assertTrue(menuRow.conflicts.first().summary.contains("菜单"))

        val previousRow = rows.getValue(RemoteKeyAction.Previous)
        assertEquals(1, previousRow.conflicts.size)
        assertEquals(KeyEvent.KEYCODE_MENU, previousRow.conflicts.first().keyCode)

        // 未冲突的默认表：所有行都不带红字标记。
        assertTrue(remoteBindingRows(default).all { it.conflicts.isEmpty() })
    }

    @Test
    fun `summary is identical for mobile and tv since both consume shared rows`() {
        // 同一 binding 态：手机 LazyColumn 与 TV 焦点网格都消费 remoteBindingRows()，
        // 因此「已绑键清单」文本必然一致（这正是 P2-13 要防的漂移）。
        val bindings = default
            .withBinding(RemoteKeyAction.Next, 87)
            .withBinding(RemoteKeyAction.Previous, 88)
            .withoutBinding(RemoteKeyAction.Menu)
        val mobileView = remoteBindingRows(bindings).map { it.displayName to it.boundKeysSummary }
        val tvView = remoteBindingRows(bindings).map { it.displayName to it.boundKeysSummary }
        assertEquals(mobileView, tvView)
        // 抽样断言：上一集/下一集的自定义键与未绑定动作的文案。
        val byAction = remoteBindingRows(bindings).associateBy { it.action }
        assertEquals("0x057", byAction.getValue(RemoteKeyAction.Next).boundKeysSummary)
        assertEquals("0x058", byAction.getValue(RemoteKeyAction.Previous).boundKeysSummary)
        // withoutBinding 清除自定义后回退默认（MENU = 0x052），不会显示"未绑定"。
        assertEquals("0x052", byAction.getValue(RemoteKeyAction.Menu).boundKeysSummary)
        assertFalse(byAction.getValue(RemoteKeyAction.Menu).hasCustomBinding)
    }
}
