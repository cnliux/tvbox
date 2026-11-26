package com.tvbox.app.domain

/**
 * 所有已知的 SharedPreferences 文件注册表（单点事实来源）。
 *
 * 目的：
 * 1. 防止 FactoryReset / Backup / Restore 在新增 prefs 文件时漏掉某个；
 * 2. 把键名收敛为常量，拼错字在编译期就能暴露（调用方用 name 引用而非裸字符串）；
 * 3. 明确标注每个 prefs 文件属于哪个档位、是否参与备份、是否在重置时被擦除。
 *
 * [tier] 表示该文件被哪些 ResetTier 清理；`emptySet()` 表示永不擦除（如长期归档）。
 * [inBackup] 表示备份流程应纳入；[wipedOnReset] 表示 FactoryReset 时应清除。
 *
 * 新增 prefs 文件时，务必在此处登记一行；否则白名单/黑名单类逻辑会静默跳过它。
 */
object PrefsRegistry {

    /** 每条注册的偏好文件 */
    data class Entry(
        /** SharedPreferences 文件名（对应 getSharedPreferences(name) 中的 name） */
        val name: String,
        /** 所属业务模块，便于审查 */
        val module: String,
        /** 此文件会被哪些 ResetTier 的 clearPreferences/clearSources/clearPlaybackHistory 清掉；
         * 空集合 = 永不擦除（仅供审计或日志） */
        val tiers: Set<ResetTier> = emptySet(),
        /** 是否在整机备份范围内 */
        val inBackup: Boolean = true,
        /** 是否在任意档位的重置中被清空 */
        val wipedOnReset: Boolean = true,
    )

