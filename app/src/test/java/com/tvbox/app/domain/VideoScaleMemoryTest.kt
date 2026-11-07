package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScaleMemoryTest {
    private val movieKey = vodVideoScaleKey("line-a", "movie-1", episodeIndex = 2)
    private val channelKey = liveVideoScaleKey("央视", "CCTV-1")
    private val roomKey = roomVideoScaleKey("huya", "12345")

    @Test
    fun contentWithoutOverrideFallsBackToGlobalDefault() {
        val settings = AppSettings(videoScaleMode = VideoScaleMode.Zoom)

        assertEquals(VideoScaleMode.Zoom, settings.resolveVideoScaleMode(movieKey))
        assertEquals(VideoScaleMode.Zoom, settings.resolveVideoScaleMode(null))
    }

    @Test
    fun contentOverrideWinsOverGlobalDefault() {
        val settings = AppSettings(videoScaleMode = VideoScaleMode.Fit)
            .withContentVideoScaleMode(movieKey, VideoScaleMode.Fill)

        assertEquals(VideoScaleMode.Fill, settings.resolveVideoScaleMode(movieKey))
        assertEquals(VideoScaleMode.Fit, settings.resolveVideoScaleMode(channelKey))
    }

    @Test
    fun changingGlobalDefaultKeepsExistingContentEntries() {
        val overridden = AppSettings().withContentVideoScaleMode(roomKey, VideoScaleMode.Fill)
        val globalChanged = overridden.copy(videoScaleMode = VideoScaleMode.Zoom)

        assertEquals(VideoScaleMode.Zoom, globalChanged.resolveVideoScaleMode(channelKey))
        assertEquals(VideoScaleMode.Fill, globalChanged.resolveVideoScaleMode(roomKey))
        assertTrue(globalChanged.contentVideoScaleModes.containsKey(roomKey))
    }

    @Test
    fun contentKeysDoNotLeakUrlsAndSeparateEpisodes() {
        val episodeTwo = vodVideoScaleKey("line-a", "movie-1", episodeIndex = 2)
        val episodeThree = vodVideoScaleKey("line-a", "movie-1", episodeIndex = 3)

        assertEquals("vod|line-a|movie-1|2", episodeTwo)
        assertEquals("live|央视|CCTV-1", channelKey)
        assertEquals("room|huya|12345", roomKey)
        assertTrue(episodeTwo != episodeThree)
        assertTrue(episodeTwo.contains("http").not())
    }
}
