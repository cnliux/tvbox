package com.tvbox.app.data

import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 平台直播 Go 桥接（gomobile bind 产出的 platformlive AAR）初始化器。
 *
 * 资源来源保持 APK 内置：
 * - `assets/douyin_sign.js`：抖音 a_bogus 签名脚本（Go 侧通过 goja 执行，
 *   .so 无法直接读 assets，因此由 Kotlin 读出后传入）；
 * - `assets/platform_live_catalog.json`：频道目录 JSON。
 *
 * 首次调用平台直播接口时初始化，重复调用为幂等操作。
 */
object PlatformLiveGoBridge {
    private const val SIGN_ASSET_NAME = "douyin_sign.js"
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
                    .removePrefix("﻿")
                val catalogJson = appContext.assets.open(CATALOG_ASSET_NAME)
                    .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                    .removePrefix("﻿")
                platformlive.Platformlive.init(signScript, catalogJson, CACHE_SECONDS.toLong(), REQUEST_TIMEOUT_SECONDS.toLong())
            } catch (error: Throwable) {
                throw IOException(bridgeErrorMessage(error), error)
            }
            started.set(true)
        }
    }

    private fun bridgeErrorMessage(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        return raw.ifBlank { "直播解析失败" }
    }
}
