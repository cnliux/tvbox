package com.tvbox.app.data

import android.content.Context
import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.data.network.parseNetworkPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.AppUpdate
import com.tvbox.app.domain.CdnStatusRow
import com.tvbox.app.domain.UaScope
import com.tvbox.app.domain.OtaCdnAttempt
import com.tvbox.app.domain.buildCdnStatusRows
import com.tvbox.app.domain.OtaCdnCandidate
import com.tvbox.app.domain.OtaCdnFailureKind
import com.tvbox.app.domain.OtaCdnHealth
import com.tvbox.app.domain.OtaCdnProbe
import com.tvbox.app.domain.OtaDownloadProgressGate
import com.tvbox.app.domain.afterFailure
import com.tvbox.app.domain.afterSuccess
import com.tvbox.app.domain.applyCdnPreference
import com.tvbox.app.domain.buildCdnProbePlan
import com.tvbox.app.domain.decodeCdnPreference
import com.tvbox.app.domain.buildOtaApkCandidates
import com.tvbox.app.domain.buildOtaManifestCandidates
import com.tvbox.app.domain.classifyOtaDownloadFailure
import com.tvbox.app.domain.decodeOtaCdnHealth
import com.tvbox.app.domain.encodeOtaCdnHealth
import com.tvbox.app.domain.isOtaDownloadStalled
import com.tvbox.app.domain.otaCdnRetryDelayMs
import com.tvbox.app.domain.otaFailureReason
import com.tvbox.app.domain.rankOtaCdnCandidates
import com.tvbox.app.domain.summarizeOtaCdnFailures
import com.tvbox.app.util.logSafeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI

interface AppUpdateRepository {
    suspend fun checkForUpdate(currentVersionCode: Long, updateChannel: com.tvbox.app.domain.UpdateChannel = com.tvbox.app.domain.UpdateChannel.Stable): AppUpdate?
    suspend fun downloadUpdate(update: AppUpdate, onProgress: (Int) -> Unit): File
    /** 用户主动取消下载：终止底层任务并清理半成品；无进行中的任务时为无害空操作。 */
    suspend fun cancelDownload() {}
    suspend fun pendingDownload(): AppUpdate? = null
    suspend fun shouldAutoInstall(apkPath: String): Boolean = true
    suspend fun isAwaitingInstallPermission(apkPath: String): Boolean = false
    suspend fun setAwaitingInstallPermission(apkPath: String, awaiting: Boolean) {}
    suspend fun forgetDownload(apkPath: String) {}
    suspend fun markInstallPrompted(apkPath: String) {}
    suspend fun chooseManualInstall(apkPath: String) {}
    /** 用户放弃保存安装包时撤销“仅手动安装”标记，恢复后续自动安装行为。 */
    suspend fun clearManualInstall(apkPath: String) {}
    suspend fun exportUpdateApk(apkPath: String, destination: Uri?): String = throw IOException("暂不支持保存安装包")

    /**
     * 安装前设置导出：读取 updateExportBeforeInstall，开启时用现有文本导出器写出 UA/设置敏感项。
     * 返回明确状态文案（成功路径 / 按设置跳过 / 失败原因），不抛异常给安装流程。
     */
    suspend fun exportSettingsBeforeInstall(): String = "未接入设置导出入口"

    /**
     * 按 updateRollbackHours 清理过期的临时回滚安装包，返回实际删除的文件数。
     * 仅扫描 OTA 自建更新目录里的明确临时/过期文件，不影响用户保存的安装包。
     */
    suspend fun purgeExpiredRollbackApks(): Int = 0

    /** 强提醒只读开关：返回值仅供 UI 决定是否采用强提醒样式，不参与任何校验。 */
    suspend fun updateStrongReminderOnly(): Boolean = true

    /** 设置页 OTA 线路状态行；默认实现返回空，不影响未接入 OTA 的占位仓库。 */
    fun cdnStatusRows(nowMs: Long = System.currentTimeMillis()): List<CdnStatusRow> = emptyList()
}

