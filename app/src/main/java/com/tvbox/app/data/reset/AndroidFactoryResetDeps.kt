package com.tvbox.app.data.reset

import android.content.Context
import com.tvbox.app.domain.FactoryResetDeps
import com.tvbox.app.domain.PrefsRegistry
import com.tvbox.app.domain.ResetTier
import java.io.File

/**
 * 恢复出厂设置的白名单路径策略（纯 Kotlin，不依赖 Android Context，可独立单测）。
 *
 * 安全边界：
 * - 只解析本对象明确列出的白名单路径，绝不递归扫根目录；
 * - [isUnder] 确保目标位于指定根（filesDir/cacheDir）之下，越界路径被拒绝。
 */
object ResetPathPolicy {

    /** 共享偏好文件名白名单，统一来源于 [PrefsRegistry]；同步更新时只需改 PrefsRegistry。 */
    val SHARED_PREFS_NAMES: List<String> get() = PrefsRegistry.allNames

    /** DataStore 偏好文件（filesDir/datastore/app_settings.preferences_pb）。 */
    const val DATASTORE_PREFS_RELATIVE: String = "datastore/app_settings.preferences_pb"

    /** 源配置导出/缓存相对目录（相对 filesDir 或 cacheDir）。 */
    private val sourceExportRelatives: List<String> = listOf(
        "exports",
        "source_config",
    )

    private val sourceCacheRelatives: List<String> = listOf(
        "source_config",
        "source_cache",
    )

    private val allFilesRelatives: List<String> = listOf(
        "go_stderr.log",
    )

    private val playbackHistoryRelatives: List<String> = listOf(
        "history",
        "thumbnails",
    )

    /**
     * 解析某档位在给定根（filesDir/cacheDir）下的白名单绝对路径。
     * 返回结果均经 [isUnder] 校验，保证在根之下。
     */
    fun resolveTier(tier: ResetTier, baseDir: File, cacheDir: File): List<File> {
        val files = mutableListOf<File>()

        // 偏好档：DataStore 偏好 + 共享偏好文件。
        files += File(baseDir, DATASTORE_PREFS_RELATIVE)
        files += SHARED_PREFS_NAMES.map { File(baseDir, "shared_prefs/$it.xml") }

        if (tier == ResetTier.PreferencesSourcesNetwork || tier == ResetTier.All) {
            // 源档：清源配置导出与缓存目录。
            sourceExportRelatives.forEach { rel -> files += File(baseDir, rel) }
            sourceCacheRelatives.forEach { rel -> files += File(cacheDir, rel) }
        }

        if (tier == ResetTier.All) {
            // 全档：额外清网络缓存（cache/ 下白名单子目录）+ go_stderr.log。
            allFilesRelatives.forEach { rel -> files += File(baseDir, rel) }
            playbackHistoryRelatives.forEach { rel -> files += File(cacheDir, rel) }
            files += File(cacheDir, "image_cache")
            files += File(cacheDir, "updates")
            files += File(cacheDir, "playback")
            files += File(cacheDir, "video")
            files += File(cacheDir, "epg")
            files += File(cacheDir, "posters")
            files += File(cacheDir, "covers")
            files += File(cacheDir, "diagnostics")
        }

        return files.filter { isUnder(it, listOf(baseDir, cacheDir)) }
    }

    /** 判断 [file] 是否位于任一 [roots] 之下（不包含根本身，也不允许越界）。 */
    fun isUnder(file: File, roots: List<File>): Boolean {
        val target = file.absolutePath
        return roots.any { root ->
            val rootPath = root.absolutePath
            // 严格位于根之下：以 root + separator 开头，且不等于根本身。
            target.startsWith(rootPath + File.separator) && target != rootPath
        }
    }

    /**
     * 生成审计 JSON 文本（手动拼接，避免引入新依赖）。
     * 输出含 timestamp、tier、deleted（路径列表）、deletedCount、failed（路径列表）。
     */
    fun auditJson(tier: ResetTier, deleted: List<String>, failed: List<String>): String {
        val timestamp = System.currentTimeMillis()
        val deletedJson = deleted.joinToString(",") { jsonEscape(it) }
        val failedJson = failed.joinToString(",") { jsonEscape(it) }
        return buildString {
            append("{\n")
            append("  \"timestamp\": ").append(timestamp).append(",\n")
            append("  \"tier\": \"").append(jsonEscape(tier.name)).append("\",\n")
            append("  \"deletedCount\": ").append(deleted.size).append(",\n")
            append("  \"deleted\": [").append(deletedJson).append("],\n")
            append("  \"failedCount\": ").append(failed.size).append(",\n")
            append("  \"failed\": [").append(failedJson).append("]\n")
            append("}")
        }
    }

