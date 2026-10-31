package com.tvbox.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvbox.app.data.AppSettingsRepository
import com.tvbox.app.data.BuiltInLiveSources
import com.tvbox.app.BuildConfig
import com.tvbox.app.data.AiConfigServer
import com.tvbox.app.data.AiRequestConfig
import com.tvbox.app.data.AiRecommendationRepository
import com.tvbox.app.data.AppUpdateRepository
import com.tvbox.app.data.DefaultAiRecommendationRepository
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
import com.tvbox.app.data.VideoApiConfigServer
import com.tvbox.app.domain.AiRecommendationItem
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.DeviceMode
import com.tvbox.app.domain.AiProviders
import com.tvbox.app.domain.ApiLine
import com.tvbox.app.domain.AppUpdate
import com.tvbox.app.domain.Category
import com.tvbox.app.domain.CustomVideoApiLine
import com.tvbox.app.domain.DoubanHotItem
import com.tvbox.app.domain.LiveChannel
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
    AiRecommend,
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

/** 直播首页的常用分类直达入口：平台 + 该平台的二级分类。 */
data class PlatformLiveQuickCategory(
    val site: PlatformLiveSite,
    val category: PlatformLiveCategory,
)

data class AiRecommendationUiItem(
    val recommendation: AiRecommendationItem,
)

