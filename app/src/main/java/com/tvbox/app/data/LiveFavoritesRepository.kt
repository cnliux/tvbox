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
                val legacyKey = legacyLiveFavoriteKey(groupName, channelName)
                val current = readKeys().toMutableSet()
                // 兼容旧版「分组#频道名」键：读取时两种键都认，写入统一升级为新键。
                val removedNew = current.remove(key)
                val removedLegacy = current.remove(legacyKey)
                if (!removedNew && !removedLegacy) current += key
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

/**
 * 电视直播频道收藏定位键：分组名长度前缀 + 分组名 + 频道名。
 * 旧键 `"$groupName#$channelName"` 在分组名或频道名自身含 `#` 时会撞键
 * （`("A#B","C")` 与 `("A","B#C")` 都得到 `A#B#C`），长度前缀可消除歧义。
 */
fun liveFavoriteKey(groupName: String, channelName: String): String =
    "${groupName.length}:$groupName#$channelName"

/** 旧版收藏键，仅用于读取历史数据时兼容，不再新写入。 */
fun legacyLiveFavoriteKey(groupName: String, channelName: String): String = "$groupName#$channelName"

/** 收藏判断统一入口：同时兼容新旧键，避免升级后旧收藏被判为未收藏。 */
fun isLiveFavorite(keys: Set<String>, groupName: String, channelName: String): Boolean =
    keys.contains(liveFavoriteKey(groupName, channelName)) ||
        keys.contains(legacyLiveFavoriteKey(groupName, channelName))
