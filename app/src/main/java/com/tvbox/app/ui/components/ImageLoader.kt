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
 */
@Composable
fun rememberTvImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context)
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
