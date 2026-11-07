package com.tvbox.app.domain

/**
 * 按内容记忆的画面比例。
 *
 * 优先取该内容上次手动选择的模式；没有记录时回退设置里的全局默认值 [AppSettings.videoScaleMode]。
 * 全局默认值变化不会清空已单独记忆的条目——用户在某部影片/频道里主动切过，就继续沿用那次选择。
 */
fun AppSettings.resolveVideoScaleMode(contentKey: String?): VideoScaleMode {
    val stored = contentKey?.let { contentVideoScaleModes[it] }
    return VideoScaleMode.entries.firstOrNull { it.storageKey == stored } ?: videoScaleMode
}

/** 把某个内容的画面比例写进覆盖表；纯函数，便于单测锁定"全局默认与单条覆盖不互相覆盖"。 */
fun AppSettings.withContentVideoScaleMode(contentKey: String?, mode: VideoScaleMode): AppSettings {
    if (contentKey.isNullOrBlank()) return copy(videoScaleMode = mode)
    return copy(contentVideoScaleModes = contentVideoScaleModes + (contentKey to mode.storageKey))
}

/** 点播：按影片标识 + 集记忆（换源不改变影片标识，换集则各自记忆）。 */
fun vodVideoScaleKey(apiLineId: String, movieId: String, episodeIndex: Int): String =
    "vod|$apiLineId|$movieId|$episodeIndex"

/** 电视/手机直播：按频道记忆（同频道换线路仍沿用同一选择）。 */
fun liveVideoScaleKey(groupName: String, channelName: String): String =
    "live|$groupName|$channelName"

/** 平台直播：按站点 + 房间记忆。 */
fun roomVideoScaleKey(site: String, roomId: String): String = "room|$site|$roomId"
