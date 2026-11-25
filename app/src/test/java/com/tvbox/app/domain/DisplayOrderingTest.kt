package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** T5-35：locale 大小写 / 中文排序 / RTL 方向语义的 JVM 单测。 */
class DisplayOrderingTest {

    @Test
    fun `index key uses root locale so turkish i stays I`() {
        assertEquals("I", DisplayOrdering.indexKey("istanbul"))
        assertEquals("T", DisplayOrdering.indexKey(" transition "))
        assertNotEquals("İ", DisplayOrdering.indexKey("i"))
        assertEquals("İ", "i".uppercase(Locale.forLanguageTag("tr-TR")))
    }

    @Test
    fun `chinese collation orders pinyin instead of unicode code points`() {
        val comparator = DisplayOrdering.chineseComparator()
        assertTrue("一 应排在 中 前（yi < zhong）", comparator.compare("一", "中") < 0)
        assertEquals(0, comparator.compare("中", "中"))
        assertNotEquals(0, comparator.compare("一", "中"))
    }

    @Test
    fun `empty title has empty index key`() {
        assertEquals("", DisplayOrdering.indexKey(""))
        assertEquals("", DisplayOrdering.indexKey("   "))
    }

    @Test
    fun `rtl flips forward direction only`() {
        assertEquals(DisplayOrdering.Direction.Right, DisplayOrdering.forwardDirection(isRtl = false))
        assertEquals(DisplayOrdering.Direction.Left, DisplayOrdering.forwardDirection(isRtl = true))
    }
}
