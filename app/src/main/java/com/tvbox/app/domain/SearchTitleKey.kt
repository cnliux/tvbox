package com.tvbox.app.domain

/**
 * 跨源搜索 titleKey 层。
 *
 * 与 [SearchMerge.kt] 正交：SearchMerge 负责「同源 (apiLineId, movieId)」去重与
 * 按源分组；本文件负责「跨源同名影片」按 titleKey（规范化标题 + 年份 + 类型）聚合，
 * 保证主源优先、冲突可解释。不修改 SearchMerge。
 */

/** 影片的跨源身份标识：规范化标题 + 年份 + 类型。 */
data class TitleKey(
    val normalizedTitle: String,
    val year: Int,
    val typeId: Int,
)

/**
 * titleKey 生成与跨源判重策略。
 *
 * 纯 Kotlin 实现，不引入任何新依赖。
 */
object TitleKeyPolicy {

    /** 相似标题判定阈值（Jaccard bigram）。 */
    private const val SIMILARITY_THRESHOLD = 0.9

    /** 年份允许误差：|a - b| ≤ 1 视为「同一年档」，用于容错不同源的入库年份差异。 */
    private const val YEAR_TOLERANCE = 1

    /**
     * 从影片生成 TitleKey。
     * - 标题：去空格 / 全角转半角 / 小写 / 去常见标点。
     * - 年份：解析失败或空白时默认 0（未知）。
     * - typeId：直接取自影片。
     */
    fun from(movie: Movie): TitleKey = TitleKey(
        normalizedTitle = normalize(movie.name),
        year = parseYear(movie.year),
        typeId = movie.typeId,
    )

    /**
     * 跨源判重：
     * 1) titleKey 完全相等，或
     * 2) 标题相似度 > 0.9 且年份差 ≤ 1（容忍「流浪地球」vs「流浪地球 」之类拼写微差）。
     */
    fun areDuplicates(a: Movie, b: Movie): Boolean {
        val ka = from(a)
        val kb = from(b)
        if (ka == kb) return true
        if (similarity(ka.normalizedTitle, kb.normalizedTitle) > SIMILARITY_THRESHOLD &&
            (ka.year - kb.year) in -YEAR_TOLERANCE..YEAR_TOLERANCE
        ) {
            return true
        }
        return false
    }

    /**
     * 字符 bigram Jaccard 相似度，取值 [0.0, 1.0]。
     * 空串对空串视为 1.0（规范化后都为空 = 标题一致）。
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val gramsA = a.windowed(2).toSet()
        val gramsB = b.windowed(2).toSet()
        if (gramsA.isEmpty() || gramsB.isEmpty()) {
            // 单字符字符串退化：按字符集 Jaccard。
            val sa = a.toSet()
            val sb = b.toSet()
            return sa.intersect(sb).size.toDouble() / sa.union(sb).size
        }
        val intersection = gramsA.intersect(gramsB).size.toDouble()
        val union = gramsA.union(gramsB).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /** 标题规范化：全角转半角 → 小写 → 保留字母/数字/CJK，其余（空格、标点）剔除。 */
    fun normalize(raw: String): String {
        val halfWidth = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            when {
                code == 0x3000 -> halfWidth.append(' ')         // 全角空格
                code in 0xFF01..0xFF5E -> halfWidth.append((code - 0xFEE0).toChar())
                else -> halfWidth.append(ch)
            }
        }
        return halfWidth.toString()
            .lowercase()
            .filter { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
    }

    /** 年份解析：取前 4 位数字，失败返回 0。 */
    private fun parseYear(raw: String): Int {
        val match = Regex("\\d{4}").find(raw) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }
}

/** 一条跨源合并冲突：同一 titleKey 下多源命中，胜者与落选者及原因。 */
data class MergeConflict(
    val titleKey: TitleKey,
    val winner: Movie,
    val losers: List<Movie>,
    val reason: String,
)

/** 聚合结果：合并后的有序影片列表 + 可解释的冲突明细。 */
data class TitleKeyMergeResult(
    val merged: List<Movie>,
    val conflicts: List<MergeConflict>,
)

/**
 * 跨源 titleKey 聚合器。
 *
 * [groups] 为按源分组的原始结果（与 [buildSearchSourceGroups] 同构的 map 亦可），
 * [mainSourceId] 为用户指定的主源；主源命中优先胜出，其余按输入顺序保留。
 */
object SearchTitleKeyMerger {

    fun merge(groups: Map<String, List<Movie>>, mainSourceId: String): TitleKeyMergeResult {
        val ordered = groups.toSortedMap(compareByDescending<String> { it == mainSourceId }.thenBy { it })
            .values
            .flatten()
        val buckets = LinkedHashMap<TitleKey, MutableList<Movie>>()
        for (movie in ordered) {
            val key = TitleKeyPolicy.from(movie)
            val bucket = buckets.entries.firstOrNull { (existingKey, members) ->
                existingKey == key || members.any { TitleKeyPolicy.areDuplicates(it, movie) }
            }
            if (bucket == null) {
                buckets[key] = mutableListOf(movie)
            } else {
                bucket.value += movie
            }
        }

        val merged = ArrayList<Movie>(buckets.size)
        val conflicts = ArrayList<MergeConflict>()
        for ((key, members) in buckets) {
            // 主源优先：若组内含主源影片则其胜出，否则保留首个（输入顺序）。
            val winner = members.firstOrNull { it.apiLineId == mainSourceId } ?: members.first()
            merged += winner
            if (members.size > 1) {
                val losers = members.filter { it !== winner }
                conflicts += MergeConflict(
                    titleKey = key,
                    winner = winner,
                    losers = losers,
                    reason = "同 titleKey(${key.normalizedTitle}|${key.year}|${key.typeId}) 命中 ${members.size} 源；" +
                        "主源=${mainSourceId} 胜出，剔除 ${losers.joinToString(",") { it.apiLineId }}",
                )
            }
        }
        return TitleKeyMergeResult(merged = merged, conflicts = conflicts)
    }
}
