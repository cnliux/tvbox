package com.tvbox.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

/** Fetches a remote configuration text over plain HTTP(S), used by the paste/URL import dialog. */
internal class SourceConfigFetcher {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15L, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val normalized = url.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            throw IOException("订阅地址必须以 http:// 或 https:// 开头")
        }
        // 与 TVBox Decoder 同口径：跟随重定向、UA 标识、BOM 去除、20MB 上限。
        val request = okhttp3.Request.Builder()
            .url(normalized)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) TVBox-Android/1.3")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("获取订阅失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("订阅内容为空")
            val stream = body.byteStream()
            var buffer = ByteArray(16_384)
            var size = 0
            var bufferOut = java.io.ByteArrayOutputStream()
            if (body.contentLength() > 0) bufferOut = java.io.ByteArrayOutputStream(body.contentLength().toInt())
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
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES = 20L * 1024 * 1024
    }
}