class DefaultAppUpdateRepository(
    context: Context,
    /** 读取当前设置的入口；默认用出厂值，保证无 UI 环境下 OTA 门禁也按最保守的「仅 Wi-Fi」生效。 */
    private val settingsProvider: suspend () -> AppSettings = { AppSettings() },
) : AppUpdateRepository {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    // S5-30：lazy 优先取已预热实例（Application.onCreate 的 IO 协程经 [warmUp] 置入）；
    // 预热未完成时回退按需初始化，框架 SharedPreferences 进程缓存仍保证不重复读盘。
    private val downloadPrefs by lazy {
        prewarmedDownloadPrefs
            ?: appContext.getSharedPreferences("background_update", Context.MODE_PRIVATE)
    }
    // OTA 多 CDN 健康度：只记录线路标识与计数/耗时，不存 URL、不写日志。
    private val cdnPrefs by lazy {
        prewarmedCdnPrefs
            ?: appContext.getSharedPreferences("ota_cdn_health", Context.MODE_PRIVATE)
    }
    private val downloadMutex = Mutex()
    private val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client = HttpClients.default.newBuilder()
        .followRedirects(true)
        .build()
    // 探活专用客户端：短超时，避免对更新检查/下载路径造成长时间阻塞。
    private val probeClient = HttpClients.default.newBuilder()
        .followRedirects(true)
        .connectTimeout(CDN_PROBE_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(CDN_PROBE_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // callTimeout 兜底：整体 withTimeout(4s) 超时后，阻塞中的 execute() 由 OkHttp 自身
        // 终止，避免子协程继续占用连接/线程到各自 budget 结束。
        .callTimeout(CDN_PROBE_OVERALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /**
     * S5-30：预热持有者。Application.onCreate 于 IO 协程调用 [warmUp]，把两个启动路径
     * `by lazy` 会读盘的 SharedPreferences 提前打开并放入进程级框架缓存；
     * 各实例的 [downloadPrefs]/[cdnPrefs] lazy 初始化时优先直接拿到已预热实例。
     */
    companion object {
        @Volatile
        private var prewarmedDownloadPrefs: android.content.SharedPreferences? = null
        @Volatile
        private var prewarmedCdnPrefs: android.content.SharedPreferences? = null

        /** 预热入口——应在后台线程（Application.onCreate 的 IO 协程）调用，幂等可重入。 */
        fun warmUp(context: Context) {
            val appCtx = context.applicationContext
            prewarmedDownloadPrefs = appCtx.getSharedPreferences("background_update", Context.MODE_PRIVATE)
            prewarmedCdnPrefs = appCtx.getSharedPreferences("ota_cdn_health", Context.MODE_PRIVATE)
        }
    }

    // ------------------------------------------------------------ 更新前策略门禁（纯逻辑 + 系统状态）

    /**
     * 当前是否为「未计量 Wi-Fi」：无权限 / 无活动网络 / 蜂窝 / 计量 Wi-Fi 一律视为不满足。
     * 与 [NetworkCapabilities.NET_CAPABILITY_NOT_METERED] 的语义一致：该能力存在才表示不限流量。
     */
    private fun isUnmeteredWifi(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * 门禁判定（与系统状态分开，便于单测）：updateWifiOnly=false 直接放行；
     * 否则必须在未计量 Wi-Fi 下才允许检查/下载更新。
     */
    internal fun otaNetworkBlockReason(settings: AppSettings, unmeteredWifi: Boolean): String? =
        otaWifiBlockReason(settings, unmeteredWifi)

    private suspend fun requireUnmeteredWifi() {
        val reason = otaNetworkBlockReason(settingsProvider(), isUnmeteredWifi())
        if (reason != null) throw OtaWifiRequiredException(reason)
    }

    override suspend fun checkForUpdate(
        currentVersionCode: Long,
        updateChannel: com.tvbox.app.domain.UpdateChannel,
    ): AppUpdate? = withContext(Dispatchers.IO) {
        // 仅 Wi-Fi 更新开启时，非未计量 Wi-Fi 直接阻断检查（SHA-256 校验不受影响）。
        requireUnmeteredWifi()
        val manifestPath = updateChannel.manifestPath ?: return@withContext null
        val candidates = orderedCandidates(buildOtaManifestCandidates(path = manifestPath))
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val startedAtMs = System.currentTimeMillis()
            try {
                // 明文清单地址一律拒绝；异常走统一失败处理（记录并尝试下一条线路）。
                requireSecureOtaUrl(candidate.url, "更新清单源")
                val request = Request.Builder()
                    .url(candidate.url)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val raw = try {
                    // 单源整体超时：慢 CDN 快速失败切下一个，避免启动检查被单个挂起连接长时间拖住。
                    withTimeout(MANIFEST_CHECK_TIMEOUT_MS) {
                        client.executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                            .use { response -> response.body.string() }
                    }
                } catch (error: NetworkException.Http) {
                    throw IOException("更新检查失败：${error.statusCode}", error)
                }
                val parsed = parseNetworkPayload {
                    parseAppUpdateManifest(
                        raw = raw,
                        currentVersionCode = currentVersionCode,
                        json = json,
                    )
                }
                recordCdnSuccess(candidate.id, System.currentTimeMillis() - startedAtMs)
                return@withContext parsed
            } catch (e: TimeoutCancellationException) {
                lastError = e
                recordCdnFailure(candidate.id)
                android.util.Log.w("TVBoxUpdate", "更新清单源[${candidate.label}]超时，尝试下一个: ${logSafeText(e.message)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                recordCdnFailure(candidate.id)
                android.util.Log.w("TVBoxUpdate", "更新清单源[${candidate.label}]失败，尝试下一个: ${logSafeText(e.message)}")
            }
        }
        throw IOException("更新检查失败，已尝试 ${candidates.size} 个 CDN：${lastError?.message}")
    }

    override suspend fun pendingDownload(): AppUpdate? = withContext(Dispatchers.IO) {
        val task = readTask() ?: return@withContext null
        if (task.manifest.versionCode <= BuildConfig.VERSION_CODE) {
            downloadPrefs.edit().remove("task").commit()
            null
        } else task.manifest.toDomain()
    }

    override suspend fun shouldAutoInstall(apkPath: String): Boolean = withContext(Dispatchers.IO) {
        readTask()?.let { it.filePath == apkPath && !it.installPrompted && !it.awaitingInstallPermission && !it.manualInstallOnly } ?: true
    }

    override suspend fun isAwaitingInstallPermission(apkPath: String): Boolean = withContext(Dispatchers.IO) {
        readTask()?.let { it.filePath == apkPath && it.awaitingInstallPermission && !it.manualInstallOnly } ?: false
    }

    override suspend fun setAwaitingInstallPermission(apkPath: String, awaiting: Boolean) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            readTask()?.takeIf { it.filePath == apkPath }?.let {
                saveTask(it.copy(awaitingInstallPermission = awaiting, installPrompted = false, manualInstallOnly = false))
            }
        }
        Unit
    }

    override suspend fun forgetDownload(apkPath: String) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (readTask()?.filePath == apkPath) downloadPrefs.edit().remove("task").commit()
        }
        // 目前唯一调用方是安装失败（文件缺失）上报：同 cancelDownload 一样清掉半成品。
        cleanupDownloadFiles(apkPath)
        Unit
    }

    override suspend fun markInstallPrompted(apkPath: String) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            readTask()?.takeIf { it.filePath == apkPath }?.let {
                saveTask(it.copy(installPrompted = true, awaitingInstallPermission = false))
            }
        }
        Unit
    }

    /**
     * 下载更新包：优先复用未完成的后台任务（保持原有断点续传语义），
     * 否则按健康度排序依次尝试多个 CDN 候选。单条线路失败只影响该线路，
     * 空间不足 / 目录不可写 / 用户取消等不可通过换线路解决的错误立即上抛。
     */
    override suspend fun downloadUpdate(update: AppUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // 下载前重复校验网络（检查与下载之间用户可能切到蜂窝/计量网络）。
        requireUnmeteredWifi()
        // 回滚包保留期到期后顺手清理过期临时文件；失败不影响本次下载主流程。
        runCatching { purgeExpiredRollbackApks() }
        // 跨 CDN 切换时新线路从 0% 重新计数，闸门保证 UI 进度单调且 100% 只发一次。
        val progressGate = OtaDownloadProgressGate(onProgress)
        val resumed = downloadMutex.withLock {
            readTask()?.takeIf { matchesPendingTask(it, update) }
        }
        if (resumed != null) {
            requireSecureOtaUrl(resumed.downloadUrl, "续传安装包地址")
            return@withContext runDownloadTask(resumed, update, progressGate::onProgress)
        }

        if (update.apkUrl.isBlank()) throw IOException("安装包地址为空")
        // 明文 APK/镜像地址一律拒绝，避免 OTA 被中间人替换安装包。
        requireSecureOtaUrl(update.apkUrl, "安装包地址")
        update.apkMirrors.filter { it.isNotBlank() }
            .forEach { requireSecureOtaUrl(it, "安装包镜像地址") }
        // 派生线路也过滤一遍（旧持久化任务可能带明文地址）。
        val candidates = orderedCandidates(
            buildOtaApkCandidates(update.apkUrl, update.apkMirrors)
                .filter { isSecureOtaUrl(it.url) },
        )
        if (candidates.isEmpty()) throw IOException("安装包地址必须使用 HTTPS")

        val attempts = mutableListOf<OtaCdnAttempt>()
        for ((index, candidate) in candidates.withIndex()) {
            if (index > 0) {
                delay(otaCdnRetryDelayMs(index - 1))
                // 切换到备用 CDN：新线路从 0% 重新计数，且重置闸门防止新线路首段进度被旧值吞掉。
                progressGate.resetForNewCdn()
                progressGate.onProgress(0)
            }
            val startedAtMs = System.currentTimeMillis()
            val task = prepareTask(update, candidate, index)
            try {
                val apk = runDownloadTask(task, update, progressGate::onProgress)
                recordCdnSuccess(candidate.id, System.currentTimeMillis() - startedAtMs)
                return@withContext apk
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordCdnFailure(candidate.id)
                attempts += OtaCdnAttempt(candidate.label, error.message)
                android.util.Log.w(
                    "TVBoxUpdate",
                    "CDN[${candidate.label}]下载失败: ${logSafeText(error.message)} (${error::class.simpleName})",
                )
                if (classifyOtaDownloadFailure(error) == OtaCdnFailureKind.Fatal) throw error
                if (index == candidates.lastIndex) {
                    if (attempts.size > 1) throw IOException(summarizeOtaCdnFailures(attempts), error)
                    throw error
                }
            }
        }
        throw IOException(summarizeOtaCdnFailures(attempts))
    }

    /**
     * 用户主动取消下载：终止系统下载任务与各线路半成品文件、清除持久化任务记录，
     * 并通知应用内下载服务停止；无进行中的任务时为无害空操作。
     */
    override suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        // 读任务与清记录必须同锁：prepareTask 在同一把锁内写入新任务记录，
        // 锁外先读后清会与「取消期间新建下载」交错，误删新任务的记录并清错文件。
        val task = downloadMutex.withLock {
            val current = readTask()
            downloadPrefs.edit().remove("task").commit()
            current
        }
        if (task != null) cleanupDownloadTask(task)
        UpdateDownloadService.cancel(appContext)
        Unit
    }

    /** 取消与失败共用的文件清理：正式包、.part 与 .fallback.apk 一并删除。 */
    private fun cleanupDownloadFiles(filePath: String) {
        listOf(filePath, "$filePath.part", "$filePath.fallback.apk")
            .forEach { path -> runCatching { File(path).delete() } }
    }

    private fun cleanupDownloadTask(task: PendingSystemDownload) {
        if (task.id > 0) runCatching { downloadManager?.remove(task.id) }
        cleanupDownloadFiles(task.filePath)
    }

    /** 为某条 CDN 创建独立的下载任务；不同线路使用不同目标文件，避免失败文件互相污染。 */
    private suspend fun prepareTask(
        update: AppUpdate,
        candidate: OtaCdnCandidate,
        attemptIndex: Int,
    ): PendingSystemDownload = downloadMutex.withLock {
        val directory = updatesDir() ?: throw IOException("无法创建更新下载目录")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新下载目录")
        val suffix = if (attemptIndex == 0) "" else "-cdn$attemptIndex"
        val target = File(directory, "JuheVideo-${update.versionCode}-${System.currentTimeMillis()}$suffix.apk")
        val id = try {
            val availableManager = downloadManager ?: throw IOException("系统下载组件不可用")
            availableManager.enqueue(DownloadManager.Request(Uri.parse(candidate.url))
                .setTitle("聚合视频 ${update.versionName}")
                .setDescription("应用更新正在后台下载")
                .setMimeType("application/vnd.android.package-archive")
                .addRequestHeader("User-Agent", USER_AGENT)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target)))
        } catch (error: Exception) {
            android.util.Log.w("TVBoxUpdate", "System downloader unavailable; using app download: ${logSafeText(error.message)} (${error::class.simpleName})")
            0L
        }
        PendingSystemDownload(
            id = id,
            filePath = target.absolutePath,
            manifest = update.toManifestDto(),
            appDownloader = id == 0L,
            activeUrl = candidate.url,
        ).also(::saveTask)
    }

    /** 执行单条线路的下载与校验；失败时清理该线路的任务（含系统下载任务）。 */
    private suspend fun runDownloadTask(
        task: PendingSystemDownload,
        update: AppUpdate,
        onProgress: (Int) -> Unit,
    ): File {
        var currentTask = task
        try {
            if (currentTask.appDownloader) return downloadInApp(currentTask, onProgress)
            var lastProgress = Int.MIN_VALUE
            // 假死兜底：字节数持续无变化（含始终为 0）超过阈值即快速失败并切换下一条线路，
            // 不必等 15 分钟整体截止；status 长期停在 PENDING/RUNNING 的假死在这里被截断。
            var lastReceived = 0L
            val startedAtMs = System.currentTimeMillis()
            var lastReceivedChangeAtMs = startedAtMs
            // 整体截止时间：状态长期停在 PENDING/PAUSED/RUNNING 时不能永久轮询。
            val deadlineMs = startedAtMs + DOWNLOAD_TIMEOUT_MS
            while (true) {
                if (System.currentTimeMillis() > deadlineMs) {
                    throw IOException("更新下载超时，请检查网络后重试")
                }
                var status = DownloadManager.STATUS_PENDING
                var reason = 0
                var received = 0L
                var total = update.apkSize
                try {
                    val availableManager = downloadManager ?: throw IOException("系统下载组件不可用")
                    availableManager.query(DownloadManager.Query().setFilterById(currentTask.id)).use { cursor ->
                        if (cursor == null || !cursor.moveToFirst()) throw IOException("系统下载任务已失效")
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: total
                    }
                } catch (error: Exception) {
                    android.util.Log.w("TVBoxUpdate", "Cannot observe system download; switching to app download: ${logSafeText(error.message)} (${error::class.simpleName})")
                    // Separate paths prevent an old system job from writing into the fallback file.
                    currentTask = currentTask.copy(id = 0, appDownloader = true, filePath = "${currentTask.filePath}.fallback.apk")
                    downloadMutex.withLock { saveTask(currentTask) }
                    return downloadInApp(currentTask, onProgress)
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    // #321：把系统失败码换算成可明确归因的文案；空间不足 / 签名冲突给出具体提示，
                    // 其余仍保留系统错误码便于排查。
                    val systemReason = if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
                        "INSUFFICIENT_SPACE"
                    } else {
                        "系统错误 $reason"
                    }
                    throw IOException(otaFailureReason(systemReason) ?: "更新下载失败（$systemReason），请重试")
                }
                val nowMs = System.currentTimeMillis()
                if (received != lastReceived) {
                    lastReceived = received
                    lastReceivedChangeAtMs = nowMs
                }
                if (isOtaDownloadStalled(received, lastReceived, lastReceivedChangeAtMs, nowMs)) {
                    throw IOException("更新下载长时间无进展，请检查网络后重试")
                }
                val progress = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else -1
                if (lastProgress != progress) { onProgress(progress); lastProgress = progress }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val apk = File(currentTask.filePath)
                    verifyUpdateApk(apk, update.apkSize, update.apkSha256)
                    onProgress(100)
                    return apk
                }
                delay(750)
            }
            @Suppress("UNREACHABLE_CODE") throw IOException("下载任务已终止")
        } catch (cancelled: CancellationException) {
            // DownloadManager / foreground service outlives this UI observer.
            throw cancelled
        } catch (error: Throwable) {
            android.util.Log.w("TVBoxUpdate", "Update download failed: ${logSafeText(error.message)} (${error::class.simpleName})")
            downloadMutex.withLock {
                // 必须在 IO 上下文中同步清掉失败任务，避免重进时误复用半成品（此处非主线程）。
                if (readTask()?.filePath == currentTask.filePath) downloadPrefs.edit().remove("task").commit()
            }
            // 失败包与 cancelDownload 使用同一套清理，避免残留半成品被误当成功安装包。
            cleanupDownloadTask(currentTask)
            throw error
        }
    }

    private suspend fun downloadInApp(task: PendingSystemDownload, onProgress: (Int) -> Unit): File {
        val apk = File(task.filePath)
        if (apk.isFile && runCatching { verifyUpdateApk(apk, task.manifest.apkSize, task.manifest.apkSha256) }.isSuccess) {
            onProgress(100)
            return apk
        }
        try {
            // T1-10：带上清单 ETag，服务侧据此做断点续传门禁（ETag 不一致时弃 .part 从头下）。
            UpdateDownloadService.start(appContext, task.downloadUrl, task.filePath,
                task.manifest.apkSize, task.manifest.apkSha256, task.manifest.apkEtag)
        } catch (error: Exception) {
            android.util.Log.w("TVBoxUpdate", "Unable to start app download service: ${logSafeText(error.message)}")
            throw IOException("无法启动应用下载服务，请返回应用后重试", error)
        }
        var lastProgress = Int.MIN_VALUE
        // 假死兜底：服务侧 readTimeout 能截断死连接，这里再防一层
        // “服务存活但字节持续不动”的情况（含始终为 0）。
        var lastReceived = 0L
        val startedAtMs = System.currentTimeMillis()
        var lastReceivedChangeAtMs = startedAtMs
        val deadlineMs = startedAtMs + DOWNLOAD_TIMEOUT_MS
        // 服务被系统杀死后进程内静态 states 会被清空，status 可能长期为 null。
        // 连续 N 次拿不到状态就判定下载任务已失效，避免永久空转。
        var consecutiveNullStatus = 0
        while (true) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw IOException("更新下载超时，请检查网络后重试")
            }
            val status = UpdateDownloadService.status(task.filePath)
            if (status == null) {
                consecutiveNullStatus++
                if (consecutiveNullStatus >= MAX_NULL_STATUS_POLLS) {
                    throw IOException("更新下载任务已失效，请重试")
                }
            } else {
                consecutiveNullStatus = 0
            }
            if (status?.error != null) throw IOException(status.error)
            if (status?.cancelled == true) throw IOException("下载已取消")
            if (status?.complete == true) {
                verifyUpdateApk(apk, task.manifest.apkSize, task.manifest.apkSha256)
                onProgress(100)
                return apk
            }
            val nowMs = System.currentTimeMillis()
            val received = status?.received ?: 0L
            if (received != lastReceived) {
                lastReceived = received
                lastReceivedChangeAtMs = nowMs
            }
            if (isOtaDownloadStalled(received, lastReceived, lastReceivedChangeAtMs, nowMs)) {
                throw IOException("更新下载长时间无进展，请检查网络后重试")
            }
            val total = status?.total?.takeIf { it > 0 } ?: task.manifest.apkSize
            val progress = if (total > 0) (((status?.received ?: 0) * 100) / total).toInt().coerceIn(0, 99) else -1
            if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
            delay(750)
        }
    }

    override suspend fun chooseManualInstall(apkPath: String) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            readTask()?.takeIf { it.filePath == apkPath }?.let {
                saveTask(it.copy(manualInstallOnly = true, awaitingInstallPermission = false))
            }
        }
        Unit
    }

    override suspend fun clearManualInstall(apkPath: String) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            readTask()?.takeIf { it.filePath == apkPath && it.manualInstallOnly }?.let {
                saveTask(it.copy(manualInstallOnly = false))
            }
        }
        Unit
    }

    override suspend fun exportUpdateApk(apkPath: String, destination: Uri?): String = withContext(Dispatchers.IO) {
        val task = readTask()?.takeIf { it.filePath == apkPath } ?: throw IOException("安装包记录已失效，请重新下载")
        val source = File(apkPath)
        verifyUpdateApk(source, task.manifest.apkSize, task.manifest.apkSha256)
        val version = task.manifest.versionName.replace(APK_NAME_SAFE_REGEX, "_")
        UpdateApkExporter(appContext).save(source, destination, "JuheVideo-v$version.apk")
    }

    /**
     * 安装前设置导出：复用已有的 [TextFileExporter] 写文件，内容由 [UaScope.exportSensitive] 生成，
     * 默认按 uaExportRedacted 脱敏。任何失败都返回明确状态文案，不阻断安装主流程。
     */
    override suspend fun exportSettingsBeforeInstall(): String = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        if (!settings.updateExportBeforeInstall) return@withContext "按设置跳过安装前设置导出"
        val content = UaScope.exportSensitive(settings, includeSensitive = !settings.uaExportRedacted)
        val fileName = "TVBox-设置备份-${System.currentTimeMillis()}.txt"
        runCatching { TextFileExporter(appContext).save(fileName, content) }
            .fold(
                onSuccess = { "安装前设置导出成功：$it" },
                onFailure = { "安装前设置导出失败：${it.message ?: it::class.simpleName}" },
            )
    }

    /** 强提醒只读：仅暴露开关，UI 自行决定是否采用强提醒样式，不参与任何下载/安装校验。 */
    override suspend fun updateStrongReminderOnly(): Boolean = settingsProvider().updateStrongReminderOnly

    /**
     * 回滚包保留：只清理 OTA 自建更新目录里带时间戳的过期临时包（含 .part / .fallback.apk），
     * 跳过当前进行中的任务文件；用户主动保存的安装包不在这个目录，不受影响。
     */
    override suspend fun purgeExpiredRollbackApks(): Int = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        val keepPath = readTask()?.filePath
        val directory = updatesDir()
        val files = directory?.listFiles()?.toList().orEmpty()
        purgeExpiredRollbackFiles(files, settings.updateRollbackHours, keepPath)
    }

    private fun updatesDir(): File? = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.let { File(it, "updates") } ?: File(appContext.cacheDir, "updates")

    private fun readTask(): PendingSystemDownload? {
        val raw = downloadPrefs.getString("task", null) ?: return null
        return runCatching { json.decodeFromString<PendingSystemDownload>(raw) }.getOrNull()
    }

    private fun saveTask(task: PendingSystemDownload) {
        check(downloadPrefs.edit().putString("task", json.encodeToString(task)).commit()) {
            "Unable to save background download state"
        }
    }

    private fun matchesPendingTask(task: PendingSystemDownload, update: AppUpdate): Boolean =
        task.manifest.versionCode == update.versionCode &&
            task.manifest.apkUrl == update.apkUrl &&
            task.manifest.apkSha256 == update.apkSha256 &&
            // T1-10：ETag 变化说明服务端包已变更，旧任务的半成品 .part 不再可续传，不复用。
            task.manifest.apkEtag == update.apkEtag

    // ------------------------------------------------------------ CDN 健康度（本机，仅计数与耗时）

    private fun readCdnHealth(): Map<String, OtaCdnHealth> = cdnPrefs.all
        .mapNotNull { (key, value) ->
            if (!key.startsWith(CDN_HEALTH_KEY_PREFIX) || value !is String) null
            else key.removePrefix(CDN_HEALTH_KEY_PREFIX) to decodeOtaCdnHealth(value)
        }
        .toMap()

    private fun writeCdnHealth(id: String, health: OtaCdnHealth) {
        if (id.isBlank()) return
        cdnPrefs.edit().putString(CDN_HEALTH_KEY_PREFIX + id, encodeOtaCdnHealth(health)).apply()
    }

    private fun recordCdnSuccess(id: String, elapsedMs: Long) {
        val current = readCdnHealth()[id] ?: OtaCdnHealth()
        writeCdnHealth(id, current.afterSuccess(System.currentTimeMillis(), elapsedMs))
    }

    private fun recordCdnFailure(id: String) {
        val current = readCdnHealth()[id] ?: OtaCdnHealth()
        writeCdnHealth(id, current.afterFailure(System.currentTimeMillis()))
    }

    /** 设置页展示线路名、最近成功耗时与手动选择标记，不暴露完整 URL。 */
    override fun cdnStatusRows(nowMs: Long): List<CdnStatusRow> {
        val health = readCdnHealth()
        val preference = decodeCdnPreference(cdnPrefs.getString("preference", null))
        // 用历史最快耗时（bestSuccessMs）作速度信号，让展示顺序与「速度优先」的实际下载顺序一致；
        // 未使用过的线路没有该数据，保持中性排序，不会被误杀。
        val latencyMs = health.mapNotNull { (id, record) ->
            record.bestSuccessMs.takeIf { it > 0L }?.let { id to it }
        }.toMap()
        return buildCdnStatusRows(
            candidates = rankOtaCdnCandidates(
                candidates = buildOtaManifestCandidates(),
                health = health,
                nowMs = nowMs,
                latencyMs = latencyMs,
            ),
            health = health,
            preference = preference,
            nowMs = nowMs,
        )
    }

    /** 速度优先排序 + 设置页的手动线路选择（默认 Auto，行为与排序结果一致）。 */
    private suspend fun orderedCandidates(candidates: List<OtaCdnCandidate>): List<OtaCdnCandidate> {
        // 先探活测速：对前若干候选并发下载固定字节，不可达的重罚置底、快的排前面，
        // 实现「不写死谁第一，实测谁快用谁」。探活异常/超时安全降级为空（退回中性排序）。
        val probes = probeCdnAvailability(candidates)
        val availability = probes.mapValues { (_, probe) -> probe.reachable }
        val latencyMs = probes.mapValues { (_, probe) -> probe.elapsedMs }
            .filterValues { it > 0L }
        return applyCdnPreference(
            rankOtaCdnCandidates(
                candidates = candidates,
                health = readCdnHealth(),
                nowMs = System.currentTimeMillis(),
                availability = availability,
                latencyMs = latencyMs,
            ),
            decodeCdnPreference(cdnPrefs.getString("preference", null)),
        )
    }

    /**
     * 对候选 CDN 并发探活测速：只探前 [buildCdnProbePlan] 预算内的若干条，
     * 每条 Range 下载固定字节并计时，整体受超时约束，避免拖慢更新检查/下载。
     * 任意异常（网络、超时、解析）都返回空表，让排序退回中性策略，不误杀可用线路。
     */
    private suspend fun probeCdnAvailability(candidates: List<OtaCdnCandidate>): Map<String, OtaCdnProbe> {
        if (candidates.isEmpty()) return emptyMap()
        val plan = buildCdnProbePlan(
            candidates,
            budgetMs = CDN_PROBE_BUDGET_MS,
            maxProbes = 5,
            minProbeMs = CDN_PROBE_MIN_MS,
        )
        if (plan.isEmpty()) return emptyMap()
        val budgetBy = plan.toMap()
        return runCatching {
            withTimeout(CDN_PROBE_OVERALL_TIMEOUT_MS) {
                coroutineScope {
                    candidates.filter { it.id in budgetBy }.map { candidate ->
                        async {
                            val probe = probeOne(candidate.url, budgetBy.getValue(candidate.id))
                            candidate.id to probe
                        }
                    }.awaitAll().toMap()
                }
            }
        }.onFailure { if (it is CancellationException && it !is TimeoutCancellationException) throw it }
            .getOrDefault(emptyMap())
    }

    /**
     * 单条候选的速度探测：Range GET 下载固定字节并计时。
     * - 2xx/3xx 且读到数据：可达，elapsedMs 为端到端耗时（用于速度排序）；
     * - 405/403（CDN 不支持 Range）：视为可达但无法测速，elapsedMs=-1 保持中性；
     * - 超时/网络错误：不可达。
     */
    private suspend fun probeOne(url: String, budgetMs: Long): OtaCdnProbe {
        if (!isSecureOtaUrl(url)) return OtaCdnProbe(reachable = false)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .header("Range", "bytes=0-${CDN_PROBE_BYTES - 1}")
            .build()
        return try {
            withTimeout(budgetMs) {
                val startedAtMs = System.currentTimeMillis()
                probeClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 405) {
                        // 不支持 Range/HEAD：不能据此判定不可用，保留候选但不加速度分。
                        return@use OtaCdnProbe(reachable = true, elapsedMs = -1L)
                    }
                    if (code == 403) {
                        // 403 多为防盗链/封禁/UA 校验失败，不应视为「可达但无法测速」，
                        // 否则该 CDN 会参与选优，直到下载时才失败、浪费重试预算。
                        return@use OtaCdnProbe(reachable = false)
                    }
                    if (code !in 200..399) return@use OtaCdnProbe(reachable = false)
                    val source = response.body.byteStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (total < CDN_PROBE_BYTES) {
                        val read = source.read(buffer, 0, minOf(buffer.size, CDN_PROBE_BYTES - total))
                        if (read <= 0) break
                        total += read
                    }
                    if (total == 0) OtaCdnProbe(reachable = false)
                    else OtaCdnProbe(
                        reachable = true,
                        elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L),
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            OtaCdnProbe(reachable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            OtaCdnProbe(reachable = false)
        }
    }
}

