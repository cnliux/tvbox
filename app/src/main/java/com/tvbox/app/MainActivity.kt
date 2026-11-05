package com.tvbox.app

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.util.Log
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import com.tvbox.app.data.DefaultAppUpdateRepository
import com.tvbox.app.data.EmbeddedPlatformLiveRepository
import com.tvbox.app.data.DefaultDoubanHotRepository
import com.tvbox.app.data.DefaultMovieRepository
import com.tvbox.app.data.DefaultSearchHotWordRepository
import com.tvbox.app.data.LiveLinePreference
import com.tvbox.app.data.SharedAppSettingsRepository
import com.tvbox.app.data.SharedDoubanHotCache
import com.tvbox.app.data.SharedHistoryRepository
import com.tvbox.app.data.SharedPlaybackHealthRepository
import com.tvbox.app.data.SharedPlatformLiveFavoritesRepository
import com.tvbox.app.data.SharedSearchHotWordCache
import com.tvbox.app.domain.DeviceMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tvbox.app.ui.TvBoxApp
import com.tvbox.app.ui.TvBoxViewModel
import com.tvbox.app.ui.theme.TVBoxTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: TvBoxViewModel by viewModels {
        TvBoxViewModelFactory(this)
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机模式默认竖屏、电视模式横屏：先同步读取已保存的设备模式，避免冷启动闪一下错误方向。
        applyBaseOrientation(readSavedDeviceMode())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 订阅"生效模式"而非保存的偏好：Auto 模式下按系统识别结果切换横竖屏。
                viewModel.state.map { it.effectiveDeviceMode }.distinctUntilChanged()
                    .collect(::applyBaseOrientation)
            }
        }
        pendingExportApkPath = savedInstanceState?.getString("pendingExportApk")
        pendingInstallPermissionAction = savedInstanceState?.getString("pendingUpdateApk")
            ?.let { InstallPermissionAction.InstallDownloadedApk(it) }
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
                theme = state.appSettings.theme,
                fontScale = state.appSettings.fontScale,
            ) {
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
                )
            }
        }
    }

    private fun readSavedDeviceMode(): DeviceMode {
        // 未保存过（或保存为 auto）时按系统特征解析，避免手机冷启动先闪横屏。
        val saved = DeviceMode.fromStorageKey(
            getSharedPreferences("app_settings", MODE_PRIVATE).getString("device_mode", null),
        )
        return if (saved == DeviceMode.Auto) DeviceMode.detectFromSystem(applicationContext) else saved
    }

    private fun applyBaseOrientation(mode: DeviceMode) {
        requestedOrientation = if (mode == DeviceMode.Mobile) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // 手机模式使用 edge-to-edge 布局，由各页面自行吸收状态栏/导航栏安全区；
        // 电视模式保持原有的系统避让行为，避免盒子端画面被系统栏挤压。
        WindowCompat.setDecorFitsSystemWindows(window, mode != DeviceMode.Mobile)
    }

    private fun saveUpdateApk(apkPath: String) {
        if (!viewModel.beginUpdateApkExport(apkPath)) return
        pendingInstallPermissionAction = null
        pendingExportApkPath = apkPath
        val version = viewModel.state.value.availableUpdate?.versionName.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
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
        viewModel.startUpdateDownload()
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
        openSystemInstaller(apkFile)
    }

    @Suppress("DEPRECATION")
    private fun openSystemInstaller(apkFile: File) {
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

    override fun onDestroy() {
        super.onDestroy()
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
            appUpdateRepository = DefaultAppUpdateRepository(activity.applicationContext),
            appSettingsRepository = SharedAppSettingsRepository(activity.applicationContext),
            playbackHealthRepository = SharedPlaybackHealthRepository(activity.applicationContext),
            historyRepository = SharedHistoryRepository(activity.applicationContext),
            platformLiveFavoritesRepository = SharedPlatformLiveFavoritesRepository(activity.applicationContext),
            liveLinePrefs = LiveLinePreference(activity.applicationContext),
        ) as T
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val HOME_BACK_EXIT_INTERVAL_MS = 2000L

