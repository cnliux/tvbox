package com.tvbox.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.tvbox.app.domain.AppSettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 旧版设置来源。仅在首次迁移与迁移失败重试时读取，迁移完成后不再参与常规读取。 */
internal fun interface LegacySettingsSource {
    suspend fun read(): LegacySettingsSnapshot
}

internal class SharedPreferencesLegacySettingsSource(context: Context) : LegacySettingsSource {
    private val prefs = context.applicationContext.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val blockedCategoriesStore = BlockedCategoriesStore(context.applicationContext)

    override suspend fun read(): LegacySettingsSnapshot = withContext(Dispatchers.IO) {
        LegacySettingsSnapshot(
            values = prefs.all,
            blockedCategoriesFromFile = blockedCategoriesStore.read(),
        )
    }

    companion object {
        /** 旧版设置所在的 SharedPreferences 文件名；迁移后原样保留，不主动清理。 */
        const val LEGACY_PREFERENCES_NAME = "app_settings"
    }
}

/**
 * 延迟到首次真正读取时才构造旧设置来源：getSharedPreferences 首次调用会同步解析 XML，
 * 而仓库在启动路径（ViewModel 工厂）上创建；迁移完成后旧来源永不参与读取，不应在启动时打开。
 */
private class DeferredLegacySettingsSource(
    private val appContext: Context,
) : LegacySettingsSource {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedPreferencesLegacySettingsSource(appContext)
    }

    override suspend fun read(): LegacySettingsSnapshot = delegate.read()
}

/**
 * 文件损坏时按空偏好重建：schemaVersion 随之丢失，下次读取会用仍保留的旧数据重跑迁移。
 */
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 基于 DataStore 的单一设置数据源。
 *
 * - 所有写入（迁移、全量保存、测速结果）都经同一把 [Mutex] 串行，等价于单写者 actor，
 *   避免「迁移写回」与「用户刚保存的设置」互相覆盖。
 * - 首次读取发现缺少 [SettingsStorageKeys.SCHEMA_VERSION] 时，从旧 SharedPreferences
 *   （含 `files/blocked_categories.json`）完整迁移，并在同一次原子写入中落 schemaVersion。
 * - 迁移失败（旧数据不可读或 DataStore 写入失败）不写 schemaVersion、不破坏已有内容，
 *   本次直接用旧快照返回设置，下次调用自动重试；期间保存操作会失败而不是写坏新存储。
 */
internal class DataStoreAppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: LegacySettingsSource,
    private val json: Json = legacySettingsJson,
) : AppSettingsRepository {

    private val writeMutex = Mutex()

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val preferences = ensureMigratedLocked()
            if (preferences != null) {
                parseSettings(preferences)
            } else {
                // 迁移尚未完成：直接展示旧快照，保证升级后设置不丢；下次读取继续重试迁移。
                readLegacyFallback()
            }
        }
    }

    override suspend fun saveSettings(settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (ensureMigratedLocked() == null) {
                throw IOException("设置存储尚未完成迁移，本轮写入已跳过")
            }
            dataStore.updateData { current ->
                current.toMutablePreferences().apply {
                    writeSettings(settings, json)
                    markMigrated()
                }
            }
            settings
        }
    }

    override suspend fun getSpeedLatencies(): Map<String, Long?> = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val preferences = ensureMigratedLocked()
            if (preferences != null) {
                decodeLegacySpeedLatencies(
                    preferences.rawSpeedLatencies(),
                    json,
                ) { message, error -> Log.w(TAG, message, error) }
            } else {
                decodeLegacySpeedLatencies(legacySpeedLatenciesRaw(), json) { message, error ->
                    Log.w(TAG, message, error)
                }
            }
        }
    }

    override suspend fun saveSpeedLatencies(latencies: Map<String, Long?>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (ensureMigratedLocked() == null) {
                Log.w(TAG, "设置存储尚未完成迁移，跳过本轮测速结果写入")
                return@withLock
            }
            dataStore.updateData { current ->
                current.toMutablePreferences().apply {
                    putSpeedLatencies(latencies)
                }
            }
        }
    }

    /**
     * 保证数据已迁移，返回可用于读取的偏好快照；返回 null 表示迁移未完成。
     * 调用方必须持有 [writeMutex]。
     */
    private suspend fun ensureMigratedLocked(): Preferences? {
        val current = runCatching { dataStore.data.first() }
            .onFailure { error -> Log.e(TAG, "读取设置存储失败", error) }
            .getOrNull()
            ?: return null

        val version = current.schemaVersion()
        if (version >= SettingsSchema.CURRENT) return current

        val snapshot = runCatching { legacySource.read() }
            .onFailure { error -> Log.e(TAG, "读取旧设置失败，保留原数据并等待下次重试", error) }
            .getOrNull()
            ?: return null
        val settings = readLegacySettings(snapshot, json) { message, error -> Log.w(TAG, message, error) }

        return runCatching {
            dataStore.updateData { stored ->
                stored.toMutablePreferences().apply {
                    writeSettings(settings, json)
                    // 屏蔽分类已并入 DataStore；旧 JSON 文件只作为只读备份保留，不再回写。
                    (snapshot.values[SettingsStorageKeys.SOURCE_SPEED_LATENCIES] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putString(SettingsStorageKeys.SOURCE_SPEED_LATENCIES, it) }
                    markMigrated()
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "设置迁移写入失败，未写入 schemaVersion，将保留旧数据重试", error)
        }.getOrNull()
    }

    private fun parseSettings(preferences: Preferences): AppSettings =
        readLegacySettings(LegacySettingsSnapshot(preferences.toSettingsValues()), json) { message, error ->
            Log.w(TAG, message, error)
        }

    /** 迁移未完成时的只读回退：使用旧快照，失败才退回默认值。 */
    private suspend fun readLegacyFallback(): AppSettings =
        runCatching { legacySource.read() }
            .onFailure { error -> Log.e(TAG, "迁移未完成且旧设置不可读，回退默认设置", error) }
            .map { snapshot -> readLegacySettings(snapshot, json) { message, error -> Log.w(TAG, message, error) } }
            .getOrDefault(AppSettings())

    private suspend fun legacySpeedLatenciesRaw(): String? =
        runCatching { legacySource.read().values[SettingsStorageKeys.SOURCE_SPEED_LATENCIES] as? String }
            .getOrNull()

    companion object {
        private const val TAG = "DataStoreAppSettings"

        fun create(context: Context): DataStoreAppSettingsRepository =
            DataStoreAppSettingsRepository(
                dataStore = context.appSettingsDataStore,
                legacySource = DeferredLegacySettingsSource(context.applicationContext),
            )
    }
}
