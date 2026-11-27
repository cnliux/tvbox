package com.tvbox.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.util.Log
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.view.WindowManager
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import com.tvbox.app.data.DataStoreAppSettingsRepository
import com.tvbox.app.data.DefaultAppUpdateRepository
import com.tvbox.app.data.EmbeddedPlatformLiveRepository
import com.tvbox.app.data.DefaultDoubanHotRepository
import com.tvbox.app.data.DefaultMovieRepository
import com.tvbox.app.data.DefaultSearchHotWordRepository
import com.tvbox.app.data.LiveDomainScoreStore
import com.tvbox.app.data.LiveLineQualityStore
import com.tvbox.app.data.LiveLinePreference
import com.tvbox.app.data.SharedDoubanHotCache
import com.tvbox.app.data.SharedHistoryRepository
import com.tvbox.app.data.SharedPlaybackHealthRepository
import com.tvbox.app.data.SharedPlatformLiveFavoritesRepository
import com.tvbox.app.data.SharedLiveFavoritesRepository
import com.tvbox.app.data.SharedSearchHotWordCache
import com.tvbox.app.data.verifyUpdateApk
import com.tvbox.app.util.logSafeText
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.LanResolverGuard
import com.tvbox.app.domain.LegalTerms
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import com.tvbox.app.crash.CrashHandler
import com.tvbox.app.ui.FirstRunScreen
import com.tvbox.app.ui.LocalTouchMode
import com.tvbox.app.ui.TvBoxApp
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.theme.TVBoxTheme
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val viewModel: TvBoxViewModel by viewModels {
        TvBoxViewModelFactory(this)
    }
    // 安装前设置导出 / OTA 门禁用同一份持久化设置源，和 ViewModel 的 DataStore 实例共享底层文件。
    private val appSettingsRepository by lazy { DataStoreAppSettingsRepository.create(applicationContext) }
    private val appUpdateRepository by lazy {
        DefaultAppUpdateRepository(
            context = applicationContext,
            settingsProvider = { appSettingsRepository.getSettings() },
        )
    }
    private var pendingInstallPermissionAction: InstallPermissionAction? = null
    private var permissionSettingsOpen = false
    private var pendingExportApkPath: String? = null
    // 首页连续按两次返回才退出应用。
    private var lastHomeBackPressTime = 0L
    private val saveApkLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(APK_MIME_TYPE)) { uri ->
        val path = pendingExportApkPath ?: return@registerForActivityResult
        pendingExportApkPath = null
        if (uri != null) viewModel.saveUpdateApk(path, uri) else viewModel.cancelUpdateApkExport()
    }
    private val exportStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val path = pendingExportApkPath ?: return@registerForActivityResult
        pendingExportApkPath = null
        if (granted) viewModel.saveUpdateApk(path, null)
        else viewModel.cancelUpdateApkExport("未获得存储权限，无法保存到下载目录；安装包仍保留，可重试")
    }
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionSettingsOpen = false
        val action = pendingInstallPermissionAction ?: return@registerForActivityResult
        pendingInstallPermissionAction = null
        handleInstallPermissionResult(action)
    }
    // Android 13+（API 33+）后台应用展示通知需要此运行时权限。
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startUpdateDownload()
        } else {
            // 拒绝后 rationale=false 表示「不再询问」或系统策略禁止：再申请也不会弹窗，
            // 只能引导去系统设置；rationale=true 属于临时拒绝，可直接重试授权。
            // 旧版本没有该权限，判断放在 API 33+ 分支并兜底，避免调用不可用的权限 API 崩溃。
            val permanent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                }.getOrDefault(false)
            } else {
                false
            }
            viewModel.cancelUpdateApkExport(
                if (permanent) "未获得通知权限（已不再询问），下载进度无法显示；请在系统设置中开启通知后重试"
                else "未获得通知权限，下载进度无法显示；可重试授权，安装包仍可保存",
            )
            viewModel.reportNotificationPermissionDenied(permanent)
        }
    }
    /**
     * #201 Intent 来源校验（纵深防御）：MainActivity 因 LAUNCHER 必须 exported，
     * 但本应用不消费任何外部 data / extra。对携带 data URI 或非 MAIN action 的启动意图
     * 做防御性剥离与告警，避免被第三方应用当作隐式通道注入内容。正常桌面图标启动
     * （action=MAIN、无 data）不受影响。
     */
    private fun sanitizeExternalIntent() {
        val incoming = intent ?: return
        val action = incoming.action
        val data = incoming.data
        if (data != null || (action != null && action != Intent.ACTION_MAIN)) {
            Log.w(
                "TVBoxIntent",
                "已剥离外部 Intent 附加数据：action=$action, scheme=${data?.scheme}",
            )
            incoming.data = null
            incoming.action = Intent.ACTION_MAIN
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sanitizeExternalIntent()
        notifyPendingCrashLogs()
        // debug 包开启 StrictMode：主线程 IO 只写 logcat，不 crash，release 编译期即剔除。
        enableStrictModeInDebug()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 订阅"生效模式"而非保存的偏好：Auto 模式下按系统识别结果切换横竖屏。
                viewModel.state.map { it.effectiveDeviceMode }.distinctUntilChanged()
                    .collect(::applyBaseOrientation)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 局域网解析服务启用守卫：只在安全提示已确认后放行；未确认时给出明确状态并不启动。
                var lastBlockReason: String? = null
                viewModel.state.map { it.appSettings }.distinctUntilChanged().collect { settings ->
                    val reason = LanResolverGuard.blockReason(settings)
                    if (reason != null && reason != lastBlockReason) {
                        Toast.makeText(this@MainActivity, "$reason，局域网解析服务未启用", Toast.LENGTH_LONG).show()
                    }
                    lastBlockReason = reason
                }
            }
        }
        pendingExportApkPath = savedInstanceState?.getString("pendingExportApk")
        pendingInstallPermissionAction = savedInstanceState?.getString("pendingUpdateApk")
            ?.let { InstallPermissionAction.InstallDownloadedApk(it) }

        // T6-39 + S2-10：首次启动法务契约检查。
        // 必须是 Compose 可观察状态：用户勾选并落盘 ack 后要能立即切到首页，
        // 否则 setContent 分支永远停在法务页（v1.0.45 曾因写成不可变 val 导致
        // "正在保存…" 卡死 —— onAccepted 只改了 screen 却没有重算本标志）。
        // S5-30：不再 runBlocking 阻塞主线程读 SharedPreferences（首个 Activity 启动路径上
        // 的同步磁盘读）。初始值给 null 表示「尚未判定」，setContent 里据此先渲染空白
        // 背景（一帧内即完成），判定结果回到主线程后再决定法务页 / 首页。
        val showLegalScreenState = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        lifecycleScope.launch {
            val requiresAck = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val prefs = getSharedPreferences("tvbox_legal", MODE_PRIVATE)
                val v = prefs.getInt(LegalTerms.VERSION_KEY, -1)
                val hash = prefs.getString(LegalTerms.HASH_KEY, null)?.takeIf { it.isNotBlank() }
                val ack = if (v >= 0 && hash != null) {
                    LegalTerms.Ack(v, hash, System.currentTimeMillis())
                } else {
                    null
                }
                LegalTerms.requiresAcknowledgement(ack)
            }
            showLegalScreenState.value = requiresAck
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // 返回前台时重算自动识别：插拔 HDMI / 外接键鼠可能改变系统设备特征。
                viewModel.refreshEffectiveDeviceMode()
                // Some TV settings apps return without an ActivityResult callback.
                permissionSettingsOpen = false
                viewModel.state.map {
                    it.updateAutoInstallPath to it.updateDownloadedApkPath.takeIf { _ -> it.updateAwaitingInstallPermission }
                }.distinctUntilChanged().collect { (automatic, awaitingPermission) ->
                    if (automatic != null) installUpdateApk(automatic)
                    else if (awaitingPermission != null && canInstallUnknownApps()) installUpdateApk(awaitingPermission)
                }
            }
        }
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            TVBoxTheme(
                fontScale = state.appSettings.fontScale,
                themeAccent = state.appSettings.themeAccent,
            ) {
                // 判定未完成时先渲染空背景（一帧），避免闪现法务页再跳首页。
                val legal = showLegalScreenState.value
                if (legal == null) {
                    // 判定中：铺满背景色，不显示任何内容（通常仅一帧）。
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(com.tvbox.app.ui.theme.TvColors.Background),
                    )
                } else if (legal) {
                    // M-02：首启法务页移入主题与设备模式作用域，手机端不再画 TV 焦点框、
                    // 且正确跟随用户字号/主题；LocalTouchMode 按设备模式提供。
                    CompositionLocalProvider(
                        LocalTouchMode provides (state.effectiveDeviceMode == DeviceMode.Mobile),
                    ) {
                        FirstRunScreen(
                            onAccepted = {
                                // 先翻状态再进首页：ack 已由 FirstRunScreen 落盘，
                                // 这里必须同步关闭法务页分支，避免残留「正在保存…」。
                                showLegalScreenState.value = false
                                viewModel.openHome()
                            },
                        )
                    }
                } else {
                    // 首页没有可返回的上层页面：连续按两次返回才退出应用，避免误触直接关闭。
                    BackHandler {
                        if (state.screen != com.tvbox.app.ui.TvScreen.Home) {
                            viewModel.goBack()
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastHomeBackPressTime < HOME_BACK_EXIT_INTERVAL_MS) {
                                lastHomeBackPressTime = 0L
                                finish()
                            } else {
                                lastHomeBackPressTime = now
                                Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    TvBoxApp(
                        state = state,
                        actions = viewModel,
                        onStartUpdateDownload = ::startBackgroundUpdateDownload,
                        onInstallUpdate = ::installUpdateApk,
                        onOpenUpdateSettings = ::openUpdateSystemSettings,
                        onSaveUpdateApk = ::saveUpdateApk,
                        onShowHealthPanel = { viewModel.dismissHealthPanel() },
                    )
                }
            }
        }
    }

    /** 仅 debug 生效：主线程磁盘/网络 IO 与资源问题写 logcat，不 crash；release 编译期即剔除。 */
    private fun enableStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    // 主题 Theme.TVBox 的 android:windowFullscreen=true 最终以 FLAG_FULLSCREEN 落到 window 上，
    // 手机模式必须 clearFlags 才能真正恢复状态栏与 systemBars inset；InsetsController.show 无法
    // 抵消主题 flag，故此处保留（已弃用但有意为之），与 PlayerViewport 的沉浸式处理同一口径。
    @Suppress("DEPRECATION")
    private fun applyBaseOrientation(mode: DeviceMode) {
        requestedOrientation = if (mode == DeviceMode.Mobile) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // 手机模式使用 edge-to-edge 布局，由各页面自行吸收状态栏/导航栏安全区；
        // 电视模式保持原有的系统避让行为，避免盒子端画面被系统栏挤压。
        WindowCompat.setDecorFitsSystemWindows(window, mode != DeviceMode.Mobile)
        // 主题 Theme.TVBox 带 windowFullscreen=true（电视端要全屏），该 flag 会隐藏系统状态栏，
        // 使 WindowInsets.statusBars 顶部 inset 恒为 0；手机模式各页面按 systemBars 避让的 padding
        // 因此失效，标题被状态栏区域压住。手机模式显式清除全屏并恢复状态栏显示。
        if (mode == DeviceMode.Mobile) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.statusBars())
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun saveUpdateApk(apkPath: String) {
        if (!viewModel.beginUpdateApkExport(apkPath)) return
        pendingInstallPermissionAction = null
        pendingExportApkPath = apkPath
        val version = viewModel.state.value.availableUpdate?.versionName.orEmpty()
            .replace(APK_NAME_SAFE_REGEX, "_")
        try {
            saveApkLauncher.launch("TVBox-v$version.apk")
        } catch (error: Exception) {
            Log.w("TVBoxUpdate", "No document picker; exporting to public Downloads", error)
            Toast.makeText(this, "系统没有可用的文件选择器，将保存到下载目录的 TVBox 文件夹", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                try { exportStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                catch (permissionError: Exception) {
                    Log.w("TVBoxUpdate", "Cannot request legacy export storage permission", permissionError)
                    pendingExportApkPath = null
                    viewModel.cancelUpdateApkExport("无法申请保存所需的存储权限，安装包仍保留")
                }
            } else {
                pendingExportApkPath = null
                viewModel.saveUpdateApk(apkPath, null)
            }
        }
    }

    private fun startBackgroundUpdateDownload() {
        // Android 13+（API 33+）后台应用展示通知需要运行时权限，低版本无需申请。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> viewModel.startUpdateDownload()
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) ->
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else ->
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        viewModel.startUpdateDownload()
    }

    /**
     * 启动时若有未确认的崩溃日志，提示一次（记录签名避免每次启动重复弹）。
     * 上报入口：本地落盘 + 启动提示；后续接后端时替换为上传即可。
     */
    private fun notifyPendingCrashLogs() {
        val logs = CrashHandler.getPendingCrashLogs()
        if (logs.isEmpty()) return
        val prefs = getSharedPreferences("crash_notify", MODE_PRIVATE)
        val sig = "${logs.first().name}|${logs.size}"
        if (sig == prefs.getString("last_sig", null)) return
        prefs.edit().putString("last_sig", sig).apply()
        Toast.makeText(
            this,
            "检测到 ${logs.size} 份崩溃日志（${logs.first().name.take(26)}），请及时排查",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun installUpdateApk(apkPath: String) {
        if (permissionSettingsOpen && !canInstallUnknownApps()) return
        if (!viewModel.beginUpdateInstallAttempt(apkPath)) return
        val apkFile = File(apkPath)
        if (!apkFile.isFile || apkFile.length() == 0L) {
            pendingInstallPermissionAction = null
            viewModel.reportUpdateInstallFailure(apkPath, "安装包不存在，请重新下载", fileMissing = true)
            return
        }
        if (!canInstallUnknownApps()) {
            requestInstallPermission(
                action = InstallPermissionAction.InstallDownloadedApk(apkPath),
                message = "请允许 TVBox 安装未知应用，允许后将继续安装更新。",
            )
            return
        }
        pendingInstallPermissionAction = null
        permissionSettingsOpen = false
        // 安装前重新校验 SHA-256 与签名证书，防止下载完成到安装之间文件被替换。
        lifecycleScope.launch {
            try {
                val pending = appUpdateRepository.pendingDownload()
                if (pending != null) {
                    verifyUpdateApk(apkFile, pending.apkSize, pending.apkSha256)
                }
                if (!apkSignatureMatchesInstalled(apkFile)) {
                    throw IOException("安装包签名与当前应用不一致，已拒绝安装")
                }
            } catch (error: IOException) {
                pendingInstallPermissionAction = null
                viewModel.reportUpdateInstallFailure(
                    apkFile.absolutePath,
                    "安装包校验失败：${logSafeText(error.message)}",
                    fileMissing = true,
                )
                return@launch
            }
            // 安装前导出设置（updateExportBeforeInstall=true）：导出失败也继续安装，仅记录明确状态。
            val exportStatus = appUpdateRepository.exportSettingsBeforeInstall()
            CrashHandler.recordOperation("ota.preinstall_export", exportStatus.take(120))
            openSystemInstaller(apkFile)
        }
    }

    /**
     * 比对 APK 与当前安装包的签名证书集合；API 28+ 使用 signingInfo，低版本回退到 signatures。
     * 任一集合为空或不一致都拒绝安装，避免仅靠系统覆盖安装隐式校验。
     */
    @Suppress("DEPRECATION")
    private fun apkSignatureMatchesInstalled(apkFile: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()
            ?: return false
        val candidate = runCatching {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }.getOrNull() ?: return false
        val installedCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }.orEmpty()
        } else {
            installed.signatures?.map { it.toCharsString() }.orEmpty()
        }
        val candidateCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }.orEmpty()
        } else {
            candidate.signatures?.map { it.toCharsString() }.orEmpty()
        }
        return installedCerts.isNotEmpty() && installedCerts == candidateCerts
    }

    @Suppress("DEPRECATION")
    private fun openSystemInstaller(apkFile: File) {
        CrashHandler.recordOperation("ota.install", "path=${apkFile.absolutePath}")
        val apkUri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        } catch (error: Exception) {
            Log.e("TVBoxUpdate", "Unable to share update APK", error)
            viewModel.reportUpdateInstallFailure(apkFile.absolutePath, "无法读取安装包，请重新下载", fileMissing = true)
            return
        }
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE)) {
            val intent = Intent(action).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                clipData = ClipData.newRawUri("TVBox update", apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
                viewModel.markUpdateInstallPrompted(apkFile.absolutePath)
                return
            } catch (error: Exception) {
                Log.w("TVBoxUpdate", "Unable to open installer: $action", error)
            }
        }
        val message = "无法打开系统安装器，安装包已保留，可重试安装"
        viewModel.reportUpdateInstallFailure(apkFile.absolutePath, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun openUpdateSystemSettings() {
        val apkPath = viewModel.state.value.updateDownloadedApkPath ?: return
        if (permissionSettingsOpen) return
        requestInstallPermission(InstallPermissionAction.InstallDownloadedApk(apkPath),
            "请在系统设置中允许 TVBox 安装未知应用，授权后返回即可继续安装", generalSettingsOnly = true)
    }

    private fun requestInstallPermission(
        action: InstallPermissionAction,
        message: String,
        generalSettingsOnly: Boolean = false,
    ) {
        val apkPath = (action as InstallPermissionAction.InstallDownloadedApk).apkPath
        viewModel.awaitUpdateInstallPermission(apkPath)
        pendingInstallPermissionAction = action
        if (canInstallUnknownApps()) {
            viewModel.resumeUpdateInstallAfterPermission(apkPath)
            return
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val packageUri = Uri.parse("package:$packageName")
        val candidates = if (generalSettingsOnly) listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        ) else listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        // Try launching directly: package visibility can make resolveActivity unreliable.
        for (intent in candidates) {
            try {
                permissionSettingsOpen = true
                installPermissionLauncher.launch(intent)
                Log.i("TVBoxUpdate", "Opened install permission settings: ${intent.action}")
                return
            } catch (error: Exception) {
                permissionSettingsOpen = false
                Log.w("TVBoxUpdate", "Unable to open permission settings: ${intent.action}", error)
            }
        }
        val failureMessage = "无法打开安装权限设置。安装包已保留，请在系统设置中允许 TVBox 安装未知应用"
        viewModel.awaitUpdateInstallPermission(apkPath, failureMessage)
        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
    }

    private fun handleInstallPermissionResult(action: InstallPermissionAction) {
        val apkPath = (action as InstallPermissionAction.InstallDownloadedApk).apkPath
        val state = viewModel.state.value
        if (state.updateDownloadedApkPath != apkPath || !state.updateAwaitingInstallPermission) return
        if (canInstallUnknownApps()) {
            // The RESUMED collector launches the installer, never this STARTED callback.
            viewModel.resumeUpdateInstallAfterPermission(apkPath)
            return
        }

        val message = "尚未获得安装授权，安装包已保留。授权后返回应用即可继续安装"
        viewModel.awaitUpdateInstallPermission(apkPath, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun canInstallUnknownApps(): Boolean {
        return try {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        } catch (error: Exception) {
            Log.w("TVBoxUpdate", "Unable to check unknown-app installation permission", error)
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportApk", pendingExportApkPath)
        val action = pendingInstallPermissionAction as? InstallPermissionAction.InstallDownloadedApk
        outState.putString("pendingUpdateApk", action?.apkPath)
        super.onSaveInstanceState(outState)
    }

    /**
     * configChanges 吞掉了旋转/分屏/折叠屏尺寸变化，Activity 不重建；
     * 必须主动重算设备模式，否则布局会停留在旧的输入方式上。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.refreshEffectiveDeviceMode()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /** T3-24：菜单键连按 5 次触发健康面板。 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            // 只计数不吞键：MENU 在直播/平台直播里还有打开频道列表等语义。
            // Compose 已消费时本回调通常不会到达；到这里再 return true 会误吞所有页面。
            viewModel.recordMenuPress()
        }
        return super.onKeyUp(keyCode, event)
    }
}

private sealed class InstallPermissionAction {
    data class InstallDownloadedApk(val apkPath: String) : InstallPermissionAction()
}

private class TvBoxViewModelFactory(
    private val activity: ComponentActivity,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val doubanHotRepository = DefaultDoubanHotRepository(
            cache = SharedDoubanHotCache(activity.applicationContext),
        )
        val appSettingsRepository = DataStoreAppSettingsRepository.create(activity.applicationContext)
        return TvBoxViewModel(
            appContext = activity.applicationContext,
            repository = DefaultMovieRepository(),
            doubanHotRepository = doubanHotRepository,
            searchHotWordRepository = DefaultSearchHotWordRepository(
                cache = SharedSearchHotWordCache(activity.applicationContext),
                doubanTitles = {
                    doubanHotRepository.getRecentHot(page = 1).items.map { it.title }
                },
            ),
            platformLiveRepository = EmbeddedPlatformLiveRepository(activity.applicationContext),
            appUpdateRepository = DefaultAppUpdateRepository(activity.applicationContext) {
                appSettingsRepository.getSettings()
            },
            appSettingsRepository = appSettingsRepository,
            playbackHealthRepository = SharedPlaybackHealthRepository(activity.applicationContext),
            historyRepository = SharedHistoryRepository(activity.applicationContext),
            platformLiveFavoritesRepository = SharedPlatformLiveFavoritesRepository(activity.applicationContext),
            liveLinePrefs = LiveLinePreference(activity.applicationContext),
            liveDomainScores = LiveDomainScoreStore(activity.applicationContext),
            liveLineQualityRecords = LiveLineQualityStore(activity.applicationContext),
            liveFavoritesRepository = SharedLiveFavoritesRepository(activity.applicationContext),
        ) as T
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val HOME_BACK_EXIT_INTERVAL_MS = 2000L
private val APK_NAME_SAFE_REGEX = Regex("[^A-Za-z0-9._-]")

