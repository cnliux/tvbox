package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2-14：集名黄金语料（每收到一条「集数错」issue，往这里加一行即可永不复发）。 */
class EpisodeKeyTest {
    private val golden = listOf(
        "第05集" to 5,
        "第5集" to 5,
        "第 5 集" to 5,
        "E05" to 5,
        "e5" to 5,
        "EP.5" to 5,
        "EP05" to 5,
        "05" to 5,
        "5" to 5,
        "第5话" to 5,
        "第5回" to 5,
        "第5期" to 5,
        "05(国语)" to 5,
        "05（国语）" to 5,
        "5-完结" to 5,
        "【05】" to 5,
        "[05]" to 5,
        "第12集" to 12,
        "E123" to 123,
        "第1集 电影" to 1,
    )

    @Test
    fun `golden corpus normalizes to the same key`() {
        golden.forEach { (raw, expected) ->
            assertEquals("解析失败: $raw", expected, EpisodeKey.parse(raw)?.num)
        }
    }

    @Test
    fun `annotation is orthogonal to episode number`() {
        assertEquals(EpAnnotation.MANDARIN, EpisodeKey.parse("第5集 国语")?.annot)
        assertEquals(EpAnnotation.CANTONESE, EpisodeKey.parse("第5集 粤语")?.annot)
        assertEquals(EpAnnotation.UNCUT, EpisodeKey.parse("E5 未删减")?.annot)
        // 版本维度不进主键：集号相同。
        assertEquals(EpisodeKey.parse("第5集 国语")?.num, EpisodeKey.parse("第5集 粤语")?.num)
    }

    @Test
    fun `different annotations do not overwrite each other`() {
        val mandarin = EpisodeKey.parse("第5集 国语")!!
        val cantonese = EpisodeKey.parse("第5集 粤语")!!
        assertFalse(EpisodeKey.matches(mandarin, cantonese))
        assertTrue(EpisodeKey.matches(EpisodeKey.parse("第5集")!!, cantonese))
        assertTrue(EpisodeKey.matches(mandarin, mandarin.copy()))
    }

    @Test
    fun `unparseable names are rejected instead of guessed`() {
        assertNull(EpisodeKey.parse("预告"))
        assertNull(EpisodeKey.parse("花絮"))
        assertNull(EpisodeKey.parse(""))
    }
}
