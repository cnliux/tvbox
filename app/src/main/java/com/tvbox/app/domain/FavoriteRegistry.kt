package com.tvbox.app.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException

/**
 * S3-18：三份独立收藏的统一登记与备份。
 *
 * 现状是 `LiveFavoritesRepository`（电视直播频道收藏）、`SharedPlatformLiveFavoritesRepository`
 * （平台直播间收藏）与屏蔽分类（旧 `BlockedCategoriesStore` 文件，现由 DataStore 的
 * `blocked_video_categories` 键承载）各自为政，导出/备份容易漏项。本注册表把四类收藏
 * 收拢到一个统一的 kind + id 视图上：
 *
 * - [FavoriteKind.MOVIE]：预留。当前应用没有电影收藏存储（[MOVIE_PREFS_NAME] 尚不存在），
 *   导出恒为空；导入会按同样结构落盘，供未来电影收藏实现直接对接，无需再改注册表。
 * - [FavoriteKind.LIVE_CHANNEL]：`live_favorites` 偏好的键集合（`分组名长度:分组名#频道名`，
 *   与 [liveFavoriteKey] 同格式；注册表只透传键字符串，不做拆解读义）。
 * - [FavoriteKind.LIVE_ROOM]：`platform_live_favorites` 偏好里的房间记录，id 为
 *   `site(小写):roomId`（与 FavoriteRoomRecord.toRoom 的 [com.tvbox.app.domain.PlatformLiveRoom.id] 一致）。
 * - [FavoriteKind.BLOCKED_CATEGORY]：DataStore `app_settings` 的 `blocked_video_categories`
 *   键（JSON 字符串数组）；读不到时回退旧版 SharedPreferences 同名键。
 *
 * 导入语义是**并集合并**（只增不删、不覆盖本机已有的更丰富记录）；`addedAtMs` 各存储均
 * 未记录加入时间，导出统一为 0（未知），导入忽略该字段。
 * 本对象的所有方法都是同步的（SharedPreferences + DataStore 一次性快照读），供备份/恢复
 * 流程调用；不要在高频渲染路径上使用。
 */
enum class FavoriteKind {
    MOVIE,
    LIVE_CHANNEL,
    LIVE_ROOM,
    BLOCKED_CATEGORY,
}

data class FavoriteEntry(
    val kind: FavoriteKind,
    val id: String,
    val addedAtMs: Long = 0L,
)

object FavoriteRegistry {
    private const val FORMAT = "tvbox-favorites"
    private const val VERSION = 1

    // —— 与既有存储的键名保持一致（改名前先改这里）——
    /** 与 LiveFavoritesRepository 的「live_favorites」偏好同名。 */
    private const val LIVE_CHANNEL_PREFS_NAME = "live_favorites"
    private const val LIVE_CHANNEL_KEYS = "keys"
    /** 与 SharedPlatformLiveFavoritesRepository 的「platform_live_favorites」偏好同名。 */
    private const val LIVE_ROOM_PREFS_NAME = "platform_live_favorites"
    private const val LIVE_ROOM_RECORDS = "rooms"
    /** 预留：电影收藏尚无生产实现，导入时先按此结构落盘。 */
    private const val MOVIE_PREFS_NAME = "movie_favorites"
    private const val MOVIE_KEYS = "keys"
    /** DataStore 键名与 SettingsStorageKeys.BLOCKED_VIDEO_CATEGORIES 保持一致（不跨层引用其 internal 常量）。 */
    private const val BLOCKED_CATEGORY_KEY = "blocked_video_categories"
    /** 旧版设置所在的 SharedPreferences 文件名；迁移完成后原样保留，作为 DataStore 缺失时的回退源。 */
    private const val LEGACY_SETTINGS_PREFS_NAME = "app_settings"

    private val payloadJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // 与 DataStoreAppSettingsRepository 同一份「app_settings」DataStore：1.3.0 的
    // preferencesDataStore 委托按 (name) 共享单例，这里不会为备份流程新开后台实例。
    private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
        name = LEGACY_SETTINGS_PREFS_NAME,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

