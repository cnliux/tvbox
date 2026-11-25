package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** S3-17：导入预览数字与备份旋转契约。 */
class SubscriptionImportPlannerTest {
    private fun ref(id: String, name: String, url: String = "http://x/$id") =
        ImportSourceRef(id, name, url)

    @Test
    fun `diff counts add remove update and rename independently`() {
        val old = listOf(ref("1", "A"), ref("2", "B"), ref("3", "C"))
        val new = listOf(
            ref("1", "A"),                     // 不变
            ref("2", "B2", "http://x/new2"),   // 改名 + 换 URL
            ref("4", "D"),                     // 新增
        )
        val diff = SubscriptionImportPlanner.diff(old, new)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(1, diff.updated)
        assertEquals(1, diff.renamed)
        assertEquals(4, diff.totalChanges)
    }

    @Test
    fun `empty incoming means all removed`() {
        val diff = SubscriptionImportPlanner.diff(listOf(ref("1", "A"), ref("2", "B")), emptyList())
        assertEquals(0, diff.added)
        assertEquals(2, diff.removed)
    }

    @Test
    fun `rotation keeps newest five slots`() {
        val backups = (1..8).toList()
        assertEquals(listOf(4, 5, 6, 7, 8), SubscriptionImportPlanner.rotateBackups(backups))
        assertEquals(listOf(1, 2, 3), SubscriptionImportPlanner.rotateBackups(listOf(1, 2, 3)))
    }
}
