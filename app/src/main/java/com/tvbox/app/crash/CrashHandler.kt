package com.tvbox.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.tvbox.app.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 全局 UncaughtExceptionHandler + 落盘日志。
 *
 * 设计取舍（Crashlytics vs 本地落盘）：
 *   - Crashlytics 需要 Google Services 插件 + Firebase 项目配置，与本项目的
 *     轻体积/无网配要求冲突，且 Release 包会引入额外依赖。
 *   - 本项目选择本地落盘（crash_logs/ 目录）+ 下次启动时通过 HomeNotice 提示用户
 *     发送日志，兼顾"零依赖"与"可排查"。
 *   - 若后续接入 CI 后端，只需替换 CrashLogWriter 的实现即可。
 */
object CrashHandler {

    private const val TAG = "TVBoxCrash"
    private const val LOG_DIR_NAME = "crash_logs"
    private const val MAX_LOG_FILES = 10
    private const val MAX_LOG_FILE_AGE_MS = 7L * 24 * 60 * 60 * 1000L // 7 天

    private lateinit var appContext: Context
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "crash-log-writer").apply { isDaemon = true }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== Uncaught exception on ${thread.name} ===", throwable)
            writeCrashLog(thread, throwable)
            // 交给系统默认处理器（会弹 ANR/强制关闭），或 null 则直接退出
            defaultHandler?.uncaughtException(thread, throwable)
        }
        purgeStaleLogs()
        Log.i(TAG, "CrashHandler installed, version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }

    /**
     * 落盘：文件名含时间戳，便于排序与清理。
     * 内容包含版本、设备、ABI、页面快照（若可获取）。
     */
    fun writeCrashLog(thread: Thread, throwable: Throwable) {
        writerExecutor.execute {
            try {
                val dir = File(appContext.externalCacheDir, LOG_DIR_NAME)
                if (!dir.exists()) dir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
                val file = File(dir, "crash_${timestamp}_${thread.name}.txt")

                FileWriter(file).use { fw ->
                    fw.write("=== TVBox Crash Report ===\n")
                    fw.write("versionCode  = ${BuildConfig.VERSION_CODE}\n")
                    fw.write("versionName  = ${BuildConfig.VERSION_NAME}\n")
                    fw.write("timestamp    = $timestamp\n")
                    fw.write("device       = ${Build.DEVICE}\n")
                    fw.write("model        = ${Build.MODEL}\n")
                    fw.write("manufacturer = ${Build.MANUFACTURER}\n")
                    fw.write("brand        = ${Build.BRAND}\n")
                    fw.write("sdk          = ${Build.VERSION.SDK_INT}\n")
                    fw.write("abi          = ${Build.SUPPORTED_ABIS.joinToString(",")}\n")
                    fw.write("thread       = ${thread.name} (id=${thread.threadId})\n")
                    fw.write("\n--- Stack Trace ---\n")
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    fw.write(sw.toString())
                }

                Log.i(TAG, "Crash log written: ${file.absolutePath}")
                purgeStaleLogs()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }
        }
    }

    /** 返回未读崩溃日志文件列表（按时间倒序），供 UI 展示"是否发送日志"入口。 */
    fun getPendingCrashLogs(): List<File> {
        val dir = File(appContext.externalCacheDir, LOG_DIR_NAME)
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.filter { System.currentTimeMillis() - it.lastModified() < MAX_LOG_FILE_AGE_MS }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_LOG_FILES)
            ?: emptyList()
    }

    /** 清除所有已读崩溃日志。 */
    fun clearCrashLogs() {
        val dir = File(appContext.externalCacheDir, LOG_DIR_NAME)
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }?.forEach { it.delete() }
    }

    private fun purgeStaleLogs() {
        val dir = File(appContext.externalCacheDir, LOG_DIR_NAME)
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("crash_") }
            ?.filter { file -> System.currentTimeMillis() - file.lastModified() < MAX_LOG_FILE_AGE_MS }
            ?.sortedBy { file -> file.lastModified() } ?: return
        val toDelete = files.dropLast(MAX_LOG_FILES.coerceAtLeast(files.size))
        toDelete.forEach { it.delete() }
    }

    fun shutdown() {
        writerExecutor.shutdown()
        try { writerExecutor.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }
}