    /** 完整清单：按出现顺序登记，不可遗漏。 */
    val entries: List<Entry> = listOf(
        // ── App 主设置（DataStore 迁移源）──────────────────────────────
        Entry("app_settings", "settings",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("app_settings_migration", "settings",
            // 迁移标记文件，跟随主设置一起擦除
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = false, wipedOnReset = true),

        // ── 直播相关 ──
        Entry("live_favorites", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("live_line_prefs", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("live_domain_scores", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        // T7-45：线路质量缓存此前漏登，导致 FactoryReset/备份静默跳过该文件。
        Entry("live_line_quality", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("live_channel_search_history", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("platform_live_favorites", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("platform_live_memory", "live",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("tvbox.live.debug", "debug",
            // 真机调试 prefs，用户态可见；跟随偏好档
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = false, wipedOnReset = true),

        // ── 回放 / 播放健康 ──
        Entry("playback_health", "playback",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("watch_history", "history",
            // watch_history 仅在 All 档擦除（历史归 All）
            tiers = setOf(ResetTier.All),
            inBackup = true, wipedOnReset = true),

        // ── 搜索 ──
        Entry("search_history", "search",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("search_hot_word_cache", "search",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        // ── 视频源 / OTA / 更新 ──
        Entry("background_update", "update",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("ota_cdn_health", "update",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        Entry("douban_hot_cache", "media",
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = true, wipedOnReset = true),

        // ── 法律 / 审计 ──
        Entry("crash_notify", "audit",
            // 崩溃通知已读标志；重置后重设可发提醒
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = false, wipedOnReset = true),

        Entry("tvbox_legal", "legal",
            // 用户协议/隐私政策确认状态；All 档重设后可重新弹窗
            tiers = setOf(ResetTier.PreferencesOnly, ResetTier.PreferencesSourcesNetwork, ResetTier.All),
            inBackup = false, wipedOnReset = true),
    )

    /** 全部 prefs 名称（去重后），供外部只读枚举使用 */
    val allNames: List<String> get() = entries.map { it.name }

    /**
     * S5-30：启动预热。在调用方线程（应为 IO 协程）打开 [names] 对应文件，触发框架的
     * 进程级 SharedPreferences 缓存（同步解析 XML 并载入内存）。启动路径上 `by lazy`
     * 消费者之后首次调用 getSharedPreferences 时直接拿到已预热实例，不再同步读盘。
     * 幂等：文件已被框架缓存时仅是一次 map 查找；单文件失败不影响其余，
     * 预热失败只是回落到 lazy 消费者的按需读盘路径，绝不向调用方传播异常。
     */
    fun warmUp(context: android.content.Context, names: List<String>) {
        val appCtx = context.applicationContext
        for (name in names) {
            try {
                appCtx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            } catch (_: Exception) {
                // 预热失败 → 消费者按需读盘兜底，忽略
            }
        }
    }

    /** 指定档位应清空的 prefs 名称列表 */
    fun namesFor(tier: ResetTier): List<String> =
        entries.filter { entry ->
            entry.wipedOnReset && entry.tiers.contains(tier)
        }.map { it.name }

    /**
     * 对 [context] 中所有 [tier] 对应的 prefs 执行 [android.content.SharedPreferences.edit().clear().commit()]。
     * 单个 prefs 失败不抛出，整体继续。
     */
    @Suppress("unused") // 由 AndroidFactoryResetDeps 在 JVM 上调用
    fun wipe(context: android.content.Context, tier: ResetTier) {
        val appCtx = context.applicationContext
        for (name in namesFor(tier)) {
            try {
                appCtx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    // 这里必须用 commit 而非 apply：wipe 在调用方后台线程执行，
                    // 且「清空后立刻读校验」依赖同步落盘；apply 的异步写会让校验读到旧值。
                    .commit()
            } catch (_: Exception) {
                // 单个 prefs 失败不影响其它
            }
        }
    }

    /** 验证清单与当前代码中的 getSharedPreferences 调用一致；供回归测试使用 */
    fun assertComplete(actualNames: Set<String>) {
        val registered = entries.map { it.name }.toSet()
        val unknown = actualNames - registered
        val missing = registered - actualNames
        if (unknown.isNotEmpty()) {
            throw AssertionError("存在未在 PrefsRegistry 登记的 prefs 文件: $unknown")
        }
        if (missing.isNotEmpty()) {
            throw AssertionError("PrefsRegistry 中存在但未被任何调用方使用的条目: $missing")
        }
    }
}

/**
 * T7-45：单个偏好键的注册表条目（键级事实来源）。
 *
 * 与上面的文件级 [PrefsRegistry.Entry] 互补：文件级回答「哪个 prefs 文件在重置/备份范围内」，
 * 键级回答「这个键叫什么、什么类型、默认值多少、是否敏感、从哪个旧键搬来」。
 * 访问偏好值时必须走 [Prefs.get]，裸字符串在编译期无法发现拼写错。
 */
data class PrefKey<T>(
    val name: String,
    val type: PrefType,
    val default: T,
    /** 该键自哪个版本引入（versionCode 语义，用于迁移判断）。 */
    val since: Int,
    /** 若该键曾重命名，记录旧名以自动搬迁。 */
    val renamedFrom: String? = null,
    /** 敏感键：FactoryReset 必清、导出必打码、日志必红act。 */
    val sensitive: Boolean = false,
)

/** 偏好键的类型标记。 */
enum class PrefType { INT, LONG, STRING, BOOLEAN, FLOAT, STRING_SET }

/**
 * T7-45：键级注册表。所有持久化偏好键在此登记，访问只准通过 [Prefs.get]/[Prefs.put]。
 *
 * 说明：本表与文件级 [PrefsRegistry.entries] 是同一任务的两面。当前仓库的偏好读写
 * 大量经由 SettingsStorageKeys 常量（已收敛），此处补「敏感键 + 迁移」语义；新增键
 * 必须在此登记，否则会被 [PrefsRegistry.assertNoOrphanKeys] 当作孤儿键告警。
 */
object PrefsKeys {

    /** 视频源探针的自定义 UA（敏感：导出/日志需打码）。 */
    val ProbeUa: PrefKey<String> = PrefKey(
        name = "probe.ua_override",
        type = PrefType.STRING,
        default = "",
        since = 43,
        sensitive = true,
    )

    /** 法务条款确认 hash（非敏感，但版本变更需重弹）。 */
    val LegalAckHash: PrefKey<String> = PrefKey(
        name = LegalTerms.HASH_KEY,
        type = PrefType.STRING,
        default = "",
        since = 43,
        sensitive = false,
    )

    /** 遥控设置 seek 步长（毫秒），曾用旧键 "seek_step"。 */
    val SeekStepMs: PrefKey<Int> = PrefKey(
        name = "player.seek_step_ms",
        type = PrefType.INT,
        default = 10_000,
        since = 1,
        renamedFrom = "seek_step",
    )

    /** 遥控 profile schema 版本。 */
    val RemoteProfileV: PrefKey<Int> = PrefKey(
        name = "remote.profile_schema",
        type = PrefType.INT,
        default = 2,
        since = 42,
    )

    /** 全部登记键（供自检遍历）。 */
    val all: List<PrefKey<*>> = listOf(ProbeUa, LegalAckHash, SeekStepMs, RemoteProfileV)

    /** 敏感键集合，供导出/日志打码判断。 */
    val sensitiveNames: Set<String> = all.filter { it.sensitive }.map { it.name }.toSet()

    /** 判断某键是否为已登记键。 */
    fun isRegistered(name: String): Boolean = all.any { it.name == name || it.renamedFrom == name }

    /** 返回某键的规范名（若来自 renamedFrom 则回填规范名）。 */
    fun canonicalName(raw: String): String = all.firstOrNull { it.renamedFrom == raw }?.name ?: raw

    /**
     * T7-45：孤儿键自检。传入从 prefs 文件实际读出的键名集合，
     * 返回其中「既不是当前规范名、也不是已登记旧名」的键。
     *
     * 纯函数、无 Android 依赖，便于在 JVM 单测与 CI 门禁中复用。
     * 调用方应把结果视为告警而非直接删除——孤儿键可能是尚未迁移的历史数据，
     * 直接删会丢用户设置。
     */
    fun assertNoOrphanKeys(observed: Set<String>): Set<String> =
        observed.filterNot { isRegistered(it) }.toSet()

    /**
     * T7-45：把观测到的键值对按 [renamedFrom] 映射为规范名，供搬迁时写入。
     * 同名键原样保留；旧名键改为规范名；未登记键原样保留（交给 [assertNoOrphanKeys] 告警）。
     */
    fun canonicalize(values: Map<String, Any?>): Map<String, Any?> =
        values.mapKeys { (key, _) -> canonicalName(key) }
}