/** 仅允许 HTTPS；本机回环（本地调试/测试）可显式使用 HTTP。 */
internal fun isSecureOtaUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase().orEmpty()
    // URI.host 对 IPv6 字面量可能带方括号（[::1]），统一剥离后判断，消除边界歧义。
    val host = uri.host?.lowercase()?.trim('[', ']').orEmpty()
    return when (scheme) {
        "https" -> host.isNotBlank()
        "http" -> host in setOf("127.0.0.1", "localhost", "::1")
        else -> false
    }
}

private fun requireSecureOtaUrl(url: String, label: String) {
    if (!isSecureOtaUrl(url)) throw IOException("$label 必须使用 HTTPS 地址（仅本机回环允许 HTTP）")
}

internal fun parseAppUpdateManifest(
    raw: String,
    currentVersionCode: Long,
    json: Json = Json { ignoreUnknownKeys = true },
): AppUpdate? {
    val dto = json.decodeFromString<UpdateManifestDto>(raw.trimStart('\uFEFF').trim())
    if (dto.versionCode <= currentVersionCode) return null
    val apkUrl = dto.apkUrl.trim()
    // 明文更新地址直接判为非法清单；SHA-256 校验仍由 verifyUpdateApk 失败关闭。
    requireSecureOtaUrl(apkUrl, "安装包地址")
    return AppUpdate(
        versionCode = dto.versionCode,
        versionName = dto.versionName.trim(),
        apkUrl = apkUrl,
        apkSha256 = dto.apkSha256.trim(),
        apkSize = dto.apkSize.coerceAtLeast(0L),
        force = dto.force,
        changelog = dto.changelog.map { it.trim() }.filter { it.isNotBlank() },
        // 可选字段：只保留 HTTPS 镜像，非法/HTTP 项静默忽略，老清单为空列表。
        apkMirrors = dto.apkMirrors.map { it.trim() }
            .filter(::isSecureOtaUrl)
            .distinct(),
        releaseHighlights = dto.releaseHighlights.map { it.trim() }.filter { it.isNotBlank() },
        // T1-10：ETag 可选；清单没给就空串，续传退化为纯 Range 语义。
        apkEtag = dto.apkEtag.trim(),
    )
}

