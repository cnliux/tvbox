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
import com.tvbox.app.data.network.HttpClients
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
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class AppDownloadStatus(
    val received: Long = 0,
    val total: Long = 0,
    val complete: Boolean = false,
    val error: String? = null,
)

/** Fallback for devices whose ROM has removed or disabled DownloadProvider. */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var activePath: String? = null
    private var activeStartId = 0
    // APK 下载是流式长请求，沿用统一客户端但放宽整次调用上限，避免大包被默认 60s 截断。
    private val client = HttpClients.default.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
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
        if (activePath == path && job?.isActive == true) {
            // 重复启动同一 path：沿用进行中的任务，但新的 startId 仍要重新声明前台，
            // 否则系统会按前台服务超时处理；进度沿用 states 中的实时值。
            if (states[path]?.total?.let { it > 0 } != true) {
                states[path] = AppDownloadStatus(total = size)
            }
            if (!startForegroundSafely(path, startId)) return START_NOT_STICKY
            return START_REDELIVER_INTENT
        }
        job?.cancel()
        activePath = path
        activeStartId = startId
        states[path] = AppDownloadStatus(total = size)
        if (!startForegroundSafely(path, startId)) return START_NOT_STICKY
        job = scope.launch {
            try {
                val target = File(path)
                if (!target.isFile || runCatching { verifyUpdateApk(target, size, sha) }.isFailure) {
                    val partial = File("$path.part")
                    val directory = target.parentFile ?: throw IOException("下载目录不可用")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建下载目录")
                    val request = Request.Builder().url(url).header("User-Agent", "TVBox-Android").build()
                    val call = client.newCall(request)
                    val cancellation = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("更新下载失败：HTTP ${response.code}")
                            val total = response.body.contentLength().takeIf { it > 0 } ?: size
                            var received = 0L
                            var notifiedAt = 0L
                            response.body.byteStream().use { input ->
                                partial.outputStream().use { output ->
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
                    } finally { cancellation?.dispose() }
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
        return START_REDELIVER_INTENT
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
            startForeground(NOTIFICATION_ID, notification(status.received, status.total))
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
        private val states = ConcurrentHashMap<String, AppDownloadStatus>()
        internal fun status(path: String): AppDownloadStatus? = states[path]
        internal fun start(context: Context, url: String, path: String, size: Long, sha: String) {
            // 只保留本次下载的状态，避免 states 随历史版本无界增长。
            states.keys.removeAll { it != path }
            // 同一 path 仍在下载时保留实时进度；已完成/失败的旧状态用新任务覆盖。
            val current = states[path]
            if (current == null || current.complete || current.error != null) {
                states[path] = AppDownloadStatus(total = size)
            }
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java)
                .putExtra("url", url).putExtra("path", path).putExtra("size", size).putExtra("sha", sha))
        }

        /** 请求停止当前更新下载；服务未运行或后台启动受限时静默忽略。 */
        internal fun cancel(context: Context) {
            runCatching {
                context.startService(Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }
    }
}
