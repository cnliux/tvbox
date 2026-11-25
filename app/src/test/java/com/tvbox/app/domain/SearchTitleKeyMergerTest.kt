package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * 跨源 titleKey 聚合金文件测试。
 *
 * 金文件：src/test/resources/search_titlekey_golden.json
 * 加载方式：javaClass.classLoader.getResourceAsStream（与运行时资源访问一致）。
 * 测试类路径仅含 JUnit，不引入 JSON 库，按受控 schema 手写轻量解析。
 */
class SearchTitleKeyMergerTest {

    // ---------- 金文件加载 ----------

    private data class GoldenMovie(
        val id: Int,
        val apiLineId: String,
        val name: String,
        val typeId: Int,
        val typeName: String,
        val year: String,
    ) {
        fun toMovie(): Movie = Movie(
            id = id,
            apiLineId = apiLineId,
            apiLineName = apiLineId,
            name = name,
            typeId = typeId,
            typeName = typeName,
            posterUrl = "",
            remarks = "",
            year = year,
            area = "",
            language = "",
            actor = "",
            director = "",
            duration = "",
            description = "",
            playSources = emptyList(),
        )
    }

    private data class GoldenScenario(
        val id: String,
        val mainSourceId: String,
        val groups: Map<String, List<Movie>>,
        val mergedSize: Int,
        val conflictCount: Int,
        val winnerApiLineId: String,
        val reasonContains: String,
    )

    private fun loadGolden(): List<GoldenScenario> {
        val stream: InputStream = javaClass.classLoader.getResourceAsStream(GOLDEN_PATH)
            ?: throw AssertionError("金文件缺失：$GOLDEN_PATH")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return parseGolden(text)
    }

    /** 受控 schema 的极简 JSON 解析：仅用于金文件，避免引入 JSON 库依赖。 */
    private fun parseGolden(text: String): List<GoldenScenario> {
        val scenariosBody = extractArray(text, "scenarios")
        val scenarioBlocks = splitTopLevel(scenariosBody)
        return scenarioBlocks.map { block ->
            val id = extractString(block, "id")
            val main = extractString(block, "mainSourceId")
            val groupsBody = extractArray(block, "groups")
            val groupBlocks = splitTopLevel(groupsBody)
            val groups = LinkedHashMap<String, MutableList<Movie>>()
            for (gb in groupBlocks) {
                val sid = extractString(gb, "sourceId")
                val moviesBody = extractArray(gb, "movies")
                val movies = splitTopLevel(moviesBody).map { mb ->
                    GoldenMovie(
                        id = extractInt(mb, "id"),
                        apiLineId = extractString(mb, "apiLineId"),
                        name = extractString(mb, "name"),
                        typeId = extractInt(mb, "typeId"),
                        typeName = extractString(mb, "typeName"),
                        year = extractString(mb, "year"),
                    ).toMovie()
                }
                groups.getOrPut(sid) { mutableListOf() }.addAll(movies)
            }
            val expectedBody = extractObject(block, "expected")
            GoldenScenario(
                id = id,
                mainSourceId = main,
                groups = groups,
                mergedSize = extractInt(expectedBody, "mergedSize"),
                conflictCount = extractInt(expectedBody, "conflictCount"),
                winnerApiLineId = extractString(expectedBody, "winnerApiLineId"),
                reasonContains = extractString(expectedBody, "reasonContains"),
            )
        }
    }

    private val GOLDEN_PATH = "search_titlekey_golden.json"

