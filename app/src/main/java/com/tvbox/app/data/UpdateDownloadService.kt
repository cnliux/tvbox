package com.tvbox.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.tvbox.app.MainActivity
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.Timeouts
import com.tvbox.app.util.logSafeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class AppDownloadStatus(
    val received: Long = 0,
    val total: Long = 0,
    val complete: Boolean = false,
    val error: String? = null,
    /** 用户主动取消标记：cancel() 先置位，即使 startService 被后台启动限制拦截，
     * 轮询方也能感知并终止，避免「取消无效、下载继续」的体验。 */
    val cancelled: Boolean = false,
)

/** Fallback for devices whose ROM has removed or disabled DownloadProvider. */
class UpdateDownloadService : Service() {
    // 协程异常统一进 CrashHandler.coroutineExceptionHandler（记录日志，不杀死进程）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashHandler.coroutineExceptionHandler)
    private var job: Job? = null
    @Volatile
    private var activePath: String? = null
    // onStartCommand（主线程写）与 runDownload 的 finally（协程内读）跨上下文访问，标 @Volatile
    // 保证 stopSelfResult 判定读到最新值，避免下载服务提前/延迟停止。
    @Volatile
    private var activeStartId = 0
    // APK 下载是流式长请求：连接/读两段取 T1-09 的 OTA 档（[Timeouts.OTA]，connect 5s + read 15s 无进度），
    // 整次调用上限仍放宽到 10 分钟，避免大包下载被 20s 的 call 预算截断（T1-10 起断点续传由本服务按 Range 负责）。
    private val client = HttpClients.default.newBuilder()
        .connectTimeout(Timeouts.OTA.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(Timeouts.OTA.readMs, TimeUnit.MILLISECONDS)
        .callTimeout(OTA_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // 用户在应用内取消下载：停掉进行中的任务并结束前台服务；
            // 服务未在运行时该分支也是无害的空操作（启动后立即停止）。
            job?.cancel()
            activePath = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val path = intent?.getStringExtra("path")
        val url = intent?.getStringExtra("url")
        if (path == null || url == null) { stopSelf(startId); return START_NOT_STICKY }
        val size = intent.getLongExtra("size", 0)
        val sha = intent.getStringExtra("sha").orEmpty()
        // T1-10：清单 ETag（可空）。非空时用于断点续传门禁：服务端 ETag 与它不一致就弃 .part 重下。
        val etag = intent.getStringExtra("etag").orEmpty()
        if (activePath == path && job?.isActive == true) {
            // 重复启动同一 path：沿用进行中的任务，但新的 startId 仍要重新声明前台，
            // 否则系统会按前台服务超时处理；进度沿用 states 中的实时值。
            val existing = states[path]
            if (existing?.error != null || existing?.complete == true) {
                // 旧任务已结束但 finally 尚未清场（竞态）：按新任务重启，避免沿用陈旧状态。
                job?.cancel()
                activePath = path
                activeStartId = startId
                states[path] = AppDownloadStatus(total = size)
                if (!startForegroundSafely(path, startId)) return START_NOT_STICKY
                job = scope.launch { runDownload(url, path, size, sha, etag, startId) }
                return START_REDELIVER_INTENT
            }
            // 进行中：更新 total（服务端 contentLength 可能比清单 size 更准）并重新声明前台。
            // existing 为 null 视为「无有效进度」，与 total<=0 同样按 size 重置；else 分支里
            // 编译器已能推断 existing 非空，直接做智能转换，避免恒真判空告警。
            if (existing == null || existing.total <= 0L) {
                states[path] = AppDownloadStatus(total = size)
            } else if (size > existing.total) {
                states[path] = existing.copy(total = size)
            }
            if (!startForegroundSafely(path, startId)) return START_NOT_STICKY
            return START_REDELIVER_INTENT
        }
        job?.cancel()
        activePath = path
        activeStartId = startId
        states[path] = AppDownloadStatus(total = size)
        if (!startForegroundSafely(path, startId)) return START_NOT_STICKY
        job = scope.launch { runDownload(url, path, size, sha, etag, startId) }
        return START_REDELIVER_INTENT
    }

    /**
     * 应用内下载主流程：流式写入 .part，校验后改名；结束与错误均写回 states。
     *
     * T1-10 断点续传：
     * - 下载前 stat 已落盘 `.part`：长度 >0 时加 `Range: bytes=<len>-` 续传；
     * - 服务端 206（Partial Content）→ 追加写入；200 → 从头写（服务端忽略 Range 或无断点）；
     * - ETag 门禁：清单 ETag（[etag]）非空且与响应 ETag 不一致 → 弃断点从头重下；
     * - 416（Range 不可满足）→ 断点已失效，弃 .part 重下一次；
     * - 完成后仍走 [verifyUpdateApk] 的 SHA-256 全量校验（失败关闭）。
     */
    private suspend fun runDownload(url: String, path: String, size: Long, sha: String, etag: String, startId: Int) {
        try {
            CrashHandler.recordOperation("ota.download.start", "size=$size sha=${sha.take(8)} etag=${etag.take(12)}")
            val target = File(path)
            if (!target.isFile || runCatching { verifyUpdateApk(target, size, sha) }.isFailure) {
                val partial = File("$path.part")
                val directory = target.parentFile ?: throw IOException("下载目录不可用")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建下载目录")
                // #312 存储检查：APK 下载前确认目标目录可用空间（保留 2 倍余量）。
                val usable = directory.usableSpace
                if (size > 0 && usable < size * 2) {
                    throw IOException("存储空间不足，需至少 ${(size / 1024 / 1024) + 1}MB 可用")
                }
                // T1-10：已落盘的 .part 作为断点；服务端 416 或 ETag 变化时弃断点、重下一次。
                var offset = if (partial.isFile) partial.length() else 0L
                var rangeRetried = false
                while (true) {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "TVBox-Android")
                        .apply { if (offset > 0L) addHeader("Range", "bytes=$offset-") }
                        .build()
                    val call = client.newCall(request)
                    val cancellation = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
                    var restartFresh = false
                    try {
                        call.execute().use { response ->
                            when (response.code) {
                                206 -> {
                                    // ETag 门禁：清单有 ETag 且与响应不一致 → 包已变更，弃断点。
                                    if (offset > 0L && etagChanged(etag, response.header("ETag"))) {
                                        restartFresh = true
                                    } else {
                                        // 服务端 Range 起点必须与本地 .part 对得上；起点为 0（服务端未真正
                                        // 续传）时按全新下载截断重写，起点错位时弃断点重请求一次。
                                        val rangeStart = contentRangeStart(response.header("Content-Range"))
                                        if (offset > 0L && rangeStart != null && rangeStart != offset) {
                                            restartFresh = true
                                        } else if (rangeStart == 0L && offset > 0L) {
                                            writeDownloadStream(response, partial, path,
                                                receivedBase = 0L, manifestSize = size, append = false)
                                            // 服务端虽回 206 但起点为 0（未真正续传）：已从头重写 .part，
                                            // 同步 offset=0，避免后续 restartFresh 判定读到旧 offset 又重复从头。
                                            offset = 0L
                                        } else {
                                            writeDownloadStream(response, partial, path,
                                                receivedBase = offset, manifestSize = size, append = true)
                                        }
                                    }
                                }
                                200 -> {
                                    // 服务端忽略 Range（或未发 Range）：从头写入，旧 .part 被截断。
                                    writeDownloadStream(response, partial, path,
                                        receivedBase = 0L, manifestSize = size, append = false)
                                }
                                416 -> {
                                    // Range Not Satisfiable：断点失效（文件变更或 .part 已完整/损坏），弃断点。
                                    restartFresh = true
                                }
                                else -> throw IOException("更新下载失败：HTTP ${response.code}")
                            }
                        }
                    } finally {
                        cancellation?.dispose()
                    }
                    if (!restartFresh) break
                    // 弃断点：删 .part、清 offset，最多重下一次（第二次仍 416/ETag 不一致则失败上抛）。
                    runCatching { partial.delete() }
                    offset = 0L
                    if (rangeRetried) throw IOException("更新包断点续传失败，请稍后重试")
                    rangeRetried = true
                }
                verifyUpdateApk(partial, size, sha)
                if (!partial.renameTo(target)) throw IOException("无法保存已下载的安装包")
            }
            states[path] = AppDownloadStatus(target.length(), target.length(), complete = true)
            Log.i("TVBoxUpdate", "Fallback download completed and verified")
        } catch (cancelled: CancellationException) {
            if (states[path]?.error == null) states[path] = AppDownloadStatus(error = "下载已中断，请重试")
            throw cancelled
        } catch (error: Exception) {
            Log.e("TVBoxUpdate", "Fallback download failed: ${logSafeText(error.message)} (${error::class.simpleName})")
            states[path] = AppDownloadStatus(error = error.message ?: "更新下载失败，请重试")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (activeStartId == startId) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * 流式写入 .part 并把进度写回 [states]（suspend：逐块写入前检查协程取消）。
     * @param receivedBase 断点续传时 206 响应的起始偏移（进度从它继续累计）；全新下载为 0。
     * @param append true 时追加写（206），false 时从头写并截断旧 .part（200）。
     * @param manifestSize 清单声明的完整包大小，用于 200（无 Range 语义）与 Content-Range 缺 total 时兜底。
     */
    private suspend fun writeDownloadStream(
        response: Response,
        partial: File,
        path: String,
        receivedBase: Long,
        manifestSize: Long,
        append: Boolean,
    ) {
        // 206 的 total 优先取 Content-Range 的 `bytes x-y/TOTAL`；TOTAL 缺失时退化为清单 size。
        val total = if (append) {
            (contentRangeTotal(response.header("Content-Range")) ?: manifestSize).coerceAtLeast(receivedBase)
        } else {
            response.body.contentLength().takeIf { it > 0 } ?: manifestSize
        }
        var received = receivedBase
        var notifiedAt = 0L
        response.body.byteStream().use { input ->
            // append=true（206）追加写；append=false（200）从头写并截断旧 .part。
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    states[path] = AppDownloadStatus(received, total)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - notifiedAt >= 1_000) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(received, total))
                        notifiedAt = now
                    }
                }
            }
        }
    }

    /** Content-Range 的完整长度：`bytes 5-9/15` → 15；total 为通配星号或非法值 → null。 */
    private fun contentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.takeIf { it.isNotEmpty() && it != "*" }?.toLongOrNull()

    /** Content-Range 的起始偏移：`bytes 5-9/15` → 5；无 Range 语义（200）或非法值 → null。 */
    private fun contentRangeStart(header: String?): Long? =
        header?.substringAfter("bytes ", " ")?.substringBefore('-', "")?.trim()?.toLongOrNull()

    /**
     * T1-10 ETag 门禁（纯逻辑，便于对照单测）：清单 ETag 非空、响应带 ETag 且归一化后不一致才算「变化」。
     * 任一为空都视为可比对成功（老清单无 ETag / 服务端不回 ETag 时保持纯 Range 续传语义）。
     */
    private fun etagChanged(manifestEtag: String, responseEtag: String?): Boolean {
        if (manifestEtag.isBlank() || responseEtag.isNullOrBlank()) return false
        return normalizeEtag(manifestEtag) != normalizeEtag(responseEtag)
    }

    private fun normalizeEtag(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("W/", 0, ignoreCase = true)) value = value.substring(2).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.lastIndex)
        return value.lowercase()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activePath?.let { states[it] = AppDownloadStatus(error = "后台下载超时，请回到应用重试") }
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** 创建通知渠道并声明前台服务；失败时写入错误状态并停止自身。 */
    private fun startForegroundSafely(path: String, startId: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "应用更新下载", NotificationManager.IMPORTANCE_LOW))
            }
            val status = states[path] ?: AppDownloadStatus()
            val notification = notification(status.received, status.total)
            startForeground(NOTIFICATION_ID, notification)
            true
        } catch (error: Exception) {
            Log.e("TVBoxUpdate", "Unable to start foreground download", error)
            states[path] = AppDownloadStatus(error = "无法启动后台下载，请回到应用后重试")
            stopSelf(startId)
            false
        }
    }

    private fun notification(received: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
        return Notification.Builder(this, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL else null)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TVBox 更新下载").setContentText(if (total > 0) "$progress%" else "正在下载")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, total <= 0).build()
    }

    companion object {
        private const val CHANNEL = "tvbox_update_download"
        private const val NOTIFICATION_ID = 10311
        private const val ACTION_CANCEL = "com.tvbox.app.action.CANCEL_UPDATE_DOWNLOAD"
        /** OTA 整次调用上限：10 分钟（只约束整包下载总时长，connect/read 两段走 [Timeouts.OTA]）。 */
        private const val OTA_CALL_TIMEOUT_MS = 10L * 60L * 1000L
        private val states = ConcurrentHashMap<String, AppDownloadStatus>()
        internal fun status(path: String): AppDownloadStatus? = states[path]
        internal fun start(context: Context, url: String, path: String, size: Long, sha: String, etag: String = "") {
            // 只保留本次下载的状态，避免 states 随历史版本无界增长。
            states.keys.removeAll { it != path }
            // 同一 path 仍在下载时保留实时进度；已完成/失败的旧状态用新任务覆盖。
            val current = states[path]
            if (current == null || current.complete || current.error != null) {
                states[path] = AppDownloadStatus(total = size)
            }
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)
                .putExtra("url", url).putExtra("path", path).putExtra("size", size).putExtra("sha", sha)
                .putExtra("etag", etag))
        }

        /** 请求停止当前更新下载；服务未运行或后台启动受限时静默忽略。 */
        internal fun cancel(context: Context) {
            // 先置取消标记：即使 startService 被 Android 12+ 后台启动限制拦截，
            // downloadInApp 的轮询也能感知并终止，避免「取消无效」。
            states.keys.firstOrNull()?.let { path ->
                states[path]?.let { states[path] = it.copy(cancelled = true) }
            }
            runCatching {
                context.startService(Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }
    }
}
