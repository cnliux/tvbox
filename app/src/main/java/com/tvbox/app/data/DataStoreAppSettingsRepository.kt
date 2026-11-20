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
 * 迁移完成标记保存在 DataStore 之外：DataStore 损坏/被清空后 schemaVersion 丢失，
 * 也不会再次读取并回放旧设置。标记只在旧数据成功写入后落盘，单向不回退。
 */
internal interface LegacySettingsMigrationMarker {
    fun isMigrated(): Boolean
    fun markMigrated()
}

/** 生产实现：独立 SharedPreferences 文件，DataStore 重置不会影响该标记。 */
internal class SharedPreferencesLegacySettingsMigrationMarker(context: Context) :
    LegacySettingsMigrationMarker {
    // lazy：避免在启动路径（ViewModel 工厂）上同步解析 XML。
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.applicationContext.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun isMigrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    override fun markMigrated() {
        if (!prefs.edit().putBoolean(KEY_MIGRATED, true).commit()) {
            throw IOException("迁移完成标记写入失败")
        }
    }

    private companion object {
        const val MIGRATION_PREFS_NAME = "app_settings_migration"
        const val KEY_MIGRATED = "migrated"
    }
}

/** 直接构造仓库时用的进程内标记；生产入口 [DataStoreAppSettingsRepository.create] 使用持久化实现。 */
internal class InMemoryLegacySettingsMigrationMarker : LegacySettingsMigrationMarker {
    @Volatile
    private var migrated = false

    override fun isMigrated(): Boolean = migrated
    override fun markMigrated() {
        migrated = true
    }
}

/**
 * 文件损坏时按空偏好重建：schemaVersion 随之丢失，是否重跑迁移由外部标记决定。
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
 * - 迁移成功后在 DataStore 外落一次性标记；DataStore 被重置/损坏时按当前内容重建 schema，
 *   绝不再次读取旧设置，避免旧默认值反复回放。
 */
internal class DataStoreAppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val legacySource: LegacySettingsSource,
    private val json: Json = legacySettingsJson,
    private val migrationMarker: LegacySettingsMigrationMarker = InMemoryLegacySettingsMigrationMarker(),
) : AppSettingsRepository {

    private val writeMutex = Mutex()

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        // 已迁移且 schema 完整：直接读，不与写操作互斥，避免高频读被迁移/保存写操作阻塞。
        val direct = runCatching { dataStore.data.first() }.getOrNull()
        if (migrationMarker.isMigrated() && direct != null && direct.schemaVersion() >= SettingsSchema.CURRENT) {
            return@withContext parseSettings(direct)
        }
        // 其余情况走锁内路径：未迁移则迁移；已迁移但 schema 缺失（存储被清空/损坏）则按当前内容重建。
        writeMutex.withLock {
            val preferences = ensureMigratedLocked()
            if (preferences != null) {
                parseSettings(preferences)
            } else if (migrationMarker.isMigrated()) {
                // 已迁移但 DataStore 暂不可读：不回读旧数据，按默认值展示。
                AppSettings()
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
        if (migrationMarker.isMigrated()) {
            runCatching { decodeLegacySpeedLatencies(dataStore.data.first().rawSpeedLatencies(), json) { _, _ -> } }.getOrElse { emptyMap() }
        } else {
            writeMutex.withLock {
                val preferences = ensureMigratedLocked()
                if (preferences != null) {
                    decodeLegacySpeedLatencies(
                        preferences.rawSpeedLatencies(),
                        json,
                    ) { message, error -> Log.w(TAG, message, error) }
                } else if (migrationMarker.isMigrated()) {
                    // 已完成迁移就不再从旧偏好补测速结果。
                    emptyMap()
                } else {
                    decodeLegacySpeedLatencies(legacySpeedLatenciesRaw(), json) { message, error ->
                        Log.w(TAG, message, error)
                    }
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
        if (version >= SettingsSchema.CURRENT) {
            // 兼容已迁移但尚无外部标记的旧安装：只补写标记，不触碰旧数据。
            if (!migrationMarker.isMigrated()) {
                runCatching { migrationMarker.markMigrated() }
                    .onFailure { error -> Log.e(TAG, "补写迁移完成标记失败", error) }
            }
            return current
        }

        if (migrationMarker.isMigrated()) {
            // DataStore 被重置/损坏：标记阻止旧设置重放，按当前内容重建 schema 后直接返回。
            Log.w(TAG, "设置存储缺少 schemaVersion，但迁移标记已存在，跳过旧设置回放")
            return runCatching {
                dataStore.updateData { stored ->
                    stored.toMutablePreferences().apply {
                        writeSettings(parseSettings(stored), json)
                        markMigrated()
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "重建设置 schema 失败", error)
            }.getOrNull()
        }

        val snapshot = runCatching { legacySource.read() }
            .onFailure { error -> Log.e(TAG, "读取旧设置失败，保留原数据并等待下次重试", error) }
            .getOrNull()
            ?: return null
        val settings = readLegacySettings(snapshot, json) { message, error -> Log.w(TAG, message, error) }

        val migrated = runCatching {
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
        }.getOrNull() ?: return null

        // 标记必须在成功写入之后才落盘；失败保留未标记状态，下次读取可补写重试。
        runCatching { migrationMarker.markMigrated() }
            .onFailure { error -> Log.e(TAG, "写入迁移完成标记失败，下次读取将补写", error) }
        return migrated
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
                migrationMarker = SharedPreferencesLegacySettingsMigrationMarker(context.applicationContext),
            )
    }
}
