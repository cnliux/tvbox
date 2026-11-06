package com.tvbox.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvbox.app.data.AppSettingsRepository
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.AppUpdateRepository
import com.tvbox.app.data.DefaultMovieRepository
import com.tvbox.app.data.SourceConfigCodec
import com.tvbox.app.data.SourceConfigFetcher
import com.tvbox.app.data.SubscriptionDecoder
import com.tvbox.app.data.DefaultLiveRepository
import com.tvbox.app.data.DefaultDoubanHotRepository
import com.tvbox.app.data.DoubanHotRepository
import com.tvbox.app.data.HistoryRepository
import com.tvbox.app.data.LiveRepository
import com.tvbox.app.data.MovieRepository
import com.tvbox.app.data.PlaybackHealthRepository
import com.tvbox.app.data.SearchHotWordRepository
import com.tvbox.app.data.DefaultSearchHotWordRepository
import com.tvbox.app.data.SearchKeywordExpander
import com.tvbox.app.data.DefaultSearchKeywordExpander
import com.tvbox.app.data.EmbeddedPlatformLiveRepository
import com.tvbox.app.data.PlatformLiveCategoryTree
import com.tvbox.app.data.PlatformLiveRepository
import com.tvbox.app.data.PlatformLiveFavoritesRepository
import com.tvbox.app.data.LiveLinePreference
import com.tvbox.app.data.LiveDomainScoreStore
import com.tvbox.app.data.key
import com.tvbox.app.data.RemoteManageRequest
import com.tvbox.app.data.RemoteManageServer
import com.tvbox.app.data.RemoteManageSite
import com.tvbox.app.data.RemoteManageSnapshot
import com.tvbox.app.data.RemoteManageVideoSource
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.CARD_AREA_MAX_ROWS
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.SOURCE_CARD_MAX_COUNT
import com.tvbox.app.domain.effectiveMode
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.AppUpdate
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.dryRunLiveSource
import com.tvbox.app.domain.DoubanHotItem
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.liveLineDomain
import com.tvbox.app.domain.rankLiveLineIndicesByDomain
import com.tvbox.app.domain.LiveSourceDryRun
import com.tvbox.app.domain.measureLiveLineLatencyMs
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.mergeMoviesBySourceAndId
import com.tvbox.app.domain.nextRotatedPlatformLiveCandidate
import com.tvbox.app.domain.resolveEffectiveEpgUrl
import com.tvbox.app.data.EpgRepository
import com.tvbox.app.data.RemoteEpgRepository
import com.tvbox.app.data.LiveFavoritesRepository
import com.tvbox.app.data.liveFavoriteKey
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.findEpgPrograms
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PlaybackAgent
import com.tvbox.app.domain.PlaybackAgentDecision
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.ResolvedPlatformLiveStream
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.TvTheme
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.findBestTitleMatchIndex
import com.tvbox.app.domain.playbackHealthKey
import com.tvbox.app.domain.toApiLines
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class TvScreen {
    Home,
    History,
    Search,
    Detail,
    Player,
    Live,
    PlatformLive,
    Settings,
    SourceManager,
    LiveSourceManager,
    VideoSourceManager,
}

enum class HomeFeedMode {
    DoubanHot,
    Catalog,
}

enum class PlatformLiveDestination {
    Sites,
    Favorites,
    ParentCategories,
    Categories,
    Rooms,
    Player,
}

/** 电视直播面板的两级层级：先分类、再频道。 */
enum class LiveGroupStage {
    Groups,
    Channels,
}

/** 直播首页的常用分类直达入口：平台 + 该平台的二级分类。 */
data class PlatformLiveQuickCategory(
    val site: PlatformLiveSite,
    val category: PlatformLiveCategory,
)

data class TvBoxUiState(
    val screen: TvScreen = TvScreen.Home,
    val apiLines: List<ApiLine> = emptyList(),
    // 包含已屏蔽/已删除的源，仅用于源管理页展示与恢复。
    val allSourceLines: List<ApiLine> = emptyList(),
    val selectedApiLineId: String = "",
    /**
     * 生效设备模式（计算值）：appSettings.deviceMode 为 Auto 时取系统识别结果，
     * 手动值则原样生效；永不为 Auto。UI 布局判定一律使用本字段而非保存的偏好。
     */
    val effectiveDeviceMode: DeviceMode = DeviceMode.Television,
    val categories: List<Category> = emptyList(),
    val homeFeedMode: HomeFeedMode = HomeFeedMode.DoubanHot,
    val selectedParentCategoryId: Int? = null,
    val selectedCategoryId: Int? = null,
    val movies: List<Movie> = emptyList(),
    val doubanHotMovies: List<DoubanHotItem> = emptyList(),
    val doubanHotFallbackActive: Boolean = false,
    val homeNotice: String? = null,
    val resolvingDoubanHotId: String? = null,
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
    val homeLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val homeError: String? = null,
    val historyItems: List<WatchHistoryItem> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Movie> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val searchCompletedSources: Int = 0,
    val searchTotalSources: Int = 0,
    val searchHotWords: List<String> = emptyList(),
    val searchHotWordsLoading: Boolean = false,
    val detailMovie: Movie? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val detailCompletedSources: Int = 0,
    val detailTotalSources: Int = 0,
    val detailReturnScreen: TvScreen = TvScreen.Home,
    val selectedSourceIndex: Int = 0,
    val selectedEpisodeIndex: Int = 0,
    val playerSourceIndex: Int = 0,
    val playerEpisodeIndex: Int = 0,
    val playerStartPositionMs: Long = 0L,
    val playerSpeed: Float = 1f,
    val playerPlayWhenReady: Boolean = true,
    val live: LiveUiState = LiveUiState(),
    val platformLive: PlatformLiveUiState = PlatformLiveUiState(),
    val update: UpdateUiState = UpdateUiState(),
    val appSettings: AppSettings = AppSettings(),
    val playbackHealth: PlaybackHealthSnapshot = PlaybackHealthSnapshot(),
    val remoteManageDialogVisible: Boolean = false,
    val remoteManageServerUrl: String? = null,
    val remoteManageServerError: String? = null,
    val remoteManageMessage: String? = null,
    val source: SourceManagerUiState = SourceManagerUiState(),
    /** 设置持久化失败时的一次性提示，由 UI 消费后清空。 */
    val settingsSaveError: String? = null,
) {
    // B3 兼容委托：保留原字段名，避免大面积改动 Compose/View 读取点；新逻辑优先写子 state.copy(...)。
    val availableUpdate: AppUpdate? get() = update.availableUpdate
    val updateDialogVisible: Boolean get() = update.dialogVisible
    val updateChecking: Boolean get() = update.checking
    val updateDownloading: Boolean get() = update.downloading
    val updateDownloadProgress: Int? get() = update.downloadProgress
    val updateDownloadedApkPath: String? get() = update.downloadedApkPath
    val updateAutoInstallPath: String? get() = update.autoInstallPath
    val updateAwaitingInstallPermission: Boolean get() = update.awaitingInstallPermission
    val updateExporting: Boolean get() = update.exporting
    val updateExportMessage: String? get() = update.exportMessage
    val updateError: String? get() = update.error

    val sourceLatencies: Map<String, Long?> get() = source.latencies
    val sourceTesting: Boolean get() = source.testing
    val sourceTestCompleted: Int get() = source.testCompleted
    val sourceTestTotal: Int get() = source.testTotal
    val sourceManagerMessage: String? get() = source.managerMessage
    val detailSourceTesting: Boolean get() = source.detailTesting
    val detailSourceTestCompleted: Int get() = source.detailTestCompleted
    val detailSourceTestTotal: Int get() = source.detailTestTotal
    val detailSourceMessage: String? get() = source.detailMessage
    val sourceImporting: Boolean get() = source.importing
    val sourceImportMessage: String? get() = source.importMessage

    val liveChannels: List<LiveChannel> get() = live.channels
    val liveChannelIndex: Int get() = live.channelIndex
    val liveLineIndex: Int get() = live.lineIndex
    val liveGroupIndex: Int get() = live.groupIndex
    val liveSelectedSourceIndex: Int get() = live.selectedSourceIndex
    val liveLoading: Boolean get() = live.loading
    val liveError: String? get() = live.error
    val liveNotice: String? get() = live.notice
    val liveReconnectNonce: Int get() = live.reconnectNonce
    val liveGroupPanelStage: LiveGroupStage get() = live.groupPanelStage
    val liveFavoriteChannelNumbers: Set<Int> get() = live.favoriteChannelNumbers
    val liveFavoriteKeys: Set<String> get() = live.favoriteKeys
    val liveEpgByChannel: Map<String, List<EpgProgram>> get() = live.epgByChannel
    val liveEpgLoaded: Boolean get() = live.epgLoaded
    val liveEpgBundledUrl: String get() = live.epgBundledUrl
    val liveLineLatencies: Map<String, Long?> get() = live.lineLatencies
    val liveLineTesting: Boolean get() = live.lineTesting

    val platformLiveDestination: PlatformLiveDestination get() = platformLive.destination
    val platformLiveSites: List<PlatformLiveSite> get() = platformLive.sites
    val platformLiveAllSites: List<PlatformLiveSite> get() = platformLive.allSites
    val platformLiveQuickCategories: List<PlatformLiveQuickCategory> get() = platformLive.quickCategories
    val platformLiveFavorites: List<PlatformLiveRoom> get() = platformLive.favorites
    val platformLiveFavoriteError: String? get() = platformLive.favoriteError
    val platformLivePlayerReturnDestination: PlatformLiveDestination get() = platformLive.playerReturnDestination
    val platformLiveSelectedSite: PlatformLiveSite? get() = platformLive.selectedSite
    val platformLiveParentCategories: List<PlatformLiveParentCategory> get() = platformLive.parentCategories
    val platformLiveSelectedParentCategory: PlatformLiveParentCategory? get() = platformLive.selectedParentCategory
    val platformLiveParentIndex: Int get() = platformLive.parentIndex
    val platformLiveCategories: List<PlatformLiveCategory> get() = platformLive.categories
    val platformLiveSelectedCategory: PlatformLiveCategory? get() = platformLive.selectedCategory
    val platformLiveCategoryIndex: Int get() = platformLive.categoryIndex
    val platformLiveRooms: List<PlatformLiveRoom> get() = platformLive.rooms
    val platformLiveRoomIndex: Int get() = platformLive.roomIndex
    val platformLiveRoomPageCount: Int get() = platformLive.roomPageCount
    val platformLiveRoomPage: Int get() = platformLive.roomPage
    val platformLiveLoadingMore: Boolean get() = platformLive.loadingMore
    val platformLiveLoading: Boolean get() = platformLive.loading
    val platformLiveResolving: Boolean get() = platformLive.resolving
    val platformLiveError: String? get() = platformLive.error
    val platformLiveNotice: String? get() = platformLive.notice
    val platformLiveStream: ResolvedPlatformLiveStream? get() = platformLive.stream

    val canLoadMore: Boolean
        get() = page < pageCount && !homeLoading && !loadingMore

    val isShowingDoubanHot: Boolean
        get() = homeFeedMode == HomeFeedMode.DoubanHot && !doubanHotFallbackActive

    val homeItemCount: Int
        get() = if (isShowingDoubanHot) doubanHotMovies.size else movies.size

    val selectedApiLine: ApiLine?
        get() = apiLines.firstOrNull { it.id == selectedApiLineId }
}

