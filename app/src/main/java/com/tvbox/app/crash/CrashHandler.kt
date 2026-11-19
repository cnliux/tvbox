package com.tvbox.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.tvbox.app.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
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

    /**
     * logcat 与文件写入共用同一把锁：崩溃线程、日志写入线程和 UI 清理线程
     * 可能同时进来，必须串行化，否则会出现交叉内容/并发删改同一文件。
     */
    private val writeLock = Any()

    private data class Redaction(val pattern: Regex, val replacement: String)

    /**
     * 脱敏规则只替换"值"、保留字段名，便于排查又不会把凭据/个人信息写进日志。
     * 覆盖：Authorization/Bearer、token/密钥/口令、签名 query、房间号字段与手机号。
     */
    private val redactions = listOf(
        Redaction(
            Regex("""(?i)(authorization\s*[:=]\s*)(bearer\s+)?[^\s,;"'}\]]+"""),
            "${'$'}1${'$'}2[REDACTED]",
        ),
        Redaction(
            Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-+/=]+"""),
            "${'$'}1[REDACTED]",
        ),
        Redaction(
            Regex(
                """(?i)((?:^|[?&\s])(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|""" +
                    """api[_-]?key|apikey|secret|client[_-]?secret|password|passwd|pwd|signature|""" +
                    """sign|x-amz-signature|x-goog-signature|session|cookie|auth)\s*=\s*)""" +
                    """[^\s&"'#,;)}\]]+""",
            ),
            "${'$'}1[REDACTED]",
        ),
        Redaction(
            Regex(
                """(?i)("(?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|apikey|secret|""" +
                    """password|passwd|pwd|signature|sign|cookie|authorization)"\s*:\s*")[^"]*(")""",
            ),
            "${'$'}1[REDACTED]${'$'}2",
        ),
        Redaction(
            Regex("""(?i)((?:roomid|rid|room|gid|uid|anchorid)@=)[^/\s]+"""),
            "${'$'}1[REDACTED]",
        ),
        Redaction(
            Regex("""(?i)((?:room(?:_?id)?|roomid|anchor(?:_?id)?|uid)\s*[:=]\s*)["']?[0-9A-Za-z_-]+"""),
            "${'$'}1[REDACTED]",
        ),
        Redaction(Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""), "[PHONE_REDACTED]"),
    )

    fun install(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 完整堆栈保留在日志里，但先脱敏再输出，避免 logcat 直接泄漏 token/URL。
            logError("=== Uncaught exception on ${thread.name} ===", throwable)
            writeCrashLog(thread, throwable)
            // 交给系统默认处理器（会弹 ANR/强制关闭），或 null 则直接退出
            defaultHandler?.uncaughtException(thread, throwable)
        }
        purgeStaleLogs()
        logInfo("CrashHandler installed, version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }

    /** 写 logcat 前统一脱敏并加锁，返回完整的（脱敏后）堆栈文本。 */
    private fun stackTraceOf(throwable: Throwable): String = StringWriter().use { sw ->
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        sw.toString()
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        val text = if (throwable == null) message else "$message\n${stackTraceOf(throwable)}"
        synchronized(writeLock) { Log.e(TAG, redact(text)) }
    }

    private fun logInfo(message: String) {
        synchronized(writeLock) { Log.i(TAG, redact(message)) }
    }

    private fun redact(text: String): String = redactions.fold(text) { acc, rule ->
        rule.pattern.replace(acc, rule.replacement)
    }

    /**
     * 落盘：文件名含时间戳，便于排序与清理。
     * 内容包含版本、设备、ABI、页面快照（若可获取）。
     */
    /** externalCacheDir 在部分设备/存储状态下可能为 null，统一回退到内部 cacheDir。 */
    private fun logDir(): File? =
        if (::appContext.isInitialized) {
            File(appContext.externalCacheDir ?: appContext.cacheDir, LOG_DIR_NAME)
        } else {
            null
        }

    fun writeCrashLog(thread: Thread, throwable: Throwable) {
        writerExecutor.execute {
            // 文件写入本身也在锁内完成，和 install()/clearCrashLogs() 的清理互不干扰。
            val path = try {
                synchronized(writeLock) { writeCrashReportLocked(thread, throwable) }
            } catch (e: Exception) {
                logError("Failed to write crash log", e)
                null
            }
            if (path != null) logInfo("Crash log written: $path")
            purgeStaleLogs()
        }
    }

    /** 调用方必须已持有 [writeLock]。 */
    private fun writeCrashReportLocked(thread: Thread, throwable: Throwable): String? {
        val dir = logDir() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        // 线程名可能带房间号/路径分隔符，做白名单化后再当文件名。
        val safeThread = thread.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "thread" }
        val file = File(dir, "crash_${timestamp}_$safeThread.txt")

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
            // threadId 需 JDK19+，CI 是 JDK17，继续用 id 并抑制弃用警告。
            @Suppress("DEPRECATION")
            fw.write("thread       = ${redact(thread.name)} (id=${thread.id})\n")
            fw.write("\n--- Stack Trace ---\n")
            // 保留完整堆栈；只把命中规则的值替换为 [REDACTED]。
            fw.write(redact(stackTraceOf(throwable)))
        }
        return file.absolutePath
    }

    /** 返回未读崩溃日志文件列表（按时间倒序），供 UI 展示"是否发送日志"入口。 */
    fun getPendingCrashLogs(): List<File> = synchronized(writeLock) {
        val dir = logDir() ?: return@synchronized emptyList()
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.filter { System.currentTimeMillis() - it.lastModified() < MAX_LOG_FILE_AGE_MS }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_LOG_FILES)
            ?: emptyList()
    }

    /** 清除所有已读崩溃日志。 */
    fun clearCrashLogs() {
        synchronized(writeLock) {
            val dir = logDir() ?: return@synchronized
            dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }?.forEach { it.delete() }
        }
    }

    private fun purgeStaleLogs() {
        synchronized(writeLock) { purgeStaleLogsLocked() }
    }

    /** 调用方必须已持有 [writeLock]。 */
    private fun purgeStaleLogsLocked() {
        val dir = logDir() ?: return
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("crash_") }
            ?.sortedBy { file -> file.lastModified() }
            ?: return
        val now = System.currentTimeMillis()
        // 先删除超过保留期的旧日志，再按数量只保留最新的 MAX_LOG_FILES 个。
        files.filter { file -> now - file.lastModified() > MAX_LOG_FILE_AGE_MS }
            .forEach { file -> file.delete() }
        files.take((files.size - MAX_LOG_FILES).coerceAtLeast(0))
            .forEach { file -> file.delete() }
    }

    fun shutdown() {
        writerExecutor.shutdown()
        try { writerExecutor.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }
}
