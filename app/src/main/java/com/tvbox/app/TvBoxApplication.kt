package com.tvbox.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.tvbox.app.crash.AnrWatchdog
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.domain.GlobalPlayState
import com.tvbox.app.domain.MemoryGovernor
import com.tvbox.app.domain.TvBoxApplicationRef
import com.tvbox.app.ui.components.trimTvImageMemoryCache

/**
 * 应用入口：尽早安装全局崩溃捕获，覆盖 Application 初始化到首个 Activity 之间的崩溃。
 * CrashHandler 只做本地落盘，零外部依赖，不影响主流程。
 * T3-24：同时初始化健康页采样所需的全局状态。
 */
class TvBoxApplication : Application() {

    /** 60s 窗口内的总长帧数；由三个 Compose 根节点按需汇出并清零。 */
    val frameTracker = com.tvbox.app.domain.ChoreographerFrameTracker()

    /** 按 MemoryGovernor.tierFromTrimLevel 维护当前内存档位（TIGHT / CRITICAL 计数）；仅用于健康页汇总。 */
    var memoryTier: MemoryGovernor.Tier = MemoryGovernor.Tier.RICH
        private set
    var throttleCount: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        // T3-24：持有应用上下文引用，供 GlobalPlayState 懒计算版本号使用。
        TvBoxApplicationRef.context = this  // 只存 Application 自身，杜绝 Activity 泄漏
        CrashHandler.install(this)
        AnrWatchdog.start()
        CrashHandler.recordOperation("app.onCreate", "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        // T3-24：启动基于 Choreographer 的 jank 采样；所有可交互页面注册后即可累计长帧数。
        frameTracker.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // #118：低内存时清空图片内存缓存，尽快释放内存，避免 TV 盒子 OOM。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { trimTvImageMemoryCache() }
            // S5-31：电源类资源总闸——投屏 MulticastLock / WakeLock 即便某处漏 release，
            // 熄屏退回后台时也会被强制释放，避免盒子待机发热/费电。
            com.tvbox.app.util.PowerResourceRegistry.releaseAll()
        }
        // T3-24：记录当前内存档位与限流次数，供健康页 Snapshot 汇总展示。
        val tier = MemoryGovernor.tierFromTrimLevel(level)
        if (tier != null) {
            memoryTier = tier
            if (tier == MemoryGovernor.Tier.TIGHT || tier == MemoryGovernor.Tier.CRITICAL) {
                throttleCount++
            }
        }
    }
}