data class TvBoxUiState(
    val screen: TvScreen = TvScreen.Home,
    val apiLines: List<ApiLine> = emptyList(),
    // 包含已屏蔽/已删除的源，仅用于源管理页展示与恢复。
    val allSourceLines: List<ApiLine> = emptyList(),
    val selectedApiLineId: String = "",
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
    val liveLoading: Boolean = false,
    val liveError: String? = null,
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
    val platformLiveCategories: List<PlatformLiveCategory> = emptyList(),
    val platformLiveSelectedCategory: PlatformLiveCategory? = null,
    val platformLiveRooms: List<PlatformLiveRoom> = emptyList(),
    val platformLiveRoomIndex: Int = 0,
    val platformLiveRoomPage: Int = 1,
    val platformLiveRoomPageCount: Int = 1,
    val platformLiveLoading: Boolean = false,
    val platformLiveLoadingMore: Boolean = false,
    val platformLiveResolving: Boolean = false,
    val platformLiveError: String? = null,
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
    val aiQuery: String = "",
    val aiTitle: String = "推荐",
    val aiResults: List<AiRecommendationUiItem> = emptyList(),
    val aiLoading: Boolean = false,
    val aiVoiceListening: Boolean = false,
    val aiError: String? = null,
    val aiResolvingKeyword: String? = null,
    val aiConfigDialogVisible: Boolean = false,
    val aiConfigServerUrl: String? = null,
    val aiConfigServerError: String? = null,
    val aiConfigSavedMessage: String? = null,
    val videoApiConfigDialogVisible: Boolean = false,
    val videoApiConfigServerUrl: String? = null,
    val videoApiConfigServerError: String? = null,
    val videoApiConfigSavedMessage: String? = null,
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
    private val repository: MovieRepository = DefaultMovieRepository(),
    private val doubanHotRepository: DoubanHotRepository = DefaultDoubanHotRepository(),
    private val searchHotWordRepository: SearchHotWordRepository = DefaultSearchHotWordRepository(),
    private val searchKeywordExpander: SearchKeywordExpander = DefaultSearchKeywordExpander(),
    private val liveRepository: LiveRepository = DefaultLiveRepository(),
    private val platformLiveRepository: PlatformLiveRepository = EmbeddedPlatformLiveRepository(),
    private val aiRecommendationRepository: AiRecommendationRepository = DefaultAiRecommendationRepository(BuildConfig.AI_API_KEY),
    private val appUpdateRepository: AppUpdateRepository = DefaultAppUpdateRepositoryPlaceholder(),
    private val appSettingsRepository: AppSettingsRepository = DefaultAppSettingsRepositoryPlaceholder(),
    private val playbackHealthRepository: PlaybackHealthRepository = DefaultPlaybackHealthRepositoryPlaceholder(),
    private val historyRepository: HistoryRepository,
    private val platformLiveFavoritesRepository: PlatformLiveFavoritesRepository,
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
    private var platformLiveRecoveryCount = 0
    private var platformLiveRecoveryInProgress = false
    private val platformLiveFavoritesMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private var aiJob: Job? = null
    private var updateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateInstallStateJob: Job? = null
    private var settingsWriteJob: Job? = null
    private var sourceTestJob: Job? = null
    private var detailSourceTestJob: Job? = null
    private var detailAutoTestedKey: String? = null
    private var importJob: Job? = null
    private var aiConfigServer: AiConfigServer? = null
    private var videoApiConfigServer: VideoApiConfigServer? = null
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
        viewModelScope.launch {
            val settings = runCatching { appSettingsRepository.getSettings() }
                .getOrDefault(AppSettings())
            repository.updateCustomApiLines(settings.customVideoApiLines.toApiLines())
            repository.setDisabledLineIds(
                (settings.disabledBuiltInLineIds + settings.blockedSourceIds).toSet(),
            )
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
                    playbackHealth = playbackHealth,
                    sourceLatencies = speedLatencies,
                )
            }
            loadHistory()
            refreshHome()
            if (normalizedSettings.checkUpdatesOnStartup) {
                checkForAppUpdate()
            }
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

    fun loadNextPage() {
        val current = _state.value
        if (!current.canLoadMore) return
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

    fun openAiRecommend() {
        _state.update { it.copy(screen = TvScreen.AiRecommend, aiVoiceListening = false, aiError = null) }
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

    fun updateAiProvider(providerId: String) {
        val provider = AiProviders.find(providerId)
        val settings = _state.value.appSettings.copy(
            aiProviderId = provider.id,
            aiModelName = provider.defaultModel,
        )
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings, aiError = null) }
    }

    fun updateAiModelName(modelName: String) {
        val settings = _state.value.appSettings.copy(aiModelName = modelName)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings, aiError = null) }
    }

    fun updateAiApiKey(apiKey: String) {
        val settings = _state.value.appSettings.copy(aiApiKey = apiKey)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings, aiError = null) }
    }

    fun openAiConfigDialog() {
        closeAiConfigDialog(stopOnly = true)
        val current = _state.value
        val provider = AiProviders.find(current.appSettings.aiProviderId)
        val modelName = current.appSettings.aiModelName.ifBlank { provider.defaultModel }
        val server = AiConfigServer(viewModelScope) { submittedModelName, submittedApiKey ->
            applyAiConfigFromPhone(
                modelName = submittedModelName,
                apiKey = submittedApiKey,
            )
        }
        aiConfigServer = server
        _state.update {
            it.copy(
                aiConfigDialogVisible = true,
                aiConfigServerUrl = null,
                aiConfigServerError = null,
                aiConfigSavedMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                server.start(
                    provider = provider,
                    modelName = modelName,
                )
            }.onSuccess { session ->
                _state.update {
                    it.copy(
                        aiConfigServerUrl = session.url,
                        aiConfigServerError = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        aiConfigServerUrl = null,
                        aiConfigServerError = "启动手机配置服务失败：${error.userMessage()}",
                    )
                }
            }
        }
    }

    fun closeAiConfigDialog() {
        closeAiConfigDialog(stopOnly = false)
    }

    fun openVideoApiConfigDialog() {
        closeVideoApiConfigDialog(stopOnly = true)
        val server = VideoApiConfigServer(viewModelScope) { submittedLineName, submittedApiUrl ->
            applyCustomVideoApiFromPhone(
                lineName = submittedLineName,
                apiUrl = submittedApiUrl,
            )
        }
        videoApiConfigServer = server
        _state.update {
            it.copy(
                videoApiConfigDialogVisible = true,
                videoApiConfigServerUrl = null,
                videoApiConfigServerError = null,
                videoApiConfigSavedMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { server.start() }
                .onSuccess { session ->
                    _state.update {
                        it.copy(
                            videoApiConfigServerUrl = session.url,
                            videoApiConfigServerError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            videoApiConfigServerUrl = null,
                            videoApiConfigServerError = "启动视频接口配置服务失败：${error.userMessage()}",
                        )
                    }
                }
        }
    }

    fun closeVideoApiConfigDialog() {
        closeVideoApiConfigDialog(stopOnly = false)
    }

    private fun closeAiConfigDialog(stopOnly: Boolean) {
        aiConfigServer?.stop()
        aiConfigServer = null
        if (!stopOnly) {
            _state.update {
                it.copy(
                    aiConfigDialogVisible = false,
                    aiConfigServerUrl = null,
                    aiConfigServerError = null,
                    aiConfigSavedMessage = null,
                )
            }
        }
    }

    private fun closeVideoApiConfigDialog(stopOnly: Boolean) {
        videoApiConfigServer?.stop()
        videoApiConfigServer = null
        if (!stopOnly) {
            _state.update {
                it.copy(
                    videoApiConfigDialogVisible = false,
                    videoApiConfigServerUrl = null,
                    videoApiConfigServerError = null,
                    videoApiConfigSavedMessage = null,
                )
            }
        }
    }

    private fun applyAiConfigFromPhone(modelName: String, apiKey: String) {
        val settings = _state.value.appSettings.copy(
            aiModelName = modelName.trim(),
            aiApiKey = apiKey.trim(),
        )
        saveSettings(settings)
        _state.update {
            it.copy(
                appSettings = settings,
                aiConfigServerError = null,
                aiConfigSavedMessage = "已收到手机配置，模型和 API Key 已保存。",
            )
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
                videoApiConfigServerError = null,
                videoApiConfigSavedMessage = "已添加视频接口：${customLine.name}",
            )
        }
        refreshHome()
        return null
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, searchError = null) }
    }

    fun updateAiQuery(query: String) {
        _state.update { it.copy(aiQuery = query, aiVoiceListening = false, aiError = null) }
    }

    fun submitAiRecommendation() {
        submitAiRecommendation(query = _state.value.aiQuery)
    }

    fun submitAiRecommendation(query: String) {
        submitAiRecommendationInternal(query = query, refreshBatch = false)
    }

    fun refreshAiRecommendationBatch() {
        submitAiRecommendationInternal(query = _state.value.aiQuery, refreshBatch = true)
    }

    fun showAiMessage(message: String) {
        _state.update {
            it.copy(
                screen = TvScreen.AiRecommend,
                aiLoading = false,
                aiVoiceListening = false,
                aiResolvingKeyword = null,
                aiError = message,
            )
        }
    }

    fun startAiVoiceListening() {
        _state.update {
            it.copy(
                screen = TvScreen.AiRecommend,
                aiLoading = false,
                aiVoiceListening = true,
                aiResolvingKeyword = null,
                aiError = null,
            )
        }
    }

    private fun submitAiRecommendationInternal(query: String, refreshBatch: Boolean) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            _state.update { it.copy(aiVoiceListening = false, aiResults = emptyList(), aiError = "请输入找片需求") }
            return
        }
        val excludedNames = if (refreshBatch) {
            _state.value.aiResults.map { it.recommendation.name }
        } else {
            emptyList()
        }
        val requestQuery = buildAiRecommendationRequest(trimmedQuery, excludedNames)
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    aiQuery = trimmedQuery,
                    aiLoading = true,
                    aiVoiceListening = false,
                    aiError = null,
                    aiResults = emptyList(),
                    aiResolvingKeyword = null,
                    aiTitle = if (refreshBatch) "AI 正在换一批" else "AI 正在找片",
                )
            }
            runCatching {
                val recommendations = aiRecommendationRepository.getRecommendations(
                    query = requestQuery,
                    userConfig = _state.value.appSettings.userAiRequestConfigOrNull(),
                )
                recommendations.title to recommendations.items.map { AiRecommendationUiItem(recommendation = it) }
            }.onSuccess { (title, items) ->
                _state.update {
                    it.copy(
                        aiLoading = false,
                        aiVoiceListening = false,
                        aiTitle = title.ifBlank { "AI 推荐" },
                        aiResults = items,
                        aiError = if (items.isEmpty()) "AI 没有返回可展示的推荐" else null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        aiLoading = false,
                        aiVoiceListening = false,
                        aiResults = emptyList(),
                        aiResolvingKeyword = null,
                        aiError = error.userMessage(),
                    )
                }
            }
        }
    }

    fun openAiRecommendation(item: AiRecommendationItem) {
        val keyword = item.searchKeyword.ifBlank { item.name }.trim()
        if (keyword.isBlank()) {
            _state.update { it.copy(aiError = "暂无该视频资源") }
            return
        }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    aiError = null,
                    aiResolvingKeyword = keyword,
                )
            }
            runCatching { findPlayableMovie(item) }
                .onSuccess { movie ->
                    if (movie == null) {
                        _state.update {
                            it.copy(
                                screen = TvScreen.AiRecommend,
                                aiResolvingKeyword = null,
                                aiError = "暂无该视频资源",
                            )
                        }
                    } else {
                        _state.update { it.copy(aiResolvingKeyword = null, aiError = null) }
                        openDetail(movie.id)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            screen = TvScreen.AiRecommend,
                            aiResolvingKeyword = null,
                            aiError = error.userMessage(),
                        )
                    }
                }
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
            runCatching { findPlayableDoubanMovie(item.title, apiLineId) }
                .onSuccess { movie ->
                    _state.update { it.copy(resolvingDoubanHotId = null) }
                    if (movie == null) {
                        _state.update { it.copy(homeNotice = "暂无该视频资源") }
                    } else {
                        openDetail(movie.id, apiLineId)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            resolvingDoubanHotId = null,
                            homeNotice = error.userMessage(),
                        )
                    }
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
            TvScreen.AiRecommend -> TvScreen.AiRecommend
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
        _state.update { state ->
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            state.copy(
                liveChannelIndex = (state.liveChannelIndex + 1) % channels.size,
                liveLineIndex = 0,
            )
        }
    }

    fun updateDeviceMode(mode: DeviceMode) {
        if (_state.value.appSettings.deviceMode == mode) return
        val settings = _state.value.appSettings.copy(deviceMode = mode)
        _state.update { it.copy(appSettings = settings) }
        saveSettings(settings)
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

    fun playPreviousLiveChannel() {
        _state.update { state ->
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            val previousIndex = if (state.liveChannelIndex <= 0) channels.lastIndex else state.liveChannelIndex - 1
            state.copy(
                liveChannelIndex = previousIndex,
                liveLineIndex = 0,
            )
        }
    }

    fun playNextLiveLine(): Boolean {
        val state = _state.value
        val lines = state.liveChannels.getOrNull(state.liveChannelIndex)?.lines.orEmpty()
        if (lines.size <= 1) return false
        _state.update {
            it.copy(liveLineIndex = (it.liveLineIndex + 1) % lines.size)
        }
        return true
    }

    fun playPreviousLiveLine(): Boolean {
        val state = _state.value
        val lines = state.liveChannels.getOrNull(state.liveChannelIndex)?.lines.orEmpty()
        if (lines.size <= 1) return false
        _state.update {
            val previousIndex = if (it.liveLineIndex <= 0) lines.lastIndex else it.liveLineIndex - 1
            it.copy(liveLineIndex = previousIndex)
        }
        return true
    }

    fun advanceLiveLineAfterFailure(): Boolean {
        val state = _state.value
        val lines = state.liveChannels.getOrNull(state.liveChannelIndex)?.lines.orEmpty()
        val nextIndex = state.liveLineIndex + 1
        if (nextIndex !in lines.indices) return false
        _state.update { it.copy(liveLineIndex = nextIndex) }
        return true
    }

    fun selectPlatformLiveSite(site: PlatformLiveSite) {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.ParentCategories,
                platformLiveSelectedSite = site,
                platformLiveParentCategories = emptyList(),
                platformLiveSelectedParentCategory = null,
                platformLiveSelectedCategory = null,
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
        val hasCategories = _state.value.platformLiveCategories.any { it.parentId == parentCategory.id }
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Categories,
                platformLiveSelectedParentCategory = parentCategory,
                platformLiveSelectedCategory = null,
                platformLiveRooms = emptyList(),
                platformLiveRoomIndex = 0,
                platformLiveError = if (hasCategories) null else "这个大分类暂时没有可用的二级分类",
                platformLiveStream = null,
            )
        }
    }

    fun selectPlatformLiveCategory(category: PlatformLiveCategory) {
        _state.update {
            it.copy(
                screen = TvScreen.PlatformLive,
                platformLiveDestination = PlatformLiveDestination.Rooms,
                platformLiveSelectedCategory = category,
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
        _state.update { state ->
            val channels = state.liveChannels
            if (channels.isEmpty()) return@update state
            state.copy(
                liveChannelIndex = index.coerceIn(0, channels.lastIndex),
                liveLineIndex = 0,
            )
        }
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
        resolvePlatformLiveRoom(forceRefresh = true)
    }

    fun recoverPlatformLiveChannel() {
        val state = _state.value
        val stream = state.platformLiveStream ?: run {
            reconnectPlatformLiveChannel()
            return
        }
        if (platformLiveRecoveryInProgress || state.platformLiveResolving) return

        if (platformLiveRecoveryCount < PLATFORM_LIVE_RETRY_PER_LINE) {
            val retryCount = platformLiveRecoveryCount
            platformLiveRecoveryCount++
            platformLiveRecoveryInProgress = true
            viewModelScope.launch {
                if (retryCount == PLATFORM_LIVE_RETRY_PER_LINE - 1) {
                    delay(PLATFORM_LIVE_SECOND_RETRY_DELAY_MS)
                }
                resolvePlatformLiveRoom(
                    forceRefresh = true,
                    candidateIndex = stream.activeCandidateIndex,
                    isRecovery = true,
                )
            }
            return
        }

        platformLiveRecoveryCount = 0
        if (stream.activeCandidateIndex < stream.candidates.lastIndex) {
            _state.update {
                it.copy(
                    platformLiveError = null,
                    platformLiveStream = stream.withActiveCandidate(stream.activeCandidateIndex + 1),
                )
            }
        } else {
            _state.update {
                it.copy(platformLiveError = "全部 CDN 线路均播放失败，请稍后重试")
            }
        }
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
            TvScreen.AiRecommend -> submitAiRecommendation()
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
            TvScreen.Search, TvScreen.History, TvScreen.Settings, TvScreen.AiRecommend -> {
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
        repository.setDisabledLineIds(
            (settings.disabledBuiltInLineIds + settings.blockedSourceIds).toSet(),
        )
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
        val isBuiltIn = current.appSettings.customVideoApiLines.none { it.id == line.id }
        val baseSettings = if (isBuiltIn) {
            current.appSettings.copy(
                disabledBuiltInLineIds = (current.appSettings.disabledBuiltInLineIds + line.id).distinct(),
            )
        } else {
            current.appSettings.copy(
                customVideoApiLines = current.appSettings.customVideoApiLines.filterNot { it.id == line.id },
            )
        }
        val settings = baseSettings.copy(
            blockedSourceIds = baseSettings.blockedSourceIds - line.id,
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
                sourceManagerMessage = if (isBuiltIn) {
                    "已删除内置源“${line.name}”，可在“恢复内置”中还原。"
                } else {
                    "已删除源“${line.name}”"
                },
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
        if (apiLineId in current.appSettings.disabledBuiltInLineIds) {
            _state.update { it.copy(sourceManagerMessage = "该内置源已删除，请先点“恢复”再屏蔽。") }
            return
        }
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
        val customIds = current.appSettings.customVideoApiLines.map { it.id }.toSet()
        val deletedBuiltInIds = invalidIds.filter { it !in customIds }
        val settings = current.appSettings.copy(
            customVideoApiLines = current.appSettings.customVideoApiLines.filterNot { it.id in invalidIds },
            disabledBuiltInLineIds = (current.appSettings.disabledBuiltInLineIds + deletedBuiltInIds).distinct(),
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

    fun restoreBuiltInLine(apiLineId: String) {
        val current = _state.value
        if (apiLineId !in current.appSettings.disabledBuiltInLineIds) return
        val settings = current.appSettings.copy(
            disabledBuiltInLineIds = current.appSettings.disabledBuiltInLineIds - apiLineId,
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
                sourceManagerMessage = "已恢复内置源",
            )
        }
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun restoreBuiltInLines() {
        val current = _state.value
        if (current.appSettings.disabledBuiltInLineIds.isEmpty()) {
            _state.update { it.copy(sourceManagerMessage = "没有已删除的内置源") }
            return
        }
        val settings = current.appSettings.copy(disabledBuiltInLineIds = emptyList())
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
                sourceManagerMessage = "已恢复全部内置源（已屏蔽的源保持屏蔽）",
            )
        }
        if (selectionMissing) {
            switchHomeApiLine(finalSettings.homeApiLineId)
        }
    }

    fun importSourceConfig(input: String) {
        val text = input.trim()
        if (text.isBlank()) {
            _state.update { it.copy(sourceImporting = false, sourceImportMessage = "请粘贴配置内容或输入订阅地址") }
            return
        }
        if (_state.value.sourceImporting) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _state.update { it.copy(sourceImporting = true, sourceImportMessage = null) }
            try {
                val configFetcher = SourceConfigFetcher()
                val isUrl = text.startsWith("http://", ignoreCase = true) ||
                    text.startsWith("https://", ignoreCase = true)
                val payload = if (isUrl) configFetcher.fetch(text) else text
                val decoded = try {
                    SubscriptionDecoder.decode(url = if (isUrl) text else "", payload)
                } catch (error: IllegalArgumentException) {
                    _state.update {
                        it.copy(sourceImporting = false, sourceImportMessage = "订阅解码失败：${error.message ?: "格式不合法"}")
                    }
                    return@launch
                }
                val result = SourceConfigCodec.parseImport(decoded)
                val imported = result.imported
                if (result.error != null || imported == null) {
                    _state.update {
                        it.copy(sourceImporting = false, sourceImportMessage = result.error ?: "配置解析失败")
                    }
                    return@launch
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
            } catch (error: Exception) {
                _state.update {
                    it.copy(sourceImporting = false, sourceImportMessage = "导入失败：${error.message}")
                }
            }
        }
    }

    fun dismissSourceImportMessage() {
        _state.update { it.copy(sourceImportMessage = null) }
    }

    fun updateLiveSourceUrls(urls: List<String>) {
        val normalized = urls.map { it.trim() }.filter { it.isNotEmpty() }
        val current = _state.value
        val settings = current.appSettings.copy(liveSourceUrls = normalized)
        applyLiveSourceConfig(settings)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 把设置中的电视直播源组合应用到仓库：内置源（剔除已删除）+ 自定义源。 */
    private fun applyLiveSourceConfig(settings: AppSettings) {
        val builtIn = BuiltInLiveSources.all.filter { it.id !in settings.disabledBuiltInLiveSourceIds }
        liveRepository.setSourceConfig(
            builtIn = builtIn,
            customUrls = settings.liveSourceUrls,
            includeBuiltIn = settings.includeBuiltInLiveSources,
        )
    }

    /** 开关"内置直播源与自定义源组合使用"。 */
    fun updateIncludeBuiltInLiveSources(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(includeBuiltInLiveSources = enabled)
        applyLiveSourceConfig(settings)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    /** 删除单条内置电视直播源（可在"恢复内置直播源"中还原）。 */
    fun deleteBuiltInLiveSource(sourceId: String) {
        if (BuiltInLiveSources.byId(sourceId) == null) return
        val current = _state.value
        val settings = current.appSettings.copy(
            disabledBuiltInLiveSourceIds =
            (current.appSettings.disabledBuiltInLiveSourceIds + sourceId).distinct(),
        )
        applyLiveSourceConfig(settings)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun restoreBuiltInLiveSource(sourceId: String) {
        val current = _state.value
        if (sourceId !in current.appSettings.disabledBuiltInLiveSourceIds) return
        val settings = current.appSettings.copy(
            disabledBuiltInLiveSourceIds = current.appSettings.disabledBuiltInLiveSourceIds - sourceId,
        )
        applyLiveSourceConfig(settings)
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
    }

    fun restoreBuiltInLiveSources() {
        val current = _state.value
        if (current.appSettings.disabledBuiltInLiveSourceIds.isEmpty()) return
        val settings = current.appSettings.copy(disabledBuiltInLiveSourceIds = emptyList())
        applyLiveSourceConfig(settings)
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
                    it.copy(
                        categories = if (result.categories.isNotEmpty()) result.categories else it.categories,
                        movies = if (reset) {
                            result.movies
                        } else {
                            (it.movies + result.movies).distinctBy { movie -> "${movie.apiLineId}-${movie.id}" }
                        },
                        page = result.page,
                        pageCount = result.pageCount,
                        total = result.total,
                        homeLoading = false,
                        loadingMore = false,
                        homeError = if (result.movies.isEmpty() && reset) "当前没有影片" else null,
                    )
                }
            }.onFailure { error ->
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
                    _state.update { it.copy(categories = categories) }
                }
        }
    }

    private suspend fun findPlayableMovie(item: AiRecommendationItem): Movie? {
        val keyword = item.searchKeyword.ifBlank { item.name }.trim()
        if (keyword.isBlank()) return null
        val movies = repository.getMovies(
            apiLineId = _state.value.selectedApiLineId,
            page = 1,
            keyword = keyword,
        ).movies
        if (movies.isEmpty()) return null

        val normalizedKeyword = keyword.normalizeAiMatchText()
        val normalizedName = item.name.normalizeAiMatchText()
        return movies.firstOrNull { movie ->
            val candidate = movie.name.normalizeAiMatchText()
            candidate == normalizedKeyword || candidate == normalizedName
        } ?: movies.firstOrNull { movie ->
            val candidate = movie.name.normalizeAiMatchText()
            candidate.isNotBlank() &&
                (candidate.contains(normalizedKeyword) || normalizedKeyword.contains(candidate))
        } ?: movies.first()
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

    private fun buildAiRecommendationRequest(query: String, excludedNames: List<String>): String {
        val distinctNames = excludedNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        if (distinctNames.isEmpty()) return query
        return buildString {
            append(query)
            append("\n请换一批推荐，不要重复这些作品：")
            append(distinctNames.joinToString("、"))
            append("。")
        }
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
                        it.copy(
                            liveChannels = channels,
                            liveChannelIndex = it.liveChannelIndex.coerceIn(0, (channels.lastIndex).coerceAtLeast(0)),
                            liveLineIndex = 0,
                            liveLoading = false,
                            liveError = if (channels.isEmpty()) "没有可用直播频道" else null,
                        )
                    }
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
            platformLiveRecoveryCount = 0
            platformLiveRecoveryInProgress = false
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
        aiConfigServer?.stop()
        aiConfigServer = null
        videoApiConfigServer?.stop()
        videoApiConfigServer = null
        super.onCleared()
    }
}

private fun Throwable.userMessage(): String {
    return localizedMessage?.takeIf { it.isNotBlank() } ?: "网络请求失败，请稍后重试"
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

private fun AppSettings.userAiRequestConfigOrNull(): AiRequestConfig? {
    val apiKey = aiApiKey.trim()
    if (apiKey.isBlank()) return null
    val provider = AiProviders.find(aiProviderId)
    return AiRequestConfig(
        chatCompletionsUrl = provider.chatCompletionsUrl,
        model = aiModelName.trim().ifBlank { provider.defaultModel },
        apiKey = apiKey,
    )
}

private fun String.normalizeAiMatchText(): String {
    return lowercase().filter { it.isLetterOrDigit() }
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
private const val PLATFORM_LIVE_RETRY_PER_LINE = 2
private const val PLATFORM_LIVE_SECOND_RETRY_DELAY_MS = 1_000L

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
