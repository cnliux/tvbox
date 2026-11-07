package com.tvbox.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class BlockedCategoriesDocument(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val categories: List<String> = emptyList(),
)

/**
 * 旧版被屏蔽视频源分类文件，常驻保存在 files/blocked_categories.json。
 *
 * 设置迁移到 DataStore 后该文件只作为只读备份保留：仅在首次迁移时读取，
 * 用于从旧版本恢复数据，不再写回。
 */
class BlockedCategoriesStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val json = Json {
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

    companion object {
        const val FILE_NAME = "blocked_categories.json"
    }
}