    /**
     * 统一导出：四类收藏的 kind + id 列表，单一 JSON 文本。
     * 输出按 (kind, id) 排序，同一台机器两次导出逐字节一致（备份幂等）。
     */
    fun exportAll(ctx: Context): String {
        val app = ctx.applicationContext
        val entries = buildList {
            addAll(prefsKeyList(app, MOVIE_PREFS_NAME, MOVIE_KEYS).map { FavoriteEntry(FavoriteKind.MOVIE, it) })
            addAll(prefsKeyList(app, LIVE_CHANNEL_PREFS_NAME, LIVE_CHANNEL_KEYS).map { FavoriteEntry(FavoriteKind.LIVE_CHANNEL, it) })
            addAll(liveRoomIds(app).map { FavoriteEntry(FavoriteKind.LIVE_ROOM, it) })
            addAll(blockedCategories(app).map { FavoriteEntry(FavoriteKind.BLOCKED_CATEGORY, it) })
        }
        val body = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            putJsonArray("entries") {
                entries
                    .distinct()
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
                    .forEach { entry ->
                        add(buildJsonObject {
                            put("kind", entry.kind.name)
                            put("id", entry.id)
                            put("addedAtMs", entry.addedAtMs)
                        })
                    }
            }
        }
        return body.toString()
    }

    /**
     * 统一导入：把 [json] 里四类收藏按**并集合并**写回各存储（只增不删）。
     * 未知 kind / 空 id 的条目跳过（前向兼容）；LIVE_ROOM 只补最小记录
     * （site/roomId 由 id 拆出，其余展示字段留空），已有记录不被覆盖。
     * 返回实际新增的条目数；JSON 根结构非法时抛 [IOException]。
     */
    fun importAll(ctx: Context, json: String): Int {
        val app = ctx.applicationContext
        val root = runCatching { payloadJson.parseToJsonElement(json.trim()) }.getOrElse {
            throw IOException("收藏 JSON 解析失败", it)
        }
        val entries = when (root) {
            is JsonObject -> root["entries"] as? JsonArray ?: emptyList()
            is JsonArray -> root
            else -> throw IOException("收藏 JSON 根节点不是对象或数组")
        }
        val byKind = entries.groupByKind()
        var imported = 0
        val movie = byKind.getOrDefault(FavoriteKind.MOVIE, emptyList())
        if (movie.isNotEmpty()) imported += mergeKeyList(app, MOVIE_PREFS_NAME, MOVIE_KEYS, movie)
        val liveChannels = byKind.getOrDefault(FavoriteKind.LIVE_CHANNEL, emptyList())
        if (liveChannels.isNotEmpty()) imported += mergeKeyList(app, LIVE_CHANNEL_PREFS_NAME, LIVE_CHANNEL_KEYS, liveChannels)
        val liveRooms = byKind.getOrDefault(FavoriteKind.LIVE_ROOM, emptyList())
        if (liveRooms.isNotEmpty()) imported += mergeLiveRooms(app, liveRooms)
        val blocked = byKind.getOrDefault(FavoriteKind.BLOCKED_CATEGORY, emptyList())
        if (blocked.isNotEmpty()) imported += mergeBlockedCategories(app, blocked)
        return imported
    }

    /** 四类收藏的本机数量（含 0 值，便于备份/设置页展示）。 */
    fun countByKind(ctx: Context): Map<FavoriteKind, Int> {
        val app = ctx.applicationContext
        return mapOf(
            FavoriteKind.MOVIE to prefsKeyList(app, MOVIE_PREFS_NAME, MOVIE_KEYS).size,
            FavoriteKind.LIVE_CHANNEL to prefsKeyList(app, LIVE_CHANNEL_PREFS_NAME, LIVE_CHANNEL_KEYS).size,
            FavoriteKind.LIVE_ROOM to liveRoomIds(app).size,
            FavoriteKind.BLOCKED_CATEGORY to blockedCategories(app).size,
        )
    }

    // ------------------------------------------------------------ 各存储的读/写（键名与既有实现一致）

    /** 读 SharedPreferences 里的 JSON 字符串数组（live_favorites / movie_favorites 的 keys 格式）。 */
    private fun prefsKeyList(app: Context, prefsName: String, key: String): List<String> {
        val raw = runCatching {
            app.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null).orEmpty()
        }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        val decoded = runCatching {
            payloadJson.decodeFromString<List<String>>(raw)
        }.getOrNull() ?: return emptyList()
        return decoded.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** 把 ids 并集合并进「JSON 字符串数组」偏好，返回新增条数。 */
    private fun mergeKeyList(app: Context, prefsName: String, key: String, ids: List<String>): Int {
        val current = prefsKeyList(app, prefsName, key)
        val fresh = ids.filter { it.isNotBlank() }.distinct().filterNot { it in current }
        if (fresh.isEmpty()) return 0
        val updated = (current + fresh).distinct()
        val ok = runCatching {
            app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(key, payloadJson.encodeToString(updated))
                .commit()
        }.isSuccess
        if (!ok) throw IOException("收藏写入失败（$prefsName）")
        return fresh.size
    }

    /** 平台直播房间 id 集合（`site(小写):roomId`，与房间记录的 id 字段一致）。 */
    private fun liveRoomIds(app: Context): List<String> {
        val raw = runCatching {
            app.getSharedPreferences(LIVE_ROOM_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LIVE_ROOM_RECORDS, null).orEmpty()
        }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        val records = runCatching {
            payloadJson.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()
        return records
            .mapNotNull { element ->
                val obj = element.jsonObject
                val site = obj["site"]?.asString()?.trim().orEmpty()
                val roomId = obj["roomId"]?.asString()?.trim().orEmpty()
                if (site.isBlank() || roomId.isBlank()) null else "${site.lowercase()}:$roomId"
            }
            .distinct()
    }

    /**
     * 房间收藏并集导入：只为尚不存在的 id 补最小记录（展示字段留空），
     * 已有记录（含更丰富的标题/封面）原样保留。返回新增条数。
     */
    private fun mergeLiveRooms(app: Context, ids: List<String>): Int {
        val existing = liveRoomIds(app)
        val raw = runCatching {
            app.getSharedPreferences(LIVE_ROOM_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LIVE_ROOM_RECORDS, null).orEmpty()
        }.getOrDefault("")
        val existingRecords = runCatching {
            if (raw.isBlank()) emptyList() else payloadJson.parseToJsonElement(raw).jsonArray
        }.getOrDefault(emptyList())
        val fresh = ids.filter { it.isNotBlank() }.distinct().filterNot { it in existing }
        if (fresh.isEmpty()) return 0
        val added = buildJsonArray {
            fresh.forEach { id ->
                val separator = id.indexOf(':')
                // id 无冒号时 site 为空 → 房间记录会被现有读端过滤掉，属于脏数据，直接跳过。
                if (separator <= 0) return@forEach
                add(buildJsonObject {
                    put("site", id.substring(0, separator).lowercase())
                    put("roomId", id.substring(separator + 1))
                    put("title", "")
                    put("anchor", "")
                    put("cover", "")
                    put("categoryId", "")
                    put("categoryName", "")
                })
            }
        }
        if (added.isEmpty()) return 0
        val merged = buildJsonArray {
            existingRecords.forEach { add(it) }
            added.forEach { add(it) }
        }
        val ok = runCatching {
            app.getSharedPreferences(LIVE_ROOM_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(LIVE_ROOM_RECORDS, payloadJson.encodeToString(merged))
                .commit()
        }.isSuccess
        if (!ok) throw IOException("收藏写入失败（$LIVE_ROOM_PREFS_NAME）")
        return added.size
    }

    /** 屏蔽分类：优先 DataStore（现行来源），读不到再回退旧版 SharedPreferences stringList。 */
    private fun blockedCategories(app: Context): List<String> {
        val fromDataStore = runCatching {
            runBlocking(Dispatchers.IO) {
                app.settingsDataStore.data.first()
                    .let { it[stringPreferencesKey(BLOCKED_CATEGORY_KEY)] }
                    ?.let(::decodeStringList)
            }
        }.getOrNull()
        if (!fromDataStore.isNullOrEmpty()) return fromDataStore
        return runCatching {
            app.getSharedPreferences(LEGACY_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(BLOCKED_CATEGORY_KEY, null)
                ?.let(::decodeStringList)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** 屏蔽分类并集导入：写回 DataStore（现行来源），并同步旧版偏好（未迁移设备仍读它）。 */
    private fun mergeBlockedCategories(app: Context, ids: List<String>): Int {
        val current = blockedCategories(app)
        val fresh = ids.filter { it.isNotBlank() }.distinct().filterNot { it in current }
        if (fresh.isEmpty()) return 0
        val updated = (current + fresh).distinct()
        runCatching {
            runBlocking(Dispatchers.IO) {
                app.settingsDataStore.edit { prefs ->
                    prefs[stringPreferencesKey(BLOCKED_CATEGORY_KEY)] =
                        payloadJson.encodeToString(updated)
                }
            }
        }.onFailure { throw IOException("屏蔽分类写入失败", it) }
        runCatching {
            app.getSharedPreferences(LEGACY_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BLOCKED_CATEGORY_KEY, payloadJson.encodeToString(updated))
                .apply()
        }
        return fresh.size
    }

    /** 兼容两种历史形态：JSON 数组字符串（DataStore/迁移值）与逗号分隔（旧 SharedPreferences stringList 展开）。 */
    private fun decodeStringList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("[")) {
            return trimmed.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
        return runCatching { payloadJson.decodeFromString<List<String>>(trimmed) }
            .getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
    }

    // ------------------------------------------------------------ JSON 条目解析

    /** 把 entries 数组按 kind 分组（未知 kind / 空 id / 非对象条目一律跳过）。 */
    private fun List<kotlinx.serialization.json.JsonElement>.groupByKind(): Map<FavoriteKind, List<String>> {
        val result = mutableMapOf<FavoriteKind, List<String>>()
        forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val kindName = obj["kind"]?.asString()?.trim()?.uppercase().orEmpty()
            val id = obj["id"]?.asString()?.trim().orEmpty()
            if (id.isBlank()) return@forEach
            val kind = runCatching { FavoriteKind.valueOf(kindName) }.getOrNull() ?: return@forEach
            result[kind] = (result[kind].orEmpty() + id).distinct()
        }
        return result
    }

    /** 安全取字符串：非 JsonPrimitive（对象/数组/null）返回 null 而不抛异常。 */
    private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content
}
