package com.tvbox.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续代理启用条件的回归测试。
 *
 * 这里的 URL 双保险的由来：桥接层顶层 protocol 只代表首条候选，
 * 混合 FLV/HLS 时会把 m3u8 误判成 flv 并交给只处理 FLV tag 的 Go 代理。
 */
class FlvContinuityProxyTest {

    @Test
    fun `only huya and douyu flv are wrapped`() {
        assertTrue(FlvContinuityProxy.shouldWrap("huya", "flv"))
        assertTrue(FlvContinuityProxy.shouldWrap("douyu", "FLV"))
        assertFalse(FlvContinuityProxy.shouldWrap("bilibili", "flv"))
        assertFalse(FlvContinuityProxy.shouldWrap("douyu", "hls"))
        assertFalse(FlvContinuityProxy.shouldWrap("huya", "http"))
    }

    @Test
    fun `site matching is case insensitive`() {
        assertTrue(FlvContinuityProxy.shouldWrap("HUYA", "flv"))
        assertTrue(FlvContinuityProxy.shouldWrap("DouYu", "fLv"))
    }

    @Test
    fun `hls url is never sent to flv proxy even if protocol says flv`() {
        assertFalse(
            FlvContinuityProxy.shouldWrap(
                "douyu", "flv", "https://x.douyu.com/play/a.m3u8?key=v",
            ),
        )
        assertFalse(
            FlvContinuityProxy.shouldWrap(
                "huya", "flv", "https://x.huya.com/play/index.m3u8",
            ),
        )
    }

    @Test
    fun `flv url and blank url both allow proxy`() {
        assertTrue(
            FlvContinuityProxy.shouldWrap(
                "douyu", "flv", "http://x.flv.douyu.com/live/abc.flv?key=v",
            ),
        )
        assertTrue(FlvContinuityProxy.shouldWrap("huya", "flv", ""))
    }

    @Test
    fun `third-party proxy resolve url is never wrapped`() {
        // 形如 https://mursor.ottiptv.cc/huya/房间号：稳定流，直接放行直连。
        assertFalse(
            FlvContinuityProxy.shouldWrap(
                "huya", "flv", "https://mursor.ottiptv.cc/huya/11342412",
            ),
        )
        assertFalse(
            FlvContinuityProxy.shouldWrap(
                "douyu", "flv", "https://mursor.ottiptv.cc/douyu/36252",
            ),
        )
    }

    @Test
    fun `official cdn path containing site name is still wrapped`() {
        // 官方 CDN 地址可能带 .flv 或签名参数，不能被误判成第三方代理地址。
        assertTrue(
            FlvContinuityProxy.shouldWrap(
                "huya", "flv", "https://hw-tct.huya.com/src/11342412-1.flv?wsSecret=abc&wsTime=123",
            ),
        )
    }
}
