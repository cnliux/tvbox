package com.tvbox.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.NetworkRetryPolicy
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.domain.parseLiveSourceEndpoint
import com.tvbox.app.domain.withFallbackUserAgent
import java.io.IOException

/** Saves plain-text config exports to the public Downloads folder. */
internal class TextFileExporter(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun save(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TVBox")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("系统下载目录不可用，请检查存储空间")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw IOException("无法写入下载目录")
                val finished = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (resolver.update(uri, finished, null, null) <= 0) {
                    throw IOException("导出未能完成，请重试")
                }
                "下载/TVBox/$fileName"
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TVBox",
            )
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("无法创建下载目录，请检查存储权限")
            }
            var outputFile = File(directory, fileName)
            var suffix = 1
            while (!outputFile.createNewFile()) {
                val baseName = fileName.substringBeforeLast(".json").ifBlank { fileName }
                outputFile = File(directory, "$baseName (${suffix++}).json")
            }
            try {
                outputFile.writeText(content, Charsets.UTF_8)
                outputFile.absolutePath
            } catch (error: Exception) {
                outputFile.delete()
                throw error
            }
        }
    }
}

/** 订阅文本拉取的超时口径：沿用既有 60s 读 / 120s 整次调用预算（不是 [com.tvbox.app.data.network.Timeouts] 五档场景之一）。 */
private const val SOURCE_CONFIG_READ_TIMEOUT_MS = 60_000L
private const val SOURCE_CONFIG_CALL_TIMEOUT_MS = 120_000L

/** Fetches a remote configuration text over plain HTTP(S), used by the paste/URL import dialog. */
internal class SourceConfigFetcher {
    private val client = HttpClients.default.newBuilder()
        // 订阅文本可能较大且允许慢速，沿用既有读超时并给整次调用更长上限。
        .readTimeout(SOURCE_CONFIG_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(SOURCE_CONFIG_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val normalized = url.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            throw IOException("订阅地址必须以 http:// 或 https:// 开头")
        }
        // 订阅地址支持行内头部参数（`url|User-Agent=xxx`）：行内值优先，其次全局自定义 UA，
        // 最后回退与 TVBox Decoder 同口径的内置 UA。
        val endpoint = com.tvbox.app.domain.parseLiveSourceEndpoint(normalized)
        val headers = endpoint.headers.withFallbackUserAgent(
            com.tvbox.app.data.network.GlobalUserAgent.value,
        )
        val userAgent = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: "Mozilla/5.0 (Linux; Android 12) TVBox-Android/1.3"
        val otherHeaders = headers.filter { !it.key.equals("User-Agent", ignoreCase = true) }
        // 与 TVBox Decoder 同口径：跟随重定向、UA 标识、BOM 去除、20MB 上限。
        val request = okhttp3.Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", userAgent)
            .apply {
                otherHeaders.forEach { (key, value) -> header(key, value) }
            }
            .build()
        try {
            client.executeCancellable(request, NetworkRetryPolicy.IdempotentGet).use { response ->
                val body = response.body
                val stream = body.byteStream()
                var buffer = ByteArray(16_384)
                var size = 0
                var bufferOut = java.io.ByteArrayOutputStream()
                // 预分配按 20MB 上限截断：服务端 Content-Length 可能虚报成几百 MB/GB，
                // 直接按它预分配会先 OOM，等不到下面的大小校验。
                if (body.contentLength() > 0) {
                    bufferOut = java.io.ByteArrayOutputStream(
                        body.contentLength().coerceAtMost(MAX_PAYLOAD_BYTES).toInt(),
                    )
                }
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    size += read
                    if (size > MAX_PAYLOAD_BYTES) {
                        throw IOException("订阅内容超过 20MB，请检查订阅地址")
                    }
                    bufferOut.write(buffer, 0, read)
                }
                var data = bufferOut.toByteArray()
                if (data.size >= 3 &&
                    data[0].toInt() == 0xEF && data[1].toInt() == 0xBB && data[2].toInt() == 0xBF
                ) {
                    val stripped = ByteArray(data.size - 3)
                    System.arraycopy(data, 3, stripped, 0, stripped.size)
                    data = stripped
                }
                String(data, Charsets.UTF_8)
            }
        } catch (error: NetworkException.Http) {
            throw IOException("获取订阅失败：HTTP ${error.statusCode}", error)
        }
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 20L * 1024 * 1024
    }
}
