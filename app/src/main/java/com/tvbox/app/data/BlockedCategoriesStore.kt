package com.tvbox.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class BlockedCategoriesDocument(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val categories: List<String> = emptyList(),
)

/**
 * 被屏蔽的视频源分类，常驻保存在 files/blocked_categories.json。
 * 使用 JSON 文件便于查看、备份与在设备间迁移。
 */
class BlockedCategoriesStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 读取文件中的屏蔽分类；文件不存在或解析失败时返回 null。 */
    fun read(): List<String>? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<BlockedCategoriesDocument>(file.readText())
                .categories
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrNull()
    }

    fun write(categories: List<String>) {
        runCatching {
            val document = BlockedCategoriesDocument(
                categories = categories.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                updatedAtMs = System.currentTimeMillis(),
            )
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(document))
            if (file.exists() && !file.delete()) return@runCatching
            temp.renameTo(file)
        }
    }

    fun filePath(): String = file.absolutePath

    companion object {
        const val FILE_NAME = "blocked_categories.json"
    }
}
