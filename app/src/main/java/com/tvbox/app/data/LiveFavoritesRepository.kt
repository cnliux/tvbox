package com.tvbox.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface LiveFavoritesRepository {
    /** 收藏键集合，键格式见 [liveFavoriteKey]。 */
    suspend fun getFavoriteKeys(): Set<String>
    /** 收藏/取消收藏，返回操作后的全量键集合。 */
    suspend fun toggleFavorite(groupName: String, channelName: String): Set<String>
}

/** 电视直播频道收藏：SharedPreferences + JSON 字符串数组（与平台直播收藏同风格）。 */
class SharedLiveFavoritesRepository(context: Context) : LiveFavoritesRepository {
    // lazy：getSharedPreferences 首次调用会同步解析 XML，避免在启动路径（ViewModel 工厂）上读盘。
    private val prefs by lazy { context.applicationContext.getSharedPreferences("live_favorites", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getFavoriteKeys(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { readKeys() }
    }

    override suspend fun toggleFavorite(groupName: String, channelName: String): Set<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = liveFavoriteKey(groupName, channelName)
                val current = readKeys().toMutableSet()
                if (!current.remove(key)) current += key
                if (!prefs.edit().putString(KEY_KEYS, json.encodeToString(current.toList())).commit()) {
                    throw java.io.IOException("直播收藏保存失败")
                }
                current
            }
        }

    private fun readKeys(): Set<String> {
        val raw = prefs.getString(KEY_KEYS, null).orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .toSet()
    }

    private companion object {
        const val KEY_KEYS = "keys"
    }
}

/** 电视直播频道收藏定位键：分组名 + 频道名（不同分组同名频道互不干扰）。 */
fun liveFavoriteKey(groupName: String, channelName: String): String = "$groupName#$channelName"
