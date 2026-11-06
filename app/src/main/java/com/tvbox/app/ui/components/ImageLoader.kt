package com.tvbox.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

/**
 * 为 TV 设备优化的 Coil ImageLoader，限制内存与磁盘缓存上限，
 * 避免低内存 TV 盒子 OOM，同时保留合理的磁盘缓存命中率。
 *
 * 必须是进程级单例：列表卡片内部也会调用本函数，若按 item 各自 `remember`
 * 会为每张海报新建一份内存缓存/磁盘缓存与请求引擎，低内存设备容易 OOM，
 * 同一目录重复打开 DiskCache 也可能触发锁冲突。
 */
@Composable
fun rememberTvImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { TvImageLoaderHolder.get(context) }
}

private object TvImageLoaderHolder {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: buildTvImageLoader(context.applicationContext).also { instance = it }
    }

    private fun buildTvImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                // 最多 25% 可用内存，硬上限 64MB（TV 盒子通常 2~4GB RAM）
                val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                    ?.memoryClass ?: 256
                val maxBytes = (memoryClass * 1024 * 1024 * 0.25).toInt().coerceAtMost(64 * 1024 * 1024)
                MemoryCache.Builder(context)
                    .maxSizeBytes(maxBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256 * 1024 * 1024) // 硬上限 256MB
                    .build()
            }
            .crossfade(false) // TV 场景关闭 crossfade，减少 GPU 压力
            .build()
    }
}
