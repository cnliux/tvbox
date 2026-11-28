package com.tvbox.app.data

import android.util.Log
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.FanOutTask
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PagedMovies
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.PlaySource
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.fanOutIncrementally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
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

    // #94 单源超时：与并发数一样由 ViewModel 在设置变更后推入，搜索/详情共用。
    @Volatile
    private var sourceTimeoutMs = SEARCH_TIMEOUT_MS

    override fun setPerSourceTimeoutMs(value: Long) {
        sourceTimeoutMs = PerSourceTimeout.clamp(value)
    }

    // 源失败熔断：连续 5 次超时的源休眠 10 分钟；关闭后不剔除任何源（仅排序）。
    @Volatile
    private var sourceCircuitBreakerEnabled = true

    override fun setSourceCircuitBreakerEnabled(enabled: Boolean) {
        sourceCircuitBreakerEnabled = enabled
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
        // 历史里的 movieId 属于原源的数字命名空间；源被删除/禁用后回退到第一个可用源
        // 会用同一个 id 打开另一部影片。这里宁可返回 null 让调用方提示“源已变更”，也不跨源猜片。
        val target = apiLines.firstOrNull { it.id == apiLineId } ?: return@withContext null
        loadPrimaryDetail(target.id, id)
    }

    override suspend fun probeLatestEpisodeCount(apiLineId: String, movieId: Int, movieName: String): Int = coroutineScope {
        // 覆盖全部已启用源（含主源），按健康分排序；不剔除冷却中的线路——
        // 更新检查应当用「最快能回答的源」，而不是被某个慢/挂的主源卡住。
        val lines = searchLines(apiLineId)
        if (lines.isEmpty() || movieName.isBlank()) return@coroutineScope 0

        // 每条源做同名匹配会发 1~2 个请求；给探测内部也限流，避免一次性把所有源打满
        // （checkHistoryUpdates 的 HISTORY_UPDATE_PARALLELISM 只管“几条历史并行”，不管每条内部的源并发）。
        val sourceGate = Semaphore(MAX_PROBE_PARALLEL_SOURCES)
        val deferreds = lines.map { line ->
            async {
                sourceGate.withPermit {
                    val movie = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(DETAIL_SOURCE_TIMEOUT_MS) {
                            // 主源尽量按 id 直接取（最准）；任一条源都可用传入的片名做同名匹配，
                            // 不依赖「主源必须先响」——这正是「不被主源阻塞」的关键。
                            // 单源抛错（withFallback 在全 baseUrl 失败时会 throw）只让该源计 0，
                            // 不让异常冒泡中止整批更新检查；但 CancellationException 必须原样上抛，
                            // 不能误吞（否则会影响外层 withTimeoutOrNull 的取消状态）。
                            try {
                                if (line.id == apiLineId) {
                                    loadPrimaryDetail(line.id, movieId) ?: findSameMovie(line, movieName)
                                } else {
                                    findSameMovie(line, movieName)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                    movie?.playSources?.maxOfOrNull { it.episodes.size } ?: 0
                }
            }
        }

        // 「最快响应优先」：任一源返回了有效集数（>0）即采纳，不必等所有源跑完。
        // 全部源都跑完仍无正数时，由 finisher 用 0 收尾，避免 select 永久挂起。
        val firstHit = CompletableDeferred<Int>()
        deferreds.forEach { d ->
            launch {
                val value = d.await()
                if (value > 0) firstHit.complete(value)
            }
        }
        launch {
            deferreds.awaitAll() // 每个 async 内部都有单源超时，必然能在有限时间内结束
            if (!firstHit.isCompleted) firstHit.complete(0)
        }
        val result = firstHit.await()
        // FIX: coroutineScope 默认会等「所有子协程都完成」才返回，即使块已算出返回值。
        // 若不主动取消，最快源命中后仍会等慢/挂主源跑完（最坏 DETAIL_SOURCE_TIMEOUT_MS），
        // 违背「不被慢源阻塞」的语义。命中正数后立即取消其余源。
        if (result > 0) deferreds.forEach { it.cancel() }
        result
    }

    override fun searchProgressively(apiLineId: String, page: Int, keyword: String): Flow<MultiSourceSearchUpdate> = channelFlow {
        val requestedPage = page.coerceAtLeast(1)
        // 搜索覆盖全部已启用的线路：不剔除冷却中的线路（冷却是播放自动换线用的，
        // 不该让一次搜索把链路“拉黑”进后续所有搜索），主源在前、其余按健康分排序。
        val lines = searchLines(apiLineId)
        if (lines.isEmpty()) {
            return@channelFlow
        }

        // 并发骨架统一交给 domain 的 fan-out 工具：限流 / 单源超时 / 失败隔离 /
        // 先到先发 / 取消传播。这里只保留「取数 + 健康度记账 + 按源累积合并」的业务语义。
        val completedMovies = mutableMapOf<String, List<Movie>>()
        val tasks: List<FanOutTask<List<Movie>>> = lines.map { line ->
            FanOutTask {
                val startedAtMs = nowMs()
                try {
                    withContext(Dispatchers.IO) {
                        loadMovies(line = line, page = requestedPage, keyword = keyword).movies
                    }.also { sourceHealth.recordSuccess(line.id, nowMs() - startedAtMs) }
                } catch (error: CancellationException) {
                    // 页面退出 / 发起新搜索导致的取消必须原样上抛，不能被当成源失败。
                    throw error
                } catch (error: Throwable) {
                    // 搜索失败只记日志、不计入冷却：避免把慢/临时抖动的源从后续搜索里踢掉，
                    // 这正是“多个源只搜到 1~2 个”的根因之一（尾源被权重超时踢出后又进冷却）。
                    Log.w(TAG, "search source failed: lineId=${line.id}, type=${error::class.simpleName}")
                    emptyList<Movie>()
                }
            }
        }
        // 搜索对所有源一视同仁：统一超时预算 + 高并发（maxParallel 默认 6），
        // 不再按权重递减超时，避免尾源只剩几百毫秒而整体超时。
        // #94 预算改为可配置（setPerSourceTimeoutMs 推入，默认仍是 SEARCH_TIMEOUT_MS）。
        fanOutIncrementally(
            tasks = tasks,
            maxParallel = maxParallelSourceRequests,
            perSourceTimeoutMs = sourceTimeoutMs,
            timeoutForSource = { sourceTimeoutMs },
        ).collect { update ->
            val line = lines[update.sourceIndex]
            val movies = update.value
            Log.d(
                TAG,
                "search source done: lineId=${line.id}, name=${line.name}, ok=${movies != null}, count=${movies?.size ?: 0}, progress=${update.completedSources}/${update.totalSources}",
            )
            // collect 在单一协程上串行执行，累积状态无需 Mutex 保护。
            completedMovies[line.id] = movies.orEmpty()
            send(
                MultiSourceSearchUpdate(
                    movies = mergeSearchMovies(
                        lines = lines,
                        moviesByLine = completedMovies,
                        keyword = keyword,
                        healthScore = sourceHealth::score,
                    ),
                    completedSources = update.completedSources,
                    totalSources = update.totalSources,
                    // 手机端按源分组用的原始快照（未跨源去重）：同一部片在每个命中的源下都可见。
                    // toMap() 做不可变拷贝，避免后续累积写入改动已发出的流元素。
                    moviesBySource = completedMovies.toMap(),
                ),
            )
        }
        Log.i(TAG, "search completed: keyword=$keyword, sources=${lines.size}, results=${completedMovies.values.sumOf { it.size }}")
    }

    /**
     * 搜索用的来源列表：全部已启用线路，主源在前、其余按健康分降序。
     * 与播放自动换线用的 [prioritizedLines] 不同，**不剔除冷却中的线路**——
     * 冷却只服务于播放时段的自动换线，搜索应当覆盖所有可用源，否则尾源被临时超时踢出后又进冷却，
     * 下一次搜索就再也搜不到它，表现为“多个源只能搜到 1~2 个内容”。
     */
    private fun searchLines(primaryLineId: String): List<ApiLine> {
        val primary = requireLine(primaryLineId)
        return buildList {
            add(primary)
            addAll(
                apiLines
                    .filterNot { it.id == primary.id }
                    .sortedByDescending { sourceHealth.score(it.id) },
            )
        }
    }

    override fun getDetailProgressively(apiLineId: String, id: Int): Flow<MovieDetailUpdate> = channelFlow {
        val primary = withContext(Dispatchers.IO) {
            loadPrimaryDetail(apiLineId = apiLineId, id = id)
        }
        if (primary == null) {
            // 主源失败：不再静默结束（否则 UI 永久 loading），发一个降级 error 信号，
            // 让调用方展示"获取失败"错误提示。备用源同名匹配依赖主源片名，无法在此继续。
            val primaryLine = try { requireLine(apiLineId) } catch (e: Throwable) { null }
            // channelFlow 默认 rendezvous 容量 0：trySend 在收集方没停在 receive() 时直接丢包，
            // 改挂起 send（收集方取消时正常传播，不丢信号也不无限堆积——更新条数有界 ≤ 线路数）。
            send(
                MovieDetailUpdate(
                    movie = Movie(
                        id = id,
                        apiLineId = apiLineId,
                        apiLineName = primaryLine?.name.orEmpty(),
                        name = "",
                        typeId = 0,
                        typeName = "",
                        posterUrl = "",
                        remarks = "",
                        year = "",
                        area = "",
                        language = "",
                        actor = "",
                        director = "",
                        duration = "",
                        description = "",
                        playSources = emptyList(),
                    ),
                    completedSources = 0,
                    totalSources = searchLines(apiLineId).size,
                    error = "主线路获取失败，请稍后重试或切换线路",
                ),
            )
            return@channelFlow
        }
        val lines = prioritizedLines(apiLineId)
        val primarySource = primary.toLinePlaySource()
        val sourceMutex = Mutex()
        val matchedSources = mutableMapOf<String, PlaySource>()
        var completedSources = 1

        send(
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
                            // 挂起 send：rendezvous 通道上 trySend 会在收集方忙于消费上一条时丢包，
                            // 并发补齐（多源同毫秒完成）时最后一条全量快照可能永久缺失。
                            // Mutex 允许跨挂起点持有，收集方取消时 send 正常抛 CancellationException。
                            send(
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
            ?: candidates.firstOrNull { candidate -> isCloseTitleMatch(normalizeTitle(candidate.name), normalizedName) }
            ?: return null

        return withFallback(line) { api ->
            api.getVod(action = "videolist", ids = matched.id.toString())
                .list
                .firstOrNull()
                ?.toDomainOrNull(line)
        }
    }

    /**
     * 片名模糊匹配：要求「双向包含」且较短名占比 >= 0.85、较短名至少 4 个字符，
     * 避免把「XX2023」之类仅年份/后缀不同的片误配成同片（探测集数会被污染）。
     * 纯子串 contains 太宽松，这里用覆盖率 + 最短长度双重收紧。
     */
    private fun isCloseTitleMatch(candidate: String, target: String): Boolean {
        val a = candidate.trim()
        val b = target.trim()
        if (a.isBlank() || b.isBlank()) return false
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length < 4) return false
        if (!longer.contains(shorter)) return false
        return shorter.length.toDouble() / longer.length.toDouble() >= 0.85
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
                    .filterNot { it.id == primary.id || sourceHealth.isCoolingDown(it.id, sourceCircuitBreakerEnabled) }
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
        // 拼音首字母查询（如 "bz"）在 MacCMS 服务端只能模糊匹配 wd=bz，会返回一堆无关标题。
        // 这里在合并前做本地过滤：只保留片名拼音首字母真能拼出该查询的结果（如 "宝珠" → bz）。
        // 非纯字母查询不受影响（matches 对非字母查询返回 false，predicate 全通过）。
        val isInitialQuery = isPinyinInitialQuery(keyword)
        val filteredByLine: Map<String, List<Movie>> = if (!isInitialQuery) {
            moviesByLine
        } else {
            moviesByLine.mapValues { (_, list) ->
                list.filter { movie -> PinyinInitialsMatcher.matches(movie.name, keyword) }
            }
        }
        val bestByKey = HashMap<String, Movie>()
        val bestScore = HashMap<String, Int>()
        filteredByLine.values.forEach { list ->
            list.forEach { movie ->
                val key = "${normalizeTitle(movie.name)}:${movie.year.trim()}"
                val relevance = scoreNameRelevance(movie.name, query)
                val existing = bestByKey[key]
                // 同相关度时按源优先级决胜：主来源固定排在备选来源之前。
                // 不能依赖 moviesByLine 的迭代顺序（HashMap 无序），否则同名影片
                // 可能保留备选源那一条，导致「主来源优先」随并发完成时序抖动。
                val preferred = when {
                    existing == null -> true
                    relevance > (bestScore[key] ?: -1) -> true
                    relevance == (bestScore[key] ?: -1) ->
                        (priorityById[movie.apiLineId] ?: Int.MAX_VALUE) <
                            (priorityById[existing.apiLineId] ?: Int.MAX_VALUE)
                    else -> false
                }
                if (preferred) {
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
        const val MAX_PARALLEL_SOURCE_REQUESTS = 6
        /** 历史跨源更新检查里，每条历史对「各源」探测的最大并发源数（内部限流，避免打满全部源）。 */
        const val MAX_PROBE_PARALLEL_SOURCES = 3
        const val SEARCH_TIMEOUT_MS = 6_000L
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
        entry.consecutiveFailures = 0
        entry.cooldownUntilMs = 0
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
        entry.consecutiveFailures += 1
        // 连续失败达到阈值才进入休眠（对照设置页方案：连续 5 次超时休眠 10 分钟）。
        entry.cooldownUntilMs = if (entry.consecutiveFailures >= BREAKER_MAX_CONSECUTIVE_FAILURES) {
            nowMs() + BREAKER_COOLDOWN_MS
        } else {
            0L
        }
    }

    @Synchronized
    fun isCoolingDown(lineId: String, breakerEnabled: Boolean = true): Boolean {
        if (!breakerEnabled) return false
        return stats[lineId]?.cooldownUntilMs?.let { it > nowMs() } == true
    }

    @Synchronized
    fun score(lineId: String): Long {
        val entry = stats[lineId] ?: return 0L
        return entry.successes * 1_000L - entry.failures * 500L - entry.averageLatencyMs / 10
    }

    private companion object {
        /** 连续失败达到该阈值后休眠。 */
        const val BREAKER_MAX_CONSECUTIVE_FAILURES = 5
        /** 休眠时长：10 分钟（对照设置页方案）。 */
        const val BREAKER_COOLDOWN_MS = 10 * 60_000L
    }
}

private data class SourceHealthStats(
    var successes: Long = 0,
    var failures: Long = 0,
    var consecutiveFailures: Int = 0,
    var averageLatencyMs: Long = 0,
    var cooldownUntilMs: Long = 0,
)
