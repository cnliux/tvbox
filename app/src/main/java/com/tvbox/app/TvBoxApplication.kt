package com.tvbox.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.tvbox.app.crash.AnrWatchdog
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.data.SharedDoubanHotCache
import com.tvbox.app.data.DefaultAppUpdateRepository
import com.tvbox.app.domain.MemoryGovernor
import com.tvbox.app.domain.PrefsRegistry
import com.tvbox.app.domain.StartupPreheatPolicy
import com.tvbox.app.domain.TvBoxApplicationRef
import com.tvbox.app.ui.components.trimTvImageMemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：尽早安装全局崩溃捕获，覆盖 Application 初始化到首个 Activity 之间的崩溃。
 * CrashHandler 只做本地落盘，零外部依赖，不影响主流程。
 * T3-24：同时初始化健康页采样所需的全局状态。
 * S5-30：onCreate 尾部在 Dispatchers.IO 协程预热启动路径上三个 `by lazy` 读盘仓库
 * （设置迁移标记 / 豆瓣热榜缓存 / 更新仓库 prefs），首个访问者（ViewModel 工厂 →
 * 首帧后 ~200ms）触发 lazy 时直接拿到已预热实例，启动路径不再同步等待读盘。
 */
class TvBoxApplication : Application() {

    /** 60s 窗口内的总长帧数；由三个 Compose 根节点按需汇出并清零。 */
    val frameTracker = com.tvbox.app.domain.ChoreographerFrameTracker()

    /** 按 MemoryGovernor.tierFromTrimLevel 维护当前内存档位（TIGHT / CRITICAL 计数）；仅用于健康页汇总。 */
    var memoryTier: MemoryGovernor.Tier = MemoryGovernor.Tier.RICH
        private set
    var throttleCount: Int = 0
        private set

    /** S5-30：IO 预热协程域。进程生命周期内不复用给 UI；SupervisorJob 保证单目标失败不拖垮其余目标。 */
    private val preheatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // T3-24：持有应用上下文引用，供 GlobalPlayState 懒计算版本号使用。
        TvBoxApplicationRef.context = this  // 只存 Application 自身，杜绝 Activity 泄漏
        CrashHandler.install(this)
        AnrWatchdog.start()
        CrashHandler.recordOperation("app.onCreate", "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        // T3-24：启动基于 Choreographer 的 jank 采样；所有可交互页面注册后即可累计长帧数。
        frameTracker.start()
        // S5-30：启动路径同步等待消除——IO 协程预热 lazy 读盘仓库（目标由 StartupPreheatPolicy 决策）。
        launchStartupPreheat()
    }

    /**
     * S5-30：按 [StartupPreheatPolicy] 决策的预热目标，在 [preheatScope]（Dispatchers.IO）上
     * 依次触发三个仓库的读盘预热：
     * - prefsRegistry：[PrefsRegistry.warmUp] 打开「app_settings_migration」（getSettings 每次都读的迁移标记）；
     * - doubanHot：[SharedDoubanHotCache.warmUp] 置入进程级已预热 prefs 实例；
     * - updateRepo：[DefaultAppUpdateRepository.warmUp] 置入后台更新 / OTA CDN 两个 prefs 实例。
     *
     * 各目标独立 try/catch：预热是纯优化，任一失败回落 lazy 消费者的按需读盘路径，绝不向启动主流程传播异常。
     */
    private fun launchStartupPreheat() {
        val targets = StartupPreheatPolicy.shouldPreheat(isTv = isTelevisionDevice(), isLowMemory = isLowRamDevice())
        preheatScope.launch {
            if (StartupPreheatPolicy.PREFS in targets) {
                try {
                    PrefsRegistry.warmUp(this@TvBoxApplication, listOf("app_settings_migration"))
                } catch (t: Throwable) {
                    com.tvbox.app.util.TLog.w("TVBoxStartupPreheat") { "prefs 预热失败（回落按需读盘）：${t.message}" }
                }
            }
            if (StartupPreheatPolicy.DOUBAN_HOT in targets) {
                try {
                    SharedDoubanHotCache.warmUp(this@TvBoxApplication)
                } catch (t: Throwable) {
                    com.tvbox.app.util.TLog.w("TVBoxStartupPreheat") { "doubanHot 预热失败（回落按需读盘）：${t.message}" }
                }
            }
            if (StartupPreheatPolicy.UPDATE_REPO in targets) {
                try {
                    DefaultAppUpdateRepository.warmUp(this@TvBoxApplication)
                } catch (t: Throwable) {
                    com.tvbox.app.util.TLog.w("TVBoxStartupPreheat") { "updateRepo 预热失败（回落按需读盘）：${t.message}" }
                }
            }
        }
    }

    /** S5-30：电视形态判定（Configuration UI_MODE）；读取失败按手机降级，少预热不冒险。 */
    private fun isTelevisionDevice(): Boolean = runCatching {
        val uiMode = resources.configuration.uiMode
        uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    }.getOrDefault(false)

    /** S5-30：系统低内存设备判定；读取失败按低内存处理（只预热最关键的设置 prefs）。 */
    private fun isLowRamDevice(): Boolean = runCatching {
        (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false
    }.getOrDefault(true)

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
