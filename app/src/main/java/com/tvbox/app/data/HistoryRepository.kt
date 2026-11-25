package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.HistoryPolicy
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.isBlockedContent
import com.tvbox.app.domain.monotonicPosition
import com.tvbox.app.domain.trimByVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 观看历史默认保留条数；可由 AppSettings.historyMaxItems 覆盖。 */
const val MAX_HISTORY_ITEMS = 100

// 契约已迁移到 domain；保留同包别名，确保既有 import 与 public API 不变。
typealias HistoryRepository = com.tvbox.app.domain.HistoryRepository

class SharedHistoryRepository internal constructor(
    private val appContext: Context? = null,
    private val testPrefs: android.content.SharedPreferences? = null,
    private val settingsProvider: (() -> AppSettings)? = null,
) : HistoryRepository {
    // 实际使用的 SharedPreferences：测试时由 testPrefs 注入，生产时从 appContext 懒加载。
    private val prefs: android.content.SharedPreferences
        get() = testPrefs ?: run {
            check(appContext != null) { "SharedHistoryRepository needs either a Context or injected test SharedPreferences" }
            appContext!!.applicationContext.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
        }
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 公开构造器（生产用）：接收 Android Context，settingsProvider 默认为 null。
     */
    constructor(context: Context) : this(appContext = context.applicationContext)

    /**
     * 内部构造器：供单元测试直接注入 SharedPreferences 和 settings 探针，规避 Android Context 依赖。
     * [settingsProvider] 返回的 AppSettings 控制策略与 TTL 行为；传 null 时使用全量默认值。
     * 仅在 com.tvbox.app.data 包内可见。
     */
    internal constructor(
        testPrefs: android.content.SharedPreferences,
        settingsProvider: (() -> AppSettings)?,
    ) : this(appContext = null, testPrefs = testPrefs, settingsProvider = settingsProvider)

    /** 读取当前生效的 AppSettings；未注入时回退全量默认值。 */
    private fun currentAppSettings(): AppSettings =
        try { settingsProvider?.invoke() ?: AppSettings() } catch (_: Exception) { AppSettings() }

    override suspend fun getHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock { readHistory() ?: emptyList() }
    }

    override suspend fun saveProgress(
        item: WatchHistoryItem,
        maxItems: Int,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            val settings = currentAppSettings()
            // S1-06 OFF 策略：跳过写入，返回原始列表（不破坏已有记录）。
            if (settings.historyPolicy == HistoryPolicy.OFF) return@withLock history
            // S1-06 访客模式：历史不写。
            if (settings.guestModeActive) return@withLock history
            // #318 兼容旧开关，优先于新策略（用户手动开启的旧开关仍生效）。
            if (settings.historyPrivacyMode) return@withLock history

            // 播放器尚未 READY 时保存会带 duration=0；若直接覆盖，历史进度条会归零且
            // 提前退出时永久丢失总时长。此时沿用同一集已有记录的时长。
            val previousSameEpisode = history.firstOrNull {
                it.apiLineId == item.apiLineId && it.movieId == item.movieId &&
                    (it.episodeIndex == item.episodeIndex || it.episodeUrl == item.episodeUrl)
            }
            val previousSameMovie = history.firstOrNull {
                it.apiLineId == item.apiLineId && it.movieId == item.movieId
            }
            val durationMs = if (item.durationMs > 0L) item.durationMs else previousSameEpisode?.durationMs ?: 0L
            // T5-31：进度单调闸——旧协程/旧记录回写更早进度时，沿用已有值，进度不倒退。
            val positionMs = monotonicPosition(previousSameEpisode, item)
            // #317：addedAt 只在首次加入该影片时写入，后续观看只刷新 updatedAt。
            // 旧数据没有该字段（解码为 0），这里补写当前时间，保证「添加时间」排序可用。
            val nowMs = System.currentTimeMillis()
            val addedAtEpochMs = previousSameMovie?.addedAtEpochMs?.takeIf { it > 0L } ?: nowMs
            // 播放进度保存不携带跨源检查结果：保留同一影片已有的检查信息，避免被回写覆盖。
            val latestEpisodeCount = maxOf(
                item.latestEpisodeCount.coerceAtLeast(0),
                previousSameMovie?.latestEpisodeCount?.coerceAtLeast(0) ?: 0,
            )
            val lastUpdateCheckEpochMs = maxOf(
                item.lastUpdateCheckEpochMs.coerceAtLeast(0L),
                previousSameMovie?.lastUpdateCheckEpochMs?.coerceAtLeast(0L) ?: 0L,
            )
            val updatedItem = item.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAtEpochMs = nowMs,
                addedAtEpochMs = addedAtEpochMs,
                latestEpisodeCount = latestEpisodeCount,
                lastUpdateCheckEpochMs = lastUpdateCheckEpochMs,
            )
            val updated = buildList {
                add(updatedItem)
                history
                    .filterNot { it.apiLineId == item.apiLineId && it.movieId == item.movieId }
                    .forEach(::add)
            }.take(maxItems.coerceAtLeast(1))
                .let { trimByVolume(it) }
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to save watch history"
            }
            updated
        }
    }

    override suspend fun updateEpisodeCheck(
        apiLineId: String,
        movieId: Int,
        latestEpisodeCount: Int,
        checkedAtEpochMs: Long,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            val index = history.indexOfFirst { it.apiLineId == apiLineId && it.movieId == movieId }
            if (index < 0) return@withLock history
            val current = history[index]
            val mergedLatest = maxOf(
                current.latestEpisodeCount.coerceAtLeast(0),
                latestEpisodeCount.coerceAtLeast(0),
            )
            val mergedCheckedAt = maxOf(
                current.lastUpdateCheckEpochMs.coerceAtLeast(0L),
                checkedAtEpochMs.coerceAtLeast(0L),
            )
            if (mergedLatest == current.latestEpisodeCount && mergedCheckedAt == current.lastUpdateCheckEpochMs) {
                return@withLock history
            }
            val updated = history.toMutableList().also {
                it[index] = current.copy(
                    latestEpisodeCount = mergedLatest,
                    lastUpdateCheckEpochMs = mergedCheckedAt,
                )
            }
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to update watch history episode check"
            }
            updated
        }
    }

    override suspend fun clearHistory(): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(prefs.edit().remove(KEY_ITEMS).commit()) { "Unable to clear watch history" }
            emptyList()
        }
    }

    override suspend fun removeHistoryItem(
        apiLineId: String,
        movieId: Int,
    ): List<WatchHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val history = readHistory() ?: return@withLock emptyList()
            val updated = history.filterNot {
                it.apiLineId == apiLineId && it.movieId == movieId
            }
            check(prefs.edit().putString(KEY_ITEMS, json.encodeToString(updated)).commit()) {
                "Unable to remove watch history item"
            }
            updated
        }
    }

    /**
     * 读取历史。返回 null 表示「存量数据存在但解码失败」（数据损坏/字段变更），
     * 调用方必须拒绝用空表覆盖，避免整段历史被误清空。
     *
     * 读取时应用策略与 TTL：
     * - [HistoryPolicy.TITLE_ONLY]：剥离敏感字段后返回匿名化版本。
     * - TTL 过滤：条目超过 [AppSettings.searchHistoryTtlDays] 天则丢弃（0=永不清，默认30天）。
     */
    private fun readHistory(): List<WatchHistoryItem>? {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchHistoryItem>>(raw)
        }.getOrNull()
            ?.filterNot { isBlockedContent(it.movieName, it.typeName, it.remarks, it.episodeTitle) }
            ?.filter { appliesTtl(it) }
            ?.sortedByDescending { it.updatedAtEpochMs }
            ?.map { applyHistoryPolicy(it) }
    }

    /**
     * 判断条目是否命中 TTL 阈值。
     * ttlDays <= 0 表示永不清理；> 0 时以 addedAtEpochMs 为基准计算过期。
     */
    private fun appliesTtl(item: WatchHistoryItem): Boolean {
        val ttlDays = currentAppSettings().searchHistoryTtlDays
        if (ttlDays <= 0) return true
        val ageMs = System.currentTimeMillis() - item.addedAtEpochMs
        val thresholdMs = ttlDays * 86_400_000L
        return ageMs <= thresholdMs
    }

    /**
     * 按当前策略改写条目：TITLE_ONLY 时剥离敏感字段，OFF/FULL 时原样返回。
     */
    private fun applyHistoryPolicy(item: WatchHistoryItem): WatchHistoryItem {
        return when (currentAppSettings().historyPolicy) {
            HistoryPolicy.FULL -> item
            HistoryPolicy.TITLE_ONLY -> item.copy(
                apiLineId = "",
                apiLineName = "",
                episodeUrl = "",
                sourceIndex = 0,
                sourceName = "",
                positionMs = 0L,
            )
            HistoryPolicy.OFF -> item // OFF 模式下不会进入此处（saveProgress 已拦截），防御性保留
        }
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}