    /** 简单的 JSON 字符串转义（覆盖常见控制字符与引号）。 */
    private fun jsonEscape(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}

/**
 * 恢复出厂设置的真实删除依赖（P0-02）。
 *
 * 委托 [ResetPathPolicy] 做白名单解析；这里只做 Context 相关的薄封装
 * （SharedPreferences 清空、审计文件原子写入）。任何一步失败都不抛出。
 */
class AndroidFactoryResetDeps(
    context: Context,
) : FactoryResetDeps {

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val cacheDir: File = appContext.cacheDir

    /** 审计文件路径。 */
    private val auditFile: File get() = File(filesDir, "reset_audit.json")

    override suspend fun clearPreferences() {
        val tier = ResetTier.PreferencesOnly
        val targets = ResetPathPolicy.resolveTier(tier, filesDir, cacheDir)
        val deleted = deleteFiles(targets)
        clearSharedPreferences(tier)
        writeAudit(tier, deleted, emptyList())
    }

    override suspend fun clearSources() {
        val tier = ResetTier.PreferencesSourcesNetwork
        val targets = ResetPathPolicy.resolveTier(tier, filesDir, cacheDir)
        val deleted = deleteFiles(targets)
        clearSharedPreferences(tier)
        writeAudit(tier, deleted, emptyList())
    }

    override suspend fun clearNetworkCaches() {
        val tier = ResetTier.PreferencesSourcesNetwork
        val cacheTargets = listOf(
            File(cacheDir, "image_cache"),
            File(cacheDir, "updates"),
            File(cacheDir, "playback"),
            File(cacheDir, "video"),
            File(cacheDir, "epg"),
            File(cacheDir, "posters"),
            File(cacheDir, "covers"),
            File(cacheDir, "diagnostics"),
        ).filter { ResetPathPolicy.isUnder(it, listOf(filesDir, cacheDir)) }
        val deleted = deleteFiles(cacheTargets)
        writeAudit(tier, deleted, emptyList())
    }

    override suspend fun clearPlaybackHistory() {
        val tier = ResetTier.All
        val targets = listOf(
            File(cacheDir, "history"),
            File(cacheDir, "thumbnails"),
            File(filesDir, "history"),
        ).filter { ResetPathPolicy.isUnder(it, listOf(filesDir, cacheDir)) }
        val deleted = deleteFiles(targets)
        clearSharedPreferences(tier)
        writeAudit(tier, deleted, emptyList())
    }

    /** 删除白名单内的文件/目录；返回成功删除的路径，失败路径被记录但不抛出。 */
    private fun deleteFiles(targets: List<File>): List<String> {
        val deleted = mutableListOf<String>()
        for (target in targets) {
            try {
                if (!ResetPathPolicy.isUnder(target, listOf(filesDir, cacheDir))) {
                    // 越界：拒绝，不删除。
                    continue
                }
                val removed = when {
                    target.isDirectory -> target.deleteRecursively()
                    else -> target.delete()
                }
                if (removed) {
                    deleted += target.absolutePath
                }
            } catch (_: Exception) {
                // 单文件失败不中断整体流程。
            }
        }
        return deleted
    }

    /** 逐个清空白名单内的 SharedPreferences；失败不抛出。 */
    private fun clearSharedPreferences(tier: ResetTier) {
        PrefsRegistry.wipe(appContext, tier)
    }

    /** 写审计文件：先写临时文件再替换，保证可解析；失败不抛出。 */
    private fun writeAudit(tier: ResetTier, deleted: List<String>, failed: List<String>) {
        try {
            val json = ResetPathPolicy.auditJson(tier, deleted, failed)
            val tmp = File(filesDir, "reset_audit.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (tmp.exists() && !tmp.renameTo(auditFile)) {
                auditFile.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 审计写入失败不中断。
        }
    }
}
