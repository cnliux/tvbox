package com.tvbox.app.domain

/** 单条节目（XMLTV programme 节点）。 */
data class EpgProgram(val title: String, val startMs: Long, val stopMs: Long) {
    /** 已播完的节目在支持时移的源上可回看；是否真的能播由源能力决定，这里只做时间判断。 */
    fun isReplayable(nowMs: Long): Boolean = stopMs in 1..nowMs

    /** 尚未开播的节目可预约提醒（入口展示用，实际提醒由 UI 层决定）。 */
    fun isUpcoming(nowMs: Long): Boolean = startMs > nowMs
}