@Serializable
private data class UpdateManifestDto(
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("versionName") val versionName: String,
    @SerialName("apkUrl") val apkUrl: String,
    @SerialName("apkSha256") val apkSha256: String = "",
    @SerialName("apkSize") val apkSize: Long = 0,
    @SerialName("force") val force: Boolean = false,
    @SerialName("changelog") val changelog: List<String> = emptyList(),
    /** 可选多 CDN 镜像；既有 8 个字段结构不变，老客户端 ignoreUnknownKeys 会忽略它。 */
    @SerialName("apkMirrors") val apkMirrors: List<String> = emptyList(),
    /**
     * 安装前要求的最低当前版本号；0 表示不限制。老 update.json 缺省为 0，
     * 新字段对老客户端透明（ignoreUnknownKeys）。
     */
    @SerialName("minVersionCode") val minVersionCode: Long = 0L,
    /** 清单发布通道：`stable`（正式）或 `canary`（预览），缺省按正式通道处理。 */
    @SerialName("channel") val channel: String = "stable",
    /** S7-40：CHANGELOG「你会感觉到」三行；老清单缺省为空。 */
    @SerialName("release_highlights") val releaseHighlights: List<String> = emptyList(),
    /**
     * T1-10：可选安装包 ETag（`apkEtag`）。老清单缺省为空串，客户端忽略；
     * 非空时用于断点续传门禁——服务端返回的 ETag 与它不一致就弃断点从头下载。
     */
    @SerialName("apkEtag") val apkEtag: String = "",
)

