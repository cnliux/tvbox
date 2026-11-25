package com.tvbox.app.ui

import com.tvbox.app.ui.components.TvA11y
import org.junit.Assert.assertEquals
import org.junit.Test

/** S6-32：TvA11y 纯文案助手的 JVM 单测（不依赖 Compose 运行时）。 */
class TvA11yTest {

    @Test
    fun `label joins only non-blank parts with Chinese comma`() {
        assertEquals("搜索，打开", TvA11y.label(" 搜索 ", null, "", "  打开  "))
        assertEquals("", TvA11y.label(null, "", "   "))
        assertEquals("第 3 集", TvA11y.label("第 3 集"))
    }

    @Test
    fun `choice appends selected state so TalkBack reads it in one pass`() {
        assertEquals("高清，已选中", TvA11y.choice("高清", selected = true))
        assertEquals("高清，未选中", TvA11y.choice("高清", selected = false))
    }

    @Test
    fun `action puts verb after noun in Chinese reading order`() {
        assertEquals("历史，打开", TvA11y.action("历史", "打开"))
        assertEquals("设置", TvA11y.label("设置"))
    }

    @Test
    fun `episode includes context and skips unknown total`() {
        assertEquals("第 2 集，共 24 集，重播", TvA11y.episode(2, 24, "重播"))
        assertEquals("第 1 集，未命名", TvA11y.episode(0, 0, " 未命名 "))
        assertEquals("第 5 集", TvA11y.episode(5, -1, ""))
    }

    @Test
    fun `progress clamps percentage to display range`() {
        assertEquals("已观看 0%", TvA11y.progress(-10))
        assertEquals("已观看 45%", TvA11y.progress(45))
        assertEquals("已观看 100%", TvA11y.progress(999))
    }
}
