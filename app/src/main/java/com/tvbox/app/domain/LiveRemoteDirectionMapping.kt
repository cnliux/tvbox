package com.tvbox.app.domain

/**
 * 直播遥控方向键语义。
 *
 * 默认 [UP_DOWN_CHANNEL]：按需求用「上下换台 / 左右切源」；
 * [LEFT_RIGHT_CHANNEL]：切回项目原有的「左右切台 / 上下换线」。
 *
 * 该枚举原本定义在 `ui/LiveRemoteKeyRouter.kt`，因 [AppSettings] 位于 domain 层，
 * 为避免 domain 依赖 ui，迁移到本文件；`ui` 层改为 import。
 */
enum class LiveRemoteDirectionMapping {
    /** 上下键换台，左右键切源（线路）。 */
    UP_DOWN_CHANNEL,

    /** 左右键换台，上下键切源（线路）。 */
    LEFT_RIGHT_CHANNEL,
    ;

    companion object {
        /** 默认值，旧数据缺失或非法时回退到此。 */
        val Default = UP_DOWN_CHANNEL

        /** 从存储字符串解析；非法值回退默认，兼容老版本数据。 */
        fun fromStorageKey(value: String?): LiveRemoteDirectionMapping =
            entries.firstOrNull { it.name == value } ?: Default
    }
}
