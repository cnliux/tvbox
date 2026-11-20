package com.tvbox.app.ui

import android.util.Log

/**
 * 播放器/直播代理详细日志的统一切口。
 *
 * 生产环境默认关闭（[AppSettings.playerLogEnabled]=false），避免每帧/每次状态变化的
 * 详细日志刷屏；调试或排查问题时可在设置中开启，日志仍按原 TAG 输出。
 *
 * 开关由 [TvBoxViewModel] 在设置加载与 [TvBoxViewModel.updatePlayerLogEnabled] 时同步，
 * 不依赖 Compose 重组，供非 UI 层（播放器、Go 代理回调）直接读取。
 */
object PlayerLog {
    @Volatile
    var enabled: Boolean = false

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (enabled) Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) Log.e(tag, message, throwable)
    }
}