private const val USER_AGENT = "TVBox-Android"

/** Wi-Fi only 门禁的明确状态文案：检查与下载共用，UI 可直接展示。 */
internal const val WIFI_ONLY_BLOCK_MESSAGE =
    "当前非未计量 Wi-Fi，已开启「仅 Wi-Fi 更新」，已阻止检查/下载更新；如需使用移动数据请先关闭该开关"

/** 门禁阻断异常：message 已是可直接展示的中文文案。 */
class OtaWifiRequiredException(message: String) : IOException(message)

/** 纯门禁判定：默认设置下非未计量 Wi-Fi 必须阻断；关闭开关后放行。 */
internal fun otaWifiBlockReason(settings: AppSettings, unmeteredWifi: Boolean): String? =
    if (settings.updateWifiOnly && !unmeteredWifi) WIFI_ONLY_BLOCK_MESSAGE else null

/**
 * 版本阶梯门禁（P39）：清单里的 `minVersionCode` 高于当前 versionCode 时，
 * 禁止在应用内直接跨版本升级（否则既无法覆盖旧签名，也可能丢配置）。
 * 返回可直接展示的中文阻断文案；满足条件或清单未声明门槛（minVersionCode=0）时返回 null。
 */
internal fun checkMinVersionBlocked(
    currentVersionCode: Long,
    minVersionCode: Long,
): String? {
    if (minVersionCode <= 0L || currentVersionCode >= minVersionCode) return null
    // versionCode 采用 x*10000 + y*100 + z 的编码，反解为 "vX.Y.Z" 展示给用户。
    val major = minVersionCode / 10_000
    val minor = (minVersionCode % 10_000) / 100
    val patch = minVersionCode % 100
    return "当前版本过低，请先升级到 v$major.$minor.$patch 以上，再检查更新"
}

