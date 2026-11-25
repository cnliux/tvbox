package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三类播放器按钮分场景配置的契约测试。 */
class PlayerControlLayoutTest {

    @Test
    fun `vod keeps episode speed and track buttons`() {
        val keys = PlayerControlLayout.visibleKeys(PlayerSurface.Vod)
        assertTrue(PlayerControlButton.Episodes.storageKey in keys)
        assertTrue(PlayerControlButton.Speed.storageKey in keys)
        assertTrue(PlayerControlButton.AudioTracks.storageKey in keys)
        assertTrue(PlayerControlButton.SubtitleTracks.storageKey in keys)
        assertTrue(PlayerControlButton.Skip.storageKey in keys)
        // 点播不该出现「线路(平台)」按钮，那是直播页的能力。
        assertFalse(PlayerControlButton.Lines.storageKey in keys)
    }

    @Test
    fun `live tv drops speed episodes and tracks but keeps lines and favorite`() {
        val keys = PlayerControlLayout.visibleKeys(PlayerSurface.LiveTv)
        assertTrue(PlayerControlButton.Lines.storageKey in keys)
        assertTrue(PlayerControlButton.Favorite.storageKey in keys)
        assertFalse(PlayerControlButton.Speed.storageKey in keys)
        assertFalse(PlayerControlButton.Episodes.storageKey in keys)
        assertFalse(PlayerControlButton.AudioTracks.storageKey in keys)
        assertFalse(PlayerControlButton.SubtitleTracks.storageKey in keys)
    }

    @Test
    fun `platform live keeps cast and lines but not episodes`() {
        val keys = PlayerControlLayout.visibleKeys(PlayerSurface.PlatformLive)
        assertTrue(PlayerControlButton.Cast.storageKey in keys)
        assertTrue(PlayerControlButton.Lines.storageKey in keys)
        assertFalse(PlayerControlButton.Episodes.storageKey in keys)
        assertFalse(PlayerControlButton.Favorite.storageKey in keys)
    }

    @Test
    fun `per surface hiding does not leak into other surfaces`() {
        var map = emptyMap<String, Set<String>>()
        map = PlayerControlLayout.setVisible(map, PlayerSurface.Vod, PlayerControlButton.Speed, false)
        assertFalse(PlayerControlLayout.isVisible(map, PlayerSurface.Vod, PlayerControlButton.Speed))
        // 电视直播从不显示倍速；点播的隐藏不应污染其它场景的清单。
        assertEquals(
            PlayerControlLayout.visibleKeys(PlayerSurface.LiveTv),
            PlayerControlLayout.visibleKeys(PlayerSurface.LiveTv, hiddenForSurface = map[PlayerSurface.LiveTv.storageKey].orEmpty()),
        )
        // 同一按钮在平台直播里若存在也不受影响（这里用 Cast 验证跨场景独立）。
        map = PlayerControlLayout.setVisible(map, PlayerSurface.Vod, PlayerControlButton.Cast, false)
        assertTrue(PlayerControlLayout.isVisible(map, PlayerSurface.PlatformLive, PlayerControlButton.Cast))
        assertFalse(PlayerControlLayout.isVisible(map, PlayerSurface.Vod, PlayerControlButton.Cast))
    }

    @Test
    fun `legacy global hidden still applies on top of per surface settings`() {
        val keys = PlayerControlLayout.visibleKeys(
            PlayerSurface.Vod,
            hiddenGlobal = setOf(PlayerControlButton.Info.storageKey),
        )
        assertFalse(PlayerControlButton.Info.storageKey in keys)
        assertTrue(PlayerControlButton.Url.storageKey in keys)
    }

    @Test
    fun `setVisible true removes tombstone and empty entry disappears`() {
        var map = PlayerControlLayout.setVisible(
            emptyMap(), PlayerSurface.Vod, PlayerControlButton.Url, false,
        )
        assertTrue(map.containsKey(PlayerSurface.Vod.storageKey))
        map = PlayerControlLayout.setVisible(map, PlayerSurface.Vod, PlayerControlButton.Url, true)
        assertFalse(map.containsKey(PlayerSurface.Vod.storageKey))
        assertTrue(PlayerControlLayout.isVisible(map, PlayerSurface.Vod, PlayerControlButton.Url))
    }
}