class TvBoxViewModel(
    /** Application Context：仅用于设备模式自动识别等系统特征读取，不持有 Activity。 */
    private val appContext: android.content.Context,
    private val repository: MovieRepository = DefaultMovieRepository(),
    private val doubanHotRepository: DoubanHotRepository = DefaultDoubanHotRepository(),
    private val searchHotWordRepository: SearchHotWordRepository = DefaultSearchHotWordRepository(),
    private val searchKeywordExpander: SearchKeywordExpander = DefaultSearchKeywordExpander(),
    private val liveRepository: LiveRepository = DefaultLiveRepository(),
    private val platformLiveRepository: PlatformLiveRepository = EmbeddedPlatformLiveRepository(),
    private val appUpdateRepository: AppUpdateRepository = DefaultAppUpdateRepositoryPlaceholder(),
    private val appSettingsRepository: AppSettingsRepository = DefaultAppSettingsRepositoryPlaceholder(),
    private val playbackHealthRepository: PlaybackHealthRepository = DefaultPlaybackHealthRepositoryPlaceholder(),
    private val historyRepository: HistoryRepository,
    private val platformLiveFavoritesRepository: PlatformLiveFavoritesRepository,
    private val liveLinePrefs: LiveLinePreference? = null,
    /** 电视直播节目单（XMLTV）仓库；无网络配置时 fetch 返回空 Map。 */
    private val epgRepository: EpgRepository = RemoteEpgRepository(),
    /** 电视直播频道收藏仓库；null 时收藏退化为仅内存 Set（如未注入的单测环境）。 */
    private val liveFavoritesRepository: LiveFavoritesRepository? = null,
    /** 直播线路按域名的成功/失败评分；用于同类源较多时优先选"最近确实能播"的域名。 */
    private val liveDomainScores: LiveDomainScoreStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(TvBoxUiState())
    val state: StateFlow<TvBoxUiState> = _state.asStateFlow()

    private var homeJob: Job? = null
    private var categoriesJob: Job? = null
    private var doubanHotResolveJob: Job? = null
    private var searchJob: Job? = null
    private var searchHotWordsJob: Job? = null
    private var detailJob: Job? = null
    private var historyResumeJob: Job? = null
    private var liveJob: Job? = null
    private var platformLiveJob: Job? = null
    private var platformLiveResolveJob: Job? = null
    private var platformLiveQuickJob: Job? = null
    private var allPlatformLiveSites: List<PlatformLiveSite> = emptyList()
    private val quickCategoryTreeCache = mutableMapOf<String, PlatformLiveCategoryTree>()

    /** 直播首页常用分类直达：平台 ID → 二级分类名称。 */
    private val quickCategoryTargets = listOf(
        "huya" to "一起看",
        "douyu" to "一起看",
    )
    private var platformLiveRecoveryInProgress = false
    // 平台直播恢复节流：连续失败窗口内限制次数，避免虎牙 FLV 周期性关流造成无限轮换/重解析。
    private var platformLiveRecoveryWindowStartMs = 0L
    private var platformLiveRecoveryWindowAttempts = 0
    // 同一条候选线路的原地重连次数（缓冲卡死/被服务端正常关流时先用完再换线路）。
    private var platformLiveSameLineRetries = 0
    // 电视直播：缓冲/卡死类失败在当前线路上的原地重连次数。
    private var liveStallRetries = 0
    // 电视直播：记录最近一次"所有线路均失败"的频道号，避免重复提示。
    private var lastExhaustedChannelNumber: Int? = null
    // 电视直播：最近一次已记入成功评分的域名（同一域名连续就绪不重复累加）。
    private var lastScoredDomain: String? = null
    // 切换订阅后要恢复的分类名（新订阅没有该分类时回"全部"）。
    private var pendingLiveGroupRestore: String? = null
    private val platformLiveFavoritesMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private val liveFavoritesMutex = Mutex()
    private var epgJob: Job? = null
    private var liveLineTestJob: Job? = null
    private var updateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateInstallStateJob: Job? = null
    private var settingsWriteJob: Job? = null

    /** 取消旧 Job 并启动新协程（ViewModel 侧常用的“最新请求覆盖”模式）。 */
    private fun relaunch(current: Job?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        current?.cancel()
        return viewModelScope.launch(block = block)
    }
    private var sourceTestJob: Job? = null
    private var detailSourceTestJob: Job? = null
    private var detailAutoTestedKey: String? = null
    private var remoteManageServer: RemoteManageServer? = null
    /** 远程网页里最近一次“获取分类”的视频源与其分类列表。 */
    private var remoteCategorySourceId: String = ""
    private var remoteCategoryNames: List<String> = emptyList()
    private val playbackAgent = PlaybackAgent()

    init {
        restorePendingUpdateDownload()
        val defaultApiLineId = repository.apiLines.firstOrNull()?.id.orEmpty()
        _state.update {
            it.copy(
                apiLines = repository.apiLines,
                allSourceLines = repository.allApiLines,
                selectedApiLineId = defaultApiLineId,
            )
        }
        loadPlatformLiveFavorites()
        loadLiveFavorites()
        viewModelScope.launch {
            val settings = runCatching { appSettingsRepository.getSettings() }
                .getOrDefault(AppSettings())
            repository.updateCustomApiLines(settings.customVideoApiLines.toApiLines())
            repository.setDisabledLineIds(settings.blockedSourceIds.toSet())
            repository.setMaxParallelSourceRequests(settings.maxParallelSourceRequests)
            applyLiveSourceConfig(settings)
            val availableApiLines = orderApiLines(repository.apiLines, settings.sourceOrder)
            val allApiLines = orderApiLines(repository.allApiLines, settings.sourceOrder)
            val selectedApiLineId = settings.homeApiLineId
                .takeIf { apiLineId -> availableApiLines.any { it.id == apiLineId } }
                ?: defaultApiLineId
            val normalizedSettings = settings.copy(homeApiLineId = selectedApiLineId)
            val playbackHealth = runCatching { playbackHealthRepository.getSnapshot() }
                .getOrDefault(PlaybackHealthSnapshot())
            val speedLatencies = runCatching { appSettingsRepository.getSpeedLatencies() }
                .getOrDefault(emptyMap())
            _state.update {
                it.copy(
                    apiLines = availableApiLines,
                    allSourceLines = allApiLines,
                    selectedApiLineId = selectedApiLineId,
                    appSettings = normalizedSettings,
                    effectiveDeviceMode = normalizedSettings.effectiveMode(appContext),
                    playbackHealth = playbackHealth,
                    source = SourceManagerUiState(latencies = speedLatencies),
                )
            }
            loadHistory()
            refreshHome()
            if (normalizedSettings.checkUpdatesOnStartup) {
                checkForAppUpdate()
            }
            loadLiveEpg()
        }
    }

    fun refreshHome(forceRefresh: Boolean = false) {
        loadCategoriesOnly()
        _state.update { state ->
            if (state.homeFeedMode == HomeFeedMode.DoubanHot) {
                state.copy(doubanHotFallbackActive = false, homeNotice = null)
            } else {
                state
            }
        }
        loadHomePage(reset = true, forceRefresh = forceRefresh)
    }

    fun selectDoubanHot() {
        val current = _state.value
        if (current.isShowingDoubanHot && current.doubanHotMovies.isNotEmpty()) return
        _state.update {
            it.copy(
                homeFeedMode = HomeFeedMode.DoubanHot,
                selectedParentCategoryId = null,
                selectedCategoryId = null,
                movies = emptyList(),
                doubanHotMovies = emptyList(),
                doubanHotFallbackActive = false,
                homeNotice = null,
                page = 1,
                pageCount = 1,
                total = 0,
            )
        }
        loadHomePage(reset = true)
    }

    fun selectParentCategory(parentCategoryId: Int) {
        val current = _state.value
        if (current.selectedParentCategoryId == parentCategoryId && current.selectedCategoryId == null) return
        _state.update {
            it.copy(
                homeFeedMode = HomeFeedMode.Catalog,
                selectedParentCategoryId = parentCategoryId,
                selectedCategoryId = null,
                movies = emptyList(),
                doubanHotFallbackActive = false,
                homeNotice = null,
                page = 1,
                pageCount = 1,
                total = 0,
            )
        }
        loadHomePage(reset = true)
    }

    fun selectChildCategory(categoryId: Int) {
        val current = _state.value
        if (current.selectedCategoryId == categoryId) return
        val parentCategoryId = current.categories
            .firstOrNull { it.id == categoryId }
            ?.parentId
            ?.takeIf { it > 0 }
            ?: current.selectedParentCategoryId
        _state.update {
            it.copy(
                homeFeedMode = HomeFeedMode.Catalog,
                selectedParentCategoryId = parentCategoryId,
                selectedCategoryId = categoryId,
                movies = emptyList(),
                doubanHotFallbackActive = false,
                homeNotice = null,
                page = 1,
                pageCount = 1,
                total = 0,
            )
        }
        loadHomePage(reset = true)
    }

    fun selectApiLine(apiLineId: String) {
        updateHomeApiLine(apiLineId)
    }

    fun updateHomeApiLine(apiLineId: String) {
        if (_state.value.apiLines.none { it.id == apiLineId }) return
        if (_state.value.selectedApiLineId == apiLineId) return
        val settings = _state.value.appSettings.copy(homeApiLineId = apiLineId)
        saveSettings(settings)
        _state.update {
            it.copy(
                appSettings = settings,
                selectedApiLineId = apiLineId,
                selectedParentCategoryId = null,
                selectedCategoryId = null,
                categories = emptyList(),
                movies = emptyList(),
                doubanHotMovies = emptyList(),
                doubanHotFallbackActive = false,
                homeNotice = null,
                page = 1,
                pageCount = 1,
                total = 0,
                homeError = null,
            )
        }
        refreshHome()
    }

    /** 首页分页防抖：防止快速滑动触发多次请求。 */
    private var lastLoadNextPageMs = 0L

    /** 触底加载失败后暂停自动重试：记录失败页与失败时间，避免失败时每 800ms 无限重发。 */
    private var loadMoreRetryPage = -1
    private var loadMoreRetryAtMs = 0L

    fun loadNextPage() {
        val current = _state.value
        if (!current.canLoadMore) return
        val nextPage = current.page + 1
        val now = System.currentTimeMillis()
        if (loadMoreRetryPage == nextPage && now - loadMoreRetryAtMs < HOME_LOAD_MORE_RETRY_COOLDOWN_MS) return
        if (now - lastLoadNextPageMs < 800L) return
        lastLoadNextPageMs = now
        loadHomePage(reset = false, forceRefresh = current.homeNotice != null)
    }

    fun openHome() {
        _state.update {
            it.copy(
                screen = TvScreen.Home,
                detailReturnScreen = TvScreen.Home,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Sites,
                stream = null),
            )
        }
    }

    fun openSearch() {
        _state.update { it.copy(screen = TvScreen.Search, searchError = null) }
        loadSearchHotWords()
    }

    fun refreshSearchHotWords() {
        loadSearchHotWords(forceRefresh = true)
    }

    private fun loadSearchHotWords(forceRefresh: Boolean = false) {
        if (searchHotWordsJob?.isActive == true) return
        searchHotWordsJob = viewModelScope.launch {
            _state.update { it.copy(searchHotWordsLoading = true) }
            val words = runCatching {
                searchHotWordRepository.getHotWords(
                    forceRefresh = forceRefresh,
                    limit = _state.value.appSettings.hotWordCount,
                )
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    searchHotWords = words.ifEmpty { it.searchHotWords },
                    searchHotWordsLoading = false,
                )
            }
        }
    }

    fun openLive() {
        _state.update { it.copy(screen = TvScreen.Live, live = it.live.copy(error = null)) }
        if (_state.value.live.channels.isEmpty()) {
            loadLiveChannels()
        }
    }

    fun openPlatformLive() {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Sites,
                error = null,
                stream = null),
            )
        }
        if (_state.value.platformLive.sites.isEmpty()) {
            loadPlatformLiveSites()
        }
    }

    fun openSettings() {
        _state.update { it.copy(screen = TvScreen.Settings, update = it.update.copy(error = null)) }
        refreshPlaybackHealth()
    }

    fun openSourceManager() {
        _state.update { it.copy(screen = TvScreen.SourceManager) }
    }

    fun openLiveSourceManager() {
        _state.update {
            it.copy(
                screen = TvScreen.LiveSourceManager,
                live = it.live.copy(channels = emptyList(), error = null),
            )
        }
        viewModelScope.launch {
            runCatching { platformLiveRepository.getSites() }
                .onSuccess { sites ->
                    allPlatformLiveSites = sites
                    _state.update { it.copy(platformLive = it.platformLive.copy(allSites = sites)) }
                }
        }
    }

    fun openVideoSourceManager() {
        _state.update { it.copy(screen = TvScreen.VideoSourceManager) }
    }

    fun refreshLive() {
        loadLiveChannels()
    }

    fun refreshPlatformLive() {
        when (_state.value.platformLive.destination) {
            PlatformLiveDestination.Sites -> loadPlatformLiveSites()
            PlatformLiveDestination.Favorites -> loadPlatformLiveFavorites()
            PlatformLiveDestination.ParentCategories -> _state.value.platformLive.selectedSite?.let(::loadPlatformLiveCategories)
            PlatformLiveDestination.Categories -> _state.value.platformLive.selectedSite?.let(::loadPlatformLiveCategories)
            PlatformLiveDestination.Rooms -> _state.value.platformLive.selectedCategory?.let {
                loadPlatformLiveRooms(it, reset = true)
            }
            PlatformLiveDestination.Player -> resolvePlatformLiveRoom(forceRefresh = true)
        }
    }

    fun checkForAppUpdate(showError: Boolean = false) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(update = it.update.copy(checking = true, error = null)) }
            runCatching { appUpdateRepository.checkForUpdate(BuildConfig.VERSION_CODE.toLong()) }
                .onSuccess { update ->
                    _state.update {
                        if (update == null) {
                            it.copy(update = it.update.copy(checking = false))
                        } else if (it.update.downloading || it.update.downloadedApkPath != null) {
                            it.copy(update = it.update.copy(checking = false))
                        } else {
                            it.copy(
                                update = it.update.copy(
                                    availableUpdate = update,
                                    dialogVisible = true,
                                    checking = false,
                                    downloading = false,
                                    downloadProgress = null,
                                    downloadedApkPath = null,
                                    error = null,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            update = it.update.copy(
                                checking = false,
                                dialogVisible = showError && it.update.availableUpdate != null,
                                error = if (showError) error.userMessage() else null,
                            ),
                        )
                    }
                }
        }
    }

    fun dismissUpdateDialog() {
        if (_state.value.update.availableUpdate?.force == true) return
        _state.update { it.copy(update = it.update.copy(dialogVisible = false, error = null)) }
    }

    fun startUpdateDownload() {
        val update = _state.value.update.availableUpdate ?: return
        if (_state.value.update.downloading) return
        updateDownloadJob?.cancel()
        _state.update {
            it.copy(update = it.update.copy(
                dialogVisible = false, downloading = true,
                downloadProgress = 0, downloadedApkPath = null,
                autoInstallPath = null, awaitingInstallPermission = false, error = null,
                exporting = false, exportMessage = null,
            ))
        }
        updateDownloadJob = viewModelScope.launch {
            try {
                updateInstallStateJob?.join()
                val apkFile = appUpdateRepository.downloadUpdate(update) { progress ->
                    _state.update { s -> s.copy(update = s.update.copy(downloadProgress = progress.takeIf { value -> value >= 0 })) }
                }
                val autoInstall = appUpdateRepository.shouldAutoInstall(apkFile.absolutePath)
                val awaitingPermission = appUpdateRepository.isAwaitingInstallPermission(apkFile.absolutePath)
                _state.update {
                    it.copy(update = it.update.copy(
                        downloading = false, downloadProgress = 100,
                        downloadedApkPath = apkFile.absolutePath,
                        awaitingInstallPermission = awaitingPermission,
                        autoInstallPath = apkFile.absolutePath.takeIf { autoInstall }, error = null,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(update = it.update.copy(
                        downloading = false, downloadProgress = null,
                        downloadedApkPath = null, autoInstallPath = null,
                        awaitingInstallPermission = false, error = error.userMessage(),
                    ))
                }
            }
        }
    }

    private fun restorePendingUpdateDownload() {
        viewModelScope.launch {
            val pending = runCatching { appUpdateRepository.pendingDownload() }.getOrNull() ?: return@launch
            if (_state.value.update.downloading) return@launch
            _state.update { it.copy(update = it.update.copy(availableUpdate = pending, dialogVisible = false)) }
            startUpdateDownload()
        }
    }

    fun markUpdateInstallPrompted(apkPath: String) {
        _state.update { it.copy(update = it.update.copy(autoInstallPath = null, dialogVisible = false, awaitingInstallPermission = false, error = null)) }
        persistUpdateInstallState { appUpdateRepository.markInstallPrompted(apkPath) }
    }

    fun beginUpdateInstallAttempt(apkPath: String): Boolean {
        if (_state.value.update.downloadedApkPath != apkPath || _state.value.update.exporting) return false
        // Consume only the UI event, not the persistent successful-launch marker.
        _state.update { it.copy(update = it.update.copy(autoInstallPath = null, dialogVisible = false, awaitingInstallPermission = false, error = null)) }
        return true
    }

    fun awaitUpdateInstallPermission(apkPath: String, message: String? = null) {
        if (_state.value.update.downloadedApkPath != apkPath) return
        _state.update { it.copy(update = it.update.copy(autoInstallPath = null, awaitingInstallPermission = true, error = message)) }
        persistUpdateInstallState { appUpdateRepository.setAwaitingInstallPermission(apkPath, true) }
    }

    fun resumeUpdateInstallAfterPermission(apkPath: String) {
        _state.update {
            if (it.update.downloadedApkPath == apkPath && it.update.awaitingInstallPermission)
                it.copy(update = it.update.copy(autoInstallPath = apkPath, error = null)) else it
        }
    }

    fun reportUpdateInstallFailure(apkPath: String, message: String, fileMissing: Boolean = false) {
        _state.update {
            it.copy(update = it.update.copy(
                autoInstallPath = null, awaitingInstallPermission = false,
                downloadedApkPath = if (fileMissing) null else it.update.downloadedApkPath, error = message,
            ))
        }
        persistUpdateInstallState {
            if (fileMissing) appUpdateRepository.forgetDownload(apkPath)
            else appUpdateRepository.setAwaitingInstallPermission(apkPath, false)
        }
    }

    fun beginUpdateApkExport(apkPath: String): Boolean {
        if (_state.value.update.downloadedApkPath != apkPath || _state.value.update.exporting) return false
        _state.update { it.copy(update = it.update.copy(autoInstallPath = null, awaitingInstallPermission = false, exporting = true, exportMessage = null, error = null)) }
        persistUpdateInstallState { appUpdateRepository.chooseManualInstall(apkPath) }
        return true
    }

    fun cancelUpdateApkExport(message: String = "已取消保存，安装包仍保留") {
        _state.update { it.copy(update = it.update.copy(exporting = false, exportMessage = message)) }
    }

    fun saveUpdateApk(apkPath: String, destination: android.net.Uri?) {
        _state.update { it.copy(update = it.update.copy(exporting = true, exportMessage = null)) }
        viewModelScope.launch {
            try {
                updateInstallStateJob?.join()
                val location = appUpdateRepository.exportUpdateApk(apkPath, destination)
                _state.update { it.copy(update = it.update.copy(exporting = false, exportMessage = "已保存：$location。请通过文件管理器打开安装")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TVBoxUpdate", "Unable to export update APK", error)
                _state.update { it.copy(update = it.update.copy(exporting = false, exportMessage = "保存失败：${error.userMessage()}")) }
            }
        }
    }

    private fun persistUpdateInstallState(write: suspend () -> Unit) {
        val previous = updateInstallStateJob
        updateInstallStateJob = viewModelScope.launch {
            previous?.join()
            try { write() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("TVBoxUpdate", "Unable to persist install state", error) }
        }
    }

    fun openHistory() {
        loadHistory()
        _state.update { it.copy(screen = TvScreen.History) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { historyRepository.clearHistory() }
                .onSuccess { history ->
                    _state.update { it.copy(historyItems = history) }
                }
        }
    }

    fun updateStartupUpdateCheck(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(checkUpdatesOnStartup = enabled)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updatePlaybackAgentAutoSwitch(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(playbackAgentAutoSwitchEnabled = enabled)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updateHomeContinueWatching(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(homeContinueWatchingEnabled = enabled)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updateVideoScaleMode(mode: com.tvbox.app.domain.VideoScaleMode) {
        val settings = _state.value.appSettings.copy(videoScaleMode = mode)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun openRemoteManageDialog() {
        closeRemoteManageDialog(stopOnly = true)
        val server = RemoteManageServer(
            scope = viewModelScope,
            snapshot = ::buildRemoteManageSnapshot,
            onSubmit = ::handleRemoteManageRequest,
        )
        remoteManageServer = server
        _state.update {
            it.copy(
                remoteManageDialogVisible = true,
                remoteManageServerUrl = null,
                remoteManageServerError = null,
                remoteManageMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { server.start() }
                .onSuccess { session ->
                    _state.update {
                        it.copy(
                            remoteManageServerUrl = session.url,
                            remoteManageServerError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            remoteManageServerUrl = null,
                            remoteManageServerError = "启动远程管理服务失败：${error.userMessage()}",
                        )
                    }
                }
        }
    }

    fun closeRemoteManageDialog() {
        closeRemoteManageDialog(stopOnly = false)
    }

    private fun closeRemoteManageDialog(stopOnly: Boolean) {
        remoteManageServer?.stop()
        remoteManageServer = null
        if (!stopOnly) {
            _state.update {
                it.copy(
                    remoteManageDialogVisible = false,
                    remoteManageServerUrl = null,
                    remoteManageServerError = null,
                )
            }
        }
    }

    private fun buildRemoteManageSnapshot(): RemoteManageSnapshot {
        val current = _state.value
        val settings = current.appSettings
        val sites = allPlatformLiveSites.ifEmpty { current.platformLive.allSites }.map { site ->
            RemoteManageSite(
                id = site.id,
                name = site.name,
                blocked = site.id in settings.blockedPlatformSiteIds,
            )
        }
        val videoSources = current.allSourceLines.map { line ->
            RemoteManageVideoSource(
                id = line.id,
                name = line.name,
                url = line.baseUrls.firstOrNull().orEmpty(),
                blocked = line.id in settings.blockedSourceIds,
                isHome = line.id == current.selectedApiLineId,
                latencyMs = current.source.latencies[line.id],
            )
        }
        return RemoteManageSnapshot(
            videoSources = videoSources,
            customLiveUrls = settings.liveSourceUrls,
            liveDefaultIndex = settings.liveSourceDefaultIndex,
            epgUrl = settings.epgUrl,
            sites = sites,
            blockedVideoCategories = settings.blockedVideoCategories,
            knownVideoCategories = if (remoteCategorySourceId.isNotBlank()) {
                remoteCategoryNames
            } else {
                current.categories.map { it.name }.distinct()
            },
            categorySourceId = remoteCategorySourceId,
            categorySourceName = current.allSourceLines
                .firstOrNull { it.id == remoteCategorySourceId }?.name.orEmpty(),
        )
    }

    private suspend fun handleRemoteManageRequest(request: RemoteManageRequest): String? {
        return when (request.action) {
            "source-import" -> {
                val source = request.content.trim().ifBlank { request.url.trim() }
                if (source.isBlank()) {
                    "请填写订阅地址或粘贴 JSON / TVBox 配置内容。"
                } else {
                    importSourceConfigInternal(source)
                }
            }
            "source-home" -> {
                val id = request.id.trim()
                if (_state.value.apiLines.none { it.id == id }) {
                    "该视频源已删除或已屏蔽，请先恢复。"
                } else {
                    updateHomeApiLine(id)
                    "已把“${_state.value.allSourceLines.firstOrNull { it.id == id }?.name ?: id}”设为首页源。"
                }
            }
            "source-block" -> {
                blockSource(request.id.trim())
                "已屏蔽该视频源。"
            }
            "source-unblock" -> {
                unblockSource(request.id.trim())
                "已取消屏蔽该视频源。"
            }
            "source-delete" -> {
                deleteApiLine(request.id.trim())
                "已删除该视频源。"
            }
            "source-test" -> {
                testAllSourcesAndSort()
                "已开始测速，完成后刷新本页可按延迟排序查看。"
            }
            "source-categories" -> {
                val id = request.id.trim()
                val line = _state.value.allSourceLines.firstOrNull { it.id == id }
                if (line == null) {
                    "视频源不存在，可能已被删除。"
                } else {
                    val categories = runCatching { repository.getCategories(id) }.getOrNull()
                    if (categories.isNullOrEmpty()) {
                        "未获取到“${line.name}”的分类，请检查该源地址是否可用。"
                    } else {
                        remoteCategorySourceId = id
                        remoteCategoryNames = categories.map { it.name }.distinct()
                        "已获取“${line.name}”的分类 ${remoteCategoryNames.size} 个，点击分类即可屏蔽或恢复。"
                    }
                }
            }
            "category-toggle" -> {
                val name = request.name.trim()
                if (name.isBlank()) {
                    "分类名称不能为空。"
                } else if (name in _state.value.appSettings.blockedVideoCategories) {
                    removeBlockedVideoCategory(name)
                    "已恢复视频分类：$name"
                } else {
                    addBlockedVideoCategory(name)
                    "已屏蔽视频分类：$name"
                }
            }
            "video-add" -> {
                val error = applyCustomVideoApiFromPhone(
                    lineName = request.name,
                    apiUrl = request.url,
                )
                error ?: "已添加视频接口：${request.name.trim()}"
            }
            "video-delete" -> {
                val id = request.id.ifBlank {
                    _state.value.appSettings.customVideoApiLines
                        .firstOrNull { it.baseUrl.equals(request.url.trim(), ignoreCase = true) }
                        ?.id.orEmpty()
                }
                if (id.isBlank()) {
                    "未找到要删除的视频接口。"
                } else {
                    deleteApiLine(id)
                    "已删除视频接口：${request.name.ifBlank { id }}"
                }
            }
            "live-add" -> {
                val url = request.url.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "直播源地址需要以 http:// 或 https:// 开头。"
                } else if (url in _state.value.appSettings.liveSourceUrls) {
                    "该直播源已存在。"
                } else {
                    updateLiveSourceUrls(_state.value.appSettings.liveSourceUrls + url, _state.value.appSettings.liveSourceDefaultGroup)
                    "已添加直播源：$url"
                }
            }
            "live-delete" -> {
                val url = request.url.trim()
                updateLiveSourceUrls(_state.value.appSettings.liveSourceUrls - url, _state.value.appSettings.liveSourceDefaultGroup)
                "已删除直播源：$url"
            }
            "live-default-set" -> {
                val index = request.value.trim().toIntOrNull()
                if (index == null || index !in _state.value.appSettings.liveSourceUrls.indices) {
                    "默认源下标无效，请刷新网页后重试。"
                } else {
                    setLiveSourceDefaultIndex(index)
                    "已把源${index + 1}设为默认直播源。"
                }
            }
            "live-default-clear" -> {
                clearLiveSourceDefaultIndex()
                "已取消默认直播源。"
            }
            "live-epg" -> {
                // 空串表示清除；与设置页同一入口，保存后立即重新拉取节目单。
                updateEpgUrl(request.url.trim())
                if (request.url.isBlank()) "已清除节目单地址。" else "已保存节目单地址，正在后台拉取。"
            }
            "site-toggle" -> {
                togglePlatformLiveSiteBlocked(request.id)
                val blocked = request.id in _state.value.appSettings.blockedPlatformSiteIds
                if (blocked) "已删除该平台站点。" else "已恢复该平台站点。"
            }
            "category-block" -> {
                val name = request.name.trim()
                if (name.isBlank()) {
                    "分类名称不能为空。"
                } else {
                    addBlockedVideoCategory(name)
                    "已屏蔽视频分类：$name"
                }
            }
            "category-unblock" -> {
                val name = request.name.trim()
                removeBlockedVideoCategory(name)
                "已恢复视频分类：$name"
            }
            else -> "未知操作：${request.action}"
        }
    }

    private fun applyCustomVideoApiFromPhone(lineName: String, apiUrl: String): String? {
        val normalizedName = lineName.trim()
        val normalizedUrl = normalizeMacCmsApiUrl(apiUrl)
            ?: return "请输入 http:// 或 https:// 开头的 MacCms 接口地址。"
        if (normalizedName.isBlank()) {
            return "接口名称需要填写。"
        }

        val customLine = CustomVideoApiLine(
            id = customVideoApiLineId(normalizedUrl),
            name = normalizedName,
            baseUrl = normalizedUrl,
        )
        val currentSettings = _state.value.appSettings
        val customLines = (currentSettings.customVideoApiLines
            .filterNot { it.id == customLine.id || it.baseUrl.equals(customLine.baseUrl, ignoreCase = true) } +
            customLine)
        val settings = currentSettings.copy(
            homeApiLineId = customLine.id,
            customVideoApiLines = customLines,
        )
        saveSettings(settings)
        repository.updateCustomApiLines(settings.customVideoApiLines.toApiLines())
        val ordered = orderApiLines(repository.apiLines, settings.sourceOrder)
        val allLines = orderApiLines(repository.allApiLines, settings.sourceOrder)
        _state.update {
            it.copy(
                apiLines = ordered,
                allSourceLines = allLines,
                selectedApiLineId = customLine.id,
                appSettings = settings,
                selectedParentCategoryId = null,
                selectedCategoryId = null,
                categories = emptyList(),
                movies = emptyList(),
                page = 1,
                pageCount = 1,
                total = 0,
                homeError = null,
                remoteManageMessage = "已添加视频接口：${customLine.name}",
            )
        }
        refreshHome()
        return null
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, searchError = null) }
    }
    
    fun clearSearch() {
        _state.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchError = null,
                searchLoading = false,
                searchCompletedSources = 0,
                searchTotalSources = 0,
            )
        }
    }

    fun openDoubanHotDetail(item: DoubanHotItem) {
        val current = _state.value
        if (current.resolvingDoubanHotId == item.doubanId) return

        val apiLineId = current.selectedApiLineId
        doubanHotResolveJob?.cancel()
        doubanHotResolveJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    homeNotice = null,
                    resolvingDoubanHotId = item.doubanId,
                )
            }
            // 与 TV 端保持一致：点击热播直接进入详情页。当前源不支持搜索或未命中时，
            // 依次回退到其他视频源查找，保证任意源下热播卡片都能打开。
            val candidateLineIds = buildList {
                add(apiLineId)
                addAll(current.apiLines.filter { line -> line.id != apiLineId }.map { line -> line.id })
            }
            if (candidateLineIds.isEmpty()) {
                _state.update { it.copy(resolvingDoubanHotId = null, homeNotice = "请先添加视频源") }
                return@launch
            }
            var movie: Movie? = null
            var matchedLineId: String? = null
            var lastError: Throwable? = null
            for (lineId in candidateLineIds.take(current.appSettings.doubanHotFallbackLines)) {
                runCatching { findPlayableDoubanMovie(item.title, lineId) }
                    .onSuccess { found ->
                        if (found != null) {
                            movie = found
                            matchedLineId = lineId
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        lastError = error
                    }
                if (movie != null) break
            }
            _state.update { it.copy(resolvingDoubanHotId = null) }
            if (movie == null) {
                _state.update {
                    it.copy(homeNotice = lastError?.userMessage() ?: "暂无该视频资源")
                }
            } else {
                openDetail(movie.id, matchedLineId ?: apiLineId)
            }
        }
    }

    fun submitSearch() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searchError = "请输入搜索关键词") }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    searchLoading = true,
                    searchError = null,
                    searchResults = emptyList(),
                    searchCompletedSources = 0,
                    searchTotalSources = 0,
                )
            }
            try {
                // 拼音首字母（如 xclf）无法在 MacCMS 服务端命中，先扩展为候选中文片名再搜索。
                val keywords = searchKeywordExpander.expandInitials(
                    query,
                    limit = _state.value.appSettings.searchSuggestionCount,
                )
                    .ifEmpty { listOf(query) }
                    .distinct()
                var completedBase = 0
                var totalSources = 0
                var mergedResults: List<Movie> = emptyList()
                for (keyword in keywords) {
                    var keywordCompletedSources = 0
                    repository.searchProgressively(
                        apiLineId = _state.value.selectedApiLineId,
                        page = 1,
                        keyword = keyword,
                    ).collect { update ->
                        totalSources = maxOf(totalSources, update.totalSources)
                        keywordCompletedSources = update.completedSources
                        mergedResults = mergeSearchResults(mergedResults, update.movies)
                        _state.update {
                            it.copy(
                                searchResults = mergedResults,
                                searchCompletedSources = completedBase + update.completedSources,
                                searchTotalSources = maxOf(
                                    totalSources * keywords.size,
                                    completedBase + update.completedSources,
                                ),
                            )
                        }
                    }
                    completedBase += totalSources.takeIf { it > 0 } ?: keywordCompletedSources
                    // 候选片名通常按可信度排序，首个候选已有结果时无需继续扩大搜索。
                    if (mergedResults.isNotEmpty()) {
                        break
                    }
                }
                _state.update {
                    it.copy(
                        searchLoading = false,
                        searchCompletedSources = completedBase,
                        searchTotalSources = completedBase,
                        searchError = if (mergedResults.isEmpty()) "没有找到相关影片" else null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(searchLoading = false, searchError = error.userMessage())
                }
            }
        }
    }

    private fun mergeSearchResults(existing: List<Movie>, incoming: List<Movie>): List<Movie> =
        mergeMoviesBySourceAndId(existing, incoming)

    fun openDetail(movieId: Int, apiLineId: String = _state.value.selectedApiLineId) {
        historyResumeJob?.cancel()
        detailJob?.cancel()
        detailSourceTestJob?.cancel()
        val current = _state.value
        val returnScreen = when (current.screen) {
            TvScreen.Search -> TvScreen.Search
            TvScreen.History -> TvScreen.History
            TvScreen.Detail -> current.detailReturnScreen
            else -> TvScreen.Home
        }
        _state.update {
            it.copy(
                screen = TvScreen.Detail,
                detailReturnScreen = returnScreen,
                detailMovie = null,
                detailLoading = true,
                detailError = null,
                detailCompletedSources = 0,
                detailTotalSources = 0,
                source = it.source.copy(
                    detailTesting = false,
                    detailTestCompleted = 0,
                    detailTestTotal = 0,
                    detailMessage = null,
                ),
                selectedSourceIndex = 0,
                selectedEpisodeIndex = 0,
                playerSourceIndex = 0,
                playerEpisodeIndex = 0,
                playerStartPositionMs = 0L,
            )
        }
        detailJob = viewModelScope.launch {
            var receivedPrimary = false
            try {
                repository.getDetailProgressively(apiLineId = apiLineId, id = movieId).collect { update ->
                    receivedPrimary = true
                    _state.update { state ->
                        val sourceIndex = if (state.detailMovie == null) {
                            resolveBestPlaybackSourceIndex(
                                state = state,
                                movie = update.movie,
                                requestedSourceIndex = update.movie.preferredSourceIndex(),
                                episodeIndex = 0,
                            )
                        } else {
                            state.selectedSourceIndex.coerceIn(
                                0,
                                update.movie.playSources.lastIndex.coerceAtLeast(0),
                            )
                        }
                        state.copy(
                            detailMovie = update.movie,
                            detailLoading = false,
                            detailError = null,
                            detailCompletedSources = update.completedSources,
                            detailTotalSources = update.totalSources,
                            selectedSourceIndex = sourceIndex,
                            selectedEpisodeIndex = 0,
                            playerSourceIndex = sourceIndex,
                            playerEpisodeIndex = 0,
                            playerStartPositionMs = 0L,
                        )
                    }
                    if (update.completedSources >= update.totalSources) {
                        val movieKey = "${update.movie.apiLineId}:${update.movie.id}"
                        if (detailAutoTestedKey != movieKey) {
                            detailAutoTestedKey = movieKey
                            testDetailSourceSpeeds()
                        }
                    }
                }
                if (!receivedPrimary) {
                    _state.update {
                        it.copy(detailLoading = false, detailError = "影片详情不存在")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(detailLoading = false, detailError = error.userMessage())
                }
            }
        }
    }

    fun selectPlaySource(index: Int) {
        _state.update { state ->
            val maxIndex = (state.detailMovie?.playSources?.lastIndex ?: 0).coerceAtLeast(0)
            state.copy(
                selectedSourceIndex = index.coerceIn(0, maxIndex),
                selectedEpisodeIndex = 0,
            )
        }
    }

    fun openPlayer(sourceIndex: Int, episodeIndex: Int, startPositionMs: Long = 0L) {
        _state.update { state ->
            val movie = state.detailMovie
            val maxSourceIndex = (movie?.playSources?.lastIndex ?: 0).coerceAtLeast(0)
            val requestedSourceIndex = sourceIndex.coerceIn(0, maxSourceIndex)
            val requestedSource = movie?.playSources?.getOrNull(requestedSourceIndex)
            val requestedEpisodeIndex = episodeIndex.coerceIn(
                0,
                (requestedSource?.episodes?.lastIndex ?: 0).coerceAtLeast(0),
            )
            val selectedSourceIndex = movie?.let {
                resolveBestPlaybackSourceIndex(
                    state = state,
                    movie = it,
                    requestedSourceIndex = requestedSourceIndex,
                    episodeIndex = requestedEpisodeIndex,
                )
            } ?: requestedSourceIndex
            val selectedSource = movie?.playSources?.getOrNull(selectedSourceIndex)
            val selectedEpisodeIndex = requestedEpisodeIndex.coerceIn(
                0,
                (selectedSource?.episodes?.lastIndex ?: 0).coerceAtLeast(0),
            )
            state.copy(
                screen = TvScreen.Player,
                selectedSourceIndex = selectedSourceIndex,
                selectedEpisodeIndex = selectedEpisodeIndex,
                playerSourceIndex = selectedSourceIndex,
                playerEpisodeIndex = selectedEpisodeIndex,
                playerStartPositionMs = startPositionMs.coerceAtLeast(0L),
                playerPlayWhenReady = true,
            )
        }
    }

    fun clearPlaybackStats() {
        viewModelScope.launch {
            runCatching { playbackHealthRepository.clear() }
                .onSuccess { snapshot ->
                    _state.update { it.copy(playbackHealth = snapshot) }
                }
        }
    }

    fun resumeHistory(item: WatchHistoryItem, returnScreen: TvScreen = TvScreen.Home) {
        historyResumeJob?.cancel()
        detailJob?.cancel()
        detailSourceTestJob?.cancel()
        _state.update {
            it.copy(
                screen = TvScreen.Detail,
                detailReturnScreen = returnScreen,
                detailMovie = null,
                detailLoading = true,
                detailError = null,
                source = it.source.copy(
                    detailTesting = false,
                    detailTestCompleted = 0,
                    detailTestTotal = 0,
                    detailMessage = null,
                ),
                selectedSourceIndex = item.sourceIndex,
                selectedEpisodeIndex = item.episodeIndex,
            )
        }
        historyResumeJob = viewModelScope.launch {
            var opened = false
            try {
                val movie = repository.getResumeDetail(item.apiLineId, item.movieId)
                if (movie == null || movie.playSources.none { it.episodes.isNotEmpty() }) {
                    _state.update { it.copy(detailLoading = false, detailError = "暂时无法获取播放地址，请稍后重试") }
                    return@launch
                }
                val (sourceIndex, episodeIndex) = resolveHistoryPosition(movie, item)
                _state.update {
                    it.copy(
                        detailMovie = movie, detailLoading = false, detailError = null,
                        selectedSourceIndex = sourceIndex, selectedEpisodeIndex = episodeIndex,
                        playerSourceIndex = sourceIndex, playerEpisodeIndex = episodeIndex,
                        playerStartPositionMs = item.positionMs.coerceAtLeast(0L),
                        playerPlayWhenReady = true, screen = TvScreen.Player,
                    )
                }
                opened = true
                // Append alternatives without replacing the playing source or resetting its playlist.
                repository.getDetailProgressively(item.apiLineId, item.movieId).collect { update ->
                    _state.update { current ->
                        val active = current.detailMovie
                        if (active == null || active.id != movie.id || active.apiLineId != movie.apiLineId ||
                            current.screen !in listOf(TvScreen.Player, TvScreen.Detail)) current
                        else {
                            val sources = (active.playSources + update.movie.playSources)
                                .distinctBy { source -> source.episodes.map { it.url } }
                            current.copy(detailMovie = active.copy(playSources = sources),
                                detailCompletedSources = update.completedSources, detailTotalSources = update.totalSources)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!opened) _state.update {
                    it.copy(detailLoading = false, detailError = "续播加载失败，请稍后重试或从搜索重新打开影片")
                }
            }
        }
    }

    fun playNextEpisode() {
        val current = _state.value
        val episodes = current.detailMovie
            ?.playSources
            ?.getOrNull(current.playerSourceIndex)
            ?.episodes
            .orEmpty()
        if (current.playerEpisodeIndex < episodes.lastIndex) {
            _state.update {
                val nextEpisodeIndex = it.playerEpisodeIndex + 1
                it.copy(
                    selectedSourceIndex = it.playerSourceIndex,
                    selectedEpisodeIndex = nextEpisodeIndex,
                    playerEpisodeIndex = nextEpisodeIndex,
                    playerStartPositionMs = 0L,
                )
            }
        }
    }

    fun playPreviousEpisode() {
        if (_state.value.playerEpisodeIndex > 0) {
            _state.update {
                val previousEpisodeIndex = it.playerEpisodeIndex - 1
                it.copy(
                    selectedSourceIndex = it.playerSourceIndex,
                    selectedEpisodeIndex = previousEpisodeIndex,
                    playerEpisodeIndex = previousEpisodeIndex,
                    playerStartPositionMs = 0L,
                )
            }
        }
    }

    fun switchToNextPlayableSource(
        blockedSourceIndexes: Set<Int>,
        positionMs: Long,
        playWhenReady: Boolean,
        issueType: PlaybackIssueType? = null,
        autoTriggered: Boolean = true,
    ): PlaybackAgentDecision {
        val current = _state.value
        if (issueType != null) {
            recordPlaybackIssue(current, issueType)
        }
        if (autoTriggered && !current.appSettings.playbackAgentAutoSwitchEnabled) {
            return PlaybackAgentDecision(nextSourceIndex = null)
        }
        val movie = current.detailMovie ?: return PlaybackAgentDecision(nextSourceIndex = null)
        val decision = playbackAgent.selectNextSource(
            movie = movie,
            currentSourceIndex = current.playerSourceIndex,
            episodeIndex = current.playerEpisodeIndex,
            blockedSourceIndexes = blockedSourceIndexes,
            healthSnapshot = current.playbackHealth,
        )
        val nextSourceIndex = decision.nextSourceIndex ?: return decision

        if (!switchPlayerSource(nextSourceIndex, positionMs, playWhenReady)) {
            return PlaybackAgentDecision(nextSourceIndex = null)
        }
        return decision
    }

    fun recordPlaybackSuccess() {
        val current = _state.value
        val key = current.playbackHealthKeyOrNull() ?: return
        viewModelScope.launch {
            runCatching { playbackHealthRepository.recordSuccess(key, System.currentTimeMillis()) }
                .onSuccess { snapshot ->
                    _state.update { it.copy(playbackHealth = snapshot) }
                }
        }
    }

    fun playNextLiveChannel() {
        lastExhaustedChannelNumber = null
        _state.update { state ->
            val channels = state.live.channels
            if (channels.isEmpty()) return@update state
            val nextIndex = (state.live.channelIndex + 1) % channels.size
            val channel = channels[nextIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            android.util.Log.i(VM_LIVE_LOG_TAG, "【切换频道】next channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}")
            state.copy(
                live = state.live.copy(
                    channelIndex = nextIndex,
                    lineIndex = targetLineIndex,
                ),
            )
        }
    }

    fun playPreviousLiveChannel() {
        lastExhaustedChannelNumber = null
        _state.update { state ->
            val channels = state.live.channels
            if (channels.isEmpty()) return@update state
            val previousIndex = if (state.live.channelIndex <= 0) channels.lastIndex else state.live.channelIndex - 1
            val channel = channels[previousIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            android.util.Log.i(VM_LIVE_LOG_TAG, "【切换频道】previous channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}")
            state.copy(
                live = state.live.copy(
                    channelIndex = previousIndex,
                    lineIndex = targetLineIndex,
                ),
            )
        }
    }

    /** 更新首页视频卡片每行数量；0 表示跟随布局自适应。 */
    fun updateVideoGridColumns(columns: Int) {
        val settings = _state.value.appSettings.copy(videoGridColumns = columns.coerceIn(0, 8))
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新平台直播卡片每行数量；0 表示跟随布局默认。 */
    fun updatePlatformLiveGridColumns(columns: Int) {
        val settings = _state.value.appSettings.copy(platformLiveGridColumns = columns.coerceIn(0, 10))
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新订阅源卡片显示数量；0 表示恢复设备默认。 */
    fun updateLiveSourceCardCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            liveSourceCardCount = count.coerceIn(0, SOURCE_CARD_MAX_COUNT),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新直播平台卡片显示数量；0 表示恢复设备默认。 */
    fun updatePlatformLiveCardCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            platformLiveCardCount = count.coerceIn(0, CARD_AREA_MAX_ROWS * 10),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新首页「继续观看」卡片数量。 */
    fun updateContinueWatchingCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            continueWatchingCount = QuantitySettings.ContinueWatchingCount.clamp(count),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新电视直播 EPG 窄卡节目条数。 */
    fun updateEpgCardRows(rows: Int) {
        val settings = _state.value.appSettings.copy(
            epgCardRows = QuantitySettings.EpgCardRows.clamp(rows),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新数字选台浮层候选行数。 */
    fun updateLiveNumberMatchRows(rows: Int) {
        val settings = _state.value.appSettings.copy(
            liveNumberMatchRows = QuantitySettings.LiveNumberMatchRows.clamp(rows),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新数字选台前缀匹配上限。 */
    fun updateLiveNumberSearchLimit(limit: Int) {
        val settings = _state.value.appSettings.copy(
            liveNumberSearchLimit = QuantitySettings.LiveNumberSearchLimit.clamp(limit),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新手机直播页「相关频道」数量。 */
    fun updateRelatedChannelCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            relatedChannelCount = QuantitySettings.RelatedChannelCount.clamp(count),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新搜索热词条数；下次刷新热词生效。 */
    fun updateHotWordCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            hotWordCount = QuantitySettings.HotWordCount.clamp(count),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新拼音首字母扩展候选数。 */
    fun updateSearchSuggestionCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            searchSuggestionCount = QuantitySettings.SearchSuggestionCount.clamp(count),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新豆瓣热播每页条数；下次翻页生效。 */
    fun updateDoubanHotPageSize(size: Int) {
        val settings = _state.value.appSettings.copy(
            doubanHotPageSize = QuantitySettings.DoubanHotPageSize.clamp(size),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新热播点击时尝试的视频源数量。 */
    fun updateDoubanHotFallbackLines(count: Int) {
        val settings = _state.value.appSettings.copy(
            doubanHotFallbackLines = QuantitySettings.DoubanHotFallbackLines.clamp(count),
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 更新本地观看历史保留条数，并立即收敛已加载列表。 */
    fun updateHistoryMaxItems(count: Int) {
        val settings = _state.value.appSettings.copy(
            historyMaxItems = QuantitySettings.HistoryMaxItems.clamp(count),
        )
        saveSettings(settings)
        _state.update {
            it.copy(
                appSettings = settings,
                historyItems = it.historyItems.take(settings.historyMaxItems),
            )
        }
    }

    /** 更新多源搜索/详情并发数。 */
    fun updateMaxParallelSourceRequests(count: Int) {
        val value = QuantitySettings.MaxParallelSourceRequests.clamp(count)
        repository.setMaxParallelSourceRequests(value)
        val settings = _state.value.appSettings.copy(maxParallelSourceRequests = value)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updateDeviceMode(mode: DeviceMode) {
        if (_state.value.appSettings.deviceMode == mode) return
        val settings = _state.value.appSettings.copy(deviceMode = mode)
        val effective = settings.effectiveMode(appContext)
        _state.update { it.copy(appSettings = settings, effectiveDeviceMode = effective) }
        saveSettings(settings)
    }

    /** 清除手动覆盖，恢复为自动识别模式。 */
    fun restoreAutoDeviceMode() {
        updateDeviceMode(DeviceMode.Auto)
    }

    /**
     * 按当前系统状态重算生效设备模式（Auto 模式下系统特征可能因插拔 HDMI /
     * 外接键鼠 / 返回前台而变化）。只更新展示用 effectiveDeviceMode，
     * 不写回用户保存的 deviceMode，避免自动识别覆盖手动值。
     */
    fun refreshEffectiveDeviceMode() {
        val current = _state.value
        val effective = current.appSettings.effectiveMode(appContext)
        if (current.effectiveDeviceMode != effective) {
            _state.update { it.copy(effectiveDeviceMode = effective) }
        }
    }

    fun updateTheme(theme: TvTheme) {
        if (_state.value.appSettings.theme == theme) return
        val settings = _state.value.appSettings.copy(theme = theme)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updateFontScale(fontScale: TvFontScale) {
        if (_state.value.appSettings.fontScale == fontScale) return
        val settings = _state.value.appSettings.copy(fontScale = fontScale)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 手机模式首次启动引导 Banner 已展示，记录该状态避免重复弹出。 */
    fun updateMobileOnboarded() {
        if (_state.value.appSettings.mobileOnboarded) return
        val settings = _state.value.appSettings.copy(mobileOnboarded = true)
        _state.update { it.copy(appSettings = settings) }
        saveSettings(settings)
    }


    /** 手动切换线路后清零卡顿计数，避免旧线路的失败次数影响新线路。 */
    fun playNextLiveLine(): Boolean {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return false
        val lines = channel.lines
        if (lines.size <= 1) return false
        liveStallRetries = 0
        val newIndex = (state.live.lineIndex + 1) % lines.size
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】next channel=${channel.name}, from=${state.live.lineIndex}, to=$newIndex, url=${lines[newIndex].url}")
        _state.update {
            it.copy(live = it.live.copy(lineIndex = newIndex))
        }
        liveLinePrefs?.saveLineIndex(channel.key(), newIndex)
        return true
    }

    /** 手动切换线路后清零卡顿计数，避免旧线路的失败次数影响新线路。 */
    fun playPreviousLiveLine(): Boolean {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return false
        val lines = channel.lines
        if (lines.size <= 1) return false
        liveStallRetries = 0
        val previousIndex = if (state.live.lineIndex <= 0) lines.lastIndex else state.live.lineIndex - 1
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】previous channel=${channel.name}, from=${state.live.lineIndex}, to=$previousIndex, url=${lines[previousIndex].url}")
        _state.update {
            it.copy(live = it.live.copy(lineIndex = previousIndex))
        }
        liveLinePrefs?.saveLineIndex(channel.key(), previousIndex)
        return true
    }

    /**
     * 对当前频道的所有线路发起首包测速，结果写入 [LiveUiState.lineLatencies]。
     *
     * 在线路浮层展开时调用：让用户按实际延迟挑线路，而不是逐条点播试错。
     * 并发执行但每条独立失败（超时记 null），不阻塞播放，也不改变当前线路。
     */
    fun testLiveChannelLines() {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return
        val lines = channel.lines
        if (lines.isEmpty()) return
        if (state.live.lineTesting) return
        // 新频道/新测速：清掉上一轮结果，避免旧延迟误导用户。
        _state.update { it.copy(live = it.live.copy(lineTesting = true, lineLatencies = emptyMap())) }
        liveLineTestJob?.cancel()
        liveLineTestJob = viewModelScope.launch {
            android.util.Log.i(VM_LIVE_LOG_TAG, "【线路测速】开始 channel=${channel.name}, lines=${lines.size}")
            val results = lines.map { line ->
                async {
                    val latency = runCatching {
                        measureLiveLineLatencyMs(line.url, line.headers)
                    }.getOrNull()
                    line.url to latency
                }
            }.awaitAll().toMap()
            android.util.Log.i(
                VM_LIVE_LOG_TAG,
                "【线路测速】完成 channel=${channel.name}, " +
                    results.entries.joinToString { (url, ms) -> "${url.takeLast(18)}=${ms?.let { "${it}ms" } ?: "超时"}" },
            )
            _state.update { it.copy(live = it.live.copy(lineLatencies = results, lineTesting = false)) }
        }
    }

    fun selectLiveLine(lineIndex: Int) {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return
        if (lineIndex !in channel.lines.indices) return
        if (lineIndex == state.live.lineIndex) return
        liveStallRetries = 0
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】select channel=${channel.name}, from=${state.live.lineIndex}, to=$lineIndex, url=${channel.lines[lineIndex].url}")
        _state.update { it.copy(live = it.live.copy(lineIndex = lineIndex)) }
        liveLinePrefs?.saveLineIndex(channel.key(), lineIndex)
    }

    /**
     * 电视直播播放异常后的自动线路容错：
     * - [LiveLineFallbackReason.Error] 是确定性失败（播放器报错/容器不支持），直接切下一条，
     *   并把"下一条"记为该频道的首选线路——下次进台不再先撞一次死线路；
     * - 缓冲/卡死/结束类失败先原地重连当前线路（同一 URL 重新 prepare），
     *   连续 [LIVE_STALL_RETRY_BEFORE_SWITCH] 次无效才真正切线路。
     *   慢 CDN 首帧本就要几秒，一见缓冲就切线会把所有线路轮流点一遍，
     *   表现为"没坏也自动切线路"。
     */
    fun advanceLiveLineAfterFailure(reason: LiveLineFallbackReason): Boolean {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return false
        val lines = channel.lines
        if (reason != LiveLineFallbackReason.Error) {
            liveStallRetries += 1
            if (liveStallRetries < LIVE_STALL_RETRY_BEFORE_SWITCH) {
                android.util.Log.w(
                    VM_LIVE_LOG_TAG,
                    "【自动容错】reason=$reason 第 $liveStallRetries/$LIVE_STALL_RETRY_BEFORE_SWITCH 次原地重连当前线路 ${state.live.lineIndex + 1}",
                )
                _state.update { it.copy(live = it.live.copy(reconnectNonce = it.live.reconnectNonce + 1)) }
                return true
            }
        }
        liveStallRetries = 0
        // 域名评分：本次失败先记到当前线路的域名上，下次进台排序时会被压低。
        recordLiveLineFailure(state)
        val nextIndex = state.live.lineIndex + 1
        android.util.Log.w(
            VM_LIVE_LOG_TAG,
            "【自动容错】reason=$reason channel=${channel.name}, currentLine=${state.live.lineIndex}, nextLine=$nextIndex, totalLines=${lines.size}",
        )
        if (nextIndex !in lines.indices) {
            // 所有线路均已尝试过，停止循环并提示用户。
            val channelNumber = channel.number
            if (lastExhaustedChannelNumber != channelNumber) {
                lastExhaustedChannelNumber = channelNumber
                _state.update {
                    it.copy(live = it.live.copy(notice = "频道 ${channelNumber} 所有线路均不可用，请换台或检查直播源"))
                }
            }
            android.util.Log.w(VM_LIVE_LOG_TAG, "【自动容错】频道 ${channel.name} 所有线路已用尽，停止切换")
            return false
        }
        _state.update { it.copy(live = it.live.copy(lineIndex = nextIndex)) }
        if (reason == LiveLineFallbackReason.Error) {
            liveLinePrefs?.saveLineIndex(channel.key(), nextIndex)
        }
        android.util.Log.i(VM_LIVE_LOG_TAG, "【自动切换成功】切换到线路 ${nextIndex + 1}/${lines.size}, url=${lines[nextIndex].url}")
        return true
    }

    /** 播放中断后的原地重连（不换线路）：bump nonce 让播放页对当前 URL 重新 prepare。 */
    fun reconnectLiveLine() {
        _state.update { it.copy(live = it.live.copy(reconnectNonce = it.live.reconnectNonce + 1)) }
    }

    /** 设置保存失败提示消费后清空，避免重复弹出。 */
    fun consumeSettingsSaveError() {
        if (_state.value.settingsSaveError == null) return
        _state.update { it.copy(settingsSaveError = null) }
    }

    /** 消费一次性轻提示（自动切线路/订阅切换结果）。 */
    fun consumeLiveNotice() {
        if (_state.value.live.notice == null) return
        _state.update { it.copy(live = it.live.copy(notice = null)) }
    }

    fun reportLiveNotice(message: String) {
        _state.update { it.copy(live = it.live.copy(notice = message)) }
    }

    /**
     * 当某频道所有线路均失败时，记录该频道并清除已耗尽标记（换台后重新尝试）。
     */
    fun onLiveChannelChanged() {
        lastExhaustedChannelNumber = null
    }

    /**
     * 电视直播画面就绪（STATE_READY）后调用：清零卡顿重试计数，并把一次成功记到域名评分表。
     *
     * 此前该计数只在真正切换线路时清零，会跨频道累计：
     * 连续几个频道各卡顿一次后，新频道第一次卡顿就直接换线，表现为"没坏也自动切线路"。
     */
    fun notifyLivePlaybackReady() {
        if (liveStallRetries != 0) {
            android.util.Log.d(VM_LIVE_LOG_TAG, "【直播就绪】画面就绪，清零卡顿重试计数（原 $liveStallRetries）")
            liveStallRetries = 0
        }
        // 域名评分：同一 CDN 域名下所有频道的成败累计，供下次选线时优先排序。
        val state = _state.value
        val url = state.live.channels.getOrNull(state.live.channelIndex)
            ?.lines?.getOrNull(state.live.lineIndex)?.url
        val domain = url?.let { liveLineDomain(it) }
        if (domain != null && domain != lastScoredDomain) {
            lastScoredDomain = domain
            liveDomainScores?.record(domain, success = true)
        }
    }

    fun selectPlatformLiveSite(site: PlatformLiveSite) {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.ParentCategories,
                selectedSite = site,
                parentCategories = emptyList(),
                selectedParentCategory = null,
                parentIndex = 0,
                selectedCategory = null,
                categoryIndex = 0,
                categories = emptyList(),
                rooms = emptyList(),
                roomIndex = 0,
                error = null,
                stream = null),
            )
        }
        loadPlatformLiveCategories(site)
    }

    fun openPlatformLiveFavorites() {
        platformLiveJob?.cancel()
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Favorites,
                rooms = it.platformLive.favorites,
                roomIndex = 0,
                loading = false,
                error = null,
                stream = null),
            )
        }
    }

    fun togglePlatformLiveFavorite(room: PlatformLiveRoom) {
        viewModelScope.launch {
            platformLiveFavoritesMutex.withLock {
                runCatching { platformLiveFavoritesRepository.toggleFavorite(room) }
                    .onSuccess { favorites ->
                        _state.update { current ->
                            if (current.platformLive.destination == PlatformLiveDestination.Favorites) {
                                current.copy(platformLive = current.platformLive.copy(
                                    favorites = favorites,
                                    rooms = favorites,
                                    roomIndex = current.platformLive.roomIndex.coerceAtMost(favorites.lastIndex.coerceAtLeast(0)),
                                    favoriteError = null),
                                )
                            } else {
                                current.copy(platformLive = current.platformLive.copy(favorites = favorites, favoriteError = null))
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { it.copy(platformLive = it.platformLive.copy(favoriteError = error.userMessage())) }
                    }
            }
        }
    }

    fun selectPlatformLiveParentCategory(parentCategory: PlatformLiveParentCategory) {
        val state = _state.value
        val parentIndex = state.platformLive.parentCategories
            .indexOfFirst { it.id == parentCategory.id }
            .coerceAtLeast(0)
        val hasCategories = state.platformLive.categories.any { it.parentId == parentCategory.id }
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Categories,
                selectedParentCategory = parentCategory,
                parentIndex = parentIndex,
                selectedCategory = null,
                categoryIndex = 0,
                rooms = emptyList(),
                roomIndex = 0,
                error = if (hasCategories) null else "这个大分类暂时没有可用的二级分类",
                stream = null),
            )
        }
    }

    fun selectPlatformLiveCategory(category: PlatformLiveCategory) {
        val categoryIndex = _state.value.platformLive.categories
            .indexOfFirst { it.id == category.id }
            .coerceAtLeast(0)
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Rooms,
                selectedCategory = category,
                categoryIndex = categoryIndex,
                rooms = emptyList(),
                roomIndex = 0,
                roomPage = 1,
                roomPageCount = 1,
                error = null,
                stream = null),
            )
        }
        loadPlatformLiveRooms(category, reset = true)
    }

    fun loadMorePlatformLiveRooms() {
        val state = _state.value
        val category = state.platformLive.selectedCategory ?: return
        if (state.platformLive.loading || state.platformLive.loadingMore ||
            state.platformLive.roomPage >= state.platformLive.roomPageCount
        ) {
            return
        }
        loadPlatformLiveRooms(category, reset = false)
    }

    fun openPlatformLiveRoom(room: PlatformLiveRoom) {
        val index = _state.value.platformLive.rooms.indexOfFirst { it.id == room.id }
        if (index < 0) return
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Player,
                playerReturnDestination = it.platformLive.destination,
                roomIndex = index,
                error = null,
                notice = null,
                stream = null),
            )
        }
        resolvePlatformLiveRoom()
    }

    fun playNextPlatformLiveRoom() {
        val state = _state.value
        val rooms = state.platformLive.rooms
        if (rooms.isEmpty()) return
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                roomIndex = (state.platformLive.roomIndex + 1) % rooms.size,
                error = null,
                stream = null),
            )
        }
        resolvePlatformLiveRoom()
    }

    fun playPreviousPlatformLiveRoom() {
        val state = _state.value
        val rooms = state.platformLive.rooms
        if (rooms.isEmpty()) return
        val previousIndex = if (state.platformLive.roomIndex <= 0) rooms.lastIndex else {
            state.platformLive.roomIndex - 1
        }
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                roomIndex = previousIndex,
                error = null,
                stream = null),
            )
        }
        resolvePlatformLiveRoom()
    }

    fun selectLiveChannel(index: Int) {
        liveStallRetries = 0
        lastExhaustedChannelNumber = null
        _state.update { state ->
            val channels = state.live.channels
            if (channels.isEmpty()) return@update state
            val safeIndex = index.coerceIn(0, channels.lastIndex)
            val channel = channels[safeIndex]
            state.copy(
                live = state.live.copy(
                    channelIndex = safeIndex,
                    lineIndex = state.rememberedLineIndexOf(channel),
                ),
            )
        }
    }

    /** 当前频道列表的分组名（按出现顺序去重），供两级面板展示。 */
    fun liveGroupNames(): List<String> = _state.value.live.channels
        .map { it.groupName }
        .distinct()

    /**
     * 切换直播分类（两级交互的第一级）。
     *
     * index=0 表示"全部"；1..groups 对应各分类，进入后把播放目标定位到该分类首个频道，
     * 并恢复该频道上次成功播放的线路。
     */
    fun selectLiveGroup(index: Int) {
        _state.update { state ->
            val channels = state.live.channels
            val groups = channels.map { it.groupName }.distinct()
            if (groups.isEmpty()) return@update state
            val safeIndex = index.coerceIn(0, groups.size)
            val targetChannel = if (safeIndex == 0) {
                state.live.channelIndex.coerceIn(0, channels.lastIndex)
            } else {
                val groupName = groups[safeIndex - 1]
                channels.indexOfFirst { it.groupName == groupName }.coerceAtLeast(0)
            }
            val channel = channels.getOrNull(targetChannel)
            state.copy(
                live = state.live.copy(
                    groupIndex = safeIndex,
                    channelIndex = targetChannel,
                    lineIndex = channel?.let { state.rememberedLineIndexOf(it) } ?: 0,
                ),
            )
        }
    }

    /** 左右方向键切换分类（含"全部"）。 */
    fun cycleLiveGroup(delta: Int) {
        val groups = liveGroupNames()
        if (groups.isEmpty()) return
        val current = _state.value.live.groupIndex
        val total = groups.size + 1
        val next = (((current + delta) % total) + total) % total
        selectLiveGroup(next)
    }

    /**
     * 两级交互：进入某个分类（第一级确认键）。
     * 只改变面板层级，不动播放目标——分类页浏览期间当前频道继续播放。
     */
    fun enterLiveGroup(index: Int) {
        val groups = liveGroupNames()
        val safeIndex = index.coerceIn(0, groups.size)
        android.util.Log.d(VM_LIVE_LOG_TAG, "【两级交互】进入分类 index=$safeIndex, name=${groups.getOrNull(safeIndex - 1) ?: "全部"}")
        if (_state.value.live.groupPanelStage == LiveGroupStage.Channels &&
            _state.value.live.groupIndex == safeIndex
        ) {
            return
        }
        _state.update {
            it.copy(
                live = it.live.copy(
                    groupIndex = safeIndex,
                    groupPanelStage = LiveGroupStage.Channels,
                ),
            )
        }
    }

    /** 两级交互：从频道列表返回分类列表。 */
    fun backToLiveGroups() {
        _state.update { it.copy(live = it.live.copy(groupPanelStage = LiveGroupStage.Groups)) }
    }

    /** 两级交互：每次打开直播面板都从分类层级开始，避免直接落在频道长列表上。 */
    fun showLivePanelGroups() {
        if (_state.value.live.groupPanelStage != LiveGroupStage.Groups) {
            _state.update { it.copy(live = it.live.copy(groupPanelStage = LiveGroupStage.Groups)) }
        }
    }

    /** 收藏/取消收藏当前播放的直播频道（通过频道号）。 */
    fun toggleLiveFavorite(channelNumber: Int) {
        val current = _state.value
        val favorites = current.live.favoriteChannelNumbers.toMutableSet()
        if (favorites.contains(channelNumber)) {
            favorites.remove(channelNumber)
        } else {
            favorites.add(channelNumber)
        }
        _state.update {
            it.copy(live = it.live.copy(favoriteChannelNumbers = favorites))
        }
    }

    /**
     * 收藏/取消收藏直播频道（按 `分组#频道名` 键，跨源刷新不丢失）。
     *
     * 有持久化仓库时异步落盘并以仓库返回值刷新 [LiveUiState.favoriteKeys]；
     * 仓库缺失时退回旧的仅内存 Set 语义（按键增删）。
     */
    fun toggleLiveFavorite(channel: LiveChannel) {
        val key = liveFavoriteKey(channel.groupName, channel.name)
        val repository = liveFavoritesRepository
        if (repository == null) {
            _state.update { state ->
                val favorites = state.live.favoriteKeys.toMutableSet()
                if (!favorites.remove(key)) favorites += key
                state.copy(live = state.live.copy(favoriteKeys = favorites))
            }
            return
        }
        viewModelScope.launch {
            liveFavoritesMutex.withLock {
                runCatching { repository.toggleFavorite(channel.groupName, channel.name) }
                    .onSuccess { keys -> _state.update { it.copy(live = it.live.copy(favoriteKeys = keys)) } }
            }
        }
    }

    /** 某频道是否已收藏（按 `分组#频道名` 键）。 */
    fun isLiveFavorite(channel: LiveChannel): Boolean =
        _state.value.live.favoriteKeys.contains(liveFavoriteKey(channel.groupName, channel.name))

    /** 某频道的完整节目单（已按开始时间升序）；未配置/未命中返回空列表。 */
    fun liveEpgPrograms(channel: LiveChannel): List<EpgProgram> =
        findEpgPrograms(
            map = _state.value.live.epgByChannel,
            groupName = channel.groupName,
            channelName = channel.name,
            logoId = channel.logo,
        )

    /** 某频道当前正在播出的节目；无节目单或不在播出时段返回 null。 */
    fun liveEpgNow(channel: LiveChannel): EpgProgram? {
        val now = System.currentTimeMillis()
        return liveEpgPrograms(channel).firstOrNull { it.startMs <= now && now < it.stopMs }
    }

    /** 某频道紧接当前节目之后的下一条节目；无节目单或已全部播完返回 null。 */
    fun liveEpgNext(channel: LiveChannel): EpgProgram? {
        val now = System.currentTimeMillis()
        return liveEpgPrograms(channel).firstOrNull { it.startMs > now }
    }

    /** 保存/清除节目单接口地址：立即持久化，并清缓存后重新拉取。 */
    fun updateEpgUrl(url: String) {
        val trimmed = url.trim()
        if (_state.value.appSettings.epgUrl == trimmed) return
        val settings = _state.value.appSettings.copy(epgUrl = trimmed)
        _state.update {
            it.copy(
                appSettings = settings,
                live = it.live.copy(epgByChannel = emptyMap(), epgLoaded = false),
            )
        }
        saveSettings(settings)
        loadLiveEpg(force = true)
    }

    /** 订阅地址标签：第 0 项"全部"，之后为各订阅地址。 */
    fun liveSourceLabels(): List<String> = buildList {
        add("全部")
        addAll(liveRepository.sourceUrls.mapIndexed { i, url ->
            "源${i + 1} · ${url.substringAfterLast('/').take(18).ifBlank { url.take(18) }}"
        })
    }

    /**
     * 切换订阅地址并重新加载频道列表。
     *
     * 统一策略：保持当前选中的**分类名**（新订阅里不存在该分类时回"全部"），
     * 分类内定位到该分类首个频道；分类也变了时播放目标随之改变。
     */
    fun selectLiveSource(index: Int) {
        val labels = liveSourceLabels()
        if (index !in labels.indices) return
        if (index == _state.value.live.selectedSourceIndex) return
        val previousGroup = _state.value.let { state ->
            val groups = state.live.channels.map { it.groupName }.distinct()
            groups.getOrNull(state.live.groupIndex - 1)
        }
        pendingLiveGroupRestore = previousGroup
        _state.update {
            it.copy(
                live = it.live.copy(
                    selectedSourceIndex = index,
                    notice = "正在切换订阅：${labels[index]}",
                ),
            )
        }
        liveRepository.setSourceSelection(index - 1)
        refreshLive()
    }

    /** 左右方向键切换订阅地址（循环）。 */
    fun cycleLiveSource(delta: Int) {
        val labels = liveSourceLabels()
        if (labels.size <= 1) return
        val current = _state.value.live.selectedSourceIndex.coerceAtLeast(0)
        val next = ((current + delta) % labels.size + labels.size) % labels.size
        selectLiveSource(next)
    }

    fun selectLiveChannelNumber(number: Int): Boolean {
        val index = number - 1
        val channels = _state.value.live.channels
        if (index !in channels.indices) return false
        selectLiveChannel(index)
        return true
    }

    fun retryPlatformLiveChannel() {
        refreshPlatformLive()
    }

    fun reconnectPlatformLiveChannel() {
        android.util.Log.w(VM_LIVE_LOG_TAG, "【平台重新解析】forceRefresh=true")
        resolvePlatformLiveRoom(forceRefresh = true)
    }

    fun switchPlatformLiveCandidate(index: Int) {
        val stream = _state.value.platformLive.stream ?: return
        val candidate = stream.candidates.getOrNull(index) ?: return
        if (platformLiveRecoveryInProgress) {
            // 用户明确的手动选择优先于自动恢复，不再静默丢弃。
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台手动切换候选】自动恢复进行中，仍以用户选择为准切换 to=$index")
        }
        android.util.Log.i(
            VM_LIVE_LOG_TAG,
            "【平台手动切换候选】from=${stream.safeActiveCandidateIndex}, to=$index, cdn=${candidate.cdn}",
        )
        // 用户手动指定线路后，重置自动恢复计数，避免刚切完就被熔断拦下。
        platformLiveRecoveryInProgress = false
        platformLiveSameLineRetries = 0
        platformLiveRecoveryWindowAttempts = 0
        platformLiveRecoveryWindowStartMs = 0L
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                error = null,
                notice = "已切换到线路 ${index + 1}/${stream.candidates.size}（${candidate.cdn}）",
                stream = stream.withActiveCandidate(index)),
            )
        }
    }

    /** 遥控器：切换到上一条候选线路（循环）。 */
    fun playPreviousPlatformLiveCandidate() {
        stepPlatformLiveCandidate(-1)
    }

    /** 遥控器：切换到下一条候选线路（循环）。 */
    fun playNextPlatformLiveCandidate() {
        stepPlatformLiveCandidate(1)
    }

    private fun stepPlatformLiveCandidate(delta: Int) {
        val stream = _state.value.platformLive.stream ?: return
        if (stream.candidates.size <= 1) {
            _state.update { it.copy(platformLive = it.platformLive.copy(notice = "当前直播间只有一条线路")) }
            return
        }
        val next = (stream.activeCandidateIndex + delta + stream.candidates.size) % stream.candidates.size
        switchPlatformLiveCandidate(next)
    }

    /** 消费平台直播轻提示。 */
    fun consumePlatformLiveNotice() {
        if (_state.value.platformLive.notice != null) {
            _state.update { it.copy(platformLive = it.platformLive.copy(notice = null)) }
        }
    }

    /**
     * 平台直播画面恢复（READY）后调用：清零同线路重连计数。
     *
     * 虎牙 FLV 每 ~125 秒被服务端正常关闭一次，属于常态而非线路故障。若不清零，
     * 连续两次关流就会把健康房间切到别的 CDN，制造"隔几分钟自动换线路"的现象。
     */
    fun notifyPlatformLivePlaybackReady() {
        if (platformLiveSameLineRetries != 0) {
            android.util.Log.d(VM_LIVE_LOG_TAG, "【平台恢复】画面就绪，清零同线路重连计数（原 $platformLiveSameLineRetries）")
            platformLiveSameLineRetries = 0
        }
    }

    /**
     * 平台直播断流恢复。
     *
     * 按失败性质分流（与电视直播同一套判据）：
     * - [LiveLineFallbackReason.Ended]：虎牙等平台的 FLV 带签名有效期（约 125 秒）会被服务端**正常关闭**，
     *   这不是 CDN 故障。此前把它当作换线路触发条件，导致候选线路被逐条轮询、用尽后再 forceRefresh，
     *   形成"每隔两分钟自动切线路"的循环。现改为**保持当前候选**重新解析拿新的签名地址。
     * - [LiveLineFallbackReason.Stall]：先原地重连同一条候选，用完次数再换候选。
     * - [LiveLineFallbackReason.Error]：CDN/地址确实不可用，直接换下一条候选。
     *
     * 同时加限次与退避：一个时间窗内恢复次数超过上限后停止自动恢复并提示用户，
     * 避免无限轮换导致持续后台请求与流量浪费。
     */
    fun recoverPlatformLiveChannel(reason: LiveLineFallbackReason = LiveLineFallbackReason.Error) {
        val state = _state.value
        val stream = state.platformLive.stream ?: run {
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】无当前流，改为重新解析")
            reconnectPlatformLiveChannel()
            return
        }
        if (platformLiveRecoveryInProgress || state.platformLive.resolving) {
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复跳过】恢复进行中或正在解析中")
            return
        }
        val now = System.currentTimeMillis()
        if (now - platformLiveRecoveryWindowStartMs > PLATFORM_LIVE_RECOVERY_WINDOW_MS) {
            platformLiveRecoveryWindowStartMs = now
            platformLiveRecoveryWindowAttempts = 0
        }
        if (platformLiveRecoveryWindowAttempts >= PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS) {
            android.util.Log.e(
                VM_LIVE_LOG_TAG,
                "【平台恢复熔断】${PLATFORM_LIVE_RECOVERY_WINDOW_MS / 1000}s 内已尝试 $platformLiveRecoveryWindowAttempts 次仍未恢复，停止自动恢复, room=${roomTag()}",
            )
            _state.update {
                it.copy(platformLive = it.platformLive.copy(error = "该房间线路连续失败，已停止自动重连。可手动切换线路或稍后重试。"))
            }
            return
        }
        platformLiveRecoveryWindowAttempts++
        platformLiveRecoveryInProgress = true
        android.util.Log.w(
            VM_LIVE_LOG_TAG,
            "【平台恢复】reason=$reason, attempt=$platformLiveRecoveryWindowAttempts/$PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS, active=${stream.safeActiveCandidateIndex}, total=${stream.candidates.size}, cdn=${stream.activeCandidateOrNull?.cdn.orEmpty()}, protocol=${stream.activeCandidateOrNull?.protocol.orEmpty()}",
        )
        when (reason) {
            LiveLineFallbackReason.Ended, LiveLineFallbackReason.Stall -> {
                // 服务端正常关流 / 卡死：优先沿用当前候选重新解析（换地址不换 CDN）。
                if (platformLiveSameLineRetries < PLATFORM_LIVE_SAME_LINE_RETRY_MAX) {
                    platformLiveSameLineRetries++
                    android.util.Log.i(VM_LIVE_LOG_TAG, "【平台恢复】保持候选 ${stream.activeCandidateIndex} 重新解析地址（第 $platformLiveSameLineRetries 次）")
                    schedulePlatformLiveReResolve(delayMs = PLATFORM_LIVE_RECOVERY_BACKOFF_MS * platformLiveSameLineRetries)
                    return
                }
                android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】当前候选重连已用尽，改为切换下一条候选")
                platformLiveSameLineRetries = 0
                rotatePlatformLiveCandidate(stream)
            }
            LiveLineFallbackReason.Error -> {
                platformLiveSameLineRetries = 0
                rotatePlatformLiveCandidate(stream)
            }
        }
    }

    /** 切换到下一条候选 CDN；已是最后一条则强制重新解析。 */
    private fun rotatePlatformLiveCandidate(stream: ResolvedPlatformLiveStream) {
        val nextIdx = nextRotatedPlatformLiveCandidate(stream)
        val nextCandidate = nextIdx?.let { stream.candidates.getOrNull(it) }
        if (nextIdx == null || nextCandidate == null) {
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】候选线路已轮完一圈或无可用候选，强制重新解析")
            platformLiveRecoveryInProgress = false
            reconnectPlatformLiveChannel()
            return
        }
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                error = null,
                notice = "已切换到线路 ${nextIdx + 1}/${stream.candidates.size}（${nextCandidate.cdn}）",
                stream = stream.withActiveCandidate(nextIdx)),
            )
        }
        android.util.Log.i(VM_LIVE_LOG_TAG, "【平台恢复】切换到候选线路 $nextIdx, cdn=${nextCandidate.cdn}")
        platformLiveRecoveryInProgress = false
    }

    /** 退避后重新解析当前房间，保持当前候选下标。 */
    private fun schedulePlatformLiveReResolve(delayMs: Long) {
        val keepCandidate = _state.value.platformLive.stream?.safeActiveCandidateIndex ?: 0
        platformLiveResolveJob = relaunch(platformLiveResolveJob) {
            kotlinx.coroutines.delay(delayMs)
            platformLiveRecoveryInProgress = false
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】退避 ${delayMs}ms 后重新解析（候选 $keepCandidate）")
            resolvePlatformLiveRoom(forceRefresh = true, candidateIndex = keepCandidate, isRecovery = true)
        }
    }

    private fun roomTag(): String {
        val state = _state.value
        return state.platformLive.rooms.getOrNull(state.platformLive.roomIndex)?.let { "${it.site}/${it.id}" } ?: "none"
    }

    fun switchPlayerSource(index: Int, positionMs: Long, playWhenReady: Boolean): Boolean {
        val current = _state.value
        val movie = current.detailMovie ?: return false
        val target = com.tvbox.app.domain.resolvePlaybackSourceSwitch(
            movie, current.playerSourceIndex, current.playerEpisodeIndex, index, positionMs, playWhenReady,
        ) ?: return false
        _state.update {
            it.copy(
                playerSourceIndex = target.sourceIndex,
                selectedSourceIndex = target.sourceIndex,
                playerEpisodeIndex = target.episodeIndex,
                selectedEpisodeIndex = target.episodeIndex,
                playerStartPositionMs = target.positionMs,
                playerPlayWhenReady = target.playWhenReady,
            )
        }
        return true
    }

    fun updatePlaybackSpeed(speed: Float) {
        if (speed <= 0f || _state.value.playerSpeed == speed) return
        _state.update { it.copy(playerSpeed = speed) }
    }

    fun updatePlayerPlayIntent(playWhenReady: Boolean) {
        _state.update { it.copy(playerPlayWhenReady = playWhenReady) }
    }

    fun savePlaybackProgress(positionMs: Long, durationMs: Long, snapshot: TvBoxUiState = _state.value) {
        val current = snapshot
        val movie = current.detailMovie ?: return
        val source = movie.playSources.getOrNull(current.playerSourceIndex) ?: return
        val episode = source.episodes.getOrNull(current.playerEpisodeIndex) ?: return
        if (episode.url.isBlank()) return

        val item = WatchHistoryItem(
            movieId = movie.id,
            apiLineId = movie.apiLineId,
            apiLineName = movie.apiLineName,
            movieName = movie.name,
            posterUrl = movie.posterUrl,
            typeName = movie.typeName,
            remarks = movie.remarks,
            sourceIndex = current.playerSourceIndex,
            sourceName = source.name,
            episodeIndex = current.playerEpisodeIndex,
            episodeTitle = episode.title,
            episodeUrl = episode.url,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        val maxItems = _state.value.appSettings.historyMaxItems
        viewModelScope.launch {
            historyWriteMutex.withLock {
                runCatching { historyRepository.saveProgress(item, maxItems = maxItems) }
                    .onSuccess { history ->
                        _state.update { it.copy(historyItems = history) }
                    }
            }
        }
    }

    fun retryCurrent() {
        when (_state.value.screen) {
            TvScreen.Home -> refreshHome(forceRefresh = true)
            TvScreen.History -> loadHistory()
            TvScreen.Search -> submitSearch()
            TvScreen.Detail -> _state.value.detailMovie?.let { openDetail(it.id) }
            TvScreen.Player -> Unit
            TvScreen.Live -> refreshLive()
            TvScreen.PlatformLive -> retryPlatformLiveChannel()
            TvScreen.Settings -> checkForAppUpdate(showError = true)
            TvScreen.SourceManager, TvScreen.LiveSourceManager, TvScreen.VideoSourceManager -> Unit
        }
    }

    fun goBack(): Boolean {
        val current = _state.value
        return when (current.screen) {
            TvScreen.Player -> {
                _state.update {
                    it.copy(
                        screen = TvScreen.Detail,
                        selectedSourceIndex = it.playerSourceIndex,
                        selectedEpisodeIndex = it.playerEpisodeIndex,
                    )
                }
                true
            }
            TvScreen.Detail -> {
                historyResumeJob?.cancel()
                detailJob?.cancel()
                _state.update { it.copy(screen = it.detailReturnScreen) }
                true
            }
            TvScreen.Search -> {
                clearSearch()
                _state.update { it.copy(screen = TvScreen.Home) }
                true
            }
            TvScreen.History, TvScreen.Settings -> {
                _state.update { it.copy(screen = TvScreen.Home) }
                true
            }
            TvScreen.Live -> {
                _state.update { it.copy(screen = TvScreen.Home) }
                true
            }
            TvScreen.PlatformLive -> {
                when (current.platformLive.destination) {
                    PlatformLiveDestination.Player -> {
                        platformLiveResolveJob?.cancel()
                        platformLiveRecoveryInProgress = false
                        platformLiveSameLineRetries = 0
                        platformLiveRecoveryWindowAttempts = 0
                        platformLiveRecoveryWindowStartMs = 0L
                        _state.update {
                            it.copy(platformLive = it.platformLive.copy(
                                destination = it.platformLive.playerReturnDestination,
                                resolving = false,
                                error = null,
                                stream = null,
                                notice = null),
                            )
                        }
                    }
                    PlatformLiveDestination.Rooms -> _state.update {
                        it.copy(platformLive = it.platformLive.copy(
                            destination = PlatformLiveDestination.Categories,
                            selectedCategory = null,
                            error = null),
                        )
                    }
                    PlatformLiveDestination.Favorites -> _state.update {
                        it.copy(platformLive = it.platformLive.copy(
                            destination = PlatformLiveDestination.Sites,
                            rooms = emptyList(),
                            error = null),
                        )
                    }
                    PlatformLiveDestination.Categories -> _state.update {
                        it.copy(platformLive = it.platformLive.copy(
                            destination = PlatformLiveDestination.ParentCategories,
                            selectedCategory = null,
                            error = null),
                        )
                    }
                    PlatformLiveDestination.ParentCategories -> _state.update {
                        it.copy(platformLive = it.platformLive.copy(
                            destination = PlatformLiveDestination.Sites,
                            selectedParentCategory = null,
                            error = null),
                        )
                    }
                    PlatformLiveDestination.Sites -> _state.update { it.copy(screen = TvScreen.Home) }
                }
                true
            }
            TvScreen.SourceManager -> {
                _state.update { it.copy(screen = TvScreen.Settings) }
                true
            }
            TvScreen.LiveSourceManager -> {
                _state.update { it.copy(screen = TvScreen.Settings) }
                true
            }
            TvScreen.VideoSourceManager -> {
                _state.update { it.copy(screen = TvScreen.Settings) }
                true
            }
            TvScreen.Home -> false
        }
    }

    private fun orderApiLines(allLines: List<ApiLine>, preferredOrder: List<String>): List<ApiLine> =
        com.tvbox.app.domain.orderApiLines(allLines, preferredOrder)

    private fun orderLinesByLatency(lines: List<ApiLine>, latencies: Map<String, Long?>): List<ApiLine> =
        com.tvbox.app.domain.orderLinesByLatency(lines, latencies)

    private fun switchHomeApiLine(apiLineId: String) {
        _state.update { it.copy(selectedApiLineId = apiLineId) }
        loadHomePage(reset = true)
    }


    // ========== Source Management Methods ==========

    fun testSourceSpeed(apiLineId: String) {
        if (_state.value.source.testing) return
        viewModelScope.launch {
            val latency = runCatching { repository.testSpeed(apiLineId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            _state.update { state ->
                state.copy(source = state.source.copy(latencies = state.source.latencies + (apiLineId to latency)))
            }
            persistSourceLatencies()
        }
    }

    fun testAllSources() {
        runSourceSpeedTest(autoSort = false)
    }

    /** 一键测速并按延迟重排：测速完成后立即把最快线路放到前面并持久化。 */
    fun testAllSourcesAndSort() {
        runSourceSpeedTest(autoSort = true)
    }

    private fun runSourceSpeedTest(autoSort: Boolean) {
        val current = _state.value
        if (current.source.testing) return
        val srcLines = current.apiLines
        if (srcLines.isEmpty()) return
        sourceTestJob?.cancel()
        sourceTestJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    source = it.source.copy(
                        testing = true,
                        testCompleted = 0,
                        testTotal = srcLines.size,
                        managerMessage = null,
                        importMessage = null,
                    ),
                )
            }
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val mutex = kotlinx.coroutines.sync.Mutex()
            var completed = 0
            coroutineScope {
                for (line in srcLines) {
                    launch {
                        semaphore.withPermit {
                            val latency = runCatching { repository.testSpeed(line.id) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                            mutex.withLock {
                                completed++
                                _state.update {
                                    it.copy(
                                        source = it.source.copy(
                                            latencies = it.source.latencies + (line.id to latency),
                                            testCompleted = completed,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val latencies = _state.value.source.latencies
            val okCount = srcLines.count { latencies[it.id] != null }
            if (autoSort) {
                val sorted = orderLinesByLatency(srcLines, latencies)
                val settings = _state.value.appSettings.copy(sourceOrder = sorted.map { it.id })
                saveSettings(settings)
                _state.update {
                    it.copy(
                        apiLines = sorted,
                        allSourceLines = orderApiLines(repository.allApiLines, settings.sourceOrder),
                        appSettings = settings,
                        source = it.source.copy(
                            testing = false,
                            managerMessage = "测速完成：成功 $okCount 个，失败 ${srcLines.size - okCount} 个；已按延迟排列（最快在前）。",
                        ),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        source = it.source.copy(
                            testing = false,
                            managerMessage = "测速完成：成功 $okCount 个，失败 ${srcLines.size - okCount} 个。可点击“按测速排序”重新排列。",
                        ),
                    )
                }
            }
            persistSourceLatencies()
        }
    }

    /** 对当前详情页的所有播放线路测速，结果会持久化并用于按延迟排列线路。 */
    fun testDetailSourceSpeeds() {
        val current = _state.value
        if (current.source.detailTesting) return
        val movie = current.detailMovie ?: return
        val lineIds = movie.playSources
            .map { it.lineId }
            .filter { it.isNotBlank() }
            .distinct()
        if (lineIds.isEmpty()) return
        detailSourceTestJob?.cancel()
        detailSourceTestJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    source = it.source.copy(
                        detailTesting = true,
                        detailTestCompleted = 0,
                        detailTestTotal = lineIds.size,
                        detailMessage = null,
                    ),
                )
            }
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val mutex = kotlinx.coroutines.sync.Mutex()
            var completed = 0
            coroutineScope {
                for (lineId in lineIds) {
                    launch {
                        semaphore.withPermit {
                            val latency = runCatching { repository.testSpeed(lineId) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                            mutex.withLock {
                                completed++
                                _state.update {
                                    it.copy(
                                        source = it.source.copy(
                                            latencies = it.source.latencies + (lineId to latency),
                                            detailTestCompleted = completed,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            persistSourceLatencies()
            val latencies = _state.value.source.latencies
            val okCount = lineIds.count { latencies[it] != null }
            _state.update {
                it.copy(
                    source = it.source.copy(
                        detailTesting = false,
                        detailMessage = "线路测速完成：成功 $okCount/${lineIds.size}，已按延迟排序。",
                    ),
                )
            }
        }
    }

    fun sortSourcesBySpeed() {
        val current = _state.value
        if (current.source.testing || current.source.importing) return
        val sorted = orderLinesByLatency(current.apiLines, current.source.latencies)
        if (sorted == current.apiLines) {
            _state.update { it.copy(source = it.source.copy(managerMessage = "尚未测速或顺序未变化，请先执行“全部测速”。")) }
            return
        }
        val settings = current.appSettings.copy(sourceOrder = sorted.map { it.id })
        saveSettings(settings)
        _state.update {
            it.copy(
                apiLines = sorted,
                allSourceLines = orderApiLines(repository.allApiLines, settings.sourceOrder),
                appSettings = settings,
                source = it.source.copy(managerMessage = "已按测速排序（最快在前）"),
            )
        }
    }

    private fun applySourceSettings(settings: AppSettings) {
        repository.updateCustomApiLines(settings.customVideoApiLines.toApiLines())
        repository.setDisabledLineIds(settings.blockedSourceIds.toSet())
    }

    private fun availableApiLinesFor(settings: AppSettings): List<ApiLine> =
        orderApiLines(repository.apiLines, settings.sourceOrder)

    private fun allApiLinesFor(settings: AppSettings): List<ApiLine> =
        orderApiLines(repository.allApiLines, settings.sourceOrder)

    private fun resolveHomeSelection(
        settings: AppSettings,
        available: List<ApiLine>,
    ): Pair<AppSettings, Boolean> {
        val selectionMissing = available.none { it.id == settings.homeApiLineId }
        val finalSettings = if (selectionMissing) {
            settings.copy(homeApiLineId = available.firstOrNull()?.id.orEmpty())
        } else {
            settings
        }
        return finalSettings to selectionMissing
    }

    fun deleteApiLine(apiLineId: String) {
        val current = _state.value
        val line = current.allSourceLines.firstOrNull { it.id == apiLineId }
            ?: current.apiLines.firstOrNull { it.id == apiLineId }
            ?: return
        val settings = current.appSettings.copy(
            customVideoApiLines = current.appSettings.customVideoApiLines.filterNot { it.id == line.id },
            blockedSourceIds = current.appSettings.blockedSourceIds - line.id,
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(
                    latencies = it.source.latencies - line.id,
                    managerMessage = "已删除源“${line.name}”",
                ),
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deleteAllSources() {
        val current = _state.value
        if (current.source.testing || current.source.importing) return
        val allCustomIds = current.appSettings.customVideoApiLines.map { it.id }.toSet()
        if (allCustomIds.isEmpty()) {
            _state.update { it.copy(source = it.source.copy(managerMessage = "暂无自定义视频源可删除")) }
            return
        }
        val settings = current.appSettings.copy(
            customVideoApiLines = emptyList(),
            blockedSourceIds = current.appSettings.blockedSourceIds.filterNot { it in allCustomIds },
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(
                    latencies = it.source.latencies.filterKeys { k -> k !in allCustomIds },
                    managerMessage = "已一键删除全部自定义视频源（${allCustomIds.size} 个）",
                ),
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deduplicateSourcesByDomain() {
        val current = _state.value
        if (current.source.testing || current.source.importing) return
        val custom = current.appSettings.customVideoApiLines
        if (custom.isEmpty()) {
            _state.update { it.copy(source = it.source.copy(managerMessage = "暂无自定义视频源")) }
            return
        }
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<CustomVideoApiLine>()
        val removed = mutableListOf<CustomVideoApiLine>()
        for (line in custom) {
            val host = try {
                java.net.URI(line.baseUrl).host?.lowercase()?.removePrefix("www.") ?: line.baseUrl.lowercase()
            } catch (e: Exception) {
                line.baseUrl.lowercase()
            }
            if (host in seen) {
                removed.add(line)
            } else {
                seen.add(host)
                kept.add(line)
            }
        }
        if (removed.isEmpty()) {
            _state.update { it.copy(source = it.source.copy(managerMessage = "未发现重复域名的视频源")) }
            return
        }
        val removedIds = removed.map { it.id }.toSet()
        val settings = current.appSettings.copy(
            customVideoApiLines = kept,
            blockedSourceIds = current.appSettings.blockedSourceIds.filterNot { it in removedIds },
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(
                    latencies = it.source.latencies.filterKeys { k -> k !in removedIds },
                    managerMessage = "已按域名去重：保留 ${kept.size} 个，删除 ${removed.size} 个重复项",
                ),
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun blockSource(apiLineId: String) {
        val current = _state.value
        val line = current.allSourceLines.firstOrNull { it.id == apiLineId } ?: return
        if (apiLineId in current.appSettings.blockedSourceIds) return
        val settings = current.appSettings.copy(
            blockedSourceIds = (current.appSettings.blockedSourceIds + apiLineId).distinct(),
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(managerMessage = "已屏蔽“${line.name}”，可随时取消屏蔽。"),
            )
        }
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun unblockSource(apiLineId: String) {
        val current = _state.value
        if (apiLineId !in current.appSettings.blockedSourceIds) return
        val line = current.allSourceLines.firstOrNull { it.id == apiLineId }
        val settings = current.appSettings.copy(
            blockedSourceIds = current.appSettings.blockedSourceIds - apiLineId,
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(managerMessage = "已取消屏蔽${line?.let { "“${it.name}”" }.orEmpty()}"),
            )
        }
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deleteInvalidSources() {
        val current = _state.value
        if (current.source.testing || current.source.importing) return
        val tested = current.source.latencies
        val invalid = current.apiLines.filter { id -> tested.containsKey(id.id) && tested[id.id] == null }
        if (invalid.isEmpty()) {
            _state.update {
                it.copy(
                    source = it.source.copy(
                        managerMessage = if (tested.isEmpty()) {
                            "尚未测速，请先点击“全部测速”，再删除无效源。"
                        } else {
                            "没有检测到无效源。"
                        },
                    ),
                )
            }
            return
        }
        val invalidIds = invalid.map { it.id }.toSet()
        val settings = current.appSettings.copy(
            customVideoApiLines = current.appSettings.customVideoApiLines.filterNot { it.id in invalidIds },
            blockedSourceIds = current.appSettings.blockedSourceIds - invalidIds,
        )
        applySourceSettings(settings)
        val available = availableApiLinesFor(settings)
        val allLines = allApiLinesFor(settings)
        val (finalSettings, selectionMissing) = resolveHomeSelection(settings, available)
        saveSettings(finalSettings)
        _state.update {
            it.copy(
                apiLines = available,
                allSourceLines = allLines,
                selectedApiLineId = finalSettings.homeApiLineId,
                appSettings = finalSettings,
                source = it.source.copy(
                    latencies = it.source.latencies - invalidIds,
                    managerMessage = "已删除 ${invalid.size} 个无效源（测速失败或超时）。",
                ),
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun importSourceConfig(input: String) {
        viewModelScope.launch { importSourceConfigInternal(input) }
    }

    /** 导入 JSON / TVBox 订阅（地址或内容），返回可直接展示的提示文本。 */
    private suspend fun importSourceConfigInternal(input: String): String {
        val text = input.trim()
        if (text.isBlank()) {
            val message = "请粘贴配置内容或输入订阅地址"
            _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
            return message
        }
        if (_state.value.source.importing) return "正在导入上一份配置，请稍候再试。"
        _state.update { it.copy(source = it.source.copy(importing = true, importMessage = null)) }
        return try {
            val configFetcher = SourceConfigFetcher()
            val isUrl = text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true)
            val payload = if (isUrl) configFetcher.fetch(text) else text
            val decoded = try {
                SubscriptionDecoder.decode(url = if (isUrl) text else "", payload)
            } catch (error: IllegalArgumentException) {
                val message = "订阅解码失败：${error.message ?: "格式不合法"}"
                _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
                return message
            }
            val result = SourceConfigCodec.parseImport(decoded)
            val imported = result.imported
            if (result.error != null || imported == null) {
                val message = result.error ?: "配置解析失败"
                _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
                return message
            }
            val current = _state.value
            val existingUrls = current.appSettings.customVideoApiLines
                .map { it.baseUrl.lowercase() }
                .toSet()
            val newLines = imported.lines.filter { it.baseUrl.lowercase() !in existingUrls }
            val mergedCustom = (current.appSettings.customVideoApiLines + newLines)
                .distinctBy { it.id }
            val newLiveUrls = imported.liveUrls.filter { url ->
                url !in current.appSettings.liveSourceUrls
            }
            // 导入前 dry-run：本地再解析一次直播源，把"能导入多少频道、哪些字段缺失"直接说出来，
            // 避免格式错的源要等进到播放页才发现。只做本地结构校验，源不可达时静默跳过（不影响导入）。
            val liveDryRuns: List<Pair<String, LiveSourceDryRun>> = newLiveUrls.mapNotNull { url ->
                val body = try {
                    configFetcher.fetch(url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                url to dryRunLiveSource(body)
            }
            val settings = current.appSettings.copy(
                customVideoApiLines = mergedCustom,
                liveSourceUrls = current.appSettings.liveSourceUrls + newLiveUrls,
            )
            applySourceSettings(settings)
            applyLiveSourceConfig(settings)
            val ordered = availableApiLinesFor(settings)
            val allLines = allApiLinesFor(settings)
            saveSettings(settings)
            val message = buildString {
                val addedParts = mutableListOf<String>()
                if (newLines.isNotEmpty()) addedParts += "新增 ${newLines.size} 个视频源"
                if (newLiveUrls.isNotEmpty()) addedParts += "新增 ${newLiveUrls.size} 个直播源"
                if (addedParts.isEmpty()) {
                    append("导入完成：没有可新增的源")
                } else {
                    append(addedParts.joinToString("，"))
                }
                if (imported.skipped > 0) append("，跳过 ${imported.skipped} 个无效条目")
                // 直播源校验摘要：每个源一行，失败的源给出具体原因。
                liveDryRuns.forEach { (url, dryRun) ->
                    append("\n· ")
                    append(dryRun.headline())
                    val warnings = dryRun.warnings()
                    if (warnings.isNotEmpty()) {
                        append("；")
                        append(warnings.joinToString("，"))
                    }
                    if (!dryRun.ok) append("（${url.takeLast(32)}）")
                }
            }
            _state.update {
                it.copy(
                    apiLines = ordered,
                    allSourceLines = allLines,
                    appSettings = settings,
                    source = it.source.copy(importing = false, managerMessage = message),
                )
            }
            message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "导入失败：${error.message}"
            _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
            message
        }
    }

    fun dismissSourceImportMessage() {
        _state.update { it.copy(source = it.source.copy(importMessage = null)) }
    }

    fun updateLiveSourceUrls(urls: List<String>, defaultGroup: String = "", defaultIndex: Int = -1) {
        val normalized = urls.map { it.trim() }.filter { it.isNotEmpty() }
        val current = _state.value
        // 使用调用方传入的 defaultIndex；未传入时用当前设置（-1 表示无默认）。
        val effectiveDefaultIndex = if (defaultIndex >= 0) defaultIndex
            else current.appSettings.liveSourceDefaultIndex
                .takeIf { idx -> idx in normalized.indices } ?: -1
        val settings = current.appSettings.copy(
            liveSourceUrls = normalized,
            liveSourceDefaultGroup = defaultGroup,
            liveSourceDefaultIndex = effectiveDefaultIndex,
        )
        applyLiveSourceConfig(settings)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 把设置中的电视直播源应用到仓库。 */
    private fun applyLiveSourceConfig(settings: AppSettings) {
        liveRepository.setSourceConfig(settings.liveSourceUrls, settings.liveSourceDefaultGroup, settings.liveSourceDefaultIndex)
    }

    /** 设置电视直播默认源：为指定下标的源标记为默认（同一时间只有一个默认源）。 */
    fun setLiveSourceDefaultIndex(index: Int) {
        val current = _state.value
        if (index !in current.appSettings.liveSourceUrls.indices) return
        val settings = current.appSettings.copy(liveSourceDefaultIndex = index)
        _state.update { it.copy(appSettings = settings) }
        // 统一走 settingsWriteJob 串行提交（saveSettings 内部为 commit），避免与专项写入乱序回退。
        saveSettings(settings)
        applyLiveSourceConfig(settings)
        // 默认源立即生效：当前频道列表可能来自旧源，不重载会让用户以为"设置没成功"。
        if (_state.value.screen == TvScreen.Live) refreshLive()
    }

    /** 清除电视直播默认源。 */
    fun clearLiveSourceDefaultIndex() {
        val current = _state.value
        if (current.appSettings.liveSourceDefaultIndex == -1) return
        val settings = current.appSettings.copy(liveSourceDefaultIndex = -1)
        _state.update { it.copy(appSettings = settings) }
        saveSettings(settings)
        applyLiveSourceConfig(settings)
        if (_state.value.screen == TvScreen.Live) refreshLive()
    }

    /** 切换剧集排序顺序（正序 ↔ 倒序），持久化到 SharedPreferences。 */
    fun toggleEpisodeSortReverse() {
        val current = _state.value
        val settings = current.appSettings.copy(episodeSortReverse = !current.appSettings.episodeSortReverse)
        _state.update { it.copy(appSettings = settings) }
        saveSettings(settings)
    }

    /** 屏蔽/恢复视频源分类：屏蔽后该分类不再出现在电视的分类与影片列表。 */
    fun addBlockedVideoCategory(categoryName: String) {
        val name = categoryName.trim()
        if (name.isBlank()) return
        val current = _state.value
        if (name in current.appSettings.blockedVideoCategories) return
        val settings = current.appSettings.copy(
            blockedVideoCategories = (current.appSettings.blockedVideoCategories + name).distinct(),
        )
        saveSettings(settings)
        val blockingCurrent = current.selectedCategoryId
            ?.let { id -> current.categories.firstOrNull { it.id == id }?.name }
            ?.let { it == name } == true
        _state.update {
            it.copy(
                appSettings = settings,
                categories = filterBlockedCategories(it.categories, settings.blockedVideoCategories),
                movies = it.movies.filter { movie -> movie.typeName !in settings.blockedVideoCategories },
                selectedCategoryId = if (blockingCurrent) null else it.selectedCategoryId,
            )
        }
        if (blockingCurrent) refreshHome()
    }

    fun removeBlockedVideoCategory(categoryName: String) {
        val name = categoryName.trim()
        val current = _state.value
        if (name !in current.appSettings.blockedVideoCategories) return
        val settings = current.appSettings.copy(
            blockedVideoCategories = current.appSettings.blockedVideoCategories - name,
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
        refreshHome()
    }

    fun clearBlockedVideoCategories() {
        val current = _state.value
        if (current.appSettings.blockedVideoCategories.isEmpty()) return
        val settings = current.appSettings.copy(blockedVideoCategories = emptyList())
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
        refreshHome()
    }

    fun updateIntroSkipSeconds(seconds: Int) {
        val settings = _state.value.appSettings.copy(introSkipSeconds = seconds.coerceIn(0, 600))
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun updateOutroSkipSeconds(seconds: Int) {
        val settings = _state.value.appSettings.copy(outroSkipSeconds = seconds.coerceIn(0, 600))
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 删除（屏蔽）或恢复平台直播站点，例如斗鱼、虎牙。 */
    fun togglePlatformLiveSiteBlocked(siteId: String) {
        val current = _state.value
        val blocked = current.appSettings.blockedPlatformSiteIds
        val settings = current.appSettings.copy(
            blockedPlatformSiteIds = if (siteId in blocked) blocked - siteId else (blocked + siteId).distinct(),
        )
        val visibleSites = allPlatformLiveSites.filter { it.id !in settings.blockedPlatformSiteIds }
        saveSettings(settings)
        _state.update { state ->
            val staysOnBlockedSite = state.platformLive.destination == PlatformLiveDestination.Rooms &&
                state.platformLive.selectedSite?.id == siteId
            state.copy(
                appSettings = settings,
                platformLive = state.platformLive.copy(
                sites = visibleSites,
                allSites = allPlatformLiveSites,
                quickCategories = state.platformLive.quickCategories
                    .filter { it.site.id !in settings.blockedPlatformSiteIds },
                selectedSite = if (staysOnBlockedSite) null else state.platformLive.selectedSite,
                destination = if (staysOnBlockedSite) {
                    PlatformLiveDestination.Sites
                } else {
                    state.platformLive.destination
                }),
            )
        }
    }

    fun sourceConfigTvboxJson(): String {
        return SourceConfigCodec.exportTvbox(
            lines = _state.value.apiLines,
            liveUrls = _state.value.appSettings.liveSourceUrls,
        )
    }

    fun sourceConfigSourcesJson(): String {
        return SourceConfigCodec.exportSourcesJson(
            lines = _state.value.apiLines,
        )
    }

    private fun persistSourceLatencies() {
        viewModelScope.launch {
            appSettingsRepository.saveSpeedLatencies(_state.value.source.latencies)
        }
    }


    private fun loadHomePage(reset: Boolean, forceRefresh: Boolean = false) {
        if (_state.value.isShowingDoubanHot) {
            loadDoubanHotPage(reset = reset, forceRefresh = forceRefresh)
        } else {
            launchCatalogHomePage(reset = reset)
        }
    }

    fun syncPlayerEpisode(episodeIndex: Int) {
        _state.update { state ->
            val episodes = state.detailMovie
                ?.playSources
                ?.getOrNull(state.playerSourceIndex)
                ?.episodes
                .orEmpty()
            if (episodeIndex !in episodes.indices) {
                state
            } else {
                state.copy(
                    selectedSourceIndex = state.playerSourceIndex,
                    selectedEpisodeIndex = episodeIndex,
                    playerEpisodeIndex = episodeIndex,
                    playerStartPositionMs = 0L,
                )
            }
        }
    }

    private fun loadDoubanHotPage(reset: Boolean, forceRefresh: Boolean) {
        homeJob?.cancel()
        val current = _state.value
        val nextPage = if (reset) 1 else current.page + 1
        if (reset) loadMoreRetryPage = -1
        homeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    homeLoading = reset,
                    loadingMore = !reset,
                    homeError = null,
                    homeNotice = if (reset) null else it.homeNotice,
                    movies = if (reset) emptyList() else it.movies,
                    doubanHotMovies = if (reset) emptyList() else it.doubanHotMovies,
                    doubanHotFallbackActive = false,
                )
            }
            runCatching {
                doubanHotRepository.getRecentHot(
                    page = nextPage,
                    forceRefresh = forceRefresh,
                    pageSize = current.appSettings.doubanHotPageSize,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        doubanHotMovies = if (reset) {
                            result.items
                        } else {
                            (it.doubanHotMovies + result.items)
                                .distinctBy { item -> item.doubanId.ifBlank { item.title } }
                        },
                        page = result.page,
                        pageCount = result.pageCount,
                        total = result.total,
                        homeLoading = false,
                        loadingMore = false,
                        homeError = if (result.items.isEmpty() && reset) "当前没有热播内容" else null,
                        homeNotice = null,
                    )
                }
                loadMoreRetryPage = -1
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (reset) {
                    val fallbackNotice = "豆瓣热播暂时不可用，已显示当前线路最近更新"
                    _state.update { it.copy(doubanHotFallbackActive = true, homeNotice = fallbackNotice) }
                    launchCatalogHomePage(
                        reset = true,
                        fallbackNotice = fallbackNotice,
                        cancelCurrent = false,
                    )
                } else {
                    loadMoreRetryPage = nextPage
                    loadMoreRetryAtMs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            homeLoading = false,
                            loadingMore = false,
                            homeNotice = "热播加载失败，稍后将自动重试",
                        )
                    }
                }
            }
        }
    }

    private fun launchCatalogHomePage(
        reset: Boolean,
        fallbackNotice: String? = null,
        cancelCurrent: Boolean = true,
    ) {
        if (cancelCurrent) homeJob?.cancel()
        val current = _state.value
        val nextPage = if (reset) 1 else current.page + 1
        if (reset) loadMoreRetryPage = -1
        homeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    homeLoading = reset,
                    loadingMore = !reset,
                    homeError = null,
                    homeNotice = fallbackNotice ?: if (reset) null else it.homeNotice,
                    movies = if (reset) emptyList() else it.movies,
                    doubanHotFallbackActive = fallbackNotice != null || it.doubanHotFallbackActive,
                )
            }
            runCatching {
                val latest = _state.value
                val selectedTypeId = latest.selectedCategoryId
                val parentTypeIds = latest.childCategoryIdsForSelectedParent()
                when {
                    selectedTypeId != null -> repository.getMovies(
                        apiLineId = latest.selectedApiLineId,
                        page = nextPage,
                        typeId = selectedTypeId,
                    )
                    parentTypeIds.isNotEmpty() -> repository.getMoviesByTypeIds(
                        apiLineId = latest.selectedApiLineId,
                        page = nextPage,
                        typeIds = parentTypeIds,
                    )
                    latest.selectedParentCategoryId != null -> repository.getMovies(
                        apiLineId = latest.selectedApiLineId,
                        page = nextPage,
                        typeId = latest.selectedParentCategoryId,
                    )
                    latest.isDefaultAllCategorySelection() -> repository.getMovies(
                        apiLineId = latest.selectedApiLineId,
                        page = nextPage,
                        typeId = DEFAULT_ALL_CATEGORY_TYPE_ID,
                    )
                    else -> repository.getMovies(
                        apiLineId = latest.selectedApiLineId,
                        page = nextPage,
                    )
                }
            }.onSuccess { result ->
                _state.update {
                    val blocked = it.appSettings.blockedVideoCategories
                    it.copy(
                        categories = filterBlockedCategories(
                            if (result.categories.isNotEmpty()) result.categories else it.categories,
                            blocked,
                        ),
                        movies = (
                            if (reset) {
                                result.movies
                            } else {
                                (it.movies + result.movies).distinctBy { movie -> "${movie.apiLineId}-${movie.id}" }
                            }
                            ).filter { movie -> movie.typeName !in blocked },
                        page = result.page,
                        pageCount = result.pageCount,
                        total = result.total,
                        homeLoading = false,
                        loadingMore = false,
                        homeError = if (result.movies.isEmpty() && reset) "当前没有影片" else null,
                    )
                }
                loadMoreRetryPage = -1
            }.onFailure { error ->
                // 取消异常（切换分类时旧 job 被 cancel）不做任何 UI 处理，避免显示错误。
                val message = error.uiFailureMessageOrNull() ?: return@onFailure
                if (!reset) {
                    loadMoreRetryPage = nextPage
                    loadMoreRetryAtMs = System.currentTimeMillis()
                }
                _state.update {
                    it.copy(
                        homeLoading = false,
                        loadingMore = false,
                        homeError = message,
                    )
                }
                if (_state.value.categories.isEmpty()) {
                    loadCategoriesOnly()
                }
            }
        }
    }

    private fun loadCategoriesOnly() {
        val apiLineId = _state.value.selectedApiLineId
        // 快速切源/连续改屏蔽分类时，旧请求可能后到；取消旧 job 并校验源，避免过期分类覆盖新源。
        categoriesJob = relaunch(categoriesJob) {
            runCatching { repository.getCategories(apiLineId = apiLineId) }
                .onSuccess { categories ->
                    _state.update { state -> state.applyCategoryResult(apiLineId, categories) }
                }
        }
    }

    private suspend fun findPlayableDoubanMovie(title: String, apiLineId: String): Movie? {
        val keyword = title.trim()
        if (keyword.isBlank()) return null
        val movies = repository.getMovies(
            apiLineId = apiLineId,
            page = 1,
            keyword = keyword,
        ).movies
        val matchedIndex = findBestTitleMatchIndex(keyword, movies.map { it.name }) ?: return null
        return movies.getOrNull(matchedIndex)
    }

    private fun loadHistory() {
        viewModelScope.launch {
            runCatching { historyRepository.getHistory() }
                .onSuccess { history ->
                    val maxItems = _state.value.appSettings.historyMaxItems
                    _state.update { it.copy(historyItems = history.take(maxItems)) }
                }
        }
    }

    private fun refreshPlaybackHealth() {
        viewModelScope.launch {
            runCatching { playbackHealthRepository.getSnapshot() }
                .onSuccess { snapshot ->
                    _state.update { it.copy(playbackHealth = snapshot) }
                }
        }
    }

    private fun saveSettings(settings: AppSettings) {
        val previous = settingsWriteJob
        settingsWriteJob = viewModelScope.launch {
            previous?.join()
            runCatching { appSettingsRepository.saveSettings(settings) }
                .onFailure {
                    _state.update { state -> state.copy(settingsSaveError = "设置保存失败") }
                }
        }
    }

    private fun recordPlaybackIssue(state: TvBoxUiState, issueType: PlaybackIssueType) {
        val key = state.playbackHealthKeyOrNull() ?: return
        viewModelScope.launch {
            runCatching {
                playbackHealthRepository.recordIssue(
                    key = key,
                    issueType = issueType,
                    nowMs = System.currentTimeMillis(),
                )
            }.onSuccess { snapshot ->
                _state.update { it.copy(playbackHealth = snapshot) }
            }
        }
    }

    /**
     * 该频道进台时优先使用的线路下标，按三级记忆依次判定：
     * 1. 该频道上次成功播放的线路（最贴近"这台就是这条线好用"）；
     * 2. 否则按域名评分挑选：同一 CDN 域名下其它频道的成败会传导过来
     *    （[rankLiveLineIndicesByDomain]），源里通道多时能直接跳过近期塌掉的域名；
     * 3. 都没有记录时回第 1 条，保持源内原顺序。
     */
    private fun TvBoxUiState.rememberedLineIndexOf(channel: com.tvbox.app.domain.LiveChannel): Int {
        val saved = liveLinePrefs?.getSavedLineIndex(channel.key()) ?: -1
        if (saved in channel.lines.indices) return saved
        val scores = liveDomainScores?.snapshot().orEmpty()
        if (scores.isEmpty()) return 0
        val ranked = rankLiveLineIndicesByDomain(channel.lines, scores, System.currentTimeMillis())
        // 只在"确有可用依据"时改顺序：最优线路评分为 0（全无记录）时保持源内顺序。
        val best = ranked.firstOrNull() ?: return 0
        val bestDomain = liveLineDomain(channel.lines[best].url) ?: return 0
        val bestScore = scores[bestDomain]?.score(System.currentTimeMillis()) ?: 0.0
        return if (bestScore > 0.0) best else 0
    }

    /** 记录当前线路的一次播放失败到域名评分表（错误/卡死/结束都会走到这里）。 */
    private fun recordLiveLineFailure(state: TvBoxUiState) {
        val url = state.live.channels.getOrNull(state.live.channelIndex)
            ?.lines?.getOrNull(state.live.lineIndex)?.url ?: return
        val domain = liveLineDomain(url) ?: return
        liveDomainScores?.record(domain, success = false)
    }

    private fun loadLiveChannels() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = TvScreen.Live,
                    live = it.live.copy(loading = true, error = null),
                )
            }
            runCatching { liveRepository.getChannels() }
                .onSuccess { channels ->
                    _state.update {
                        // 重新加载后按频道恢复上次成功播放的线路；线路数变少时回退到第 1 条。
                        val rememberedLine = channels.getOrNull(
                            it.live.channelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0)),
                        )?.let { channel ->
                            liveLinePrefs?.getSavedLineIndex(channel.key()) ?: -1
                        } ?: -1
                        val lineCount = channels.getOrNull(it.live.channelIndex)?.lines?.size ?: 0
                        // 订阅切换：尽量保持原分类名；新订阅没有该分类时回"全部"。
                        val groups = channels.map { channel -> channel.groupName }.distinct()
                        val restoreGroup = pendingLiveGroupRestore
                        pendingLiveGroupRestore = null
                        val groupIndex = if (restoreGroup != null) {
                            val idx = groups.indexOf(restoreGroup)
                            if (idx >= 0) idx + 1 else 0
                        } else {
                            it.live.groupIndex.coerceAtMost(groups.size)
                        }
                        val targetChannel = if (groupIndex == 0) {
                            it.live.channelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0))
                        } else {
                            channels.indexOfFirst { channel -> channel.groupName == groups[groupIndex - 1] }
                                .coerceAtLeast(0)
                        }
                        val targetLine = channels.getOrNull(targetChannel)
                            ?.let { channel -> it.rememberedLineIndexOf(channel) } ?: 0
                        it.copy(
                            live = it.live.copy(
                                channels = channels,
                                channelIndex = targetChannel,
                                lineIndex = if (rememberedLine in 0 until lineCount && targetChannel == it.live.channelIndex) rememberedLine else targetLine,
                                groupIndex = groupIndex,
                                loading = false,
                                notice = restoreGroup?.let { _ -> "已切换订阅" } ?: it.live.notice,
                                error = if (channels.isEmpty()) "没有可用直播频道" else null,
                            ),
                        )
                    }
                    // 源自带 EPG（M3U 头部 url-tvg 等）：用户未显式配置时自动采用，
                    // 已手工配置则以用户配置优先，不覆盖。
                    adoptBundledLiveEpg()
                    // 频道列表就绪后补拉节目单（幂等：已加载过则直接返回）。
                    loadLiveEpg()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            live = it.live.copy(loading = false, error = error.userMessage()),
                        )
                    }
                }
        }
    }

    private fun loadPlatformLiveSites() {
        platformLiveJob?.cancel()
        platformLiveResolveJob?.cancel()
        platformLiveJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = TvScreen.PlatformLive,
                    platformLive = it.platformLive.copy(
                    destination = PlatformLiveDestination.Sites,
                    loading = true,
                    loadingMore = false,
                    resolving = false,
                    error = null,
                    stream = null),
                )
            }
            runCatching { platformLiveRepository.getSites() }
                .onSuccess { sites ->
                    allPlatformLiveSites = sites
                    val blockedSiteIds = _state.value.appSettings.blockedPlatformSiteIds
                    val visibleSites = sites.filter { it.id !in blockedSiteIds }
                    _state.update {
                        if (it.platformLive.destination != PlatformLiveDestination.Sites) it else it.copy(platformLive = it.platformLive.copy(
                            sites = visibleSites,
                            allSites = sites,
                            loading = false,
                            error = if (visibleSites.isEmpty()) {
                                "所有平台直播站点均已删除，可在源管理页恢复"
                            } else {
                                null
                            }),
                        )
                    }
                    loadPlatformLiveQuickCategories(visibleSites)
                }
                .onFailure { error ->
                    val message = error.uiFailureMessageOrNull() ?: return@onFailure
                    _state.update {
                        if (it.platformLive.destination != PlatformLiveDestination.Sites) it else it.copy(platformLive = it.platformLive.copy(
                            loading = false,
                            error = message),
                        )
                    }
                }
        }
    }

    /** 后台加载常用分类所在平台的分类树，按名称匹配生成直达入口；失败静默。 */
    private fun loadPlatformLiveQuickCategories(sites: List<PlatformLiveSite>) {
        platformLiveQuickJob?.cancel()
        platformLiveQuickJob = viewModelScope.launch {
            val quick = quickCategoryTargets.mapNotNull { (siteId, categoryName) ->
                val site = sites.firstOrNull { it.id == siteId } ?: return@mapNotNull null
                val tree = quickCategoryTreeCache[siteId]
                    ?: runCatching { platformLiveRepository.getCategoryTree(siteId) }.getOrNull()
                    ?.also { quickCategoryTreeCache[siteId] = it }
                    ?: return@mapNotNull null
                val category = tree.categories.firstOrNull { it.name == categoryName }
                    ?: return@mapNotNull null
                PlatformLiveQuickCategory(site = site, category = category)
            }
            if (quick.isNotEmpty()) {
                _state.update { it.copy(platformLive = it.platformLive.copy(quickCategories = quick)) }
            }
        }
    }

    fun openPlatformLiveQuickCategory(quick: PlatformLiveQuickCategory) {
        val tree = quickCategoryTreeCache[quick.site.id]
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLive = it.platformLive.copy(
                destination = PlatformLiveDestination.Rooms,
                selectedSite = quick.site,
                parentCategories = tree?.parentCategories ?: emptyList(),
                selectedParentCategory = tree?.parentCategories
                    ?.firstOrNull { parent -> parent.id == quick.category.parentId },
                categories = tree?.categories ?: emptyList(),
                selectedCategory = quick.category,
                rooms = emptyList(),
                roomIndex = 0,
                roomPage = 1,
                roomPageCount = 1,
                error = null,
                stream = null),
            )
        }
        loadPlatformLiveRooms(quick.category, reset = true)
    }

    private fun loadPlatformLiveFavorites() {
        viewModelScope.launch {
            platformLiveFavoritesMutex.withLock {
                runCatching { platformLiveFavoritesRepository.getFavorites() }
                    .onSuccess { favorites ->
                        _state.update { current ->
                            if (current.platformLive.destination == PlatformLiveDestination.Favorites) {
                                current.copy(platformLive = current.platformLive.copy(favorites = favorites, rooms = favorites, favoriteError = null))
                            } else {
                                current.copy(platformLive = current.platformLive.copy(favorites = favorites, favoriteError = null))
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { it.copy(platformLive = it.platformLive.copy(favoriteError = error.userMessage())) }
                    }
            }
        }
    }

    /** 读取持久化的电视直播收藏键；仓库缺失或读取失败时保持现值（内存收藏不受影响）。 */
    private fun loadLiveFavorites() {
        val repository = liveFavoritesRepository ?: return
        viewModelScope.launch {
            liveFavoritesMutex.withLock {
                runCatching { repository.getFavoriteKeys() }
                    .onSuccess { keys -> _state.update { it.copy(live = it.live.copy(favoriteKeys = keys)) } }
            }
        }
    }

    /**
     * 采用直播源自带的 EPG 地址（M3U 头部 `url-tvg` / `x-tvg-url` / `tvg-url`）。
     *
     * 优先级：用户手工配置的 [AppSettings.epgUrl] > 源自带地址。
     * 即用户没配过 EPG 时自动启用源里的地址；配过则不覆盖（设置页可清除以回到自动）。
     */
    private fun adoptBundledLiveEpg() {
        val bundled = liveRepository.bundledEpgUrl?.trim().orEmpty()
        val current = _state.value
        // 换源后旧源的自带地址必须失效：新源没有 url-tvg 时回退到"未配置"。
        if (bundled == current.live.epgBundledUrl) return
        _state.update {
            it.copy(
                live = it.live.copy(
                    epgBundledUrl = bundled,
                    epgByChannel = emptyMap(),
                    epgLoaded = false,
                ),
            )
        }
        // 只作为"用户未配置时的回退值"，绝不写入 appSettings，避免被其它保存动作落盘，
        // 导致换源后仍残留上一份源的 EPG 地址。
        loadLiveEpg(force = true)
    }

    /** 当前实际使用的 EPG 地址：用户配置优先，其次源自带（M3U 头部 url-tvg）。 */
    private fun effectiveEpgUrl(state: TvBoxUiState): String =
        resolveEffectiveEpgUrl(state.appSettings.epgUrl, state.live.epgBundledUrl)

    /**
     * 拉取 XMLTV 节目单全量 Map 存入状态；EPG 是增强项：
     * 未配置地址直接返回，失败静默并标记已加载，避免反复重试慢源。
     * [force] 为 true 时忽略 [LiveUiState.epgLoaded] 重新拉取（保存新地址时用）。
     */
    private fun loadLiveEpg(force: Boolean = false) {
        val url = effectiveEpgUrl(_state.value)
        if (url.isBlank()) return
        if (!force && _state.value.live.epgLoaded) return
        epgJob = relaunch(epgJob) {
            // 取消必须透传：否则被取消的旧请求会以空节目单写回 epgLoaded=true。
            val programs = try {
                epgRepository.fetch(url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyMap()
            }
            _state.update { state -> state.applyEpgResult(url, programs) }
        }
    }

    private fun loadPlatformLiveCategories(site: PlatformLiveSite) {
        platformLiveJob?.cancel()
        platformLiveJob = viewModelScope.launch {
            _state.update {
                if (it.platformLive.selectedSite?.id != site.id) it else {
                    it.copy(platformLive = it.platformLive.copy(loading = true, error = null))
                }
            }
            runCatching { platformLiveRepository.getCategoryTree(site.id) }
                .onSuccess { tree ->
                    _state.update {
                        if (it.platformLive.selectedSite?.id != site.id) it else {
                            it.copy(platformLive = it.platformLive.copy(
                                destination = PlatformLiveDestination.ParentCategories,
                                parentCategories = tree.parentCategories,
                                selectedParentCategory = null,
                                categories = tree.categories,
                                loading = false,
                                error = if (tree.parentCategories.isEmpty()) "没有可用直播大分类" else null),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (it.platformLive.selectedSite?.id != site.id) it else {
                            it.copy(platformLive = it.platformLive.copy(loading = false, error = error.userMessage()))
                        }
                    }
                }
        }
    }

    private fun loadPlatformLiveRooms(category: PlatformLiveCategory, reset: Boolean) {
        val state = _state.value
        val site = state.platformLive.selectedSite ?: return
        val page = if (reset) 1 else state.platformLive.roomPage + 1
        platformLiveJob?.cancel()
        platformLiveJob = viewModelScope.launch {
            _state.update {
                if (it.platformLive.selectedCategory?.id != category.id) it else {
                    if (reset) {
                        it.copy(platformLive = it.platformLive.copy(loading = true, loadingMore = false, error = null))
                    } else {
                        it.copy(platformLive = it.platformLive.copy(loadingMore = true, error = null))
                    }
                }
            }
            runCatching { platformLiveRepository.getRooms(site.id, category.id, page) }
                .onSuccess { result ->
                    _state.update {
                        if (it.platformLive.selectedCategory?.id != category.id) it else {
                            val rooms = if (reset) {
                                result.rooms
                            } else {
                                (it.platformLive.rooms + result.rooms).distinctBy { room -> room.id }
                            }
                            it.copy(platformLive = it.platformLive.copy(
                                rooms = rooms,
                                // 空页不推进页码：否则自动加载会连续把剩余页全部请求一遍。
                                roomPage = if (result.rooms.isEmpty() && !reset) it.platformLive.roomPage else result.page,
                                roomPageCount = result.pageCount,
                                loading = false,
                                loadingMore = false,
                                error = if (rooms.isEmpty()) "这个分类暂时没有正在直播的房间" else null),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (it.platformLive.selectedCategory?.id != category.id) it else {
                            it.copy(platformLive = it.platformLive.copy(
                                loading = false,
                                loadingMore = false,
                                error = error.userMessage()),
                            )
                        }
                    }
                }
        }
    }

    private fun resolvePlatformLiveRoom(
        forceRefresh: Boolean = false,
        candidateIndex: Int = 0,
        isRecovery: Boolean = false,
    ) {
        val state = _state.value
        val room = state.platformLive.rooms.getOrNull(state.platformLive.roomIndex) ?: return
        val siteId = state.platformLive.selectedSite?.id
        if (!isRecovery) {
            // 用户主动换房间/手动重连：清空恢复熔断窗口与重连计数。
            platformLiveRecoveryInProgress = false
            platformLiveSameLineRetries = 0
            platformLiveRecoveryWindowAttempts = 0
            platformLiveRecoveryWindowStartMs = 0L
        }
        platformLiveResolveJob?.cancel()
        platformLiveResolveJob = viewModelScope.launch {
            _state.update {
                if (it.platformLive.destination != PlatformLiveDestination.Player ||
                    it.platformLive.selectedSite?.id != siteId ||
                    it.platformLive.rooms.getOrNull(it.platformLive.roomIndex)?.id != room.id
                ) {
                    it
                } else {
                    it.copy(platformLive = it.platformLive.copy(resolving = true, error = null, stream = null))
                }
            }
            runCatching { platformLiveRepository.resolve(room, forceRefresh) }
                .onSuccess { stream ->
                    _state.update {
                        if (it.platformLive.destination != PlatformLiveDestination.Player ||
                            it.platformLive.selectedSite?.id != siteId ||
                            it.platformLive.rooms.getOrNull(it.platformLive.roomIndex)?.id != room.id
                        ) {
                            it
                        } else {
                            it.copy(platformLive = it.platformLive.copy(
                                resolving = false,
                                error = null,
                                stream = stream.withActiveCandidate(candidateIndex)),
                            )
                        }
                    }
                    platformLiveRecoveryInProgress = false
                }
                .onFailure { error ->
                    val message = error.uiFailureMessageOrNull() ?: return@onFailure
                    _state.update {
                        if (it.platformLive.destination != PlatformLiveDestination.Player ||
                            it.platformLive.selectedSite?.id != siteId ||
                            it.platformLive.rooms.getOrNull(it.platformLive.roomIndex)?.id != room.id
                        ) {
                            it
                        } else {
                            it.copy(platformLive = it.platformLive.copy(
                                resolving = false,
                                stream = null,
                                error = message),
                            )
                        }
                    }
                    platformLiveRecoveryInProgress = false
                }
        }
    }

    private fun resolveHistoryPosition(movie: Movie, item: WatchHistoryItem): Pair<Int, Int> =
        com.tvbox.app.domain.resolveHistoryPosition(movie, item)

    private fun resolveBestPlaybackSourceIndex(
        state: TvBoxUiState,
        movie: Movie,
        requestedSourceIndex: Int,
        episodeIndex: Int,
    ): Int {
        val safeRequestedSourceIndex = requestedSourceIndex.coerceIn(
            0,
            movie.playSources.lastIndex.coerceAtLeast(0),
        )
        if (!state.appSettings.playbackAgentAutoSwitchEnabled) {
            return safeRequestedSourceIndex
        }
        return playbackAgent.selectBestSource(
            movie = movie,
            requestedSourceIndex = safeRequestedSourceIndex,
            episodeIndex = episodeIndex,
            healthSnapshot = state.playbackHealth,
        )?.sourceIndex ?: safeRequestedSourceIndex
    }

    override fun onCleared() {
        remoteManageServer?.stop()
        remoteManageServer = null
        super.onCleared()
    }
}

private fun TvBoxUiState.childCategoryIdsForSelectedParent(): List<Int> {
    val parentId = selectedParentCategoryId ?: return emptyList()
    return categories
        .filter { it.parentId == parentId }
        .map { it.id }
}

private fun TvBoxUiState.isDefaultAllCategorySelection(): Boolean {
    return selectedParentCategoryId == null && selectedCategoryId == null
}

private fun TvBoxUiState.playbackHealthKeyOrNull(): String? {
    val movie = detailMovie ?: return null
    val source = movie.playSources.getOrNull(playerSourceIndex) ?: return null
    if (source.episodes.getOrNull(playerEpisodeIndex)?.url.isNullOrBlank()) return null
    return playbackHealthKey(
        movieId = movie.id,
        episodeIndex = playerEpisodeIndex,
        source = source,
    )
}

private fun normalizeMacCmsApiUrl(rawUrl: String): String? =
    com.tvbox.app.domain.normalizeMacCmsApiUrl(rawUrl)

private fun customVideoApiLineId(baseUrl: String): String =
    com.tvbox.app.domain.customVideoApiLineId(baseUrl)

private const val DEFAULT_ALL_CATEGORY_TYPE_ID = 13
// 首页触底加载失败后，同一页自动重试的冷却时间，避免持续失败时每 800ms 重发请求。
private const val HOME_LOAD_MORE_RETRY_COOLDOWN_MS = 30_000L
// 直播断流后切换到下一条候选线路前的最长等待时间（移动端 5s，避免在慢网络上误判）。
private const val PLATFORM_LIVE_BUFFER_TIMEOUT_MS = 5_000L
// 电视直播：缓冲/卡死类失败在同一线路上原地重连达到该次数后，才切下一条线路。
private const val LIVE_STALL_RETRY_BEFORE_SWITCH = 2
// 平台直播恢复熔断：该时间窗内最多自动恢复次数（虎牙 FLV 约 125s 关流一次，正常播放不该触顶）。
private const val PLATFORM_LIVE_RECOVERY_WINDOW_MS = 120_000L
private const val PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS = 6
// 平台直播：同一条候选线路原地重连（重新解析地址）的最大次数，超过后换 CDN。
private const val PLATFORM_LIVE_SAME_LINE_RETRY_MAX = 2
// 平台直播重连退避基数，按重连次数线性递增，避免瞬时高频请求。
private const val PLATFORM_LIVE_RECOVERY_BACKOFF_MS = 1_500L
private const val VM_LIVE_LOG_TAG = "TVBoxLiveVM"

private class DefaultAppUpdateRepositoryPlaceholder : AppUpdateRepository {
    override suspend fun checkForUpdate(currentVersionCode: Long): AppUpdate? = null
    override suspend fun downloadUpdate(update: AppUpdate, onProgress: (Int) -> Unit): java.io.File {
        error("AppUpdateRepository is not configured")
    }
}

private class DefaultAppSettingsRepositoryPlaceholder : AppSettingsRepository {
    override suspend fun getSettings(): AppSettings = AppSettings()
    override suspend fun saveSettings(settings: AppSettings): AppSettings = settings
}

private class DefaultPlaybackHealthRepositoryPlaceholder : PlaybackHealthRepository {
    override suspend fun getSnapshot(): PlaybackHealthSnapshot = PlaybackHealthSnapshot()
    override suspend fun recordIssue(
        key: String,
        issueType: PlaybackIssueType,
        nowMs: Long,
    ): PlaybackHealthSnapshot = PlaybackHealthSnapshot()

    override suspend fun recordSuccess(key: String, nowMs: Long): PlaybackHealthSnapshot = PlaybackHealthSnapshot()

    override suspend fun clear(): PlaybackHealthSnapshot = PlaybackHealthSnapshot()
}
