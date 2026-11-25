package com.tvbox.app.data

import java.io.File

/**
 * 存储分级清理：纯 Kotlin 文件统计，不依赖 Android framework，便于单测。
 *
 * 安全边界：
 * - 只扫描白名单目录下的**一级文件**，不递归进入子目录；
 * - 只删除命名明确为「临时 / 旧版 / 过期」的单个文件，永不删除目录本身；
 * - 每类都带「清理影响说明」，供 UI 在用户勾选前展示。
 */
class StorageCleanupRepository {

    /** 分类静态信息（即使扫描结果为空，UI 也能展示分类与影响说明）。 */
    data class CategoryInfo(
        val key: String,
        val label: String,
        val impact: String,
        /** 是否属于「智能清理」可自动处理的安全类别。 */
        val smartSafe: Boolean,
    )

    /** 扫描结果：某一分类当前可清理的文件与预计释放量。 */
    data class StorageCategory(
        val key: String,
        val label: String,
        /** 清理影响说明。 */
        val impact: String,
        val files: List<File>,
        val bytes: Long,
    ) {
        val fileCount: Int get() = files.size
    }

    /** 单次清理结果：返回明确文案，不抛异常给调用方。 */
    data class CleanResult(
        val category: String,
        val deletedCount: Int,
        val freedBytes: Long,
        val skippedCount: Int,
        val message: String,
    )

    private data class Spec(
        val key: String,
        val label: String,
        val impact: String,
        val dirs: List<String>,
        val namePattern: Regex,
        /** 仅删除达到该天数的文件；null 表示名称本身已明确标记临时，不限时间。 */
        val minAgeDays: Int?,
        val smartSafe: Boolean,
    )

    private val specs = listOf(
        Spec(
            key = "playback-cache",
            label = "播放缓存",
            impact = "仅删除播放缓存的临时分片，下次播放会重新缓冲；不影响观看历史与已收藏的源。",
            dirs = listOf("cache/playback", "cache/video", "cache"),
            namePattern = Regex("(?i).*(\\.tmp|\\.part|\\.cache|\\.download|~)$"),
            minAgeDays = 1,
            smartSafe = true,
        ),
        Spec(
            key = "spider-legacy",
            label = "Spider/JAR 旧版",
            impact = "仅删除名字明确标记为旧版或备份的 Spider JAR；正在使用的 jar 不会被匹配。",
            dirs = listOf("spider", "jars", "spider/jars", "cache/spider"),
            namePattern = Regex("(?i).*(-old|\\.bak|\\.backup|-\\d{8,})\\.jar(?:\\.tmp|\\.part)?$"),
            minAgeDays = 7,
            smartSafe = true,
        ),
        Spec(
            key = "posters",
            label = "海报封面",
            impact = "仅删除海报/封面目录里的临时缩略文件，正式封面保留；清理后详情页会按需重新加载。",
            dirs = listOf("posters", "covers", "cache/posters", "cache/covers"),
            namePattern = Regex("(?i).*(\\.tmp|\\.part|\\.old|~)$"),
            minAgeDays = 3,
            // 海报直接影响首页观感，智能清理不擅自处理，交给用户单独确认。
            smartSafe = false,
        ),
        Spec(
            key = "crash-logs",
            label = "崩溃日志",
            impact = "仅删除 7 天前的崩溃日志文本；近期日志保留，便于继续排查问题。",
            dirs = listOf("crash", "crash_logs", "logs/crash"),
            namePattern = Regex("(?i).*\\.(txt|log)$"),
            minAgeDays = 7,
            smartSafe = true,
        ),
        Spec(
            key = "diagnostic-logs",
            label = "诊断日志",
            impact = "仅删除过期的诊断/播放器日志文件，不影响正在记录的日志。",
            dirs = listOf("diagnostics", "logs", "cache/diagnostics"),
            namePattern = Regex("(?i).*(\\.log|\\.log\\.\\d+|\\.trace|\\.txt)$"),
            minAgeDays = 3,
            smartSafe = true,
        ),
        Spec(
            key = "epg-cache",
            label = "EPG 缓存",
            impact = "仅删除过期的 EPG 节目单缓存；下次进入直播页会重新拉取，不影响频道收藏。",
            dirs = listOf("epg", "cache/epg"),
            namePattern = Regex("(?i).*(\\.xml|\\.json)(\\.gz|\\.tmp)?$"),
            minAgeDays = 2,
            // 删除后需要联网重新拉取，智能清理不擅自处理。
            smartSafe = false,
        ),
        Spec(
            key = "history-thumbnails",
            label = "历史缩略图",
            impact = "仅删除历史缩略图的临时文件；正式缩略图与观看历史记录保留。",
            dirs = listOf("history", "history/thumbnails", "cache/history", "cache/thumbnails"),
            namePattern = Regex("(?i).*(\\.tmp|\\.part|\\.old|~)$"),
            minAgeDays = 3,
            smartSafe = true,
        ),
    )