/** 发布通道常量：`stable`（正式）与 `canary`（预览）。旧 update.json 缺省按正式通道处理。 */
internal const val OTA_CHANNEL_STABLE = "stable"
internal const val OTA_CHANNEL_CANARY = "canary"

/** 清单通道解析：仅接受 stable / canary，其他或缺失值一律回退到正式通道，避免未知值误覆盖本地偏好。 */
internal fun normalizeOtaChannel(raw: String?): String =
    when (raw?.trim()?.lowercase()) {
        OTA_CHANNEL_CANARY -> OTA_CHANNEL_CANARY
        else -> OTA_CHANNEL_STABLE
    }

/** OTA 自建更新包命名：`JuheVideo-<versionCode>-<timestamp>[-cdnN].apk[.part|.fallback.apk]`。 */
private val ROLLBACK_NAME_REGEX =
    Regex("^JuheVideo-\\d+-(\\d+)(?:-cdn\\d+)?\\.apk(?:\\.part|\\.fallback)?$")

/** 从文件名解析写入时间（毫秒）；非 OTA 生成的文件返回 null。 */
internal fun rollbackFileTimestampMillis(name: String): Long? =
    ROLLBACK_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

/**
 * 回滚包过期清理（纯逻辑，便于单测）：仅删除命名匹配 OTA 规则且达到保留期的文件。
 * [keepPath] 对应当前进行中的任务，必须跳过；不递归、不删目录、不碰其他文件。
 */
