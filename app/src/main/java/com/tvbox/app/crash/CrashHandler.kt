package com.tvbox.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.tvbox.app.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler

/** SimpleDateFormat 非线程安全，崩溃写盘可能跨线程，按线程各持一份复用。 */
private val OPERATION_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
private val CRASH_FILE_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
/** 线程名白名单化（用作崩溃日志文件名）用的正则。 */
private val THREAD_NAME_SAFE_REGEX = Regex("[^A-Za-z0-9_-]")

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
    // #210：单文件大小上限，避免超长堆栈/大 recent operations 写满存储。
    private const val MAX_LOG_FILE_BYTES = 1024L * 1024L // 1 MB
    // 截断时保留的头/尾各 40KB：头部含版本/设备/堆栈起点，尾部含 recent operations。
    private const val TRUNCATE_KEEP_BYTES = 40L * 1024L
    // #327：同类崩溃聚类。分组计数单独落盘；文件名不能带 crash_ 前缀，
    // 否则会被 getPendingCrashLogs / purgeStaleLogs 当成崩溃日志清理或上报。
    private const val GROUP_FILE_NAME = "groups.tsv"
    private const val MAX_GROUP_ENTRIES = 64

    private lateinit var appContext: Context
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "crash-log-writer").apply { isDaemon = true }
    }

    /**
     * 全局协程异常处理器：协程内未捕获异常记录到崩溃日志（不杀死进程）。
     * 挂到长时间运行的 scope（播放器、下载、网络任务）时使用：
     *   CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashHandler.coroutineExceptionHandler)
     */
    val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logError("=== Uncaught coroutine exception ===", throwable)
        writeCrashLog(Thread.currentThread(), throwable)
    }

    /**
     * 最近操作上下文（环形缓冲）：崩溃报告中附带崩溃前最近的用户操作，
     * 便于定位"最后一步做了什么"（切集/切换线路/下载/安装等）。
     */
    private const val MAX_RECENT_OPERATIONS = 24
    private val recentOperations = ArrayDeque<String>()

    /** 记录一条最近操作；detail 为可选的补充信息（房间号/集数/路径等）。 */
    @Synchronized
    fun recordOperation(operation: String, detail: String? = null) {
        val text = if (detail.isNullOrBlank()) operation else "$operation | $detail"
        val ts = OPERATION_TIME_FORMAT.get()!!.format(System.currentTimeMillis())
        recentOperations.addLast("$ts  $text")
        while (recentOperations.size > MAX_RECENT_OPERATIONS) {
            recentOperations.removeFirst()
        }
    }

    /** 与 recordOperation 共用同一把 object 锁，保证环形缓冲读写互斥。 */
    @Synchronized
    private fun recentOperationsText(): String =
        if (recentOperations.isEmpty()) "(none)" else recentOperations.joinToString("\n")

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
        // 旧日志清理放到后台线程，避免 Application.onCreate 同步做目录扫描。
        writerExecutor.execute { purgeStaleLogs() }
        logInfo("CrashHandler installed, version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
    }

    /** 写 logcat 前统一脱敏并加锁，返回完整的（脱敏后）堆栈文本。 */
    private fun stackTraceOf(throwable: Throwable): String = StringWriter().use { sw ->
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        sw.toString()
    }

    /**
     * #327：同类崩溃聚类——取（已脱敏）堆栈首行（通常是「异常类型: 消息」）计算 SHA-256，
     * 取前 12 位十六进制作为 group hash。先脱敏再 hash，避免把 token / 手机号等敏感值
     * 的哈希写进分组文件；现有脱敏规则与轮转逻辑完全不变。
     */
    private fun stackGroupHash(redactedStackTrace: String): String {
        val firstLine = redactedStackTrace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(firstLine.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(12)
    }

    /**
     * #327：累加分组计数（每类崩溃一行：hash、次数、最近发生时间），供统计同类崩溃。
     * 调用方必须已持有 [writeLock]；单文件覆写是「读-改-写」全量替换，不会出现半行中间态。
     */
    private fun recordGroupCount(dir: File, groupHash: String) {
        val file = File(dir, GROUP_FILE_NAME)
        val counts = LinkedHashMap<String, Pair<Int, Long>>()
        runCatching {
            if (file.isFile) {
                file.readLines().forEach { line ->
                    val parts = line.split('\t')
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
                    val lastAt = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                    counts[parts[0].trim()] = count to lastAt
                }
            }
        }
        val previous = counts[groupHash]
        counts[groupHash] = ((previous?.first ?: 0) + 1) to System.currentTimeMillis()
        // 只保留最近活跃的若干分组，避免长期运行导致计数文件无限增长。
        val kept = counts.entries.sortedByDescending { it.value.second }.take(MAX_GROUP_ENTRIES)
        runCatching {
            FileWriter(file, false).use { writer ->
                kept.forEach { (hash, stat) -> writer.write("$hash\t${stat.first}\t${stat.second}\n") }
            }
        }
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

        val timestamp = CRASH_FILE_TIME_FORMAT.get()!!.format(System.currentTimeMillis())
        // 线程名可能带房间号/路径分隔符，做白名单化后再当文件名。
        val safeThread = thread.name.replace(THREAD_NAME_SAFE_REGEX, "_").take(40).ifBlank { "thread" }
        val file = File(dir, "crash_${timestamp}_$safeThread.txt")

        // #327：脱敏只做一次，结果同时用于日志正文与分组 hash；保持既有脱敏与轮转逻辑不变。
        val stackTrace = redact(stackTraceOf(throwable))
        val groupHash = stackGroupHash(stackTrace)
        recordGroupCount(dir, groupHash)

        val report = buildString {
            append("=== TVBox Crash Report ===\n")
            // #327：日志内标注 group hash，便于把同类崩溃手动归类统计。
            append("groupHash    = $groupHash\n")
            append("versionCode  = ${BuildConfig.VERSION_CODE}\n")
            append("versionName  = ${BuildConfig.VERSION_NAME}\n")
            append("timestamp    = $timestamp\n")
            append("device       = ${Build.DEVICE}\n")
            append("model        = ${Build.MODEL}\n")
            append("manufacturer = ${Build.MANUFACTURER}\n")
            append("brand        = ${Build.BRAND}\n")
            append("sdk          = ${Build.VERSION.SDK_INT}\n")
            append("abi          = ${Build.SUPPORTED_ABIS.joinToString(",")}\n")
            // threadId 需 JDK19+，CI 是 JDK17，继续用 id 并抑制弃用警告。
            @Suppress("DEPRECATION")
            append("thread       = ${redact(thread.name)} (id=${thread.id})\n")
            append("\n--- Stack Trace ---\n")
            // 保留完整堆栈；只把命中规则的值替换为 [REDACTED]。
            append(stackTrace)
            append("\n\n--- Recent Operations ---\n")
            append(redact(recentOperationsText()))
        }
        FileWriter(file).use { fw -> fw.write(truncateIfOversized(report)) }
        return file.absolutePath
    }

    /**
     * #210：日志超过 [MAX_LOG_FILE_BYTES] 时截断，保留头尾各 [TRUNCATE_KEEP_BYTES] 字节，
     * 中间插入截断标记。按字符（UTF-8 近似）裁剪，避免把中文字符截成半个导致乱码。
     */
    private fun truncateIfOversized(report: String): String {
        val bytes = report.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes <= MAX_LOG_FILE_BYTES) return report
        val keep = TRUNCATE_KEEP_BYTES.toInt()
        if (report.length <= keep * 2) return report.take(keep)
        val marker = "\n\n... [日志超过 ${MAX_LOG_FILE_BYTES / 1024}KB，中间内容已截断] ...\n\n"
        return report.take(keep) + marker + report.takeLast(keep)
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
        // 先删除超过保留期的旧日志，再在「存活文件」里按数量裁剪：
        // 已删除的过期文件不能占用保留名额，否则可能多删有效日志。
        val survivors = mutableListOf<File>()
        files.forEach { file ->
            if (now - file.lastModified() > MAX_LOG_FILE_AGE_MS) {
                file.delete()
            } else {
                survivors += file
            }
        }
        // files 与 survivors 均按 lastModified 升序，最旧的排在前面。
        survivors.take((survivors.size - MAX_LOG_FILES).coerceAtLeast(0))
            .forEach { file -> file.delete() }
    }

    fun shutdown() {
        writerExecutor.shutdown()
        try { writerExecutor.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }
}
