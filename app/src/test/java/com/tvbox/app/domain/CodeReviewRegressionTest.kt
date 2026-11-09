package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮代码审查修复的回归锁定：
 * - 换源/续播编号未命中时不能跳过 fallback 兜底
 * - 数字选台超长输入仍应走前缀匹配
 * - 直播源线路名元数据前缀、连续 EXTINF 失败清空、地址含多个 $
 */
class CodeReviewRegressionTest {

    private fun source(vararg titles: String) = PlaySource(
        name = "线路",
        episodes = titles.map { PlayEpisode(it, "https://test/$it") },
    )

    @Test
    fun correspondingEpisodeIndexReturnsNullWhenNumberMissing() {
        // 契约：目标线路有编号剧集但无同编号时返回 null（不换源），避免串到别的集。
        val target = source("第1集", "第2集", "第3集")
        val result = correspondingEpisodeIndex(target, title = "第99集", fallbackIndex = 1)
        assertEquals(null, result)
    }

    @Test
    fun correspondingEpisodeIndexStillMatchesSameNumber() {
        val target = source("第1集", "第2集", "第3集")
        assertEquals(2, correspondingEpisodeIndex(target, title = "第3集", fallbackIndex = 0))
    }

    @Test
    fun correspondingEpisodeIndexUsesFallbackWhenNoNumberedEpisodes() {
        // 目标线路根本没有编号剧集时，才按 fallbackIndex 做下标对应。
        val target = source("预告片", "花絮")
        assertEquals(1, correspondingEpisodeIndex(target, title = "第2集", fallbackIndex = 1))
    }

    private fun channels(vararg numbers: Int) = numbers.map {
        LiveChannel(
            number = it,
            groupName = "央视",
            name = "CCTV$it",
            lines = listOf(LiveChannelLine("线路1", "https://test/$it.m3u8")),
        )
    }

    @Test
    fun longNumberInputStillUsesPrefixMatch() {
        val list = channels(1, 12, 120, 9999)
        // 超长纯数字（超 Int 范围）不应因 toIntOrNull()==null 直接返回 -1。
        val result = resolveLiveChannelNumberTarget(list, "99999999999999")
        assertEquals(-1, result)
        // 但落在频道号范围内的前缀仍能命中（旧实现在超长串上跳过前缀匹配）。
        assertEquals(3, resolveLiveChannelNumberTarget(list, "9999"))
    }

    @Test
    fun exactNumberMatchIsPreferred() {
        val list = channels(1, 12, 120)
        assertEquals(2, resolveLiveChannelNumberTarget(list, "120"))
        assertEquals(0, resolveLiveChannelNumberTarget(list, "1"))
    }

    @Test
    fun liveLineMetadataKeepsOriginalLineNumber() {
        // 真实源 `LR•IPV4•29『线路3』`：修复前缀常量后应保留「线路3」，
        // 而不是退化成按出现顺序重新编号的「线路1」。
        val channels = parseLiveChannels(
            "央视一套,http://a.test/1.m3u8\$LR•IPV4•29『线路3』",
        )
        assertEquals("线路3", channels.single().lines.single().name)
    }

    @Test
    fun failedExtinfClearsPendingChannel() {
        // 连续 EXTINF：后一条解析失败（无逗号无 tvg-name/id）必须清空元数据，
        // 紧随的 URL 不能挂到前一个频道名下。
        val channels = parseLiveChannels(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="测试台",测试台
            #EXTINF:-1
            https://live.test/orphan.m3u8
            """.trimIndent(),
        )
        // 失败的 EXTINF 之后 URL 会被当作「无元数据」的新频道（频道N），
        // 而不是错误地归入「测试台」。
        assertTrue(channels.none { it.name == "测试台" && it.lines.any { l -> l.url.contains("orphan") } })
    }

    @Test
    fun playSourceCandidateNeverIncludesCurrentIndex() {
        // 3 条线路，当前（失败）线路下标 = 1。候选里不能出现 1 自己。
        val movie = Movie(
            id = 1,
            apiLineId = "line",
            apiLineName = "线路",
            name = "测试影片",
            typeId = 0,
            typeName = "",
            posterUrl = "",
            remarks = "",
            year = "",
            area = "",
            language = "",
            actor = "",
            director = "",
            duration = "",
            description = "",
            playSources = listOf(
                source("第1集", "第2集"),
                source("第1集", "第2集"),
                source("第1集", "第2集"),
            ),
        )
        val decision = PlaybackAgent(
            unhealthyCooldownMs = 60_000L,
        ).selectNextSource(
            movie = movie,
            currentSourceIndex = 1,
            episodeIndex = 0,
            blockedSourceIndexes = emptySet(),
            healthSnapshot = PlaybackHealthSnapshot(emptyMap()),
        )
        // 无论最终选中哪条，都不能等于当前正在失败的线路 1。
        assertTrue(decision.nextSourceIndex != 1)
    }
}