internal fun purgeExpiredRollbackFiles(
    files: List<File>,
    retentionHours: Int,
    keepPath: String? = null,
    nowMs: Long = System.currentTimeMillis(),
): Int {
    val retention = retentionHours.coerceAtLeast(0)
    var removed = 0
    for (file in files) {
        if (!file.isFile) continue
        if (keepPath != null && file.absolutePath == keepPath) continue
        val timestamp = rollbackFileTimestampMillis(file.name) ?: continue
        val ageHours = ((nowMs - timestamp).coerceAtLeast(0L)) / 3_600_000L
        if (ageHours >= retention && runCatching { file.delete() }.getOrDefault(false)) removed++
    }
    return removed
}

/** 导出 APK 文件名中需要替换掉的非法字符。 */
private val APK_NAME_SAFE_REGEX = Regex("[^A-Za-z0-9._-]")

/** 单次更新下载的整体上限（20MB 级 APK 在弱网下留足余量），超时后走失败清理而不是永久轮询。 */
private const val DOWNLOAD_TIMEOUT_MS = 15L * 60L * 1000L

/** 单个更新清单源的整体超时：慢 CDN 快速失败并切换下一个，不让启动检查长时间挂起。 */
private const val MANIFEST_CHECK_TIMEOUT_MS = 10_000L

