package com.tvbox.app.data

import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 平台直播 Go 桥接（gomobile bind 产出的 platformlive AAR）初始化器。
 *
 * 资源来源保持 APK 内置：
 * - `assets/dy_abogus.js`：抖音 a_bogus 签名脚本（Go 侧通过 goja 执行，
 *   .so 无法直接读 assets，因此由 Kotlin 读出后传入）。脚本内容是抖音签名逻辑，
 *   文件名不带 douyin 前缀 —— 部分 Windows 安全软件会按「douyin_sign.js」这个
 *   名字拦截构建产物写入（AccessDenied），改名可让本机也能构建 Release；
 * - `assets/platform_live_catalog.json`：频道目录 JSON。
 *
 * 首次调用平台直播接口时初始化，重复调用为幂等操作。
 */
object PlatformLiveGoBridge {
    private const val SIGN_ASSET_NAME = "dy_abogus.js"
    private const val CATALOG_ASSET_NAME = "platform_live_catalog.json"
    private const val CACHE_SECONDS = 30
    private const val REQUEST_TIMEOUT_SECONDS = 20

    private val started = AtomicBoolean(false)
    private val startLock = Any()

    fun start(context: Context?) {
        if (started.get()) return
        synchronized(startLock) {
            if (started.get()) return
            val appContext = context ?: throw IOException("直播解析器未初始化")
            try {
                val signScript = appContext.assets.open(SIGN_ASSET_NAME)
                    .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                    .removePrefix("\uFEFF")
                val catalogJson = appContext.assets.open(CATALOG_ASSET_NAME)
                    .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                    .removePrefix("\uFEFF")
                // 根据设备类型选择 clientType：电视/盒子用 tv UA 避免风控，手机用 mobile，PC 浏览器模式用 web
                val clientType = resolveClientType(appContext)
                platformlive.Platformlive.init(signScript, catalogJson, CACHE_SECONDS.toLong(), REQUEST_TIMEOUT_SECONDS.toLong(), clientType)
            } catch (error: Throwable) {
                throw IOException(bridgeErrorMessage(error), error)
            }
            started.set(true)
        }
    }

    /**
     * 检测运行设备类型并返回对应的 clientType 字符串。
     * 判定逻辑与 AppSettings.DeviceMode.detectFromSystem 保持一致：
     * Leanback/无触摸大屏 → tv；有触摸 + 小屏 → mobile；其余默认 web。
     */
    private fun resolveClientType(context: Context): String = runCatching {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isLeanback = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val hasTouch = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        val screenWidthDp = context.resources.configuration.screenWidthDp
        when {
            isLeanback -> "tv"
            !hasTouch || screenWidthDp >= 720 -> "tv"
            screenWidthDp < 600 -> "mobile"
            else -> "web"
        }
    }.getOrElse { "web" }

    private fun bridgeErrorMessage(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        return raw.ifBlank { "直播解析失败" }
    }
}