    /** 全部分类（含影响说明），供 UI 展示勾选项。 */
    fun categoryInfos(): List<CategoryInfo> = specs.map {
        CategoryInfo(it.key, it.label, it.impact, it.smartSafe)
    }

    /** 扫描各分类当前可清理的文件，不改文件系统。 */
    fun scan(baseDir: File): List<StorageCategory> = specs.map { spec ->
        val files = candidates(spec, baseDir)
        StorageCategory(spec.key, spec.label, spec.impact, files, files.sumOf { it.length() })
    }

    /** 清理单一分类；未知分类返回明确状态而非静默成功。 */
    fun clean(category: String, baseDir: File): CleanResult {
        val spec = specs.firstOrNull { it.key.equals(category, ignoreCase = true) }
            ?: return CleanResult(
                category, 0, 0L, 0,
                "未知的清理分类：$category",
            )
        val files = candidates(spec, baseDir)
        var deleted = 0
        var freed = 0L
        var skipped = 0
        for (file in files) {
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deleted++
                freed += size
            } else {
                skipped++
            }
        }
        val suffix = if (skipped > 0) "；$skipped 个文件删除失败已跳过" else ""
        val message = if (deleted == 0) {
            "「${spec.label}」没有符合条件的临时/过期文件$suffix"
        } else {
            "已清理「${spec.label}」$deleted 个文件，释放 ${formatBytes(freed)}$suffix"
        }
        return CleanResult(spec.key, deleted, freed, skipped, message)
    }

    /**
     * 智能清理：只处理 [Spec.smartSafe] 分类（临时分片、旧版 JAR、过期日志、历史临时缩略图）。
     * 海报与 EPG 缓存需要用户单独确认，不在这里自动清理。
     */
    fun smartClean(baseDir: File): CleanResult {
        val results = specs.filter { it.smartSafe }.map { clean(it.key, baseDir) }
        val deleted = results.sumOf { it.deletedCount }
        val freed = results.sumOf { it.freedBytes }
        val skipped = results.sumOf { it.skippedCount }
        val detail = results.filter { it.deletedCount > 0 }
            .joinToString("、") { "${it.category} ${it.deletedCount} 个" }
        val message = if (deleted == 0) {
            "智能清理完成，没有符合条件的临时/过期文件"
        } else {
            "智能清理完成：$detail，共释放 ${formatBytes(freed)}"
        }
        return CleanResult("smart", deleted, freed, skipped, message)
    }

    private fun candidates(spec: Spec, baseDir: File): List<File> {
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val result = mutableListOf<File>()
        for (relative in spec.dirs) {
            // 只列目录一级，绝不递归；listFiles() 为 null 表示该目录不存在或不可读。
            val listed = File(baseDir, relative).listFiles() ?: continue
            for (file in listed) {
                if (!file.isFile) continue
                if (!spec.namePattern.matches(file.name)) continue
                val minAge = spec.minAgeDays
                if (minAge != null && now - file.lastModified() < minAge.toLong() * 86_400_000L) continue
                if (seen.add(file.absolutePath)) result += file
            }
        }
        return result
    }

    companion object {
        /** 人类可读的释放量文案。 */
        fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "0 B"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
