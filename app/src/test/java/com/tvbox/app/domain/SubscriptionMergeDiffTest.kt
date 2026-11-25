package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMergeDiffTest {

    private fun entry(
        id: String = "src1",
        name: String = "源$id",
        apiUrl: String = "http://a/$id.json",
        liveUrls: List<String> = listOf("http://live/$id.txt"),
        enabled: Boolean = true,
    ) = SourceEntry(id, name, apiUrl, liveUrls, enabled)

    // 1. 全新增（current 空）
    @Test
    fun diffAddsAllWhenCurrentEmpty() {
        val incoming = listOf(entry(id = "a"), entry(id = "b"), entry(id = "c"))
        val diff = SubscriptionMerger.diff(emptyList(), incoming)
        assertEquals(3, diff.added.size)
        assertEquals(0, diff.updated.size)
        assertEquals(0, diff.removed.size)
        assertEquals(0, diff.unchanged.size)
        assertEquals(0, diff.conflicts.size)
    }

    // 2. 全删除（incoming 空）
    @Test
    fun diffRemovesAllWhenIncomingEmpty() {
        val current = listOf(entry(id = "a"), entry(id = "b"))
        val diff = SubscriptionMerger.diff(current, emptyList())
        assertEquals(0, diff.added.size)
        assertEquals(0, diff.updated.size)
        assertEquals(2, diff.removed.size)
        assertEquals(0, diff.unchanged.size)
        assertEquals(setOf("a", "b"), diff.removed.map { it.id }.toSet())
    }

    // 3. 无变化 → unchanged 全部
    @Test
    fun diffReportsUnchangedWhenIdentical() {
        val current = listOf(entry(id = "a"), entry(id = "b"))
        val incoming = listOf(entry(id = "a"), entry(id = "b"))
        val diff = SubscriptionMerger.diff(current, incoming)
        assertEquals(0, diff.added.size)
        assertEquals(0, diff.updated.size)
        assertEquals(0, diff.removed.size)
        assertEquals(2, diff.unchanged.size)
    }

    // 4. 改 name → updated，changedFields=["name"]
    @Test
    fun diffDetectsNameChange() {
        val diff = SubscriptionMerger.diff(listOf(entry()), listOf(entry(name = "改名")))
        assertEquals(1, diff.updated.size)
        assertEquals(listOf("name"), diff.updated[0].changedFields)
    }

    // 5. 改 apiUrl → updated，changedFields=["apiUrl"]
    @Test
    fun diffDetectsApiUrlChange() {
        val diff = SubscriptionMerger.diff(listOf(entry()), listOf(entry(apiUrl = "http://b/2.json")))
        assertEquals(1, diff.updated.size)
        assertEquals(listOf("apiUrl"), diff.updated[0].changedFields)
    }

    // 6. 改 liveUrls → updated，changedFields=["liveUrls"]
    @Test
    fun diffDetectsLiveUrlsChange() {
        val diff = SubscriptionMerger.diff(listOf(entry()), listOf(entry(liveUrls = listOf("http://live/new.txt"))))
        assertEquals(1, diff.updated.size)
        assertEquals(listOf("liveUrls"), diff.updated[0].changedFields)
    }

    // 7. 仅 enabled 变化 → 也算 updated
    @Test
    fun diffDetectsEnabledToggleAsUpdate() {
        val diff = SubscriptionMerger.diff(listOf(entry()), listOf(entry(enabled = false)))
        assertEquals(1, diff.updated.size)
        assertEquals(listOf("enabled"), diff.updated[0].changedFields)
    }

    // 8. incoming 内重复 id → conflict(DuplicateId)
    @Test
    fun diffFlagsDuplicateIdConflict() {
        val incoming = listOf(entry(id = "dup"), entry(id = "dup", name = "别名"))
        val diff = SubscriptionMerger.diff(emptyList(), incoming)
        assertTrue(diff.conflicts.any { it.kind == ConflictKind.DuplicateId })
    }

    // 9. incoming 内重复 name（不同 id）→ conflict(DuplicateName)
    @Test
    fun diffFlagsDuplicateNameConflict() {
        val incoming = listOf(entry(id = "x", name = "同"), entry(id = "y", name = "同"))
        val diff = SubscriptionMerger.diff(emptyList(), incoming)
        assertTrue(diff.conflicts.any { it.kind == ConflictKind.DuplicateName })
    }

    // 10. incoming 内重复 url（不同 id）→ conflict(DuplicateUrl)
    @Test
    fun diffFlagsDuplicateUrlConflict() {
        val shared = "http://live/same.txt"
        val incoming = listOf(
            entry(id = "x", liveUrls = listOf(shared)),
            entry(id = "y", liveUrls = listOf(shared)),
        )
        val diff = SubscriptionMerger.diff(emptyList(), incoming)
        assertTrue(diff.conflicts.any { it.kind == ConflictKind.DuplicateUrl })
    }

    // 11. summarize：各计数 + 无冲突
    @Test
    fun summarizeReportsCountsAndNoConflict() {
        val diff = SubscriptionMerger.diff(
            listOf(entry(id = "keep"), entry(id = "gone"), entry(id = "upd")),
            listOf(entry(id = "keep"), entry(id = "upd", name = "新名"), entry(id = "new")),
        )
        val summary = SubscriptionMerger.summarize(diff)
        assertTrue(summary.contains("新增 1"))
        assertTrue(summary.contains("更新 1"))
        assertTrue(summary.contains("删除 1"))
        assertTrue(summary.contains("无冲突"))
    }

    // 12. summarize：有冲突文案
    @Test
    fun summarizeReportsConflictCount() {
        val incoming = listOf(entry(id = "dup"), entry(id = "dup", name = "别名"))
        val diff = SubscriptionMerger.diff(emptyList(), incoming)
        val summary = SubscriptionMerger.summarize(diff)
        assertTrue(summary.contains("冲突"))
        assertTrue(!summary.contains("无冲突"))
    }

    // 13. diff 幂等（同输入两次 diff 结果相等）
    @Test
    fun diffIsIdempotent() {
        val current = listOf(entry(id = "a"), entry(id = "b"), entry(id = "c", name = "旧名"))
        val incoming = listOf(entry(id = "a"), entry(id = "c", name = "新名"), entry(id = "d"))
        val first = SubscriptionMerger.diff(current, incoming)
        val second = SubscriptionMerger.diff(current, incoming)
        assertEquals(first, second)
    }
}
