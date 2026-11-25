package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCleanupRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(relative: String): File = File(tmp.root, relative).apply {
        parentFile?.mkdirs()
        createNewFile()
    }

    private fun oldFile(relative: String, ageDays: Int): File = newFile(relative).apply {
        assertTrue(setLastModified(System.currentTimeMillis() - ageDays * 86_400_000L))
    }

    @Test
    fun allSevenCategoriesExposeImpactDescription() {
        val infos = StorageCleanupRepository().categoryInfos()
        assertEquals(7, infos.size)
        assertTrue(infos.all { it.impact.isNotBlank() })
        assertEquals(
            setOf(
                "playback-cache", "spider-legacy", "posters", "crash-logs",
                "diagnostic-logs", "epg-cache", "history-thumbnails",
            ),
            infos.map { it.key }.toSet(),
        )
    }

    @Test
    fun scanOnlyPicksMatchingExpiredFilesAndNeverTouchesRegularFiles() {
        val repo = StorageCleanupRepository()
        val temp = oldFile("cache/playback/segment.tmp", 3)
        val regular = oldFile("cache/playback/movie.mp4", 30)
        val freshTemp = newFile("cache/playback/fresh.tmp") // 刚创建，未过期

        val playback = repo.scan(tmp.root).first { it.key == "playback-cache" }
        assertEquals(listOf(temp.absolutePath), playback.files.map { it.absolutePath })
        assertFalse(playback.files.any { it.absolutePath == regular.absolutePath })
        assertFalse(playback.files.any { it.absolutePath == freshTemp.absolutePath })

        val result = repo.clean("playback-cache", tmp.root)
        assertEquals(1, result.deletedCount)
        assertFalse(temp.exists())
        assertTrue(regular.exists())
        assertTrue(freshTemp.exists())
    }

    @Test
    fun smartCleanSkipsPostersAndEpgEvenWhenExpired() {
        val repo = StorageCleanupRepository()
        val poster = oldFile("posters/cover.tmp", 10)
        val epg = oldFile("epg/guide.xml", 10)
        val log = oldFile("logs/player.log", 10)

        val result = repo.smartClean(tmp.root)
        assertTrue(result.deletedCount >= 1)
        assertTrue(poster.exists())
        assertTrue(epg.exists())
        assertFalse(log.exists())
    }

    @Test
    fun cleanUnknownCategoryReturnsExplicitStatus() {
        val result = StorageCleanupRepository().clean("not-a-category", tmp.root)
        assertEquals(0, result.deletedCount)
        assertTrue(result.message.contains("未知的清理分类"))
    }
}
