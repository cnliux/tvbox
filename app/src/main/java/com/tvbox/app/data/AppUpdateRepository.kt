package com.tvbox.app.data

import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.data.network.parseNetworkPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import com.tvbox.app.domain.AppUpdate
import com.tvbox.app.domain.OtaCdnAttempt
import com.tvbox.app.domain.OtaCdnCandidate
import com.tvbox.app.domain.OtaCdnFailureKind
import com.tvbox.app.domain.OtaCdnHealth
import com.tvbox.app.domain.OtaDownloadProgressGate
import com.tvbox.app.domain.afterFailure
import com.tvbox.app.domain.afterSuccess
import com.tvbox.app.domain.applyCdnPreference
import com.tvbox.app.domain.decodeCdnPreference
import com.tvbox.app.domain.buildOtaApkCandidates
import com.tvbox.app.domain.buildOtaManifestCandidates
import com.tvbox.app.domain.classifyOtaDownloadFailure
import com.tvbox.app.domain.decodeOtaCdnHealth
import com.tvbox.app.domain.encodeOtaCdnHealth
import com.tvbox.app.domain.otaCdnRetryDelayMs
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

interface AppUpdateRepository {
    suspend fun checkForUpdate(currentVersionCode: Long): AppUpdate?
    suspend fun downloadUpdate(update: AppUpdate, onProgress: (Int) -> Unit): File
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
}

class DefaultAppUpdateRepository(
    context: Context,
) : AppUpdateRepository {
    private val appContext = context.applicationContext
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val downloadPrefs by lazy { appContext.getSharedPreferences("background_update", Context.MODE_PRIVATE) }
    // OTA 多 CDN 健康度：只记录线路标识与计数/耗时，不存 URL、不写日志。
    private val cdnPrefs by lazy { appContext.getSharedPreferences("ota_cdn_health", Context.MODE_PRIVATE) }
    private val downloadMutex = Mutex()
    private val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client = HttpClients.default.newBuilder()
        .followRedirects(true)
        .build()

    override suspend fun checkForUpdate(currentVersionCode: Long): AppUpdate? = withContext(Dispatchers.IO) {
        val candidates = orderedCandidates(buildOtaManifestCandidates())
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val startedAtMs = System.currentTimeMillis()
            val request = Request.Builder()
                .url(candidate.url)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                val raw = try {
                    client.executeCancellable(request, NetworkRetryPolicy.IdempotentGet)
                        .use { response -> response.body.string() }
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
        // 跨 CDN 切换时新线路从 0% 重新计数，闸门保证 UI 进度单调且 100% 只发一次。
        val progressGate = OtaDownloadProgressGate(onProgress)
        val resumed = downloadMutex.withLock {
            readTask()?.takeIf { matchesPendingTask(it, update) }
        }
        if (resumed != null) return@withContext runDownloadTask(resumed, update, progressGate::onProgress)

        if (update.apkUrl.isBlank()) throw IOException("安装包地址为空")
        val candidates = orderedCandidates(buildOtaApkCandidates(update.apkUrl, update.apkMirrors))
        if (candidates.isEmpty()) throw IOException("安装包地址为空")

        val attempts = mutableListOf<OtaCdnAttempt>()
        for ((index, candidate) in candidates.withIndex()) {
            if (index > 0) {
                delay(otaCdnRetryDelayMs(index - 1))
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

    /** 为某条 CDN 创建独立的下载任务；不同线路使用不同目标文件，避免失败文件互相污染。 */
    private suspend fun prepareTask(
        update: AppUpdate,
        candidate: OtaCdnCandidate,
        attemptIndex: Int,
    ): PendingSystemDownload = downloadMutex.withLock {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "updates") } ?: File(appContext.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新下载目录")
        val suffix = if (attemptIndex == 0) "" else "-cdn$attemptIndex"
        val target = File(directory, "TVBox-${update.versionCode}-${System.currentTimeMillis()}$suffix.apk")
        val id = try {
            val availableManager = downloadManager ?: throw IOException("系统下载组件不可用")
            availableManager.enqueue(DownloadManager.Request(Uri.parse(candidate.url))
                .setTitle("TVBox ${update.versionName}")
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
            cdnId = candidate.id,
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
            // 整体截止时间：状态长期停在 PENDING/PAUSED/RUNNING 时不能永久轮询。
            val deadlineMs = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
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
                    throw IOException(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "设备存储空间不足，请清理后重试"
                        else "更新下载失败（系统错误 $reason），请重试")
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
                if (readTask()?.filePath == currentTask.filePath) downloadPrefs.edit().remove("task").commit()
            }
            if (currentTask.id > 0) runCatching { downloadManager?.remove(currentTask.id) }
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
            UpdateDownloadService.start(appContext, task.downloadUrl, task.filePath,
                task.manifest.apkSize, task.manifest.apkSha256)
        } catch (error: Exception) {
            android.util.Log.w("TVBoxUpdate", "Unable to start app download service: ${logSafeText(error.message)}")
            throw IOException("无法启动应用下载服务，请返回应用后重试", error)
        }
        var lastProgress = Int.MIN_VALUE
        val deadlineMs = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
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
            if (status?.complete == true) {
                verifyUpdateApk(apk, task.manifest.apkSize, task.manifest.apkSha256)
                onProgress(100)
                return apk
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
        val version = task.manifest.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        UpdateApkExporter(appContext).save(source, destination, "TVBox-v$version.apk")
    }

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
            task.manifest.apkSha256 == update.apkSha256

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

    /** 健康度排序 + 设置页的手动线路选择（默认 Auto，行为与排序结果一致）。 */
    private fun orderedCandidates(candidates: List<OtaCdnCandidate>): List<OtaCdnCandidate> =
        applyCdnPreference(
            rankOtaCdnCandidates(candidates, readCdnHealth(), System.currentTimeMillis()),
            decodeCdnPreference(cdnPrefs.getString("preference", null)),
        )
}

internal fun parseAppUpdateManifest(
    raw: String,
    currentVersionCode: Long,
    json: Json = Json { ignoreUnknownKeys = true },
): AppUpdate? {
    val dto = json.decodeFromString<UpdateManifestDto>(raw.trimStart('\uFEFF').trim())
    if (dto.versionCode <= currentVersionCode) return null
    return AppUpdate(
        versionCode = dto.versionCode,
        versionName = dto.versionName.trim(),
        apkUrl = dto.apkUrl.trim(),
        apkSha256 = dto.apkSha256.trim(),
        apkSize = dto.apkSize.coerceAtLeast(0L),
        force = dto.force,
        changelog = dto.changelog.map { it.trim() }.filter { it.isNotBlank() },
        // 可选字段：只保留 HTTPS 镜像，非法/HTTP 项静默忽略，老清单为空列表。
        apkMirrors = dto.apkMirrors.map { it.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) }
            .distinct(),
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
)

private const val USER_AGENT = "TVBox-Android"

/** 单次更新下载的整体上限（20MB 级 APK 在弱网下留足余量），超时后走失败清理而不是永久轮询。 */
private const val DOWNLOAD_TIMEOUT_MS = 15L * 60L * 1000L

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
    val cdnId: String = "",
)

private val PendingSystemDownload.downloadUrl: String
    get() = activeUrl.ifBlank { manifest.apkUrl }

private fun UpdateManifestDto.toDomain() = AppUpdate(
    versionCode, versionName, apkUrl, apkSha256, apkSize, force, changelog, apkMirrors,
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
)
