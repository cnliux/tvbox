package com.tvbox.app

import android.app.Application
import com.tvbox.app.crash.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用入口：尽早安装全局崩溃捕获，覆盖 Application 初始化到首个 Activity 之间的崩溃。
 * CrashHandler 只做本地落盘，零外部依赖，不影响主流程。
 */
class TvBoxApplication : Application() {

    /**
     * 应用级协程作用域：所有挂在它上面的协程未捕获异常统一进 CrashHandler.coroutineExceptionHandler，
     * 记录到崩溃日志（不杀死进程）。应用生命周期内常驻。
     */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CrashHandler.coroutineExceptionHandler,
    )

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        CrashHandler.recordOperation("app.onCreate", "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }
}
