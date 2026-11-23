package com.tvbox.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.tvbox.app.crash.AnrWatchdog
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.ui.components.trimTvImageMemoryCache

/**
 * 应用入口：尽早安装全局崩溃捕获，覆盖 Application 初始化到首个 Activity 之间的崩溃。
 * CrashHandler 只做本地落盘，零外部依赖，不影响主流程。
 */
class TvBoxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        AnrWatchdog.start()
        CrashHandler.recordOperation("app.onCreate", "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // #118：低内存时清空图片内存缓存，尽快释放内存，避免 TV 盒子 OOM。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { trimTvImageMemoryCache() }
        }
    }
}
