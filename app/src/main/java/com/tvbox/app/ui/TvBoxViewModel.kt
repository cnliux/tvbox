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
import com.tvbox.app.data.key
import com.tvbox.app.data.RemoteManageRequest
import com.tvbox.app.data.RemoteManageServer
import com.tvbox.app.data.RemoteManageSite
import com.tvbox.app.data.RemoteManageSnapshot
import com.tvbox.app.data.RemoteManageVideoSource
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.effectiveMode
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.AppUpdate
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.DoubanHotItem
import com.tvbox.app.domain.LiveChannel
import com.tvbox.app.domain.LiveLineFallbackReason
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
    val liveChannels: List<LiveChannel> = emptyList(),
    val liveChannelIndex: Int = 0,
    val liveLineIndex: Int = 0,
    /** 直播分类在当前频道列表中的下标；0 表示“全部”，其余按下标过滤分组。 */
    val liveGroupIndex: Int = 0,
    /** 当前选中的订阅地址下标（-1 表示使用全部订阅地址合并加载）。 */
    val liveSelectedSourceIndex: Int = -1,
    val liveLoading: Boolean = false,
    val liveError: String? = null,
    /** 直播页轻提示（自动切线路结果、订阅切换结果等）；由 UI 消费后清空。 */
    val liveNotice: String? = null,
    /** 原地重连当前线路的触发计数（不改变 URL，仅让播放页重新 prepare）。 */
    val liveReconnectNonce: Int = 0,
    /** 直播面板两级层级：分类列表 / 频道列表。 */
    val liveGroupPanelStage: LiveGroupStage = LiveGroupStage.Groups,
    val liveFavoriteChannelNumbers: Set<Int> = emptySet(),
    /** 电视直播收藏键集合（`分组#频道名`），持久化于 LiveFavoritesRepository；旧频道号集合保留兼容。 */
    val liveFavoriteKeys: Set<String> = emptySet(),
    /** XMLTV 节目单：key 为频道 id / display-name，value 按开始时间升序。 */
    val liveEpgByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    /** 节目单是否已完成一次拉取（无论成败），避免反复重试慢源。 */
    val liveEpgLoaded: Boolean = false,
    val platformLiveDestination: PlatformLiveDestination = PlatformLiveDestination.Sites,
    val platformLiveSites: List<PlatformLiveSite> = emptyList(),
    /** 平台直播站点全量列表（含已删除的），供源管理页恢复使用。 */
    val platformLiveAllSites: List<PlatformLiveSite> = emptyList(),
    val platformLiveQuickCategories: List<PlatformLiveQuickCategory> = emptyList(),
    val platformLiveFavorites: List<PlatformLiveRoom> = emptyList(),
    val platformLiveFavoriteError: String? = null,
    val platformLivePlayerReturnDestination: PlatformLiveDestination = PlatformLiveDestination.Rooms,
    val platformLiveSelectedSite: PlatformLiveSite? = null,
    val platformLiveParentCategories: List<PlatformLiveParentCategory> = emptyList(),
    val platformLiveSelectedParentCategory: PlatformLiveParentCategory? = null,
    /** 一级分类上次选中的下标：返回一级分类页时用于恢复滚动位置与焦点。 */
    val platformLiveParentIndex: Int = 0,
    val platformLiveCategories: List<PlatformLiveCategory> = emptyList(),
    val platformLiveSelectedCategory: PlatformLiveCategory? = null,
    /** 二级分类上次选中的下标：返回二级分类页时用于恢复滚动位置与焦点。 */
    val platformLiveCategoryIndex: Int = 0,
    val platformLiveRooms: List<PlatformLiveRoom> = emptyList(),
    val platformLiveRoomIndex: Int = 0,
    val platformLiveRoomPage: Int = 1,
    val platformLiveRoomPageCount: Int = 1,
    val platformLiveLoading: Boolean = false,
    val platformLiveLoadingMore: Boolean = false,
    val platformLiveResolving: Boolean = false,
    val platformLiveError: String? = null,
    /** 平台直播轻提示（自动切换线路结果等）；由 UI 消费后清空。 */
    val platformLiveNotice: String? = null,
    val platformLiveStream: ResolvedPlatformLiveStream? = null,
    val availableUpdate: AppUpdate? = null,
    val updateDialogVisible: Boolean = false,
    val updateChecking: Boolean = false,
    val updateDownloading: Boolean = false,
    val updateDownloadProgress: Int? = null,
    val updateDownloadedApkPath: String? = null,
    val updateAutoInstallPath: String? = null,
    val updateAwaitingInstallPermission: Boolean = false,
    val updateExporting: Boolean = false,
    val updateExportMessage: String? = null,
    val updateError: String? = null,
    val appSettings: AppSettings = AppSettings(),
    val playbackHealth: PlaybackHealthSnapshot = PlaybackHealthSnapshot(),
    val remoteManageDialogVisible: Boolean = false,
    val remoteManageServerUrl: String? = null,
    val remoteManageServerError: String? = null,
    val remoteManageMessage: String? = null,
    val sourceLatencies: Map<String, Long?> = emptyMap(),
    val sourceTesting: Boolean = false,
    val sourceTestCompleted: Int = 0,
    val sourceTestTotal: Int = 0,
    val sourceManagerMessage: String? = null,
    val detailSourceTesting: Boolean = false,
    val detailSourceTestCompleted: Int = 0,
    val detailSourceTestTotal: Int = 0,
    val detailSourceMessage: String? = null,
    val sourceImporting: Boolean = false,
    val sourceImportMessage: String? = null,
) {
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
) : ViewModel() {
    private val _state = MutableStateFlow(TvBoxUiState())
    val state: StateFlow<TvBoxUiState> = _state.asStateFlow()

    private var homeJob: Job? = null
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
    // 切换订阅后要恢复的分类名（新订阅没有该分类时回"全部"）。
    private var pendingLiveGroupRestore: String? = null
    private val platformLiveFavoritesMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private val liveFavoritesMutex = Mutex()
    private var epgJob: Job? = null
    private var updateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateInstallStateJob: Job? = null
    private var settingsWriteJob: Job? = null
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
                    sourceLatencies = speedLatencies,
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

    fun loadNextPage() {
        val current = _state.value
        if (!current.canLoadMore) return
        val now = System.currentTimeMillis()
        if (now - lastLoadNextPageMs < 800L) return
        lastLoadNextPageMs = now
        loadHomePage(reset = false, forceRefresh = current.homeNotice != null)
    }

    fun openHome() {
        _state.update {
            it.copy(
                screen = TvScreen.Home,
                detailReturnScreen = TvScreen.Home,
                platformLiveDestination = PlatformLiveDestination.Sites,
                platformLiveStream = null,
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
            val words = runCatching { searchHotWordRepository.getHotWords(forceRefresh) }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    searchHotWords = words.ifEmpty { it.searchHotWords },
                    searchHotWordsLoading = false,
                )
            }
        }
    }

    fun openLive() {
        _state.update { it.copy(screen = TvScreen.Live, liveError = null) }
        if (_state.value.liveChannels.isEmpty()) {
            loadLiveChannels()
        }
    }

    fun openPlatformLive() {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Sites,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        if (_state.value.platformLiveSites.isEmpty()) {
            loadPlatformLiveSites()
        }
    }

    fun openSettings() {
        _state.update { it.copy(screen = TvScreen.Settings, updateError = null) }
        refreshPlaybackHealth()
    }

    fun openSourceManager() {
        _state.update { it.copy(screen = TvScreen.SourceManager) }
    }

    fun openLiveSourceManager() {
        _state.update { it.copy(screen = TvScreen.LiveSourceManager, liveChannels = emptyList(), liveError = null) }
        viewModelScope.launch {
            runCatching { platformLiveRepository.getSites() }
                .onSuccess { sites ->
                    allPlatformLiveSites = sites
                    _state.update { it.copy(platformLiveAllSites = sites) }
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
        when (_state.value.platformLiveDestination) {
            PlatformLiveDestination.Sites -> loadPlatformLiveSites()
            PlatformLiveDestination.Favorites -> loadPlatformLiveFavorites()
            PlatformLiveDestination.ParentCategories -> _state.value.platformLiveSelectedSite?.let(::loadPlatformLiveCategories)
            PlatformLiveDestination.Categories -> _state.value.platformLiveSelectedSite?.let(::loadPlatformLiveCategories)
            PlatformLiveDestination.Rooms -> _state.value.platformLiveSelectedCategory?.let {
                loadPlatformLiveRooms(it, reset = true)
            }
            PlatformLiveDestination.Player -> resolvePlatformLiveRoom(forceRefresh = true)
        }
    }

    fun checkForAppUpdate(showError: Boolean = false) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(updateChecking = true, updateError = null) }
            runCatching { appUpdateRepository.checkForUpdate(BuildConfig.VERSION_CODE.toLong()) }
                .onSuccess { update ->
                    _state.update {
                        if (update == null) {
                            it.copy(updateChecking = false)
                        } else if (it.updateDownloading || it.updateDownloadedApkPath != null) {
                            it.copy(updateChecking = false)
                        } else {
                            it.copy(
                                availableUpdate = update,
                                updateDialogVisible = true,
                                updateChecking = false,
                                updateDownloading = false,
                                updateDownloadProgress = null,
                                updateDownloadedApkPath = null,
                                updateError = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            updateChecking = false,
                            updateDialogVisible = showError && it.availableUpdate != null,
                            updateError = if (showError) error.userMessage() else null,
                        )
                    }
                }
        }
    }

    fun dismissUpdateDialog() {
        if (_state.value.availableUpdate?.force == true) return
        _state.update { it.copy(updateDialogVisible = false, updateError = null) }
    }

    fun startUpdateDownload() {
        val update = _state.value.availableUpdate ?: return
        if (_state.value.updateDownloading) return
        updateDownloadJob?.cancel()
        _state.update {
            it.copy(updateDialogVisible = false, updateDownloading = true,
                updateDownloadProgress = 0, updateDownloadedApkPath = null,
                updateAutoInstallPath = null, updateAwaitingInstallPermission = false, updateError = null,
                updateExporting = false, updateExportMessage = null)
        }
        updateDownloadJob = viewModelScope.launch {
            try {
                updateInstallStateJob?.join()
                val apkFile = appUpdateRepository.downloadUpdate(update) { progress ->
                    _state.update { it.copy(updateDownloadProgress = progress.takeIf { value -> value >= 0 }) }
                }
                val autoInstall = appUpdateRepository.shouldAutoInstall(apkFile.absolutePath)
                val awaitingPermission = appUpdateRepository.isAwaitingInstallPermission(apkFile.absolutePath)
                _state.update {
                    it.copy(updateDownloading = false, updateDownloadProgress = 100,
                        updateDownloadedApkPath = apkFile.absolutePath,
                        updateAwaitingInstallPermission = awaitingPermission,
                        updateAutoInstallPath = apkFile.absolutePath.takeIf { autoInstall }, updateError = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(updateDownloading = false, updateDownloadProgress = null,
                        updateDownloadedApkPath = null, updateAutoInstallPath = null,
                        updateAwaitingInstallPermission = false, updateError = error.userMessage())
                }
            }
        }
    }

    private fun restorePendingUpdateDownload() {
        viewModelScope.launch {
            val pending = runCatching { appUpdateRepository.pendingDownload() }.getOrNull() ?: return@launch
            if (_state.value.updateDownloading) return@launch
            _state.update { it.copy(availableUpdate = pending, updateDialogVisible = false) }
            startUpdateDownload()
        }
    }

    fun markUpdateInstallPrompted(apkPath: String) {
        _state.update { it.copy(updateAutoInstallPath = null, updateDialogVisible = false,
            updateAwaitingInstallPermission = false, updateError = null) }
        persistUpdateInstallState { appUpdateRepository.markInstallPrompted(apkPath) }
    }

    fun beginUpdateInstallAttempt(apkPath: String): Boolean {
        if (_state.value.updateDownloadedApkPath != apkPath || _state.value.updateExporting) return false
        // Consume only the UI event, not the persistent successful-launch marker.
        _state.update { it.copy(updateAutoInstallPath = null, updateDialogVisible = false,
            updateAwaitingInstallPermission = false, updateError = null) }
        return true
    }

    fun awaitUpdateInstallPermission(apkPath: String, message: String? = null) {
        if (_state.value.updateDownloadedApkPath != apkPath) return
        _state.update { it.copy(updateAutoInstallPath = null, updateAwaitingInstallPermission = true,
            updateError = message) }
        persistUpdateInstallState { appUpdateRepository.setAwaitingInstallPermission(apkPath, true) }
    }

    fun resumeUpdateInstallAfterPermission(apkPath: String) {
        _state.update {
            if (it.updateDownloadedApkPath == apkPath && it.updateAwaitingInstallPermission)
                it.copy(updateAutoInstallPath = apkPath, updateError = null) else it
        }
    }

    fun reportUpdateInstallFailure(apkPath: String, message: String, fileMissing: Boolean = false) {
        _state.update { it.copy(updateAutoInstallPath = null, updateAwaitingInstallPermission = false,
            updateDownloadedApkPath = if (fileMissing) null else it.updateDownloadedApkPath, updateError = message) }
        persistUpdateInstallState {
            if (fileMissing) appUpdateRepository.forgetDownload(apkPath)
            else appUpdateRepository.setAwaitingInstallPermission(apkPath, false)
        }
    }

    fun beginUpdateApkExport(apkPath: String): Boolean {
        if (_state.value.updateDownloadedApkPath != apkPath || _state.value.updateExporting) return false
        _state.update { it.copy(updateAutoInstallPath = null, updateAwaitingInstallPermission = false,
            updateExporting = true, updateExportMessage = null, updateError = null) }
        persistUpdateInstallState { appUpdateRepository.chooseManualInstall(apkPath) }
        return true
    }

    fun cancelUpdateApkExport(message: String = "已取消保存，安装包仍保留") {
        _state.update { it.copy(updateExporting = false, updateExportMessage = message) }
    }

    fun saveUpdateApk(apkPath: String, destination: android.net.Uri?) {
        _state.update { it.copy(updateExporting = true, updateExportMessage = null) }
        viewModelScope.launch {
            try {
                updateInstallStateJob?.join()
                val location = appUpdateRepository.exportUpdateApk(apkPath, destination)
                _state.update { it.copy(updateExporting = false,
                    updateExportMessage = "已保存：$location。请通过文件管理器打开安装") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TVBoxUpdate", "Unable to export update APK", error)
                _state.update { it.copy(updateExporting = false, updateExportMessage = "保存失败：${error.userMessage()}") }
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
        val sites = allPlatformLiveSites.ifEmpty { current.platformLiveAllSites }.map { site ->
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
                latencyMs = current.sourceLatencies[line.id],
            )
        }
        return RemoteManageSnapshot(
            videoSources = videoSources,
            customLiveUrls = settings.liveSourceUrls,
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
            for (lineId in candidateLineIds.take(MAX_DOUBAN_HOT_FALLBACK_LINES)) {
                runCatching { findPlayableDoubanMovie(item.title, lineId) }
                    .onSuccess { found ->
                        if (found != null) {
                            movie = found
                            matchedLineId = lineId
                        }
                    }
                    .onFailure { lastError = it }
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
                val keywords = searchKeywordExpander.expandInitials(query)
                    .ifEmpty { listOf(query) }
                    .distinct()
                    .take(3)
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

    private fun mergeSearchResults(existing: List<Movie>, incoming: List<Movie>): List<Movie> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(mutableSetOf()) { "${it.apiLineId}:${it.id}" }
        val merged = existing.toMutableList()
        incoming.forEach { movie ->
            if (seen.add("${movie.apiLineId}:${movie.id}")) {
                merged += movie
            }
        }
        return merged
    }

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
                detailSourceTesting = false,
                detailSourceTestCompleted = 0,
                detailSourceTestTotal = 0,
                detailSourceMessage = null,
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
                detailSourceTesting = false,
                detailSourceTestCompleted = 0,
                detailSourceTestTotal = 0,
                detailSourceMessage = null,
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
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            val nextIndex = (state.liveChannelIndex + 1) % channels.size
            val channel = channels[nextIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            android.util.Log.i(VM_LIVE_LOG_TAG, "【切换频道】next channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}")
            state.copy(
                liveChannelIndex = nextIndex,
                liveLineIndex = targetLineIndex,
            )
        }
    }

    fun playPreviousLiveChannel() {
        lastExhaustedChannelNumber = null
        _state.update { state ->
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            val previousIndex = if (state.liveChannelIndex <= 0) channels.lastIndex else state.liveChannelIndex - 1
            val channel = channels[previousIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            android.util.Log.i(VM_LIVE_LOG_TAG, "【切换频道】previous channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}")
            state.copy(
                liveChannelIndex = previousIndex,
                liveLineIndex = targetLineIndex,
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
        val channel = state.liveChannels.getOrNull(state.liveChannelIndex) ?: return false
        val lines = channel.lines
        if (lines.size <= 1) return false
        liveStallRetries = 0
        val newIndex = (state.liveLineIndex + 1) % lines.size
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】next channel=${channel.name}, from=${state.liveLineIndex}, to=$newIndex, url=${lines[newIndex].url}")
        _state.update {
            it.copy(liveLineIndex = newIndex)
        }
        liveLinePrefs?.saveLineIndex(channel.key(), newIndex)
        return true
    }

    /** 手动切换线路后清零卡顿计数，避免旧线路的失败次数影响新线路。 */
    fun playPreviousLiveLine(): Boolean {
        val state = _state.value
        val channel = state.liveChannels.getOrNull(state.liveChannelIndex) ?: return false
        val lines = channel.lines
        if (lines.size <= 1) return false
        liveStallRetries = 0
        val previousIndex = if (state.liveLineIndex <= 0) lines.lastIndex else state.liveLineIndex - 1
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】previous channel=${channel.name}, from=${state.liveLineIndex}, to=$previousIndex, url=${lines[previousIndex].url}")
        _state.update {
            it.copy(liveLineIndex = previousIndex)
        }
        liveLinePrefs?.saveLineIndex(channel.key(), previousIndex)
        return true
    }

    fun selectLiveLine(lineIndex: Int) {
        val state = _state.value
        val channel = state.liveChannels.getOrNull(state.liveChannelIndex) ?: return
        if (lineIndex !in channel.lines.indices) return
        if (lineIndex == state.liveLineIndex) return
        liveStallRetries = 0
        android.util.Log.i(VM_LIVE_LOG_TAG, "【手动切换线路】select channel=${channel.name}, from=${state.liveLineIndex}, to=$lineIndex, url=${channel.lines[lineIndex].url}")
        _state.update { it.copy(liveLineIndex = lineIndex) }
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
        val channel = state.liveChannels.getOrNull(state.liveChannelIndex) ?: return false
        val lines = channel.lines
        if (reason != LiveLineFallbackReason.Error) {
            liveStallRetries += 1
            if (liveStallRetries < LIVE_STALL_RETRY_BEFORE_SWITCH) {
                android.util.Log.w(
                    VM_LIVE_LOG_TAG,
                    "【自动容错】reason=$reason 第 $liveStallRetries/$LIVE_STALL_RETRY_BEFORE_SWITCH 次原地重连当前线路 ${state.liveLineIndex + 1}",
                )
                _state.update { it.copy(liveReconnectNonce = it.liveReconnectNonce + 1) }
                return true
            }
        }
        liveStallRetries = 0
        val nextIndex = state.liveLineIndex + 1
        android.util.Log.w(
            VM_LIVE_LOG_TAG,
            "【自动容错】reason=$reason channel=${channel.name}, currentLine=${state.liveLineIndex}, nextLine=$nextIndex, totalLines=${lines.size}",
        )
        if (nextIndex !in lines.indices) {
            // 所有线路均已尝试过，停止循环并提示用户。
            val channelNumber = channel.number
            if (lastExhaustedChannelNumber != channelNumber) {
                lastExhaustedChannelNumber = channelNumber
                _state.update {
                    it.copy(liveNotice = "频道 ${channelNumber} 所有线路均不可用，请换台或检查直播源")
                }
            }
            android.util.Log.w(VM_LIVE_LOG_TAG, "【自动容错】频道 ${channel.name} 所有线路已用尽，停止切换")
            return false
        }
        _state.update { it.copy(liveLineIndex = nextIndex) }
        if (reason == LiveLineFallbackReason.Error) {
            liveLinePrefs?.saveLineIndex(channel.key(), nextIndex)
        }
        android.util.Log.i(VM_LIVE_LOG_TAG, "【自动切换成功】切换到线路 ${nextIndex + 1}/${lines.size}, url=${lines[nextIndex].url}")
        return true
    }

    /** 播放中断后的原地重连（不换线路）：bump nonce 让播放页对当前 URL 重新 prepare。 */
    fun reconnectLiveLine() {
        _state.update { it.copy(liveReconnectNonce = it.liveReconnectNonce + 1) }
    }

    /** 消费一次性轻提示（自动切线路/订阅切换结果）。 */
    fun consumeLiveNotice() {
        if (_state.value.liveNotice == null) return
        _state.update { it.copy(liveNotice = null) }
    }

    fun reportLiveNotice(message: String) {
        _state.update { it.copy(liveNotice = message) }
    }

    /**
     * 当某频道所有线路均失败时，记录该频道并清除已耗尽标记（换台后重新尝试）。
     */
    fun onLiveChannelChanged() {
        lastExhaustedChannelNumber = null
    }

    /**
     * 电视直播画面就绪（STATE_READY）后调用：清零卡顿重试计数。
     *
     * 此前该计数只在真正切换线路时清零，会跨频道累计：
     * 连续几个频道各卡顿一次后，新频道第一次卡顿就直接换线，表现为"没坏也自动切线路"。
     */
    fun notifyLivePlaybackReady() {
        if (liveStallRetries != 0) {
            android.util.Log.d(VM_LIVE_LOG_TAG, "【直播就绪】画面就绪，清零卡顿重试计数（原 $liveStallRetries）")
            liveStallRetries = 0
        }
    }

    fun selectPlatformLiveSite(site: PlatformLiveSite) {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.ParentCategories,
                platformLiveSelectedSite = site,
                platformLiveParentCategories = emptyList(),
                platformLiveSelectedParentCategory = null,
                platformLiveParentIndex = 0,
                platformLiveSelectedCategory = null,
                platformLiveCategoryIndex = 0,
                platformLiveCategories = emptyList(),
                platformLiveRooms = emptyList(),
                platformLiveRoomIndex = 0,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        loadPlatformLiveCategories(site)
    }

    fun openPlatformLiveFavorites() {
        platformLiveJob?.cancel()
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Favorites,
                platformLiveRooms = it.platformLiveFavorites,
                platformLiveRoomIndex = 0,
                platformLiveLoading = false,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
    }

    fun togglePlatformLiveFavorite(room: PlatformLiveRoom) {
        viewModelScope.launch {
            platformLiveFavoritesMutex.withLock {
                runCatching { platformLiveFavoritesRepository.toggleFavorite(room) }
                    .onSuccess { favorites ->
                        _state.update { current ->
                            if (current.platformLiveDestination == PlatformLiveDestination.Favorites) {
                                current.copy(
                                    platformLiveFavorites = favorites,
                                    platformLiveRooms = favorites,
                                    platformLiveRoomIndex = current.platformLiveRoomIndex.coerceAtMost(favorites.lastIndex.coerceAtLeast(0)),
                                    platformLiveFavoriteError = null,
                                )
                            } else {
                                current.copy(platformLiveFavorites = favorites, platformLiveFavoriteError = null)
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { it.copy(platformLiveFavoriteError = error.userMessage()) }
                    }
            }
        }
    }

    fun selectPlatformLiveParentCategory(parentCategory: PlatformLiveParentCategory) {
        val state = _state.value
        val parentIndex = state.platformLiveParentCategories
            .indexOfFirst { it.id == parentCategory.id }
            .coerceAtLeast(0)
        val hasCategories = state.platformLiveCategories.any { it.parentId == parentCategory.id }
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Categories,
                platformLiveSelectedParentCategory = parentCategory,
                platformLiveParentIndex = parentIndex,
                platformLiveSelectedCategory = null,
                platformLiveCategoryIndex = 0,
                platformLiveRooms = emptyList(),
                platformLiveRoomIndex = 0,
                platformLiveError = if (hasCategories) null else "这个大分类暂时没有可用的二级分类",
                platformLiveStream = null,
            )
        }
    }

    fun selectPlatformLiveCategory(category: PlatformLiveCategory) {
        val categoryIndex = _state.value.platformLiveCategories
            .indexOfFirst { it.id == category.id }
            .coerceAtLeast(0)
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Rooms,
                platformLiveSelectedCategory = category,
                platformLiveCategoryIndex = categoryIndex,
                platformLiveRooms = emptyList(),
                platformLiveRoomIndex = 0,
                platformLiveRoomPage = 1,
                platformLiveRoomPageCount = 1,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        loadPlatformLiveRooms(category, reset = true)
    }

    fun loadMorePlatformLiveRooms() {
        val state = _state.value
        val category = state.platformLiveSelectedCategory ?: return
        if (state.platformLiveLoading || state.platformLiveLoadingMore ||
            state.platformLiveRoomPage >= state.platformLiveRoomPageCount
        ) {
            return
        }
        loadPlatformLiveRooms(category, reset = false)
    }

    fun openPlatformLiveRoom(room: PlatformLiveRoom) {
        val index = _state.value.platformLiveRooms.indexOfFirst { it.id == room.id }
        if (index < 0) return
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Player,
                platformLivePlayerReturnDestination = it.platformLiveDestination,
                platformLiveRoomIndex = index,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        resolvePlatformLiveRoom()
    }

    fun playNextPlatformLiveRoom() {
        val state = _state.value
        val rooms = state.platformLiveRooms
        if (rooms.isEmpty()) return
        _state.update {
            it.copy(
                platformLiveRoomIndex = (state.platformLiveRoomIndex + 1) % rooms.size,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        resolvePlatformLiveRoom()
    }

    fun playPreviousPlatformLiveRoom() {
        val state = _state.value
        val rooms = state.platformLiveRooms
        if (rooms.isEmpty()) return
        val previousIndex = if (state.platformLiveRoomIndex <= 0) rooms.lastIndex else {
            state.platformLiveRoomIndex - 1
        }
        _state.update {
            it.copy(
                platformLiveRoomIndex = previousIndex,
                platformLiveError = null,
                platformLiveStream = null,
            )
        }
        resolvePlatformLiveRoom()
    }

    fun selectLiveChannel(index: Int) {
        liveStallRetries = 0
        lastExhaustedChannelNumber = null
        _state.update { state ->
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            val safeIndex = index.coerceIn(0, channels.lastIndex)
            val channel = channels[safeIndex]
            state.copy(
                liveChannelIndex = safeIndex,
                liveLineIndex = state.rememberedLineIndexOf(channel),
            )
        }
    }

    /** 当前频道列表的分组名（按出现顺序去重），供两级面板展示。 */
    fun liveGroupNames(): List<String> = _state.value.liveChannels
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
            val channels = state.liveChannels
            val groups = channels.map { it.groupName }.distinct()
            if (groups.isEmpty()) return@update state
            val safeIndex = index.coerceIn(0, groups.size)
            val targetChannel = if (safeIndex == 0) {
                state.liveChannelIndex.coerceIn(0, channels.lastIndex)
            } else {
                val groupName = groups[safeIndex - 1]
                channels.indexOfFirst { it.groupName == groupName }.coerceAtLeast(0)
            }
            val channel = channels.getOrNull(targetChannel)
            state.copy(
                liveGroupIndex = safeIndex,
                liveChannelIndex = targetChannel,
                liveLineIndex = channel?.let { state.rememberedLineIndexOf(it) } ?: 0,
            )
        }
    }

    /** 左右方向键切换分类（含"全部"）。 */
    fun cycleLiveGroup(delta: Int) {
        val groups = liveGroupNames()
        if (groups.isEmpty()) return
        val current = _state.value.liveGroupIndex
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
        if (_state.value.liveGroupPanelStage == LiveGroupStage.Channels &&
            _state.value.liveGroupIndex == safeIndex
        ) {
            return
        }
        _state.update {
            it.copy(
                liveGroupIndex = safeIndex,
                liveGroupPanelStage = LiveGroupStage.Channels,
            )
        }
    }

    /** 两级交互：从频道列表返回分类列表。 */
    fun backToLiveGroups() {
        _state.update { it.copy(liveGroupPanelStage = LiveGroupStage.Groups) }
    }

    /** 两级交互：每次打开直播面板都从分类层级开始，避免直接落在频道长列表上。 */
    fun showLivePanelGroups() {
        if (_state.value.liveGroupPanelStage != LiveGroupStage.Groups) {
            _state.update { it.copy(liveGroupPanelStage = LiveGroupStage.Groups) }
        }
    }

    /** 收藏/取消收藏当前播放的直播频道（通过频道号）。 */
    fun toggleLiveFavorite(channelNumber: Int) {
        val current = _state.value
        val favorites = current.liveFavoriteChannelNumbers.toMutableSet()
        if (favorites.contains(channelNumber)) {
            favorites.remove(channelNumber)
        } else {
            favorites.add(channelNumber)
        }
        _state.update {
            it.copy(liveFavoriteChannelNumbers = favorites)
        }
    }

    /**
     * 收藏/取消收藏直播频道（按 `分组#频道名` 键，跨源刷新不丢失）。
     *
     * 有持久化仓库时异步落盘并以仓库返回值刷新 [TvBoxUiState.liveFavoriteKeys]；
     * 仓库缺失时退回旧的仅内存 Set 语义（按键增删）。
     */
    fun toggleLiveFavorite(channel: LiveChannel) {
        val key = liveFavoriteKey(channel.groupName, channel.name)
        val repository = liveFavoritesRepository
        if (repository == null) {
            _state.update { state ->
                val favorites = state.liveFavoriteKeys.toMutableSet()
                if (!favorites.remove(key)) favorites += key
                state.copy(liveFavoriteKeys = favorites)
            }
            return
        }
        viewModelScope.launch {
            liveFavoritesMutex.withLock {
                runCatching { repository.toggleFavorite(channel.groupName, channel.name) }
                    .onSuccess { keys -> _state.update { it.copy(liveFavoriteKeys = keys) } }
            }
        }
    }

    /** 某频道是否已收藏（按 `分组#频道名` 键）。 */
    fun isLiveFavorite(channel: LiveChannel): Boolean =
        _state.value.liveFavoriteKeys.contains(liveFavoriteKey(channel.groupName, channel.name))

    /** 某频道的完整节目单（已按开始时间升序）；未配置/未命中返回空列表。 */
    fun liveEpgPrograms(channel: LiveChannel): List<EpgProgram> =
        findEpgPrograms(
            map = _state.value.liveEpgByChannel,
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
            it.copy(appSettings = settings, liveEpgByChannel = emptyMap(), liveEpgLoaded = false)
        }
        saveSettings(settings)
        appSettingsRepository.commitEpgUrl(trimmed)
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
        if (index == _state.value.liveSelectedSourceIndex) return
        val previousGroup = _state.value.let { state ->
            val groups = state.liveChannels.map { it.groupName }.distinct()
            groups.getOrNull(state.liveGroupIndex - 1)
        }
        pendingLiveGroupRestore = previousGroup
        _state.update { it.copy(liveSelectedSourceIndex = index, liveNotice = "正在切换订阅：${labels[index]}") }
        liveRepository.setSourceSelection(index - 1)
        refreshLive()
    }

    /** 左右方向键切换订阅地址（循环）。 */
    fun cycleLiveSource(delta: Int) {
        val labels = liveSourceLabels()
        if (labels.size <= 1) return
        val current = _state.value.liveSelectedSourceIndex.coerceAtLeast(0)
        val next = ((current + delta) % labels.size + labels.size) % labels.size
        selectLiveSource(next)
    }

    fun selectLiveChannelNumber(number: Int): Boolean {
        val index = number - 1
        val channels = _state.value.liveChannels
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
        val stream = _state.value.platformLiveStream ?: return
        if (index !in stream.candidates.indices) return
        if (platformLiveRecoveryInProgress) {
            // 用户明确的手动选择优先于自动恢复，不再静默丢弃。
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台手动切换候选】自动恢复进行中，仍以用户选择为准切换 to=$index")
        }
        android.util.Log.i(VM_LIVE_LOG_TAG, "【平台手动切换候选】from=${stream.activeCandidateIndex}, to=$index, cdn=${stream.candidates[index].cdn}")
        // 用户手动指定线路后，重置自动恢复计数，避免刚切完就被熔断拦下。
        platformLiveRecoveryInProgress = false
        platformLiveSameLineRetries = 0
        platformLiveRecoveryWindowAttempts = 0
        platformLiveRecoveryWindowStartMs = 0L
        _state.update {
            it.copy(
                platformLiveError = null,
                platformLiveNotice = "已切换到线路 ${index + 1}/${stream.candidates.size}（${stream.candidates[index].cdn}）",
                platformLiveStream = stream.withActiveCandidate(index),
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
        val stream = _state.value.platformLiveStream ?: return
        if (stream.candidates.size <= 1) {
            _state.update { it.copy(platformLiveNotice = "当前直播间只有一条线路") }
            return
        }
        val next = (stream.activeCandidateIndex + delta + stream.candidates.size) % stream.candidates.size
        switchPlatformLiveCandidate(next)
    }

    /** 消费平台直播轻提示。 */
    fun consumePlatformLiveNotice() {
        if (_state.value.platformLiveNotice != null) {
            _state.update { it.copy(platformLiveNotice = null) }
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
        val stream = state.platformLiveStream ?: run {
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】无当前流，改为重新解析")
            reconnectPlatformLiveChannel()
            return
        }
        if (platformLiveRecoveryInProgress || state.platformLiveResolving) {
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
                it.copy(platformLiveError = "该房间线路连续失败，已停止自动重连。可手动切换线路或稍后重试。")
            }
            return
        }
        platformLiveRecoveryWindowAttempts++
        platformLiveRecoveryInProgress = true
        android.util.Log.w(
            VM_LIVE_LOG_TAG,
            "【平台恢复】reason=$reason, attempt=$platformLiveRecoveryWindowAttempts/$PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS, active=${stream.activeCandidateIndex}, total=${stream.candidates.size}, cdn=${stream.activeCandidate.cdn}, protocol=${stream.activeCandidate.protocol}",
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

    /** 切换到下一条候选 CDN；已是最后一条则回到第一条并强制重新解析。 */
    private fun rotatePlatformLiveCandidate(stream: ResolvedPlatformLiveStream) {
        if (stream.candidates.isEmpty()) {
            platformLiveRecoveryInProgress = false
            reconnectPlatformLiveChannel()
            return
        }
        val nextIdx = (stream.activeCandidateIndex + 1) % stream.candidates.size
        if (nextIdx == 0) {
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】候选线路已轮完一圈，强制重新解析")
            platformLiveRecoveryInProgress = false
            reconnectPlatformLiveChannel()
            return
        }
        _state.update {
            it.copy(
                platformLiveError = null,
                platformLiveNotice = "已切换到线路 ${nextIdx + 1}/${stream.candidates.size}（${stream.candidates[nextIdx].cdn}）",
                platformLiveStream = stream.withActiveCandidate(nextIdx),
            )
        }
        android.util.Log.i(VM_LIVE_LOG_TAG, "【平台恢复】切换到候选线路 $nextIdx, cdn=${stream.candidates[nextIdx].cdn}")
        platformLiveRecoveryInProgress = false
    }

    /** 退避后重新解析当前房间，保持当前候选下标。 */
    private fun schedulePlatformLiveReResolve(delayMs: Long) {
        val keepCandidate = _state.value.platformLiveStream?.activeCandidateIndex ?: 0
        viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            platformLiveRecoveryInProgress = false
            android.util.Log.w(VM_LIVE_LOG_TAG, "【平台恢复】退避 ${delayMs}ms 后重新解析（候选 $keepCandidate）")
            resolvePlatformLiveRoom(forceRefresh = true, candidateIndex = keepCandidate, isRecovery = true)
        }
    }

    private fun roomTag(): String {
        val state = _state.value
        return state.platformLiveRooms.getOrNull(state.platformLiveRoomIndex)?.let { "${it.site}/${it.id}" } ?: "none"
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
        viewModelScope.launch {
            historyWriteMutex.withLock {
                runCatching { historyRepository.saveProgress(item) }
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
                when (current.platformLiveDestination) {
                    PlatformLiveDestination.Player -> _state.update {
                        it.copy(
                            platformLiveDestination = it.platformLivePlayerReturnDestination,
                            platformLiveResolving = false,
                            platformLiveError = null,
                            platformLiveStream = null,
                        )
                    }
                    PlatformLiveDestination.Rooms -> _state.update {
                        it.copy(
                            platformLiveDestination = PlatformLiveDestination.Categories,
                            platformLiveSelectedCategory = null,
                            platformLiveError = null,
                        )
                    }
                    PlatformLiveDestination.Favorites -> _state.update {
                        it.copy(
                            platformLiveDestination = PlatformLiveDestination.Sites,
                            platformLiveRooms = emptyList(),
                            platformLiveError = null,
                        )
                    }
                    PlatformLiveDestination.Categories -> _state.update {
                        it.copy(
                            platformLiveDestination = PlatformLiveDestination.ParentCategories,
                            platformLiveSelectedCategory = null,
                            platformLiveError = null,
                        )
                    }
                    PlatformLiveDestination.ParentCategories -> _state.update {
                        it.copy(
                            platformLiveDestination = PlatformLiveDestination.Sites,
                            platformLiveSelectedParentCategory = null,
                            platformLiveError = null,
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

    private fun orderApiLines(allLines: List<ApiLine>, preferredOrder: List<String>): List<ApiLine> {
        val indexMap = preferredOrder.mapIndexed { i, id -> id to i }.toMap()
        return allLines.sortedBy { line -> indexMap[line.id] ?: Int.MAX_VALUE }
    }

    private fun orderLinesByLatency(lines: List<ApiLine>, latencies: Map<String, Long?>): List<ApiLine> {
        return lines.sortedWith(compareBy(
            { latencies[it.id] == null },
            { latencies[it.id] ?: 0L },
        ))
    }

    private fun switchHomeApiLine(apiLineId: String) {
        _state.update { it.copy(selectedApiLineId = apiLineId) }
        loadHomePage(reset = true)
    }


    // ========== Source Management Methods ==========

    fun testSourceSpeed(apiLineId: String) {
        if (_state.value.sourceTesting) return
        viewModelScope.launch {
            val latency = runCatching { repository.testSpeed(apiLineId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            _state.update { state ->
                state.copy(sourceLatencies = state.sourceLatencies + (apiLineId to latency))
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
        if (current.sourceTesting) return
        val srcLines = current.apiLines
        if (srcLines.isEmpty()) return
        sourceTestJob?.cancel()
        sourceTestJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    sourceTesting = true,
                    sourceTestCompleted = 0,
                    sourceTestTotal = srcLines.size,
                    sourceManagerMessage = null,
                    sourceImportMessage = null,
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
                                        sourceLatencies = it.sourceLatencies + (line.id to latency),
                                        sourceTestCompleted = completed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val latencies = _state.value.sourceLatencies
            val okCount = srcLines.count { latencies[it.id] != null }
            if (autoSort) {
                val sorted = orderLinesByLatency(srcLines, latencies)
                val settings = _state.value.appSettings.copy(sourceOrder = sorted.map { it.id })
                saveSettings(settings)
                _state.update {
                    it.copy(
                        sourceTesting = false,
                        apiLines = sorted,
                        allSourceLines = orderApiLines(repository.allApiLines, settings.sourceOrder),
                        appSettings = settings,
                        sourceManagerMessage = "测速完成：成功 $okCount 个，失败 ${srcLines.size - okCount} 个；已按延迟排列（最快在前）。",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        sourceTesting = false,
                        sourceManagerMessage = "测速完成：成功 $okCount 个，失败 ${srcLines.size - okCount} 个。可点击“按测速排序”重新排列。",
                    )
                }
            }
            persistSourceLatencies()
        }
    }

    /** 对当前详情页的所有播放线路测速，结果会持久化并用于按延迟排列线路。 */
    fun testDetailSourceSpeeds() {
        val current = _state.value
        if (current.detailSourceTesting) return
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
                    detailSourceTesting = true,
                    detailSourceTestCompleted = 0,
                    detailSourceTestTotal = lineIds.size,
                    detailSourceMessage = null,
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
                                        sourceLatencies = it.sourceLatencies + (lineId to latency),
                                        detailSourceTestCompleted = completed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            persistSourceLatencies()
            val latencies = _state.value.sourceLatencies
            val okCount = lineIds.count { latencies[it] != null }
            _state.update {
                it.copy(
                    detailSourceTesting = false,
                    detailSourceMessage = "线路测速完成：成功 $okCount/${lineIds.size}，已按延迟排序。",
                )
            }
        }
    }

    fun sortSourcesBySpeed() {
        val current = _state.value
        if (current.sourceTesting || current.sourceImporting) return
        val sorted = orderLinesByLatency(current.apiLines, current.sourceLatencies)
        if (sorted == current.apiLines) {
            _state.update { it.copy(sourceManagerMessage = "尚未测速或顺序未变化，请先执行“全部测速”。") }
            return
        }
        val settings = current.appSettings.copy(sourceOrder = sorted.map { it.id })
        saveSettings(settings)
        _state.update {
            it.copy(
                apiLines = sorted,
                allSourceLines = orderApiLines(repository.allApiLines, settings.sourceOrder),
                appSettings = settings,
                sourceManagerMessage = "已按测速排序（最快在前）",
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
                sourceLatencies = it.sourceLatencies - line.id,
                sourceManagerMessage = "已删除源“${line.name}”",
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deleteAllSources() {
        val current = _state.value
        if (current.sourceTesting || current.sourceImporting) return
        val allCustomIds = current.appSettings.customVideoApiLines.map { it.id }.toSet()
        if (allCustomIds.isEmpty()) {
            _state.update { it.copy(sourceManagerMessage = "暂无自定义视频源可删除") }
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
                sourceLatencies = it.sourceLatencies.filterKeys { it !in allCustomIds },
                sourceManagerMessage = "已一键删除全部自定义视频源（${allCustomIds.size} 个）",
            )
        }
        persistSourceLatencies()
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deduplicateSourcesByDomain() {
        val current = _state.value
        if (current.sourceTesting || current.sourceImporting) return
        val custom = current.appSettings.customVideoApiLines
        if (custom.isEmpty()) {
            _state.update { it.copy(sourceManagerMessage = "暂无自定义视频源") }
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
            _state.update { it.copy(sourceManagerMessage = "未发现重复域名的视频源") }
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
                sourceLatencies = it.sourceLatencies.filterKeys { it !in removedIds },
                sourceManagerMessage = "已按域名去重：保留 ${kept.size} 个，删除 ${removed.size} 个重复项",
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
                sourceManagerMessage = "已屏蔽“${line.name}”，可随时取消屏蔽。",
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
                sourceManagerMessage = "已取消屏蔽${line?.let { "“${it.name}”" }.orEmpty()}",
            )
        }
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun deleteInvalidSources() {
        val current = _state.value
        if (current.sourceTesting || current.sourceImporting) return
        val tested = current.sourceLatencies
        val invalid = current.apiLines.filter { id -> tested.containsKey(id.id) && tested[id.id] == null }
        if (invalid.isEmpty()) {
            _state.update {
                it.copy(
                    sourceManagerMessage = if (tested.isEmpty()) {
                        "尚未测速，请先点击“全部测速”，再删除无效源。"
                    } else {
                        "没有检测到无效源。"
                    },
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
                sourceLatencies = it.sourceLatencies - invalidIds,
                sourceManagerMessage = "已删除 ${invalid.size} 个无效源（测速失败或超时）。",
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
            _state.update { it.copy(sourceImporting = false, sourceImportMessage = message) }
            return message
        }
        if (_state.value.sourceImporting) return "正在导入上一份配置，请稍候再试。"
        _state.update { it.copy(sourceImporting = true, sourceImportMessage = null) }
        return try {
            val configFetcher = SourceConfigFetcher()
            val isUrl = text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true)
            val payload = if (isUrl) configFetcher.fetch(text) else text
            val decoded = try {
                SubscriptionDecoder.decode(url = if (isUrl) text else "", payload)
            } catch (error: IllegalArgumentException) {
                val message = "订阅解码失败：${error.message ?: "格式不合法"}"
                _state.update { it.copy(sourceImporting = false, sourceImportMessage = message) }
                return message
            }
            val result = SourceConfigCodec.parseImport(decoded)
            val imported = result.imported
            if (result.error != null || imported == null) {
                val message = result.error ?: "配置解析失败"
                _state.update { it.copy(sourceImporting = false, sourceImportMessage = message) }
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
            }
            _state.update {
                it.copy(
                    sourceImporting = false,
                    apiLines = ordered,
                    allSourceLines = allLines,
                    appSettings = settings,
                    sourceManagerMessage = message,
                )
            }
            message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "导入失败：${error.message}"
            _state.update { it.copy(sourceImporting = false, sourceImportMessage = message) }
            message
        }
    }

    fun dismissSourceImportMessage() {
        _state.update { it.copy(sourceImportMessage = null) }
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
        // 立即同步提交，避免 apply() 异步延迟导致设置丢失。
        appSettingsRepository.commitLiveSourceDefaultIndex(index)
        saveSettings(settings)
        applyLiveSourceConfig(settings)
    }

    /** 清除电视直播默认源。 */
    fun clearLiveSourceDefaultIndex() {
        val current = _state.value
        if (current.appSettings.liveSourceDefaultIndex == -1) return
        val settings = current.appSettings.copy(liveSourceDefaultIndex = -1)
        _state.update { it.copy(appSettings = settings) }
        appSettingsRepository.commitLiveSourceDefaultIndex(-1)
        saveSettings(settings)
        applyLiveSourceConfig(settings)
    }

    /** 切换剧集排序顺序（正序 ↔ 倒序），持久化到 SharedPreferences。 */
    fun toggleEpisodeSortReverse() {
        val current = _state.value
        val settings = current.appSettings.copy(episodeSortReverse = !current.appSettings.episodeSortReverse)
        _state.update { it.copy(appSettings = settings) }
        appSettingsRepository.commitLiveSourceDefaultIndex(settings.liveSourceDefaultIndex)
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

    private fun filterBlockedCategories(
        categories: List<Category>,
        blockedNames: List<String>,
    ): List<Category> {
        if (blockedNames.isEmpty()) return categories
        val blockedIds = categories.filter { it.name in blockedNames }.map { it.id }.toSet()
        return categories.filter { it.name !in blockedNames && it.parentId !in blockedIds }
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
            val staysOnBlockedSite = state.platformLiveDestination == PlatformLiveDestination.Rooms &&
                state.platformLiveSelectedSite?.id == siteId
            state.copy(
                appSettings = settings,
                platformLiveSites = visibleSites,
                platformLiveAllSites = allPlatformLiveSites,
                platformLiveQuickCategories = state.platformLiveQuickCategories
                    .filter { it.site.id !in settings.blockedPlatformSiteIds },
                platformLiveSelectedSite = if (staysOnBlockedSite) null else state.platformLiveSelectedSite,
                platformLiveDestination = if (staysOnBlockedSite) {
                    PlatformLiveDestination.Sites
                } else {
                    state.platformLiveDestination
                },
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
            appSettingsRepository.saveSpeedLatencies(_state.value.sourceLatencies)
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
                doubanHotRepository.getRecentHot(page = nextPage, forceRefresh = forceRefresh)
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
            }.onFailure { error ->
                if (reset) {
                    val fallbackNotice = "豆瓣热播暂时不可用，已显示当前线路最近更新"
                    _state.update { it.copy(doubanHotFallbackActive = true, homeNotice = fallbackNotice) }
                    launchCatalogHomePage(
                        reset = true,
                        fallbackNotice = fallbackNotice,
                        cancelCurrent = false,
                    )
                } else {
                    _state.update {
                        it.copy(
                            homeLoading = false,
                            loadingMore = false,
                            homeNotice = "热播加载失败，点击加载更多重试",
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
            }.onFailure { error ->
                // 取消异常（切换分类时旧 job 被 cancel）不做任何 UI 处理，避免显示错误。
                if (error is CancellationException) return@onFailure
                _state.update {
                    it.copy(
                        homeLoading = false,
                        loadingMore = false,
                        homeError = error.userMessage(),
                    )
                }
                if (_state.value.categories.isEmpty()) {
                    loadCategoriesOnly()
                }
            }
        }
    }

    private fun loadCategoriesOnly() {
        viewModelScope.launch {
            runCatching { repository.getCategories(apiLineId = _state.value.selectedApiLineId) }
                .onSuccess { categories ->
                    val blocked = _state.value.appSettings.blockedVideoCategories
                    _state.update { it.copy(categories = filterBlockedCategories(categories, blocked)) }
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
                    _state.update { it.copy(historyItems = history) }
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

    /** 该频道上次成功播放的线路下标；无记忆或线路数变化时回第 1 条。 */
    private fun TvBoxUiState.rememberedLineIndexOf(channel: com.tvbox.app.domain.LiveChannel): Int {
        val saved = liveLinePrefs?.getSavedLineIndex(channel.key()) ?: -1
        return if (saved in channel.lines.indices) saved else 0
    }

    private fun loadLiveChannels() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = TvScreen.Live,
                    liveLoading = true,
                    liveError = null,
                )
            }
            runCatching { liveRepository.getChannels() }
                .onSuccess { channels ->
                    _state.update {
                        // 重新加载后按频道恢复上次成功播放的线路；线路数变少时回退到第 1 条。
                        val rememberedLine = channels.getOrNull(
                            it.liveChannelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0)),
                        )?.let { channel ->
                            liveLinePrefs?.getSavedLineIndex(channel.key()) ?: -1
                        } ?: -1
                        val lineCount = channels.getOrNull(it.liveChannelIndex)?.lines?.size ?: 0
                        // 订阅切换：尽量保持原分类名；新订阅没有该分类时回"全部"。
                        val groups = channels.map { channel -> channel.groupName }.distinct()
                        val restoreGroup = pendingLiveGroupRestore
                        pendingLiveGroupRestore = null
                        val groupIndex = if (restoreGroup != null) {
                            val idx = groups.indexOf(restoreGroup)
                            if (idx >= 0) idx + 1 else 0
                        } else {
                            it.liveGroupIndex.coerceAtMost(groups.size)
                        }
                        val targetChannel = if (groupIndex == 0) {
                            it.liveChannelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0))
                        } else {
                            channels.indexOfFirst { channel -> channel.groupName == groups[groupIndex - 1] }
                                .coerceAtLeast(0)
                        }
                        val targetLine = channels.getOrNull(targetChannel)
                            ?.let { channel -> it.rememberedLineIndexOf(channel) } ?: 0
                        it.copy(
                            liveChannels = channels,
                            liveChannelIndex = targetChannel,
                            liveLineIndex = if (rememberedLine in 0 until lineCount && targetChannel == it.liveChannelIndex) rememberedLine else targetLine,
                            liveGroupIndex = groupIndex,
                            liveLoading = false,
                            liveNotice = restoreGroup?.let { _ -> "已切换订阅" } ?: it.liveNotice,
                            liveError = if (channels.isEmpty()) "没有可用直播频道" else null,
                        )
                    }
                    // 频道列表就绪后补拉节目单（幂等：已加载过则直接返回）。
                    loadLiveEpg()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            liveLoading = false,
                            liveError = error.userMessage(),
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
                    platformLiveDestination = PlatformLiveDestination.Sites,
                    platformLiveLoading = true,
                    platformLiveLoadingMore = false,
                    platformLiveResolving = false,
                    platformLiveError = null,
                    platformLiveStream = null,
                )
            }
            runCatching { platformLiveRepository.getSites() }
                .onSuccess { sites ->
                    allPlatformLiveSites = sites
                    val blockedSiteIds = _state.value.appSettings.blockedPlatformSiteIds
                    val visibleSites = sites.filter { it.id !in blockedSiteIds }
                    _state.update {
                        if (it.platformLiveDestination != PlatformLiveDestination.Sites) it else it.copy(
                            platformLiveSites = visibleSites,
                            platformLiveAllSites = sites,
                            platformLiveLoading = false,
                            platformLiveError = if (visibleSites.isEmpty()) {
                                "所有平台直播站点均已删除，可在源管理页恢复"
                            } else {
                                null
                            },
                        )
                    }
                    loadPlatformLiveQuickCategories(visibleSites)
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    _state.update {
                        if (it.platformLiveDestination != PlatformLiveDestination.Sites) it else it.copy(
                            platformLiveLoading = false,
                            platformLiveError = error.userMessage(),
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
                _state.update { it.copy(platformLiveQuickCategories = quick) }
            }
        }
    }

    fun openPlatformLiveQuickCategory(quick: PlatformLiveQuickCategory) {
        val tree = quickCategoryTreeCache[quick.site.id]
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Rooms,
                platformLiveSelectedSite = quick.site,
                platformLiveParentCategories = tree?.parentCategories ?: emptyList(),
                platformLiveSelectedParentCategory = tree?.parentCategories
                    ?.firstOrNull { parent -> parent.id == quick.category.parentId },
                platformLiveCategories = tree?.categories ?: emptyList(),
                platformLiveSelectedCategory = quick.category,
                platformLiveRooms = emptyList(),
                platformLiveRoomIndex = 0,
                platformLiveRoomPage = 1,
                platformLiveRoomPageCount = 1,
                platformLiveError = null,
                platformLiveStream = null,
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
                            if (current.platformLiveDestination == PlatformLiveDestination.Favorites) {
                                current.copy(platformLiveFavorites = favorites, platformLiveRooms = favorites, platformLiveFavoriteError = null)
                            } else {
                                current.copy(platformLiveFavorites = favorites, platformLiveFavoriteError = null)
                            }
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { it.copy(platformLiveFavoriteError = error.userMessage()) }
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
                    .onSuccess { keys -> _state.update { it.copy(liveFavoriteKeys = keys) } }
            }
        }
    }

    /**
     * 拉取 XMLTV 节目单全量 Map 存入状态；EPG 是增强项：
     * 未配置地址直接返回，失败静默并标记已加载，避免反复重试慢源。
     * [force] 为 true 时忽略 [TvBoxUiState.liveEpgLoaded] 重新拉取（保存新地址时用）。
     */
    private fun loadLiveEpg(force: Boolean = false) {
        val url = _state.value.appSettings.epgUrl
        if (url.isBlank()) return
        if (!force && _state.value.liveEpgLoaded) return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val programs = runCatching { epgRepository.fetch(url) }.getOrDefault(emptyMap())
            _state.update { state ->
                // 地址在拉取期间被改掉时丢弃本次结果。
                if (state.appSettings.epgUrl != url) state
                else state.copy(liveEpgByChannel = programs, liveEpgLoaded = true)
            }
        }
    }

    private fun loadPlatformLiveCategories(site: PlatformLiveSite) {
        platformLiveJob?.cancel()
        platformLiveJob = viewModelScope.launch {
            _state.update {
                if (it.platformLiveSelectedSite?.id != site.id) it else {
                    it.copy(platformLiveLoading = true, platformLiveError = null)
                }
            }
            runCatching { platformLiveRepository.getCategoryTree(site.id) }
                .onSuccess { tree ->
                    _state.update {
                        if (it.platformLiveSelectedSite?.id != site.id) it else {
                            it.copy(
                                platformLiveDestination = PlatformLiveDestination.ParentCategories,
                                platformLiveParentCategories = tree.parentCategories,
                                platformLiveSelectedParentCategory = null,
                                platformLiveCategories = tree.categories,
                                platformLiveLoading = false,
                                platformLiveError = if (tree.parentCategories.isEmpty()) "没有可用直播大分类" else null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (it.platformLiveSelectedSite?.id != site.id) it else {
                            it.copy(platformLiveLoading = false, platformLiveError = error.userMessage())
                        }
                    }
                }
        }
    }

    private fun loadPlatformLiveRooms(category: PlatformLiveCategory, reset: Boolean) {
        val state = _state.value
        val site = state.platformLiveSelectedSite ?: return
        val page = if (reset) 1 else state.platformLiveRoomPage + 1
        platformLiveJob?.cancel()
        platformLiveJob = viewModelScope.launch {
            _state.update {
                if (it.platformLiveSelectedCategory?.id != category.id) it else {
                    if (reset) {
                        it.copy(platformLiveLoading = true, platformLiveLoadingMore = false, platformLiveError = null)
                    } else {
                        it.copy(platformLiveLoadingMore = true, platformLiveError = null)
                    }
                }
            }
            runCatching { platformLiveRepository.getRooms(site.id, category.id, page) }
                .onSuccess { result ->
                    _state.update {
                        if (it.platformLiveSelectedCategory?.id != category.id) it else {
                            val rooms = if (reset) {
                                result.rooms
                            } else {
                                (it.platformLiveRooms + result.rooms).distinctBy { room -> room.id }
                            }
                            it.copy(
                                platformLiveRooms = rooms,
                                platformLiveRoomPage = result.page,
                                platformLiveRoomPageCount = result.pageCount,
                                platformLiveLoading = false,
                                platformLiveLoadingMore = false,
                                platformLiveError = if (rooms.isEmpty()) "这个分类暂时没有正在直播的房间" else null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        if (it.platformLiveSelectedCategory?.id != category.id) it else {
                            it.copy(
                                platformLiveLoading = false,
                                platformLiveLoadingMore = false,
                                platformLiveError = error.userMessage(),
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
        val room = state.platformLiveRooms.getOrNull(state.platformLiveRoomIndex) ?: return
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
                if (it.platformLiveDestination != PlatformLiveDestination.Player ||
                    it.platformLiveRooms.getOrNull(it.platformLiveRoomIndex)?.id != room.id
                ) {
                    it
                } else {
                    it.copy(platformLiveResolving = true, platformLiveError = null, platformLiveStream = null)
                }
            }
            runCatching { platformLiveRepository.resolve(room, forceRefresh) }
                .onSuccess { stream ->
                    _state.update {
                        if (it.platformLiveDestination != PlatformLiveDestination.Player ||
                            it.platformLiveRooms.getOrNull(it.platformLiveRoomIndex)?.id != room.id
                        ) {
                            it
                        } else {
                            it.copy(
                                platformLiveResolving = false,
                                platformLiveError = null,
                                platformLiveStream = stream.withActiveCandidate(candidateIndex),
                            )
                        }
                    }
                    platformLiveRecoveryInProgress = false
                }
                .onFailure { error ->
                    _state.update {
                        if (it.platformLiveDestination != PlatformLiveDestination.Player ||
                            it.platformLiveRooms.getOrNull(it.platformLiveRoomIndex)?.id != room.id
                        ) {
                            it
                        } else {
                            it.copy(
                                platformLiveResolving = false,
                                platformLiveStream = null,
                                platformLiveError = error.userMessage(),
                            )
                        }
                    }
                    platformLiveRecoveryInProgress = false
                }
        }
    }

    private fun resolveHistoryPosition(movie: Movie, item: WatchHistoryItem): Pair<Int, Int> {
        val sourceIndexByUrl = movie.playSources.indexOfFirst { source ->
            source.episodes.any { episode -> episode.url == item.episodeUrl }
        }
        val sourceIndex = when {
            sourceIndexByUrl >= 0 -> sourceIndexByUrl
            item.sourceIndex in movie.playSources.indices -> item.sourceIndex
            else -> movie.preferredSourceIndex()
        }.coerceAtLeast(0)

        val episodes = movie.playSources.getOrNull(sourceIndex)?.episodes.orEmpty()
        val episodeIndexByUrl = episodes.indexOfFirst { it.url == item.episodeUrl }
        val episodeIndex = when {
            episodeIndexByUrl >= 0 -> episodeIndexByUrl
            item.episodeIndex in episodes.indices -> item.episodeIndex
            else -> 0
        }.coerceAtLeast(0)

        return sourceIndex to episodeIndex
    }

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

private fun Throwable.userMessage(): String {
    // 不把原始异常（如 "Unexpected JSON token at offset 0 ... JSON input: ..."）直接展示给用户，
    // 统一转换成可读提示，原始内容仅用于本地排查。
    // 先去掉内部异常类名前缀（如 "DouyuResolverError: WebView 初始化超时"），只保留可读部分。
    val raw = localizedMessage.orEmpty().trim()
        .replace(Regex("^[A-Za-z][A-Za-z0-9_.]*(?:Error|Exception):\\s*"), "")
        .trim()
    val friendly = when {
        raw.isBlank() -> "请求失败，请稍后重试"
        raw.contains("timeout", ignoreCase = true) || raw.contains("超时") -> "网络超时，请稍后重试"
        raw.contains("Unable to resolve host", ignoreCase = true) ||
            raw.contains("Connection refused", ignoreCase = true) ||
            raw.contains("Unable to connect", ignoreCase = true) -> "网络连接失败，请检查网络"
        raw.contains("JSON input:", ignoreCase = true) ||
            raw.contains("Unexpected JSON", ignoreCase = true) ||
            raw.contains("Expected start of the object", ignoreCase = true) ->
            "当前视频源不支持该操作，请在顶部切换到其他视频源后重试"
        else -> raw
    }
    return friendly.take(80)
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

private fun normalizeMacCmsApiUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val withoutQuery = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
    if (withoutQuery.length <= "http://".length) return null
    return "$withoutQuery/"
}

private fun customVideoApiLineId(baseUrl: String): String {
    return "custom_" + Integer.toUnsignedString(baseUrl.lowercase().hashCode(), 36)
}

private const val DEFAULT_ALL_CATEGORY_TYPE_ID = 13
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
// 热播点击回退查找时最多尝试的视频源数量，避免逐个源串行搜索过慢。
private const val MAX_DOUBAN_HOT_FALLBACK_LINES = 6
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
