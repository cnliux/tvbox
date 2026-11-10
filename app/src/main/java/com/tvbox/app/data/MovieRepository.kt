package com.tvbox.app.data

import android.util.Log
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PagedMovies
import com.tvbox.app.domain.PlaySource
import com.tvbox.app.domain.QuantitySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.IOException

// 契约已迁移到 domain；保留同包别名，确保既有 import 与 public API 不变。
typealias MultiSourceSearchUpdate = com.tvbox.app.domain.MultiSourceSearchUpdate
typealias MovieDetailUpdate = com.tvbox.app.domain.MovieDetailUpdate
typealias MovieRepository = com.tvbox.app.domain.MovieRepository

class DefaultMovieRepository(
    private val apiFactory: (String) -> MacCmsApi = MacCmsNetwork::api,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MovieRepository {
    @Volatile
    private var maxParallelSourceRequests = MAX_PARALLEL_SOURCE_REQUESTS

    override fun setMaxParallelSourceRequests(value: Int) {
        maxParallelSourceRequests = QuantitySettings.MaxParallelSourceRequests.clamp(value)
    }
    @Volatile
    private var customApiLines: List<ApiLine> = emptyList()

    @Volatile
    private var disabledLineIds: Set<String> = emptySet()
    private val categoriesCache = TimedLruCache<List<Category>>(maxEntries = 24)
    private val moviesCache = TimedLruCache<PagedMovies>(maxEntries = 80)
    private val detailsCache = TimedLruCache<Movie?>(maxEntries = 60)
    private val sourceHealth = SourceHealthTracker(nowMs)

    private val TAG = "MovieRepository"

    override val allApiLines: List<ApiLine>
        get() = customApiLines

    override val apiLines: List<ApiLine>
        get() {
            val lines = customApiLines
            val disabled = disabledLineIds
            return lines.filter { it.id !in disabled }
        }

    override fun updateCustomApiLines(customApiLines: List<ApiLine>) {
        this.customApiLines = customApiLines
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.baseUrls.isNotEmpty() }
            .distinctBy { it.id }
        // 缓存 key 只含 line.id/page/type/keyword，不含 baseUrls：同名 id 改地址或
        // 禁用/启用线路后必须清空，否则最长 30 分钟仍在返回旧源数据。
        clearCaches()
    }

    override fun setDisabledLineIds(disabledIds: Set<String>) {
        this.disabledLineIds = disabledIds.filter { it.isNotBlank() }.toSet()
        clearCaches()
    }

    private fun clearCaches() {
        categoriesCache.clear()
        moviesCache.clear()
        detailsCache.clear()
    }

    override suspend fun testSpeed(apiLineId: String): Long? {
        val line = apiLines.firstOrNull { it.id == apiLineId } ?: return null
        for (baseUrl in line.baseUrls) {
            val latencyMs = runCatching { testSpeedUrl(baseUrl) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (latencyMs != null) return latencyMs
        }
        return null
    }

    private suspend fun testSpeedUrl(baseUrl: String): Long {
        val api = apiFactory(baseUrl)
        val startedAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(SPEED_TEST_TIMEOUT_MS) {
                api.getVod(action = "list", ids = "1")
            }
        } ?: throw IOException("测速超时")
        return System.currentTimeMillis() - startedAt
    }

    override suspend fun getCategories(apiLineId: String): List<Category> = withContext(Dispatchers.IO) {
        val line = requireLine(apiLineId)
        val key = "categories:${line.id}"
        categoriesCache.get(key, nowMs())?.let { return@withContext it }
        val categories = withFallback(line) { api ->
            api.getVod(action = "list").categories.mapNotNull { it.toDomainOrNull() }
        }
        categoriesCache.put(key, categories, nowMs() + MOVIES_CACHE_TTL_MS)
        categories
    }

    override suspend fun getMovies(apiLineId: String, page: Int, typeId: Int?, keyword: String?): PagedMovies = withContext(Dispatchers.IO) {
        val line = requireLine(apiLineId)
        loadMovies(line = line, page = page, typeId = typeId, keyword = keyword)
    }

    override suspend fun getMoviesByTypeIds(apiLineId: String, page: Int, typeIds: List<Int>): PagedMovies = withContext(Dispatchers.IO) {
        val line = requireLine(apiLineId)
        val distinctTypeIds = typeIds
            .filter { it > 0 }
            .distinct()

        if (distinctTypeIds.isEmpty()) {
            return@withContext getMovies(apiLineId = apiLineId, page = page)
        }

        if (distinctTypeIds.size == 1) {
            return@withContext getMovies(apiLineId = apiLineId, page = page, typeId = distinctTypeIds.single())
        }

        val requestedPage = page.coerceAtLeast(1)
        val results = coroutineScope {
            distinctTypeIds.map { typeId ->
                async {
                    try {
                        Result.success(
                            loadMovies(line = line, page = requestedPage, typeId = typeId),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }

        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) {
            throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("分类数据加载失败")
        }

        val movies = pages
            .flatMap { it.movies }
            .distinctBy { "${it.apiLineId}-${it.id}" }

        PagedMovies(
            page = requestedPage,
            pageCount = pages.maxOfOrNull { it.pageCount }?.coerceAtLeast(1) ?: 1,
            total = pages.sumOf { it.total },
            apiLine = line,
            categories = pages
                .flatMap { it.categories }
                .distinctBy { it.id },
            movies = movies,
        )
    }

    override suspend fun getDetail(apiLineId: String, id: Int): Movie? {
        return getDetailProgressively(apiLineId = apiLineId, id = id).lastOrNull()?.movie
    }

    override suspend fun getResumeDetail(apiLineId: String, id: Int): Movie? = withContext(Dispatchers.IO) {
        require(apiLines.any { it.id == apiLineId }) { "原影视接口已不可用，请从搜索重新打开影片" }
        loadPrimaryDetail(apiLineId, id)
    }

    override fun searchProgressively(apiLineId: String, page: Int, keyword: String): Flow<MultiSourceSearchUpdate> = channelFlow {
        val requestedPage = page.coerceAtLeast(1)
        val lines = prioritizedLines(apiLineId)
        if (lines.isEmpty()) {
            return@channelFlow
        }

        val semaphore = Semaphore(maxParallelSourceRequests)
        val updatesMutex = Mutex()
        val completedMovies = mutableMapOf<String, List<Movie>>()
        var completedSources = 0

        coroutineScope {
            lines.map { line ->
                async {
                    val startedAtMs = nowMs()
                    var completedNormally = true
                    val result = try {
                        semaphore.withPermit {
                            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                // 同上：并行搜索的网络读取必须在 IO 线程执行。
                                withContext(Dispatchers.IO) {
                                    loadMovies(line = line, page = requestedPage, keyword = keyword).movies
                                }
                            } ?: run {
                                completedNormally = false
                                sourceHealth.recordFailure(line.id)
                                emptyList()
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        completedNormally = false
                        sourceHealth.recordFailure(line.id)
                        Log.d(TAG, "search source failed: lineId=${line.id}, type=${error::class.simpleName}")
                        emptyList()
                    }
                    if (completedNormally) {
                        sourceHealth.recordSuccess(line.id, nowMs() - startedAtMs)
                    }

                    updatesMutex.withLock {
                        completedSources += 1
                        completedMovies[line.id] = result
                        trySend(
                            MultiSourceSearchUpdate(
                                movies = mergeSearchMovies(
                                    lines = lines,
                                    moviesByLine = completedMovies,
                                    keyword = keyword,
                                    healthScore = sourceHealth::score,
                                ),
                                completedSources = completedSources,
                                totalSources = lines.size,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    override fun getDetailProgressively(apiLineId: String, id: Int): Flow<MovieDetailUpdate> = channelFlow {
        val primary = withContext(Dispatchers.IO) {
            loadPrimaryDetail(apiLineId = apiLineId, id = id)
        } ?: return@channelFlow
        val lines = prioritizedLines(apiLineId)
        val primarySource = primary.toLinePlaySource()
        val sourceMutex = Mutex()
        val matchedSources = mutableMapOf<String, PlaySource>()
        var completedSources = 1

        trySend(
            MovieDetailUpdate(
                movie = primary.copy(playSources = listOfNotNull(primarySource)),
                completedSources = completedSources,
                totalSources = lines.size,
            ),
        )

        val semaphore = Semaphore(maxParallelSourceRequests)
        coroutineScope {
            lines
                .filterNot { it.id == primary.apiLineId }
                .map { line ->
                    async {
                        val startedAtMs = nowMs()
                        var completedNormally = true
                        val matched = try {
                            semaphore.withPermit {
                                val response = withTimeoutOrNull(DETAIL_SOURCE_TIMEOUT_MS) {
                                    // channelFlow 的 async 继承收集方（主线程）上下文，
                                    // 网络读取若不切 IO 会阻塞主线程并堆积按键事件。
                                    Result.success(
                                        withContext(Dispatchers.IO) {
                                            findSameMovie(line, primary.name)?.toLinePlaySource()
                                        },
                                    )
                                }
                                if (response == null) {
                                    completedNormally = false
                                    sourceHealth.recordFailure(line.id)
                                }
                                response?.getOrThrow()
                            }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        completedNormally = false
                        sourceHealth.recordFailure(line.id)
                        Log.d(TAG, "detail source failed: lineId=${line.id}, type=${error::class.simpleName}")
                        null
                    }
                        if (completedNormally) {
                            sourceHealth.recordSuccess(line.id, nowMs() - startedAtMs)
                        }

                        sourceMutex.withLock {
                            completedSources += 1
                            matched?.let { matchedSources[line.id] = it }
                            val sources = buildList {
                                primarySource?.let(::add)
                                lines
                                    .filterNot { it.id == primary.apiLineId }
                                    .mapNotNull { matchedSources[it.id] }
                                    .forEach(::add)
                            }
                            trySend(
                                MovieDetailUpdate(
                                    movie = primary.copy(playSources = sources.ifEmpty { primary.playSources }),
                                    completedSources = completedSources,
                                    totalSources = lines.size,
                                ),
                            )
                        }
                    }
                }
                .awaitAll()
        }
    }

    private fun requireLine(apiLineId: String): ApiLine {
        return apiLines.firstOrNull { it.id == apiLineId }
            ?: apiLines.firstOrNull()
            ?: throw IOException("没有可用的视频源，请先在源管理中启用至少一个源")
    }

    private suspend fun <T> withFallback(
        line: ApiLine,
        block: suspend (MacCmsApi) -> T,
    ): T {
        var lastError: Throwable? = null
        for (baseUrl in line.baseUrls) {
            try {
                return block(apiFactory(baseUrl))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("线路不可用：${line.name}")
    }

    private suspend fun findSameMovie(line: ApiLine, movieName: String): Movie? {
        val normalizedName = normalizeTitle(movieName)
        val candidates = withFallback(line) { api ->
            api.getVod(action = "videolist", page = 1, keyword = movieName)
                .list
                .mapNotNull { it.toDomainOrNull(line) }
        }
        val matched = candidates.firstOrNull { normalizeTitle(it.name) == normalizedName }
            ?: candidates.firstOrNull { candidate ->
                val normalizedCandidate = normalizeTitle(candidate.name)
                normalizedCandidate.isNotBlank() &&
                    (normalizedCandidate.contains(normalizedName) || normalizedName.contains(normalizedCandidate))
            }
            ?: return null

        return withFallback(line) { api ->
            api.getVod(action = "videolist", ids = matched.id.toString())
                .list
                .firstOrNull()
                ?.toDomainOrNull(line)
        }
    }

    private fun Movie.toLinePlaySource(): com.tvbox.app.domain.PlaySource? {
        val selected = playSources.getOrNull(preferredSourceIndex()) ?: return null
        if (selected.episodes.isEmpty()) return null
        return selected.copy(
            name = apiLineName,
            lineId = apiLineId,
            lineName = apiLineName,
            sourceName = selected.name,
        )
    }

    private fun normalizeTitle(title: String): String {
        return title
            .lowercase()
            .filter { it.isLetterOrDigit() }
    }

    /** 片名/关键词归一化：仅保留字母数字并小写，用于相关度比对。 */
    private fun normalizeSearchName(title: String): String = normalizeTitle(title)

    /**
     * 片名与搜索关键词的相关度分（0~100）：
     * 精确等于 > 前缀匹配 > 包含匹配 > 不相关。
     * 分数用于「准确率优先」排序，让真正命中关键词的片子排在最前。
     */
    private fun scoreNameRelevance(movieName: String, keyword: String): Int {
        val name = normalizeSearchName(movieName)
        val query = normalizeSearchName(keyword)
        if (query.isEmpty()) return 0
        return when {
            name == query -> 100
            name.startsWith(query) -> 70
            name.contains(query) -> 40
            else -> 0
        }
    }

    private suspend fun loadMovies(
        line: ApiLine,
        page: Int,
        typeId: Int? = null,
        keyword: String? = null,
    ): PagedMovies {
        val normalizedKeyword = keyword?.trim().orEmpty()
        val key = "movies:${line.id}:${page.coerceAtLeast(1)}:${typeId ?: "all"}:$normalizedKeyword"
        moviesCache.get(key, nowMs())?.let { return it }
        val result = withFallback(line) { api ->
            api.getVod(
                action = "videolist",
                page = page.coerceAtLeast(1),
                typeId = typeId,
                keyword = normalizedKeyword.takeIf { it.isNotBlank() },
            ).toPagedMovies(line)
        }
        moviesCache.put(key, result, nowMs() + MOVIES_CACHE_TTL_MS)
        return result
    }

    private suspend fun loadPrimaryDetail(apiLineId: String, id: Int): Movie? {
        val line = requireLine(apiLineId)
        val key = "detail:${line.id}:$id"
        detailsCache.get(key, nowMs())?.let { return it }
        val detail = withFallback(line) { api ->
            api.getVod(action = "videolist", ids = id.toString())
                .list
                .firstOrNull()
                ?.toDomainOrNull(line)
        }
        detailsCache.put(key, detail, nowMs() + DETAIL_CACHE_TTL_MS)
        return detail
    }

    private fun prioritizedLines(primaryLineId: String): List<ApiLine> {
        val primary = requireLine(primaryLineId)
        return buildList {
            add(primary)
            addAll(
                apiLines
                    .filterNot { it.id == primary.id || sourceHealth.isCoolingDown(it.id) }
                    .sortedByDescending { sourceHealth.score(it.id) },
            )
        }
    }

    /**
     * 合并多源搜索结果，并按「准确率优先」排序：
     * 1. 相关度分（关键词对片名：精确 > 前缀 > 包含）降序 —— 这是「准确率优先显示」的核心；
     * 2. 源健康分（成功次数/失败冷却/平均延迟）降序 —— 高可信源的同类结果排前；
     * 3. 源优先级（主源在前）升序 —— 作为并列时的稳定兜底。
     *
     * 去重时保留相关度分最高的一条，而不是「哪个源先返回就留谁」。
     */
    private fun mergeSearchMovies(
        lines: List<ApiLine>,
        moviesByLine: Map<String, List<Movie>>,
        keyword: String,
        healthScore: (String) -> Long,
    ): List<Movie> {
        val priorityById = lines.mapIndexed { index, line -> line.id to index }.toMap()
        val query = normalizeTitle(keyword)
        val bestByKey = HashMap<String, Movie>()
        val bestScore = HashMap<String, Int>()
        moviesByLine.values.forEach { list ->
            list.forEach { movie ->
                val key = "${normalizeTitle(movie.name)}:${movie.year.trim()}"
                val relevance = scoreNameRelevance(movie.name, query)
                if (relevance > (bestScore[key] ?: -1)) {
                    bestScore[key] = relevance
                    bestByKey[key] = movie
                }
            }
        }
        return bestByKey.values
            .sortedWith(
                compareByDescending<Movie> { relevanceOf(it, query) }
                    .thenByDescending { healthScore(it.apiLineId) }
                    .thenBy { priorityById[it.apiLineId] ?: Int.MAX_VALUE },
            )
    }

    /** 片名与关键词的相关度分，去重时已记录在 bestScore，排序时复算以保证一致。 */
    private fun relevanceOf(movie: Movie, query: String): Int = scoreNameRelevance(movie.name, query)

    private companion object {
        const val MAX_PARALLEL_SOURCE_REQUESTS = 3
        const val SEARCH_TIMEOUT_MS = 3_000L
        const val DETAIL_SOURCE_TIMEOUT_MS = 4_000L
        const val SPEED_TEST_TIMEOUT_MS = 10_000L
        const val MOVIES_CACHE_TTL_MS = 5 * 60_000L
        const val DETAIL_CACHE_TTL_MS = 30 * 60_000L
    }
}

private class TimedLruCache<T>(
    private val maxEntries: Int,
) {
    private val entries = object : LinkedHashMap<String, TimedCacheEntry<T>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedCacheEntry<T>>): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(key: String, nowMs: Long): T? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs > nowMs) return entry.value
        entries.remove(key)
        return null
    }

    @Synchronized
    fun put(key: String, value: T, expiresAtMs: Long) {
        entries[key] = TimedCacheEntry(value = value, expiresAtMs = expiresAtMs)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

private data class TimedCacheEntry<T>(
    val value: T,
    val expiresAtMs: Long,
)

private class SourceHealthTracker(
    private val nowMs: () -> Long,
) {
    private val stats = mutableMapOf<String, SourceHealthStats>()

    @Synchronized
    fun recordSuccess(lineId: String, latencyMs: Long) {
        val entry = stats.getOrPut(lineId, ::SourceHealthStats)
        entry.successes += 1
        entry.averageLatencyMs = if (entry.averageLatencyMs == 0L) {
            latencyMs
        } else {
            (entry.averageLatencyMs * 3 + latencyMs) / 4
        }
    }

    @Synchronized
    fun recordFailure(lineId: String) {
        val entry = stats.getOrPut(lineId, ::SourceHealthStats)
        entry.failures += 1
        entry.cooldownUntilMs = nowMs() + FAILURE_COOLDOWN_MS
    }

    @Synchronized
    fun isCoolingDown(lineId: String): Boolean {
        return stats[lineId]?.cooldownUntilMs?.let { it > nowMs() } == true
    }

    @Synchronized
    fun score(lineId: String): Long {
        val entry = stats[lineId] ?: return 0L
        return entry.successes * 1_000L - entry.failures * 500L - entry.averageLatencyMs / 10
    }

    private companion object {
        const val FAILURE_COOLDOWN_MS = 2 * 60_000L
    }
}

private data class SourceHealthStats(
    var successes: Long = 0,
    var failures: Long = 0,
    var averageLatencyMs: Long = 0,
    var cooldownUntilMs: Long = 0,
)