    private fun extractArray(text: String, key: String): String {
        val start = text.indexOf("\"$key\"")
        require(start >= 0) { "字段缺失：$key" }
        val open = text.indexOf('[', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        error("数组未闭合：$key")
    }

    private fun extractObject(text: String, key: String): String {
        val start = text.indexOf("\"$key\"")
        require(start >= 0) { "字段缺失：$key" }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        error("对象未闭合：$key")
    }

    private fun splitTopLevel(body: String): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in body) {
            when (ch) {
                '{', '[' -> {
                    depth++; cur.append(ch)
                }
                '}', ']' -> {
                    depth--; cur.append(ch)
                }
                ',' -> {
                    if (depth == 0) {
                        blocks += cur.toString(); cur = StringBuilder()
                    } else cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) blocks += cur.toString()
        return blocks.filter { it.isNotBlank() }
    }

    private fun extractString(text: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
        require(match != null) { "字符串字段缺失：$key" }
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun extractInt(text: String, key: String): Int {
        val match = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)
        require(match != null) { "整数字段缺失：$key" }
        return match.groupValues[1].toInt()
    }

    // ---------- titleKey 规范化 ----------

    private fun movie(apiLineId: String, id: Int, name: String, typeId: Int = 1, year: String = "2019"): Movie =
        Movie(
            id = id,
            apiLineId = apiLineId,
            apiLineName = apiLineId,
            name = name,
            typeId = typeId,
            typeName = "科幻",
            posterUrl = "",
            remarks = "",
            year = year,
            area = "",
            language = "",
            actor = "",
            director = "",
            duration = "",
            description = "",
            playSources = emptyList(),
        )

    @Test
    fun titleKeyNormalizesCaseWidthAndPunctuation() {
        val a = TitleKeyPolicy.from(movie("m", 1, "  流浪地球！"))
        val b = TitleKeyPolicy.from(movie("m", 2, "ＬＩＵＬＡＮＧｄｉｑｉｕ"))  // 全角
        val c = TitleKeyPolicy.from(movie("m", 3, "流浪地球"))
        assertEquals(c.normalizedTitle, a.normalizedTitle)
        // 全角字母转半角并小写
        assertEquals("liulangdiqiu", b.normalizedTitle)
        assertEquals("流浪地球", c.normalizedTitle)
    }

    @Test
    fun titleKeyUnknownYearDefaultsToZero() {
        val key = TitleKeyPolicy.from(movie("m", 1, "无名", year = ""))
        assertEquals(0, key.year)
    }

    // ---------- areDuplicates 三场景 ----------

    @Test
    fun areDuplicatesSameNameSameYearAcrossSources() {
        val a = movie("main", 1, "流浪地球", year = "2019")
        val b = movie("backup", 2, "流浪地球", year = "2019")
        assertTrue(TitleKeyPolicy.areDuplicates(a, b))
    }

    @Test
    fun areDuplicatesSameNameDiffYearNotMerged() {
        val a = movie("main", 1, "流浪地球", year = "2019")
        val b = movie("backup", 2, "流浪地球2", year = "2023")
        assertTrue(!TitleKeyPolicy.areDuplicates(a, b))
    }

    @Test
    fun areDuplicatesSimilarTitleYearDiffOneMerged() {
        val a = movie("main", 1, "你的名字", year = "2016")
        val b = movie("backup", 2, "你的名字。", year = "2017")
        assertTrue(TitleKeyPolicy.areDuplicates(a, b))
    }

    // ---------- similarity 边界 ----------

    @Test
    fun similarityBoundaries() {
        assertEquals(1.0, TitleKeyPolicy.similarity("abc", "abc"), 1e-9)
        assertEquals(0.0, TitleKeyPolicy.similarity("abc", "xyz"), 1e-9)
        // "abc" vs "abd" 共享 ab，bigram {ab,bc} vs {ab,bd} → Jaccard = 1/3
        val half = TitleKeyPolicy.similarity("abc", "abd")
        assertTrue(half in 0.3..0.4)
    }

    // ---------- merge ----------

    @Test
    fun mergePrefersMainSource() {
        val groups = mapOf(
            "backup" to listOf(movie("backup", 2, "流浪地球", year = "2019")),
            "main" to listOf(movie("main", 1, "流浪地球", year = "2019")),
        )
        val result = SearchTitleKeyMerger.merge(groups, mainSourceId = "main")
        assertEquals(1, result.merged.size)
        assertEquals("main", result.merged.first().apiLineId)
        assertEquals(1, result.conflicts.size)
    }

    @Test
    fun mergeNoConflictWhenSingleSource() {
        val groups = mapOf(
            "main" to listOf(movie("main", 1, "A"), movie("main", 2, "B")),
        )
        val result = SearchTitleKeyMerger.merge(groups, mainSourceId = "main")
        assertEquals(2, result.merged.size)
        assertTrue(result.conflicts.isEmpty())
    }

    // ---------- 金文件 3 场景全覆盖 ----------

    @Test
    fun goldenScenario1SameNameSameYearMergesWithMainSourceWinner() {
        val scenario = loadGolden().first { it.id == "scenario_1_same_name_same_year" }
        val result = SearchTitleKeyMerger.merge(scenario.groups, scenario.mainSourceId)
        assertEquals(scenario.mergedSize, result.merged.size)
        assertEquals(scenario.conflictCount, result.conflicts.size)
        assertEquals(scenario.winnerApiLineId, result.conflicts.first().winner.apiLineId)
        assertTrue(result.conflicts.first().reason.contains(scenario.reasonContains))
    }

    @Test
    fun goldenScenario2SameNameDiffYearKeepsBoth() {
        val scenario = loadGolden().first { it.id == "scenario_2_same_name_diff_year" }
        val result = SearchTitleKeyMerger.merge(scenario.groups, scenario.mainSourceId)
        assertEquals(scenario.mergedSize, result.merged.size)
        assertEquals(scenario.conflictCount, result.conflicts.size)
        // 未合并 → 主源影片仍保留在 merged 中
        assertEquals(scenario.winnerApiLineId, result.merged.first().apiLineId)
    }

    @Test
    fun goldenScenario3SimilarTitleYearDiffOneMerges() {
        val scenario = loadGolden().first { it.id == "scenario_3_similar_title_year_diff_1" }
        val result = SearchTitleKeyMerger.merge(scenario.groups, scenario.mainSourceId)
        assertEquals(scenario.mergedSize, result.merged.size)
        assertEquals(scenario.conflictCount, result.conflicts.size)
        assertEquals(scenario.winnerApiLineId, result.conflicts.first().winner.apiLineId)
        assertTrue(result.conflicts.first().reason.contains(scenario.reasonContains))
    }
}