/** 探活整体上限：前若干候选并发测速，整体留足余量后强制结束（结果回退中性排序）。 */
private const val CDN_PROBE_OVERALL_TIMEOUT_MS = 4_000L

/** 探活单条预算：下载固定字节的端到端测速上限；5 条并发各 1.2s，仍受整体 4s 约束。 */
private const val CDN_PROBE_BUDGET_MS = 3_600L
private const val CDN_PROBE_MIN_MS = 1_200L

/**
 * 单条探活下载字节数：64KB 足以让快慢线路拉开差距（快线约 0.1s、慢线约 0.5s），
 * 又不会在慢线路上耗尽预算。对小于该值的清单文件（如 858B 的 update.json）读满即止。
 */
private const val CDN_PROBE_BYTES = 64 * 1024

/** 探活单条连接/读取超时：短于整体上限，确保不可达线路快速判定。 */
private const val CDN_PROBE_CONNECT_TIMEOUT_MS = 1_500L
private const val CDN_PROBE_READ_TIMEOUT_MS = 1_500L

/** downloadInApp 中连续拿不到 Service 状态的最大次数（每次间隔 750ms）。 */
private const val MAX_NULL_STATUS_POLLS = 20

private const val CDN_HEALTH_KEY_PREFIX = "cdn_"

@Serializable
private data class PendingSystemDownload(
    val id: Long,
    val filePath: String,
    val manifest: UpdateManifestDto,
    val installPrompted: Boolean = false,
    val awaitingInstallPermission: Boolean = false,
    val appDownloader: Boolean = false,
    val manualInstallOnly: Boolean = false,
    /** 当前实际下载线路；旧版本持久化的任务没有该字段，回退到清单 apkUrl。 */
    val activeUrl: String = "",
)

private val PendingSystemDownload.downloadUrl: String
    get() = activeUrl.ifBlank { manifest.apkUrl }

private fun UpdateManifestDto.toDomain() = AppUpdate(
    versionCode, versionName, apkUrl, apkSha256, apkSize, force, changelog, apkMirrors,
    minVersionCode = minVersionCode,
    channel = channel,
    releaseHighlights = releaseHighlights,
    apkEtag = apkEtag,
)

private fun AppUpdate.toManifestDto() = UpdateManifestDto(
    versionCode = versionCode,
    versionName = versionName,
    apkUrl = apkUrl,
    apkSha256 = apkSha256,
    apkSize = apkSize,
    force = force,
    changelog = changelog,
    apkMirrors = apkMirrors,
    minVersionCode = minVersionCode,
    channel = channel,
    releaseHighlights = releaseHighlights,
    apkEtag = apkEtag,
)
