package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.LiveChannel

/**
 * 记住每个电视频道上次成功播放的线路下标。
 *
 * Key 必须同时含分组名与频道名：TXT 源里"央视卫视/CCTV1"与"灵鹿整合/CCTV1"
 * 是两个不同频道（线路数完全不同），只按频道名记会互相污染——
 * 记住的线号超出另一分组该频道的线路数时直接黑屏。
 */
class LiveLinePreference(context: Context) {
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val prefs by lazy { context.applicationContext.getSharedPreferences("live_line_prefs", Context.MODE_PRIVATE) }

    fun getSavedLineIndex(channel: LiveChannelKey): Int {
        if (channel.channelName.isBlank()) return -1
        return prefs.getInt(key(channel), -1)
    }

    fun saveLineIndex(channel: LiveChannelKey, lineIndex: Int) {
        if (channel.channelName.isBlank()) return
        if (lineIndex < 0) return
        prefs.edit().putInt(key(channel), lineIndex).apply()
    }

    private fun key(channel: LiveChannelKey): String {
        val group = channel.groupName.trim().replace("|", "_").replace("\n", "_")
        val name = channel.channelName.trim().replace("|", "_").replace("\n", "_")
        return "live_line:$group#$name"
    }
}

/** 线路记忆的频道定位键（分组 + 频道名）。 */
data class LiveChannelKey(val groupName: String, val channelName: String)

/** 频道在线路记忆中的定位键。 */
fun LiveChannel.key(): LiveChannelKey = LiveChannelKey(groupName = groupName, channelName = name)
