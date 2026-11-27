package com.tvbox.app.ui

import com.tvbox.app.util.TLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvbox.app.data.AppSettingsRepository
import com.tvbox.app.data.PlatformLiveMemoryStore
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
import com.tvbox.app.domain.HistoryPolicy
import com.tvbox.app.domain.CdnStatusRow
import com.tvbox.app.domain.LiveInfoBannerDuration
import com.tvbox.app.domain.DirectTuneTimeout
import com.tvbox.app.domain.PerSourceTimeout
import com.tvbox.app.domain.SettingsPageSummary
import com.tvbox.app.domain.buildSettingsPageSummary
import com.tvbox.app.domain.settingsEntriesFrom
import com.tvbox.app.domain.SourceHealthSetting
import com.tvbox.app.domain.UserAgentSetting
import com.tvbox.app.domain.measureSourceLatencies
import com.tvbox.app.domain.withFallbackUserAgent
import com.tvbox.app.domain.withThirdPartyProxyPrepended
import com.tvbox.app.domain.StartupPhase
import com.tvbox.app.domain.StartupTimeline
import com.tvbox.app.domain.lastWatchedLabel
import com.tvbox.app.domain.phaseDurations
import com.tvbox.app.domain.HomeFeedPageState
import com.tvbox.app.domain.canLoadMoreHomeFeed
import com.tvbox.app.domain.resolveResumePosition
import com.tvbox.app.domain.QuantitySettings
import com.tvbox.app.domain.effectiveMode
import com.tvbox.app.domain.withContentSubtitleTrack
import com.tvbox.app.domain.withContentVideoScaleMode
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
import com.tvbox.app.domain.rankLinesByQuality
import com.tvbox.app.domain.lineQualityKey
import com.tvbox.app.domain.LiveSourceDryRun
import com.tvbox.app.domain.measureLiveLineLatencyMs
import com.tvbox.app.domain.probeLiveLineLatencyWithConfidence
import com.tvbox.app.domain.LiveLineFallbackReason
import com.tvbox.app.domain.RecoveryBackoff
import com.tvbox.app.domain.AutoSwitchGate
import com.tvbox.app.domain.AutoSwitchThrottleState
import com.tvbox.app.domain.StartupTask
import com.tvbox.app.domain.startupDeferralMs
import com.tvbox.app.domain.gateAutoSwitch
import com.tvbox.app.domain.resetAutoSwitchThrottle
import com.tvbox.app.domain.mergeMoviesBySourceAndId
import com.tvbox.app.domain.buildSearchFlow
import com.tvbox.app.domain.SearchEmission
import com.tvbox.app.domain.otaFailureReason
import com.tvbox.app.domain.nextRotatedPlatformLiveCandidate
import com.tvbox.app.domain.resolveEffectiveEpgUrl
import com.tvbox.app.data.EpgRepository
import com.tvbox.app.data.RemoteEpgRepository
import com.tvbox.app.data.LiveFavoritesRepository
import com.tvbox.app.data.liveFavoriteKey
import com.tvbox.app.data.legacyLiveFavoriteKey
import com.tvbox.app.data.isLiveFavorite as containsLiveFavorite
import com.tvbox.app.domain.EpgCachePolicy
import com.tvbox.app.domain.EpgData
import com.tvbox.app.domain.EpgProgram
import com.tvbox.app.domain.EpgRenderState
import com.tvbox.app.domain.findEpgPrograms
import com.tvbox.app.domain.Movie
import com.tvbox.app.domain.PlaybackAgent
import com.tvbox.app.domain.PlaybackAgentDecision
import com.tvbox.app.domain.PlaybackHealthSnapshot
import com.tvbox.app.domain.PlaybackIssueType
import com.tvbox.app.domain.PlayerSpeedSetting
import com.tvbox.app.domain.PlayerBrightnessSetting
import com.tvbox.app.domain.FastSeekSetting
import com.tvbox.app.domain.HistoryLayout
import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.correspondingEpisodeIndex
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.ResolvedPlatformLiveStream
import com.tvbox.app.domain.WatchHistoryItem
import com.tvbox.app.domain.continueWatching
import com.tvbox.app.domain.ThemeAccent
import com.tvbox.app.domain.TvFontScale
import com.tvbox.app.domain.findBestTitleMatchIndex
import com.tvbox.app.domain.findResumePoint
import com.tvbox.app.domain.playbackHealthKey
import com.tvbox.app.domain.toApiLines
import com.tvbox.app.domain.GlobalPlayState
import com.tvbox.app.domain.RemoteBreadcrumb
import com.tvbox.app.domain.ResolvedSite
import com.tvbox.app.util.logSafeText
import com.tvbox.app.util.logSafeUrl
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 导入订阅时单个直播源 dry-run 的最长等待，避免一条死链拖住整次导入。 */
private const val LIVE_SOURCE_DRY_RUN_TIMEOUT_MS = 5_000L

enum class TvScreen {
    Home,
    History,
    Search,
    Detail,
    Player,
    Live,
    PlatformLive,
    Settings,
    /** AI 找片：只返回候选片名，点击候选后才进入当前源搜索。 */
    AiSearch,
    SourceManager,
    LiveSourceManager,
    VideoSourceManager,
}

enum class HomeFeedMode {
    DoubanHot,
    Catalog,
}

/**
 * 通知权限被拒后的一次性提示。[permanent] 表示用户选择了「不再询问」（或策略禁止），
 * 此时再次申请不会弹窗，只能引导去系统设置开启；临时拒绝仍可重试授权。
 */
data class NotificationPermissionDenial(val permanent: Boolean) {
    /** 可直接展示的文案，含设置路径，便于用户自行处理。 */
    val message: String
        get() = if (permanent) {
            "通知权限已被拒绝且不再询问，更新下载进度无法显示。请到系统设置 → 应用 → TVBox → 通知中允许通知后重试。"
        } else {
            "未获得通知权限，更新下载进度无法显示。可重试授权，或在系统设置中开启通知。"
        }
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
    /** 历史记录是否已完成首次读取：首页「继续观看」据此避免半加载造成的布局跳动。 */
    val historyLoaded: Boolean = false,
    val historyUpdateChecking: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Movie> = emptyList(),
    /** 每源原始搜索结果（未跨源去重），供手机端按源分组；key 为 apiLineId。 */
    val searchResultsBySource: Map<String, List<Movie>> = emptyMap(),
    /** 手机端搜索源筛选：空表示全部源，否则为选中的 apiLineId。底层仍搜全部源，只影响展示。 */
    val searchSourceFilter: String = "",
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val searchCompletedSources: Int = 0,
    val searchTotalSources: Int = 0,
    val searchHotWords: List<String> = emptyList(),
    val searchHotWordsLoading: Boolean = false,
    /** 搜索记录（已提交的关键词，最新在前，去重，上限 SEARCH_HISTORY_MAX_ITEMS）。 */
    val searchHistory: List<String> = emptyList(),
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
    /** AI 找片页面状态：仅由 ViewModel 驱动，页面不自行发请求。 */
    val aiSearch: com.tvbox.app.ai.AiSearchUiState = com.tvbox.app.ai.AiSearchUiState(),
    /** S1-04：无 key 构建不暴露 AI 入口，避免「点了就报错」的半成品按钮。 */
    val aiSearchAvailable: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val playbackHealth: PlaybackHealthSnapshot = PlaybackHealthSnapshot(),
    /** 播放管家的可解释证据时间轴（最新在前，上限 50 条）。 */
    val playbackEvidence: List<com.tvbox.app.domain.PlaybackEvidence> = emptyList(),
    val remoteManageDialogVisible: Boolean = false,
    val remoteManageServerUrl: String? = null,
    val remoteManageServerError: String? = null,
    val remoteManageMessage: String? = null,
    val source: SourceManagerUiState = SourceManagerUiState(),
    /** 设置持久化失败时的一次性提示，由 UI 消费后清空。 */
    val settingsSaveError: String? = null,
    /** 通知权限被拒的一次性提示；由 UI 展示「重试授权 / 去设置」后清空。 */
    val notificationPermissionDenial: NotificationPermissionDenial? = null,
    /** T3-24：菜单键连按计数器，到达阈值后触发健康面板。 */
    val menuPressCount: Int = 0,
    /** T3-24：是否弹出健康面板（一次性信号，展示后由 UI 消费清除）。 */
    val showHealthPanel: Boolean = false,
    /** T3-24：健康页汇总数据快照，由应用层提供。 */
    val healthSnapshot: com.tvbox.app.domain.HealthSnapshot = com.tvbox.app.domain.HealthSnapshot(),
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
    val updateCardDismissed: Boolean get() = update.cardDismissed

    val sourceLatencies: Map<String, Long?> get() = source.latencies
    val sourceTesting: Boolean get() = source.testing
    val sourceTestCompleted: Int get() = source.testCompleted
    val sourceTestTotal: Int get() = source.testTotal
    val sourceManagerMessage: String? get() = source.managerMessage
    val sourceImporting: Boolean get() = source.importing
    val sourceImportMessage: String? get() = source.importMessage
    /** T2-11/T2-13：按显示名解析后的源集合，供冲突面板使用。 */
    val resolvedSites: List<com.tvbox.app.domain.ResolvedSite> get() = resolveSourceCandidates(allSourceLines)

    val liveChannels: List<LiveChannel> get() = live.channels
    val liveChannelIndex: Int get() = live.channelIndex
    val liveLineIndex: Int get() = live.lineIndex
    val liveLoading: Boolean get() = live.loading
    val liveError: String? get() = live.error
    val liveNotice: String? get() = live.notice
    val liveReconnectNonce: Int get() = live.reconnectNonce
    val liveFavoriteKeys: Set<String> get() = live.favoriteKeys
    val liveEpgByChannel: Map<String, List<EpgProgram>> get() = live.epgByChannel
    val liveLineLatencies: Map<String, Long?> get() = live.lineLatencies
    val liveLineTesting: Boolean get() = live.lineTesting

    val platformLiveDestination: PlatformLiveDestination get() = platformLive.destination
    val platformLiveSites: List<PlatformLiveSite> get() = platformLive.sites
    val platformLiveAllSites: List<PlatformLiveSite> get() = platformLive.allSites
    val platformLiveQuickCategories: List<PlatformLiveQuickCategory> get() = platformLive.quickCategories
    val platformLiveFavorites: List<PlatformLiveRoom> get() = platformLive.favorites
    val platformLiveFavoriteError: String? get() = platformLive.favoriteError
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
    val platformLiveLoadingMoreError: String? get() = platformLive.loadingMoreError
    val platformLiveLoading: Boolean get() = platformLive.loading
    val platformLiveResolving: Boolean get() = platformLive.resolving
    val platformLiveError: String? get() = platformLive.error
    val platformLiveNotice: String? get() = platformLive.notice
    val platformLiveStream: ResolvedPlatformLiveStream? get() = platformLive.stream

    val canLoadMore: Boolean
        // 复用 domain 分页判定：服务端未返回总页数（pageCount<=0）时仍允许试探下一页，
        // 避免列表被锁死；加载中的门控仍留在 UI 侧，语义与原来一致。
        get() = canLoadMoreHomeFeed(
            HomeFeedPageState(page = page, pageCount = pageCount, itemCount = homeItemCount)
        ) && !homeLoading && !loadingMore

    val isShowingDoubanHot: Boolean
        get() = homeFeedMode == HomeFeedMode.DoubanHot && !doubanHotFallbackActive

    /** 首页「继续观看」：开关、条数上限与排序/去重/进度过滤统一由 domain 处理。 */
    val continueWatchingItems: List<WatchHistoryItem>
        get() = if (appSettings.homeContinueWatchingEnabled) {
            historyItems.continueWatching(QuantitySettings.ContinueWatchingCount.default)
        } else {
            emptyList()
        }

    /** 历史尚未读完时首页整体延迟渲染，避免「继续观看」行出现/消失造成布局跳动。 */
    val continueWatchingLoading: Boolean
        get() = appSettings.homeContinueWatchingEnabled && !historyLoaded

    val homeItemCount: Int
        get() = if (isShowingDoubanHot) {
            doubanHotMovies.size.coerceAtMost(QuantitySettings.HomeHotContentCount.default)
        } else {
            movies.size
        }

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
    /** 搜索记录存储：懒创建，JVM 单测不依赖 Android Context。 */
    private val searchHistoryStore: com.tvbox.app.data.SearchHistoryStore? = null,
    private val liveLinePrefs: LiveLinePreference? = null,
    private val epgRepository: EpgRepository = RemoteEpgRepository(),
    /** 电视直播频道收藏仓库；null 时收藏退化为仅内存 Set（如未注入的单测环境）。 */
    private val liveFavoritesRepository: LiveFavoritesRepository? = null,
    /** 直播线路按域名的成功/失败评分；用于同类源较多时优先选"最近确实能播"的域名。 */
    private val liveDomainScores: LiveDomainScoreStore? = null,
    /** 直播线路级起播质量；域名无记录时作为第二级择优依据（null 时退化为源内顺序）。 */
    private val liveLineQualityRecords: com.tvbox.app.data.LiveLineQualityStore? = null,
    /** 平台直播「上次观看」本机记忆；null 时按 Application Context 懒创建，便于 JVM 单测。 */
    private val platformLiveMemoryStore: PlatformLiveMemoryStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(TvBoxUiState())
    val state: StateFlow<TvBoxUiState> = _state.asStateFlow()

    /** AI 找片仓库懒创建：JVM 单测不触发网络栈初始化。 */
    private val aiSearchRepository by lazy { com.tvbox.app.ai.AiSearchRepository() }
    private var aiSearchJob: kotlinx.coroutines.Job? = null

    init {
        // S1-04：无 key 构建直接隐藏 AI 入口，不展示「点了就报错」的死按钮。
        _state.value = _state.value.copy(aiSearchAvailable = aiSearchRepository.apiKeyConfigured)
    }

    /** 搜索记录存储懒创建：JVM 单测无 Android Context 时不触发。 */
    private val searchHistory by lazy {
        searchHistoryStore ?: com.tvbox.app.data.SearchHistoryStore(appContext, ttlDays = _state.value.appSettings.searchHistoryTtlDays)
    }

    /** 设置页当前值摘要（第 261–270 轮）：UI 可直接渲染，无需进入子页。 */
    val settingsSummary: SettingsPageSummary
        get() = buildSettingsPageSummary(settingsEntriesFrom(_state.value.appSettings))

    /** 平台直播「上次观看」标签（第 271–280 轮）。 */
    val lastWatchedPlatformLabel: String
        get() = lastWatchedLabel(runCatching { platformLiveMemory.load() }.getOrNull())

    /** 进入房间时记住房间标识；站点/分类由调用方补齐，避免依赖易变的 UI 模型字段。 */
    fun rememberPlatformLiveMemory(roomId: String, site: String = "", categoryId: String = "") {
        runCatching { platformLiveMemory.save(site, categoryId, roomId) }
    }

    /** OTA 线路状态行（第 281–290 轮）：只含线路名与计数，不含完整 URL。 */
    fun cdnStatusRows(nowMs: Long = System.currentTimeMillis()): List<CdnStatusRow> =
        appUpdateRepository.cdnStatusRows(nowMs)

    private var startupTimeline = StartupTimeline()

    /** 启动阶段打点（第 281–290 轮）：只记录时间线，不改变任何启动任务顺序。 */
    fun recordStartupPhase(phase: StartupPhase, atMs: Long = System.currentTimeMillis()) {
        startupTimeline = startupTimeline.record(phase, atMs)
    }

    val startupTotalDurationMs: Long?
        get() = com.tvbox.app.domain.startupTotalMs(startupTimeline)

    val startupPhaseDurationsMs: Map<StartupPhase, Long>
        get() = startupTimeline.phaseDurations()

    private val platformLiveMemory by lazy {
        platformLiveMemoryStore ?: PlatformLiveMemoryStore(appContext)
    }

    private var homeJob: Job? = null
    private var categoriesJob: Job? = null
    private var doubanHotResolveJob: Job? = null
    /** 搜索触发信号：updateSearchQuery（输入中，防抖）与 submitSearch（显式提交，立即）共用。
     * 缓冲溢出丢弃最旧请求，保证最新查询词不被丢失（搜索只需最新词，无需排队）。 */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var searchPipelineJob: Job? = null
    private var searchHotWordsJob: Job? = null
    private var detailJob: Job? = null
    /** 最近一次详情请求（movieId 到 apiLineId），用于 detailMovie 尚未加载成功时的重试。 */
    private var lastDetailRequest: Pair<Int, String>? = null
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
    // 线路浮层「测速」防重入：避免连点导致多轮并发测速互相覆盖。
    private var platformLiveLatencyMeasuring = false
    // 平台直播恢复节流：连续失败窗口内限制次数，避免虎牙 FLV 周期性关流造成无限轮换/重解析。
    private var platformLiveRecoveryWindowStartMs = 0L
    private var platformLiveRecoveryWindowAttempts = 0
    // 同一条候选线路的原地重连次数（缓冲卡死/被服务端正常关流时先用完再换线路）。
    private var platformLiveSameLineRetries = 0
    // 任务2/3 自适应退避：按失败原因差异化基线 + 指数递增 + 抖动，跨房间共用一个策略实例。
    private val platformLiveBackoff = RecoveryBackoff()
    // 当前这轮自动恢复中已失败的候选下标。键取「房间 + 候选列表」而非对象身份，
    // 这样 withActiveCandidate() 产生的副本不会把已试过的线路清空。
    private var platformLiveRotationKey: String? = null
    private val platformLiveTriedCandidates = mutableSetOf<Int>()
    // 用户手动切换候选线路失败后要回退到的原线路下标（#60 线路切换失败回原线路并提示）。
    // 仅在「手动切换后新线路因 Error 失败」这一场景生效；画面就绪或进入自动轮换后清除。
    private var platformLiveManualFallbackIndex: Int? = null
    // 详情页选集滚动位置记忆（影片 id + 线路 -> 首个可见项下标）。
    // 返回详情页时恢复用户上次滚动到的位置，而不是被"滚到选中集"逻辑拉回去。
    private val detailEpisodeScrollIndex = HashMap<String, Int>()

    // 电视直播：缓冲/卡死类失败在当前线路上的原地重连次数。
    private var liveStallRetries = 0
    /** 自动换线的退避状态；用户主动切线路 / 切频道时清零。 */
    private var autoSwitchThrottle = AutoSwitchThrottleState()
    // 电视直播：记录最近一次"所有线路均失败"的频道号，避免重复提示。
    private var lastExhaustedChannelNumber: Int? = null
    // 电视直播：最近一次已记入成功评分的域名（同一域名连续就绪不重复累加）。
    private var lastScoredDomain: String? = null
    // 线路级起播质量：本次选线/重连的发起时刻，用于估算起播耗时；null 表示尚未开始计时。
    private var liveLineStartAtMs: Long? = null
    // 切换订阅后要恢复的分类名（新订阅没有该分类时回"全部"）。
    private var pendingLiveGroupRestore: String? = null
    private val platformLiveFavoritesMutex = Mutex()
    private val historyWriteMutex = Mutex()
    private val liveFavoritesMutex = Mutex()
    private var epgJob: Job? = null
    private var liveLineTestJob: Job? = null
    /** 当前测速归属的频道/订阅身份；切台换源后旧协程的迟到结果一律作废。 */
    @Volatile
    private var liveLineTestIdentity: String? = null
    /** 测速代次：即使切回同名频道，上一轮协程的迟到回调也不能清掉新一轮的状态。 */
    @Volatile
    private var liveLineTestGeneration = 0L
    private var updateJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var updateInstallStateJob: Job? = null
    /** 用户取消更新时的底层清理任务：新下载开始前须等待其完成，避免取消与重启竞态。 */
    private var updateCancelJob: Job? = null
    /** 保存安装包的唯一在途任务：避免重复点击并行导出，取消时也能真正中止。 */
    private var updateApkExportJob: Job? = null
    private var settingsWriteJob: Job? = null

    /** 取消旧 Job 并启动新协程（ViewModel 侧常用的“最新请求覆盖”模式）。 */
    private fun relaunch(current: Job?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        current?.cancel()
        return viewModelScope.launch(block = block)
    }
    private var sourceTestJob: Job? = null
    private var detailSourceTestJob: Job? = null
    /** 视频源健康管理循环：应用运行期间按周期静默体检，开关关闭时循环空转。 */
    private var sourceHealthJob: Job? = null
    /** T2-16：各视频源的探活冷却状态（避免把活源自己探死）。 */
    private val probeCooldownStates = mutableMapOf<String, com.tvbox.app.domain.ProbeCooldown.State>()
    private var detailAutoTestedKey: String? = null
    private var remoteManageServer: RemoteManageServer? = null
    /** 远程网页里最近一次“获取分类”的视频源与其分类列表。 */
    private var remoteCategorySourceId: String = ""
    private var remoteCategoryNames: List<String> = emptyList()
    private val playbackAgent = PlaybackAgent()
    private var historyUpdateCheckJob: Job? = null
    private var lastHistoryUpdateCheckAtEpochMs: Long = 0L

    init {
        recordStartupPhase(StartupPhase.ApplicationInit)
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
        // S5-27：搜索改 flatMapLatest + debounce。快速切换查询词时，旧搜索流被 flatMapLatest
        // 取消，迟到回包不会覆盖新查询的结果（用户投诉「搜索很蠢 / 结果闪一下跳回无关内容」）。
        // 输入中的请求走 280ms 防抖（TV 遥控逐字符输入，280ms 不吃手感）；
        // 显式提交（回车/热词点击）跳过防抖立即执行。
        searchPipelineJob = viewModelScope.launch {
            searchRequests
                .debounce { request -> if (request.immediate) 0L else SEARCH_DEBOUNCE_MS }
                .map { it.query }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        flowOf(null)
                    } else {
                        _state.update { it.copy(searchLoading = true, searchError = null) }
                        buildSearchFlow(
                            query = query,
                            selectedApiLineId = _state.value.selectedApiLineId,
                            expandInitials = searchKeywordExpander::expandInitials,
                            suggestionCount = _state.value.appSettings.searchSuggestionCount,
                            search = repository::searchProgressively,
                        )
                            .onCompletion { cause ->
                                // 正常跑完（所有源已返回/无源）才复位 loading；
                                // 取消（新查询接管）由新查询自己置位，避免旧流复位误闪 loading。
                                if (cause == null) {
                                    _state.update { it.copy(searchLoading = false, searchError = null) }
                                }
                            }
                            .map<SearchEmission, SearchEmission?> { it }
                    }
                }
                .collect { emission ->
                    if (emission == null) {
                        resetSearchResultsState()
                    } else {
                        _state.update {
                            it.copy(
                                searchResults = emission.results,
                                searchResultsBySource = emission.resultsBySource,
                                searchCompletedSources = emission.completedSources,
                                searchTotalSources = emission.totalSources,
                                searchLoading = false,
                                searchError = null,
                            )
                        }
                    }
                }
        }
        // 切台/换源后当前频道身份改变：统一终止在途测速，避免旧协程继续占用网络或写回旧结果。
        // 只切线路（lineIndex）不改变身份，用户主动发起的测速不会被误取消。
        viewModelScope.launch {
            var lastIdentity = currentLiveLineTestIdentity(_state.value)
            _state.collect { current ->
                val identity = currentLiveLineTestIdentity(current)
                if (identity != lastIdentity) {
                    lastIdentity = identity
                    if (liveLineTestJob != null) cancelLiveChannelLineTest()
                }
            }
        }
        viewModelScope.launch {
            val settings = runCatching { appSettingsRepository.getSettings() }
                .getOrDefault(AppSettings())
            repository.updateCustomApiLines(settings.customVideoApiLines.toApiLines())
            repository.setDisabledLineIds(settings.blockedSourceIds.toSet())
            // S5-29：ANR 保守模式下把搜索/详情源并发上限压到策略档位（6→3）；
            // 用户设置值更低时以用户值为准，取 min 即可，不写回设置（降级不落盘）。
            repository.setMaxParallelSourceRequests(
                minOf(
                    settings.maxParallelSourceRequests,
                    com.tvbox.app.domain.AnrConservativePolicy.searchParallelism(
                        com.tvbox.app.crash.AnrWatchdog.isConservativeMode,
                    ),
                ),
            )
            // #94 启动时把已保存的单源超时推给仓库，搜索/详情立即按该预算执行。
            repository.setPerSourceTimeoutMs(settings.perSourceTimeoutMs)
            repository.setSourceCircuitBreakerEnabled(settings.sourceCircuitBreakerEnabled)
            // 自定义 UA：视频接口请求、订阅拉取与直播播放兜底共用同一口径。
            com.tvbox.app.data.network.GlobalUserAgent.value = UserAgentSetting.resolve(settings.customUserAgent)
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
                    playerSpeed = normalizedSettings.playerSpeed,
                    effectiveDeviceMode = normalizedSettings.effectiveMode(appContext),
                    playbackHealth = playbackHealth,
                    source = SourceManagerUiState(latencies = speedLatencies),
                )
            }
            PlayerLog.enabled = normalizedSettings.playerLogEnabled
            loadHistory()
            refreshHome()
            // 首屏必需项完成后，把 OTA 检查与直播 EPG 延后到首帧后 / idle，
            // 避免与首页取数竞争协程与网络；分级策略由 domain 纯函数定义并可单测。
            launchDeferredStartupTasks(normalizedSettings.checkUpdatesOnStartup)
            // 视频源健康管理：默认开启，启动后静默周期测速并自动排序。
            startSourceHealthMonitor()
        }
    }

    /** 非首屏必需的启动任务：各自独立协程，失败或超时都不会拖住首页。 */
    private fun launchDeferredStartupTasks(checkUpdatesOnStartup: Boolean) {
        viewModelScope.launch {
            delay(startupDeferralMs(StartupTask.LiveEpg))
            loadLiveEpg()
        }
        if (checkUpdatesOnStartup) {
            viewModelScope.launch {
                delay(startupDeferralMs(StartupTask.OtaCheck))
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
        // 切源后旧的热播解析结果归属旧源，不能继续跳详情。
        doubanHotResolveJob?.cancel()
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
        // 与 UI 的触底 effect 双重保护：加载中/已到末页时不再发起重复请求。
        if (!current.canLoadMore || current.loadingMore) return
        val nextPage = current.page + 1
        val now = System.currentTimeMillis()
        if (loadMoreRetryPage == nextPage && now - loadMoreRetryAtMs < HOME_LOAD_MORE_RETRY_COOLDOWN_MS) return
        if (now - lastLoadNextPageMs < 800L) return
        lastLoadNextPageMs = now
        loadHomePage(reset = false, forceRefresh = current.homeNotice != null)
    }

    fun openHome() {
        // 热播解析是「点卡片后异步搜片再跳详情」，用户已主动回首页时不能再被带回详情页。
        doubanHotResolveJob?.cancel()
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
        loadSearchHistory()
    }

    /** 进入搜索页时读取持久化的搜索记录。 */
    private fun loadSearchHistory() {
        viewModelScope.launch {
            val items = runCatching { searchHistory.load() }.getOrDefault(emptyList())
            _state.update { it.copy(searchHistory = items) }
        }
    }

    /** 提交搜索后记录关键词（去重、最新在前）。 */
    private fun recordSearchHistory(keyword: String) {
        viewModelScope.launch {
            runCatching { searchHistory.add(keyword) }
            loadSearchHistory()
        }
    }

    /** 删除单条搜索记录。 */
    fun removeSearchHistory(keyword: String) {
        viewModelScope.launch {
            runCatching { searchHistory.remove(keyword) }
            loadSearchHistory()
        }
    }

    /** 清空全部搜索记录。 */
    fun clearSearchHistory() {
        viewModelScope.launch {
            runCatching { searchHistory.clear() }
            _state.update { it.copy(searchHistory = emptyList()) }
        }
    }

    fun refreshSearchHotWords() {
        loadSearchHotWords(forceRefresh = true)
    }

    private fun loadSearchHotWords(forceRefresh: Boolean = false) {
        // 「换一批」必须取消旧请求并重新发起，而不是因为旧任务在途就被静默丢弃。
        searchHotWordsJob = relaunch(searchHotWordsJob) {
            _state.update { it.copy(searchHotWordsLoading = true) }
            val words = try {
                searchHotWordRepository.getHotWords(
                    forceRefresh = forceRefresh,
                    limit = _state.value.appSettings.hotWordCount,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
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
        // 任务5 订阅每次从远程拉取最新内容：不再以「已有频道」为条件跳过刷新。
        // 仓库 getChannels() 每次都是全新 HTTP 拉取；这里无论是否有缓存都触发一次刷新，
        // 保证订阅地址每次进入都拿到最新内容。已加载的频道在刷新期间保持展示（不闪空），
        // 拉取成功后由 loadLiveChannels 内部按原频道/分类恢复位置。
        loadLiveChannels()
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

    /**
     * T3-21：当前内存档位。健康页的 tier 计数仍由 TvBoxApplication 独立维护，
     * 这里只读同一份值用于收紧海报/预取/EPG 消费，不改动健康页现有逻辑。
     */
    private fun currentMemoryTier(): com.tvbox.app.domain.MemoryGovernor.Tier =
        (appContext as? com.tvbox.app.TvBoxApplication)?.memoryTier
            ?: com.tvbox.app.domain.MemoryGovernor.Tier.RICH

    /** T3-21：首页触底自动预取的页数预算；CRITICAL 档为 0，关闭自动预取（手动加载仍可用）。 */
    fun autoPrefetchPages(): Int = com.tvbox.app.domain.MemoryGovernor.effectivePrefetchPages(
        requested = 2,
        tier = currentMemoryTier(),
    )

    /** T3-24：记录一次菜单键按下，达到阈值时触发健康面板展示。 */
    fun recordMenuPress() {
        val current = _state.value
        val nextCount = current.menuPressCount + 1
        GlobalPlayState.menuPressCount = nextCount
        // T3-23：菜单连按也是异常出口之一，记录进 64 帧面包屑便于事后定位。
        GlobalPlayState.breadcrumb.record(
            System.currentTimeMillis(),
            "menuPress",
            "count=$nextCount",
        )
        if (nextCount >= com.tvbox.app.domain.HealthPageMetrics.MENU_PRESSES_TO_OPEN) {
            // 重置计数器并生成快照
            GlobalPlayState.menuPressCount = 0
            val app = (appContext as? com.tvbox.app.TvBoxApplication)
            val snapshot = com.tvbox.app.domain.HealthSnapshot(
                longFrames = app?.frameTracker?.collectAndReset()?.coerceAtLeast(0) ?: 0,
                throttled = app?.throttleCount ?: 0,
                tier = app?.memoryTier ?: com.tvbox.app.domain.MemoryGovernor.Tier.RICH,
            )
            _state.update { it.copy(menuPressCount = 0, showHealthPanel = true, healthSnapshot = snapshot) }
        } else {
            _state.update { it.copy(menuPressCount = nextCount) }
        }
    }

    /** T3-24：消费健康面板展示信号（UI 显示后调用以清除）。 */
    fun dismissHealthPanel() {
        _state.update { it.copy(showHealthPanel = false) }
    }

    /** 打开 AI 找片：入口只在设置项开启时可见；Key 状态只读展示。 */
    fun openAiSearch() {
        if (!_state.value.appSettings.aiSearchEnabled) return
        _state.update {
            it.copy(
                screen = TvScreen.AiSearch,
                aiSearch = com.tvbox.app.ai.AiSearchUiState(
                    voiceEnabled = it.appSettings.voiceSearchEnabled,
                    keySourceLabel = aiSearchRepository.apiKeySourceLabel(),
                    missBehavior = com.tvbox.app.ai.AiMissBehavior.fromStorageKey(it.appSettings.aiMissBehavior),
                ),
            )
        }
    }

    fun updateAiQuery(query: String) {
        _state.update { it.copy(aiSearch = it.aiSearch.copy(query = query, errorMessage = null)) }
    }

    /** 提交 AI 检索：只更新候选列表，失败给出可读原因，不跳播放器。 */
    fun submitAiSearch(query: String = _state.value.aiSearch.query) {
        aiSearchJob?.cancel()
        _state.update {
            it.copy(aiSearch = it.aiSearch.copy(query = query, loading = true, errorMessage = null))
        }
        aiSearchJob = viewModelScope.launch {
            val config = com.tvbox.app.ai.AiSearchConfig.from(_state.value.appSettings)
            runCatching { aiSearchRepository.search(query, config) }
                .onSuccess { result ->
                    _state.update { state ->
                        state.copy(
                            aiSearch = state.aiSearch.copy(
                                candidates = result.candidates,
                                loading = false,
                                errorMessage = if (result.candidates.isEmpty()) {
                                    "未找到候选，${if (result.missBehavior == com.tvbox.app.ai.AiMissBehavior.Search) "已切换到普通搜索" else "可换个描述再试"}"
                                } else null,
                            ),
                        )
                    }
                    if (result.candidates.isEmpty() && result.missBehavior == com.tvbox.app.ai.AiMissBehavior.Search) {
                        openSearch()
                    }
                }
                .onFailure { error ->
                    _state.update { state ->
                        state.copy(
                            aiSearch = state.aiSearch.copy(
                                loading = false,
                                errorMessage = error.message ?: "AI 找片失败，请稍后重试",
                            ),
                        )
                    }
                }
        }
    }

    /** 点候选卡片：把片名填入普通搜索并触发查询，不直接播放。 */
    fun openAiCandidate(title: String) {
        updateSearchQuery(title)
        openSearch()
        submitSearch()
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
            runCatching {
                appUpdateRepository.checkForUpdate(
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    updateChannel = _state.value.appSettings.updateChannel,
                )
            }
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
                                    cardDismissed = false,
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

    /**
     * 用户主动取消更新下载：终止底层下载 Job、清理系统/服务侧任务并隐藏悬浮卡片。
     * 与 [startUpdateDownload] 的静默换 Job 不同，这里是显式用户取消，卡片不保留。
     */
    fun cancelUpdateDownload() {
        val job = updateDownloadJob
        updateDownloadJob = null
        job?.cancel()
        _state.update {
            it.copy(update = it.update.copy(
                downloading = false, downloadProgress = null,
                autoInstallPath = null, awaitingInstallPermission = false,
                error = null, cardDismissed = true,
            ))
        }
        // 底层清理（删持久任务/系统下载/前台服务）纳入可等待的取消任务，
        // 使 startUpdateDownload 能 join 它，避免「取消后立即重下」与之竞态。
        updateCancelJob = viewModelScope.launch { runCatching { appUpdateRepository.cancelDownload() } }
    }

    /** 关闭下载悬浮卡片（失败/提示态），不终止底层任务；返回键在卡片可见时也走这里。 */
    fun dismissUpdateCard() {
        _state.update { it.copy(update = it.update.copy(cardDismissed = true)) }
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
                exporting = false, exportMessage = null, cardDismissed = false,
            ))
        }
        updateDownloadJob = viewModelScope.launch {
            try {
                // 若刚取消过下载，先等底层清理（删持久任务/系统任务）完成，
                // 避免新下载与尚未落地的取消清理竞态（如旧任务残留覆盖新任务）。
                updateCancelJob?.join()
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
            // 用户已取消/关闭下载卡片时，不因 ViewModel 重建而复活下载；
            // 否则「取消后立刻杀进程再启动」会让已取消的任务重新开始。
            if (_state.value.update.cardDismissed) return@launch
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
        // #321：安装失败统一走错误文案映射，签名冲突 / 空间不足给出具体可行动提示。
        val friendlyMessage = otaFailureReason(message) ?: message
        _state.update {
            it.copy(update = it.update.copy(
                autoInstallPath = null, awaitingInstallPermission = false,
                downloadedApkPath = if (fileMissing) null else it.update.downloadedApkPath, error = friendlyMessage,
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
        val update = _state.value.update
        updateApkExportJob?.cancel()
        updateApkExportJob = null
        _state.update { it.copy(update = it.update.copy(exporting = false, exportMessage = message)) }
        val apkPath = update.downloadedApkPath
        // 仅撤销本次导出写入的“仅手动安装”标记：用户只是放弃保存，不改变后续自动安装行为。
        // 已成功保存时 exporting 为 false，迟到的取消回调不会误清标记。
        if (update.exporting && apkPath != null) {
            persistUpdateInstallState { appUpdateRepository.clearManualInstall(apkPath) }
        }
    }

    fun saveUpdateApk(apkPath: String, destination: android.net.Uri?) {
        // 重复点击或上一次导出尚在途时直接忽略，避免多个协程并行写文件、后结束的失败提示覆盖成功提示。
        if (_state.value.update.exporting) return
        _state.update { it.copy(update = it.update.copy(exporting = true, exportMessage = null)) }
        updateApkExportJob?.cancel()
        updateApkExportJob = viewModelScope.launch {
            try {
                updateInstallStateJob?.join()
                val location = appUpdateRepository.exportUpdateApk(apkPath, destination)
                // 取消/换包后的迟到结果不得覆盖当前 UI 状态。
                if (!_state.value.update.exporting || _state.value.update.downloadedApkPath != apkPath) return@launch
                _state.update { it.copy(update = it.update.copy(exporting = false, exportMessage = "已保存：$location。请通过文件管理器打开安装")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!_state.value.update.exporting) return@launch
                TLog.w("TVBoxUpdate", error) { "Unable to export update APK" }
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
            catch (error: Exception) { TLog.w("TVBoxUpdate", error) { "Unable to persist install state" } }
        }
    }

    fun openHistory() {
        _state.update { it.copy(screen = TvScreen.History) }
        loadHistory(triggerUpdateCheck = true)
    }

    /** 下拉刷新历史（#222）：仅重新加载，不切换页面。 */
    fun refreshHistory() {
        loadHistory(triggerUpdateCheck = true)
    }

    fun clearHistory() {
        viewModelScope.launch {
            // 与 savePlaybackProgress 共用写锁：退出播放器时的 onDispose 保存仍在提交中，
            // 不加锁直接清空会让这次保存随后落盘，历史“清空后又回来”。
            historyWriteMutex.withLock {
                runCatching { historyRepository.clearHistory() }
                    .onSuccess { history ->
                        _state.update { it.copy(historyItems = history) }
                    }
            }
        }
    }

    fun removeHistoryItem(item: WatchHistoryItem) {
        viewModelScope.launch {
            // 与 clearHistory 同理：与进度保存共用写锁，避免删除后又被排队中的保存写回。
            historyWriteMutex.withLock {
                runCatching {
                    historyRepository.removeHistoryItem(item.apiLineId, item.movieId)
                }.onSuccess { history ->
                    _state.update { it.copy(historyItems = history) }
                }
            }
        }
    }

    /**
     * #309：多选批量删除。复用单条 [removeHistoryItem] 的仓库逻辑，在同一把
     * [historyWriteMutex] 内逐条提交——既保持与进度保存的互斥语义，又不必改动仓库契约。
     * 任一条失败只跳过该条，已成功的结果作为最新列表回写；全部失败时保留原列表。
     */
    fun removeHistoryItems(items: List<WatchHistoryItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            historyWriteMutex.withLock {
                var latest = _state.value.historyItems
                items.forEach { item ->
                    runCatching {
                        historyRepository.removeHistoryItem(item.apiLineId, item.movieId)
                    }.onSuccess { history -> latest = history }
                }
                _state.update { it.copy(historyItems = latest) }
            }
        }
    }

    /**
     * #311：一键清理引用已删除源的历史记录——apiLineId 不在当前可用 apiLines 中的条目。
     * 复用 [removeHistoryItems] 的批量删除逻辑。线路尚未加载（空表）时直接返回，
     * 避免把全部历史误判为失效。
     */
    fun removeStaleHistoryItems() {
        val current = _state.value
        if (current.apiLines.isEmpty()) return
        val validIds = current.apiLines.map { it.id }.toSet()
        val stale = current.historyItems.filter { it.apiLineId !in validIds }
        removeHistoryItems(stale)
    }

    fun updateStartupUpdateCheck(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(checkUpdatesOnStartup = enabled)
        commitSettings(settings)
    }

    fun updatePlayerLogEnabled(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(playerLogEnabled = enabled)
        commitSettings(settings)
        PlayerLog.enabled = enabled
    }

    fun updateUpdateChannel(channel: com.tvbox.app.domain.UpdateChannel) {
        if (_state.value.appSettings.updateChannel == channel) return
        val settings = _state.value.appSettings.copy(updateChannel = channel)
        commitSettings(settings)
    }

    fun updatePlaybackAgentAutoSwitch(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(playbackAgentAutoSwitchEnabled = enabled)
        commitSettings(settings)
    }

    fun updateHomeContinueWatching(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(homeContinueWatchingEnabled = enabled)
        commitSettings(settings)
    }

    fun updateVideoScaleMode(mode: com.tvbox.app.domain.VideoScaleMode) {
        val settings = _state.value.appSettings.copy(videoScaleMode = mode)
        commitSettings(settings)
    }

    /** 解码策略：写入 AppSettings 后由共享播放宿主按新策略重建播放器。 */
    fun updateDecoderStrategy(strategy: com.tvbox.app.domain.DecoderStrategy) {
        val settings = _state.value.appSettings.copy(decoderStrategy = strategy)
        commitSettings(settings)
    }

    /**
     * 按内容记忆画面比例：写入覆盖表，不影响全局默认值；已单独记忆的其他内容保持原样。
     * 内容 key 由 domain 的 vod/live/room 构造函数生成，避免把完整地址塞进偏好。
     */
    fun updateContentVideoScaleMode(
        contentKey: String,
        mode: com.tvbox.app.domain.VideoScaleMode,
    ) {
        val settings = _state.value.appSettings.withContentVideoScaleMode(contentKey, mode)
        commitSettings(settings)
    }

    /**
     * 按内容记忆字幕轨道：写入覆盖表，不影响其他内容；[trackId] 空串表示关闭字幕。
     */
    fun updateContentSubtitleTrack(contentKey: String, trackId: String) {
        val settings = _state.value.appSettings.withContentSubtitleTrack(contentKey, trackId)
        commitSettings(settings)
    }

    /** S1-01：远程管理 LAN 开关。开启会暴露同网段访问权限，调用方须先向用户二次确认。 */
    fun updateRemoteManageLanEnabled(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(remoteManageLanEnabled = enabled)
        commitSettings(settings)
        // 服务正在运行时改开关需要重启才能改变 bind 地址。
        if (_state.value.remoteManageDialogVisible) openRemoteManageDialog()
    }

    fun openRemoteManageDialog() {
        closeRemoteManageDialog(stopOnly = true)
        val server = RemoteManageServer(
            snapshot = ::buildRemoteManageSnapshot,
            onSubmit = ::handleRemoteManageRequest,
            // S1-01：默认只听回环；只有用户在设置里显式 opt-in 才监听 0.0.0.0。
            lanEnabled = _state.value.appSettings.remoteManageLanEnabled,
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
                val settings = _state.value.appSettings
                // 默认源按 URL 追踪：只有默认源本身还在时才重算下标，避免删除前面的源后静默改指其它源。
                val defaultUrl = settings.liveSourceUrls.getOrNull(settings.liveSourceDefaultIndex)
                val remaining = settings.liveSourceUrls - url
                val nextDefaultIndex = defaultUrl
                    ?.let { target -> remaining.indexOf(target).takeIf { idx -> idx >= 0 } }
                    ?: -1
                updateLiveSourceUrls(remaining, settings.liveSourceDefaultGroup, nextDefaultIndex)
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
        // 输入过程中的自动搜索：走 280ms 防抖，快速输入只保留最后一个字符序列的搜索。
        searchRequests.tryEmit(SearchRequest(query = query.trim(), immediate = false))
    }

    /** 空查询/清空搜索时把结果区复位（不写 searchQuery，由调用方负责）。 */
    private fun resetSearchResultsState() {
        _state.update {
            it.copy(
                searchResults = emptyList(),
                searchResultsBySource = emptyMap(),
                searchSourceFilter = "",
                searchError = null,
                searchLoading = false,
                searchCompletedSources = 0,
                searchTotalSources = 0,
            )
        }
    }

    fun clearSearch() {
        // 清空结果的同时取消仍在流式返回的多源搜索，避免旧结果在返回首页后又写回状态。
        // flatMapLatest 会在收到空查询时取消在途内层流并复位结果区。
        _state.update { it.copy(searchQuery = "", searchSourceFilter = "") }
        searchRequests.tryEmit(SearchRequest(query = "", immediate = true))
        resetSearchResultsState()
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
            // 异步搜片可能持续数秒：用户已按返回/切页/切源时，不能强行把页面拉回详情页。
            ensureActive()
            val latest = _state.value
            if (latest.screen != TvScreen.Home || latest.selectedApiLineId != apiLineId ||
                latest.resolvingDoubanHotId != item.doubanId
            ) {
                _state.update {
                    if (it.resolvingDoubanHotId == item.doubanId) it.copy(resolvingDoubanHotId = null) else it
                }
                return@launch
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
            // 空查询走明确的空态提示，不写 searchError：错误态会被空态分支掩盖，
            // 且「重试」对空查询没有意义。
            _state.update { it.copy(searchResults = emptyList(), searchLoading = false, searchError = null) }
            return
        }
        // 显式提交（回车/搜索按钮/热词点击）：跳过防抖立即执行，并把结果区复位为加载态。
        // 实际搜索由 init 里的 flatMapLatest 管道承接，这里只发信号，保证乱序回包仍被取消保护。
        recordSearchHistory(query)
        _state.update {
            it.copy(
                searchLoading = true,
                searchError = null,
                searchResults = emptyList(),
                searchResultsBySource = emptyMap(),
                searchCompletedSources = 0,
                searchTotalSources = 0,
            )
        }
        searchRequests.tryEmit(SearchRequest(query = query, immediate = true))
    }

    /** 手机端切换搜索源筛选（空=全部源）。仅影响展示，不重新发起搜索。 */
    fun setSearchSourceFilter(sourceId: String) {
        _state.update { it.copy(searchSourceFilter = sourceId) }
    }

    fun openDetail(movieId: Int, apiLineId: String = _state.value.selectedApiLineId) {
        historyResumeJob?.cancel()
        detailJob?.cancel()
        detailSourceTestJob?.cancel()
        // 记录本次请求的影片与来源：详情加载失败时 detailMovie 仍为 null，
        // 「重试」需要靠它复现同一请求，否则只会拿默认源去查另一个源才有的影片。
        val requestKey = movieId to apiLineId
        lastDetailRequest = requestKey
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
                    // 渐进补线的迟到结果只属于本次请求；换片/返回后不得覆盖新详情。
                    if (lastDetailRequest != requestKey || _state.value.screen != TvScreen.Detail) return@collect
                    // 主源获取失败（repository 发出降级 error 信号）：直接展示错误，不进入正常渲染。
                    if (update.error != null) {
                        _state.update {
                            it.copy(
                                detailLoading = false,
                                detailError = update.error,
                                detailMovie = null,
                            )
                        }
                        return@collect
                    }
                    receivedPrimary = true
                    val isFirstDetail = _state.value.detailMovie == null
                    val historyForResume = if (isFirstDetail) {
                        val current = _state.value.historyItems
                        if (current.isNotEmpty() || _state.value.historyLoaded) {
                            current
                        } else {
                            runCatching { historyRepository.getHistory() }.getOrDefault(emptyList())
                        }
                    } else {
                        emptyList()
                    }
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
                        // 本机有这部影片的观看记录时，详情页默认停在上次看到的那条线路与那一集：
                        // 否则「播放」按钮会从第 1 集开头起播，用户得自己再找回进度。
                        val resumeHistory = if (state.historyItems.isNotEmpty()) state.historyItems else historyForResume
                        val resume = if (state.detailMovie == null) {
                            findResumePoint(update.movie, resumeHistory)
                        } else {
                            null
                        }
                        val mergedHistory = if (
                            state.detailMovie == null &&
                            state.historyItems.isEmpty() &&
                            historyForResume.isNotEmpty()
                        ) {
                            historyForResume.take(
                                com.tvbox.app.domain.MemoryGovernor.effectivePosterLimit(
                                    state.appSettings.historyMaxItems,
                                    currentMemoryTier(),
                                ),
                            )
                        } else {
                            state.historyItems
                        }
                        state.copy(
                            detailMovie = update.movie,
                            detailLoading = false,
                            detailError = null,
                            detailCompletedSources = update.completedSources,
                            detailTotalSources = update.totalSources,
                            selectedSourceIndex = resume?.sourceIndex ?: sourceIndex,
                            selectedEpisodeIndex = resume?.episodeIndex ?: 0,
                            playerSourceIndex = resume?.sourceIndex ?: sourceIndex,
                            playerEpisodeIndex = resume?.episodeIndex ?: 0,
                            playerStartPositionMs = resume?.positionMs ?: 0L,
                            historyItems = mergedHistory,
                            historyLoaded = if (mergedHistory.isNotEmpty()) true else state.historyLoaded,
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
                    TLog.w(DETAIL_LOG_TAG) { "【详情为空】movieId=$movieId 未收到主源数据" }
                    _state.update {
                        it.copy(detailLoading = false, detailError = "影片详情不存在")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TLog.w(DETAIL_LOG_TAG) { "【详情加载失败】movieId=$movieId type=${error::class.simpleName} msg=${error.userMessage()}" }
                _state.update {
                    it.copy(detailLoading = false, detailError = error.userMessage())
                }
            }
        }
    }

    fun selectPlaySource(index: Int) {
        _state.update { state ->
            val movie = state.detailMovie
            val maxIndex = (movie?.playSources?.lastIndex ?: 0).coerceAtLeast(0)
            val targetIndex = index.coerceIn(0, maxIndex)
            val current = movie?.playSources?.getOrNull(state.selectedSourceIndex)
            val target = movie?.playSources?.getOrNull(targetIndex)
            // 换源必须停在**同一集**，不能回到第 1 集。
            // 旧实现直接写 selectedEpisodeIndex = 0，用户换到另一条线路后要重新
            // 拖动到原来那一集，正是「剧集切换不跟随」的直接原因。
            val currentEpisode = current?.episodes?.getOrNull(state.selectedEpisodeIndex)
            val preservedEpisodeIndex = if (currentEpisode != null && target != null) {
                correspondingEpisodeIndex(
                    source = target,
                    title = currentEpisode.title,
                    fallbackIndex = state.selectedEpisodeIndex,
                ) ?: state.selectedEpisodeIndex.coerceIn(0, target.episodes.lastIndex.coerceAtLeast(0))
            } else {
                0
            }
            state.copy(
                selectedSourceIndex = targetIndex,
                selectedEpisodeIndex = preservedEpisodeIndex.coerceIn(
                    0,
                    (target?.episodes?.lastIndex ?: 0).coerceAtLeast(0),
                ),
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
                        // 备用线路补齐时新片长可能更短：按历史时长 98% 钳制，
                        // 避免 seek 紧贴片尾直接触发「自动跳下一集」。
                        playerStartPositionMs = resolveResumePosition(
                            savedPositionMs = item.positionMs,
                            playerPositionMs = 0L,
                            durationMs = item.durationMs,
                            isPrepared = false,
                            sourceChanged = true,
                        ).positionMs,
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
            val now = System.currentTimeMillis()
            runCatching { playbackHealthRepository.recordSuccess(key, now) }
                .onSuccess { snapshot ->
                    _state.update { it.copy(playbackHealth = snapshot) }
                }
            val evidence = com.tvbox.app.domain.PlaybackEvidence(
                atMs = now,
                source = key,
                event = com.tvbox.app.domain.PlaybackEvidenceEvent.PlaybackStarted,
                detail = "本次播放成功",
            )
            runCatching { playbackHealthRepository.recordEvidence(evidence) }
                .onSuccess { list ->
                    _state.update { it.copy(playbackEvidence = list) }
                }
        }
    }

    private var lastManualLiveSwitchMs = 0L

    /** 左右切台节流：丢弃 250ms 内的重复切台，避免快速连按连续重连多频道。 */
    private fun allowManualLiveSwitch(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastManualLiveSwitchMs < 250L) return false
        lastManualLiveSwitchMs = now
        return true
    }

    fun playNextLiveChannel() {
        if (!allowManualLiveSwitch()) return
        lastExhaustedChannelNumber = null
        autoSwitchThrottle = resetAutoSwitchThrottle()
        _state.update { state ->
            val channels = state.live.channels
            if (channels.isEmpty()) return@update state
            val nextIndex = (state.live.channelIndex + 1) % channels.size
            val channel = channels[nextIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            TLog.i(VM_LIVE_LOG_TAG) { "【切换频道】next channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}" }
            state.copy(
                live = state.live.copy(
                    channelIndex = nextIndex,
                    lineIndex = targetLineIndex,
                ),
            )
        }
    }

    fun playPreviousLiveChannel() {
        if (!allowManualLiveSwitch()) return
        lastExhaustedChannelNumber = null
        autoSwitchThrottle = resetAutoSwitchThrottle()
        _state.update { state ->
            val channels = state.live.channels
            if (channels.isEmpty()) return@update state
            val previousIndex = if (state.live.channelIndex <= 0) channels.lastIndex else state.live.channelIndex - 1
            val channel = channels[previousIndex]
            val targetLineIndex = state.rememberedLineIndexOf(channel)
            TLog.i(VM_LIVE_LOG_TAG) { "【切换频道】previous channel=${channel.groupName}/${channel.name}, lineIndex=$targetLineIndex, lines=${channel.lines.size}" }
            state.copy(
                live = state.live.copy(
                    channelIndex = previousIndex,
                    lineIndex = targetLineIndex,
                ),
            )
        }
    }

    /** 关闭首页提示条（如热播/详情失败提示），避免状态变化后仍长期残留。 */
    fun dismissHomeNotice() {
        _state.update { it.copy(homeNotice = null) }
    }

    /** 更新电视直播 EPG 窄卡节目条数。 */
    fun updateEpgCardRows(rows: Int) {
        val settings = _state.value.appSettings.copy(
            epgCardRows = QuantitySettings.EpgCardRows.clamp(rows),
        )
        commitSettings(settings)
    }

    /** 更新数字选台浮层候选行数。 */
    fun updateLiveNumberMatchRows(rows: Int) {
        val settings = _state.value.appSettings.copy(
            liveNumberMatchRows = QuantitySettings.LiveNumberMatchRows.clamp(rows),
        )
        commitSettings(settings)
    }

    /** 更新数字选台前缀匹配上限。 */
    fun updateLiveNumberSearchLimit(limit: Int) {
        val settings = _state.value.appSettings.copy(
            liveNumberSearchLimit = QuantitySettings.LiveNumberSearchLimit.clamp(limit),
        )
        commitSettings(settings)
    }

    /** P1-08：更新数字选台静默自动提交超时（毫秒）；0=手动确认。 */
    fun updateLiveNumberAutoCommitMs(ms: Long) {
        val settings = _state.value.appSettings.copy(
            liveNumberAutoCommitMs = DirectTuneTimeout.clamp(ms),
        )
        commitSettings(settings)
    }

    /** 更新手机直播页「相关频道」数量。 */
    fun updateRelatedChannelCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            relatedChannelCount = QuantitySettings.RelatedChannelCount.clamp(count),
        )
        commitSettings(settings)
    }

    /** 更新搜索热词条数；下次刷新热词生效。 */
    fun updateHotWordCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            hotWordCount = QuantitySettings.HotWordCount.clamp(count),
        )
        commitSettings(settings)
    }

    /** 更新拼音首字母扩展候选数。 */
    fun updateSearchSuggestionCount(count: Int) {
        val settings = _state.value.appSettings.copy(
            searchSuggestionCount = QuantitySettings.SearchSuggestionCount.clamp(count),
        )
        commitSettings(settings)
    }

    /** 更新豆瓣热播每页条数；下次翻页生效。 */
    fun updateDoubanHotPageSize(size: Int) {
        val settings = _state.value.appSettings.copy(
            doubanHotPageSize = QuantitySettings.DoubanHotPageSize.clamp(size),
        )
        commitSettings(settings)
    }

    /** 更新热播点击时尝试的视频源数量。 */
    fun updateDoubanHotFallbackLines(count: Int) {
        val settings = _state.value.appSettings.copy(
            doubanHotFallbackLines = QuantitySettings.DoubanHotFallbackLines.clamp(count),
        )
        commitSettings(settings)
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
                historyItems = it.historyItems.take(
                    com.tvbox.app.domain.MemoryGovernor.effectivePosterLimit(
                        settings.historyMaxItems,
                        currentMemoryTier(),
                    ),
                ),
            )
        }
    }

    /** 更新多源搜索/详情并发数。 */
    fun updateMaxParallelSourceRequests(count: Int) {
        val value = QuantitySettings.MaxParallelSourceRequests.clamp(count)
        repository.setMaxParallelSourceRequests(value)
        val settings = _state.value.appSettings.copy(maxParallelSourceRequests = value)
        commitSettings(settings)
    }

    /** #277 更新直播切台横幅时长；0 为按设备模式自动决定。 */
    fun updateLiveInfoBannerDurationMs(ms: Long) {
        val value = LiveInfoBannerDuration.clamp(ms)
        val settings = _state.value.appSettings.copy(liveInfoBannerDurationMs = value)
        commitSettings(settings)
    }

    /** #94 更新单源超时并立即推给仓库，下一次搜索/详情按新预算执行。 */
    fun updatePerSourceTimeoutMs(ms: Long) {
        val value = PerSourceTimeout.clamp(ms)
        repository.setPerSourceTimeoutMs(value)
        val settings = _state.value.appSettings.copy(perSourceTimeoutMs = value)
        commitSettings(settings)
    }

    /** #318 更新历史隐私模式；开启后仅阻止新记录写入，不清空已有历史。 */
    fun updateHistoryPrivacyMode(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(historyPrivacyMode = enabled)
        commitSettings(settings)
    }

    /** S1-06 更新观看历史记录策略：完整 / 仅片名 / 关闭。 */
    fun updateHistoryPolicy(policy: HistoryPolicy) {
        val settings = _state.value.appSettings.copy(historyPolicy = policy)
        commitSettings(settings)
    }

    /** S1-06 更新搜索历史存活天数（0=永不清理，1/7/30 为可选档位）。 */
    fun updateSearchHistoryTtlDays(days: Int) {
        val clamped = days.coerceIn(0, 365)
        val settings = _state.value.appSettings.copy(searchHistoryTtlDays = clamped)
        commitSettings(settings)
    }

    /** S1-06 切换访客模式：关闭所有历史写入（观看 + 搜索），不持久化至跨会话存储。 */
    fun toggleGuestMode() {
        val settings = _state.value.appSettings.copy(guestModeActive = !_state.value.appSettings.guestModeActive)
        commitSettings(settings)
        searchHistory.isVisitorMode = settings.guestModeActive
    }

    /** #54/#266 更新硬解设备白名单（逗号分隔的 Build.MODEL 前缀）。 */
    fun updateDecoderDeviceWhitelist(value: String) {
        val settings = _state.value.appSettings.copy(decoderDeviceWhitelist = value)
        commitSettings(settings)
    }

    /** 更新自定义 User-Agent：立即推给全局 UA 持有点，下一次请求即生效。 */
    fun updateCustomUserAgent(value: String) {
        val sanitized = UserAgentSetting.sanitize(value)
        com.tvbox.app.data.network.GlobalUserAgent.value = UserAgentSetting.resolve(sanitized)
        val settings = _state.value.appSettings.copy(customUserAgent = sanitized)
        commitSettings(settings)
    }

    /** 虎牙/斗鱼第三方代理解析开关：开启后存在候选时优先用第三方地址加载直播。 */
    fun updatePlatformThirdPartyProxyEnabled(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(platformThirdPartyProxyEnabled = enabled)
        commitSettings(settings)
    }

    /** 第三方代理地址模板（每行一条，{room} 占位房间号、{site} 占位站点）。 */
    fun updatePlatformThirdPartyProxyTemplates(value: String) {
        val settings = _state.value.appSettings.copy(platformThirdPartyProxyTemplates = value)
        commitSettings(settings)
    }

    /** 更新视频源健康管理开关；打开后立即触发一次后台体检，不必等下一个周期。 */
    fun updateSourceHealthCheckEnabled(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(sourceHealthCheckEnabled = enabled)
        commitSettings(settings)
        if (enabled) {
            viewModelScope.launch { runBackgroundSourceHealthCheck() }
        }
    }

    /**
     * T2-18：源体检卡「立即体检」：不管周期性开关是否关闭，马上跑一轮静默后台体检
     * （仍遵守探活冷却与「用户正在手动测速则跳过」的既有约束）。
     */
    fun runSourceHealthCheckNow() {
        viewModelScope.launch { runBackgroundSourceHealthCheck(force = true) }
    }

    /** 源失败熔断开关：连续超时的源自动休眠，避免反复打到坏源拖慢首屏。 */
    fun updateSourceCircuitBreakerEnabled(enabled: Boolean) {
        val settings = _state.value.appSettings.copy(sourceCircuitBreakerEnabled = enabled)
        commitSettings(settings)
        repository.setSourceCircuitBreakerEnabled(enabled)
    }

    /** 画面亮度覆盖（0=跟随系统，1..100=百分比）。 */
    fun updatePlayerBrightness(value: Int) {
        val clamped = PlayerBrightnessSetting.clamp(value)
        if (_state.value.appSettings.playerBrightness == clamped) return
        commitSettings(_state.value.appSettings.copy(playerBrightness = clamped))
    }

    /** 片尾自动进入下一集开关。 */
    fun updateAutoNextEpisodeEnabled(enabled: Boolean) {
        if (_state.value.appSettings.autoNextEpisodeEnabled == enabled) return
        commitSettings(_state.value.appSettings.copy(autoNextEpisodeEnabled = enabled))
    }

    /** 手机端播放器手势开关。 */
    fun updatePlayerGesturesEnabled(enabled: Boolean) {
        if (_state.value.appSettings.playerGesturesEnabled == enabled) return
        commitSettings(_state.value.appSettings.copy(playerGesturesEnabled = enabled))
    }

    /** 快进/快退步长（秒）。 */
    fun updateFastSeekStepSeconds(value: Int) {
        val clamped = FastSeekSetting.clamp(value)
        if (_state.value.appSettings.fastSeekStepSeconds == clamped) return
        commitSettings(_state.value.appSettings.copy(fastSeekStepSeconds = clamped))
    }

    /** 历史页布局模式。 */
    fun updateHistoryLayout(layout: HistoryLayout) {
        if (_state.value.appSettings.historyLayout == layout) return
        commitSettings(_state.value.appSettings.copy(historyLayout = layout))
    }

    /** 历史封面更新角标开关。 */
    fun updateHistoryCoverBadgeEnabled(enabled: Boolean) {
        if (_state.value.appSettings.historyCoverBadgeEnabled == enabled) return
        commitSettings(_state.value.appSettings.copy(historyCoverBadgeEnabled = enabled))
    }

    /** 崩溃日志只本地开关。 */
    fun updateCrashLogLocalOnly(enabled: Boolean) {
        if (_state.value.appSettings.crashLogLocalOnly == enabled) return
        commitSettings(_state.value.appSettings.copy(crashLogLocalOnly = enabled))
    }

    /**
     * 手机端设置中心 v2 的通用偏好更新入口：立即持久化，不引入草稿态。
     * 具体字段的收敛（区间/枚举）由设置 UI 在 lambda 内完成，ViewModel 只负责比较与落盘。
     */
    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        val current = _state.value.appSettings
        val next = transform(current)
        if (next != current) commitSettings(next)
    }

    /** 更新 UA 的单个作用域字段；输入统一经过 Header 注入清洗。 */
    fun updateScopedUserAgent(scope: String, value: String) {
        val sanitized = UserAgentSetting.sanitize(value)
        updateAppSettings { settings ->
            when (scope) {
                "api" -> settings.copy(uaForApi = sanitized)
                "spider" -> settings.copy(uaForSpider = sanitized)
                "live" -> settings.copy(uaForLive = sanitized)
                "epg" -> settings.copy(uaForEpg = sanitized)
                "playback" -> settings.copy(uaForPlayback = sanitized)
                "webview" -> settings.copy(uaForWebView = sanitized)
                else -> settings
            }
        }
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

    fun updateFontScale(fontScale: TvFontScale) {
        if (_state.value.appSettings.fontScale == fontScale) return
        val settings = _state.value.appSettings.copy(fontScale = fontScale)
        commitSettings(settings)
    }

    /** 更新主题强调色方案（任务7）：切换全局 TvColors token 与 TV 焦点描边。 */
    fun updateThemeAccent(themeAccent: ThemeAccent) {
        if (_state.value.appSettings.themeAccent == themeAccent) return
        val settings = _state.value.appSettings.copy(themeAccent = themeAccent)
        commitSettings(settings)
    }

    /**
     * 手机端设置页「重置本组」：把指定分组内可直接编辑的 AppSettings 字段恢复为默认值。
     * categoryName 对应 SettingsMobileScreen 的 MobileSettingCategory.name；
     * 仅重置该分组展示的标量选项，不触碰源列表/历史等独立管理的数据。
     */
    fun resetCategorySettings(categoryName: String) {
        val current = _state.value.appSettings
        val d = AppSettings()
        val settings = when (categoryName) {
            "Playback" -> current.copy(
                playerSpeed = d.playerSpeed,
                playerBrightness = d.playerBrightness,
                autoNextEpisodeEnabled = d.autoNextEpisodeEnabled,
                playerGesturesEnabled = d.playerGesturesEnabled,
                decoderStrategy = d.decoderStrategy,
                fastSeekStepSeconds = d.fastSeekStepSeconds,
                playbackAgentAutoSwitchEnabled = d.playbackAgentAutoSwitchEnabled,
                videoScaleMode = d.videoScaleMode,
                decoderDeviceWhitelist = d.decoderDeviceWhitelist,
                liveInfoBannerDurationMs = d.liveInfoBannerDurationMs,
                episodeSortReverse = d.episodeSortReverse,
                introSkipSeconds = d.introSkipSeconds,
                outroSkipSeconds = d.outroSkipSeconds,
                autoNextCountdownSeconds = d.autoNextCountdownSeconds,
                firstPacketTimeoutMs = d.firstPacketTimeoutMs,
                reconnectAttempts = d.reconnectAttempts,
                bufferTargetMs = d.bufferTargetMs,
                backgroundPlaybackMode = d.backgroundPlaybackMode,
                audioOutputMode = d.audioOutputMode,
                gestureLeftBrightnessEnabled = d.gestureLeftBrightnessEnabled,
                gestureRightVolumeEnabled = d.gestureRightVolumeEnabled,
                gestureHorizontalSeekEnabled = d.gestureHorizontalSeekEnabled,
                gestureDoubleTapSideEnabled = d.gestureDoubleTapSideEnabled,
                gestureDoubleTapCenterEnabled = d.gestureDoubleTapCenterEnabled,
                gestureLongPressSpeedEnabled = d.gestureLongPressSpeedEnabled,
                gesturePinchZoomEnabled = d.gesturePinchZoomEnabled,
                gestureTopSwipeBackEnabled = d.gestureTopSwipeBackEnabled,
                gestureSensitivity = d.gestureSensitivity,
                gestureEdgeGuardEnabled = d.gestureEdgeGuardEnabled,
                gestureLockEnabled = d.gestureLockEnabled,
                playbackManagerPreferHealthy = d.playbackManagerPreferHealthy,
                playbackManagerManualButton = d.playbackManagerManualButton,
                seekCoolingMs = d.seekCoolingMs,
                manualSourceLockMinutes = d.manualSourceLockMinutes,
            )
            "NetworkSources" -> current.copy(
                sourceHealthCheckEnabled = d.sourceHealthCheckEnabled,
                customUserAgent = d.customUserAgent,
                perSourceTimeoutMs = d.perSourceTimeoutMs,
                maxParallelSourceRequests = d.maxParallelSourceRequests,
                uaScopeMode = d.uaScopeMode,
                uaForApi = d.uaForApi,
                uaForSpider = d.uaForSpider,
                uaForLive = d.uaForLive,
                uaForEpg = d.uaForEpg,
                uaForPlayback = d.uaForPlayback,
                uaForWebView = d.uaForWebView,
                uaExportRedacted = d.uaExportRedacted,
                epgCacheHours = d.epgCacheHours,
                epgTimezone = d.epgTimezone,
                epgOutOfRangePolicy = d.epgOutOfRangePolicy,
                platformDefaultQuality = d.platformDefaultQuality,
                flvProxyMode = d.flvProxyMode,
                platformThirdPartyProxyEnabled = d.platformThirdPartyProxyEnabled,
                platformThirdPartyProxyTemplates = d.platformThirdPartyProxyTemplates,
                lanResolverEnabled = d.lanResolverEnabled,
                lanResolverConfirmed = d.lanResolverConfirmed,
            )
            "Interface" -> current.copy(
                deviceMode = d.deviceMode,
                fontScale = d.fontScale,
                homeContinueWatchingEnabled = d.homeContinueWatchingEnabled,
                checkUpdatesOnStartup = d.checkUpdatesOnStartup,
                playerLogEnabled = d.playerLogEnabled,
                historyLayout = d.historyLayout,
                historyCoverBadgeEnabled = d.historyCoverBadgeEnabled,
                continueWatchingMaxItems = d.continueWatchingMaxItems,
                historyAutoCleanupEnabled = d.historyAutoCleanupEnabled,
                resumeConflictPolicy = d.resumeConflictPolicy,
                aiSearchEnabled = d.aiSearchEnabled,
                voiceSearchEnabled = d.voiceSearchEnabled,
                aiTextRedactionEnabled = d.aiTextRedactionEnabled,
                aiOnlyTitleYearEnabled = d.aiOnlyTitleYearEnabled,
                aiMissBehavior = d.aiMissBehavior,
                aiResultCacheMinutes = d.aiResultCacheMinutes,
            )
            "SystemData" -> current.copy(
                updateChannel = d.updateChannel,
                crashLogLocalOnly = d.crashLogLocalOnly,
                historyPrivacyMode = d.historyPrivacyMode,
                historyMaxItems = d.historyMaxItems,
                hotWordCount = d.hotWordCount,
                searchSuggestionCount = d.searchSuggestionCount,
                doubanHotPageSize = d.doubanHotPageSize,
                doubanHotFallbackLines = d.doubanHotFallbackLines,
                updateWifiOnly = d.updateWifiOnly,
                updateExportBeforeInstall = d.updateExportBeforeInstall,
                updateRollbackHours = d.updateRollbackHours,
                updateStrongReminderOnly = d.updateStrongReminderOnly,
            )
            else -> return
        }
        if (settings != current) commitSettings(settings)
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
        autoSwitchThrottle = resetAutoSwitchThrottle()
        liveLineStartAtMs = System.currentTimeMillis()
        val newIndex = (state.live.lineIndex + 1) % lines.size
        TLog.i(VM_LIVE_LOG_TAG) { "【手动切换线路】next channel=${channel.name}, from=${state.live.lineIndex}, to=$newIndex, url=${logSafeUrl(lines[newIndex].url)}" }
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
        autoSwitchThrottle = resetAutoSwitchThrottle()
        liveLineStartAtMs = System.currentTimeMillis()
        val previousIndex = if (state.live.lineIndex <= 0) lines.lastIndex else state.live.lineIndex - 1
        TLog.i(VM_LIVE_LOG_TAG) { "【手动切换线路】previous channel=${channel.name}, from=${state.live.lineIndex}, to=$previousIndex, url=${logSafeUrl(lines[previousIndex].url)}" }
        _state.update {
            it.copy(live = it.live.copy(lineIndex = previousIndex))
        }
        liveLinePrefs?.saveLineIndex(channel.key(), previousIndex)
        return true
    }

    /**
     * 线路测速归属：订阅 + 频道下标 + 分组/频道名。只切线路（lineIndex）不产生新身份，
     * 频道/订阅一变，旧协程的结果就不能再写进当前频道的延迟表。
     */
    private fun currentLiveLineTestIdentity(state: TvBoxUiState): String? {
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return null
        return "${state.live.selectedSourceIndex}:${state.live.channelIndex}:${channel.groupName}#${channel.name}"
    }

    /**
     * 对当前频道的所有线路发起首包测速（手动触发），结果写入 [LiveUiState.lineLatencies]。
     *
     * 在线路浮层展开时调用：让用户按实际延迟挑线路，而不是逐条点播试错。
     * 置 [LiveUiState.lineTesting] 让 UI 显示「测速中…」，并在进行中忽略重复点击。
     */
    fun testLiveChannelLines() {
        if (_state.value.live.lineTesting) return
        _state.update { it.copy(live = it.live.copy(lineTesting = true)) }
        startLiveLineTestRound()
    }

    /**
     * 测速核心：并发测量当前频道所有线路首包延迟，逐条发布不可变快照。
     * 手动（[testLiveChannelLines]）与浮层自动重测共用；本函数不改 [LiveUiState.lineTesting]，
     * 由调用方决定是否显示「测速中…」（自动重测静默刷新，不抢占手动按钮状态）。
     */
    fun startLiveLineTestRound() {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return
        val lines = channel.lines
        if (lines.isEmpty()) return
        val identity = currentLiveLineTestIdentity(state) ?: return
        // 动态重测：保留上一轮延迟数值，本轮逐条覆盖（不清空），UI 不会闪回「测速中」。
        liveLineTestJob?.cancel()
        val generation = ++liveLineTestGeneration
        liveLineTestIdentity = identity
        liveLineTestJob = viewModelScope.launch {
            TLog.i(VM_LIVE_LOG_TAG) { "【线路测速】开始 channel=${channel.name}, lines=${lines.size}" }
            // async 并发，但写入统一加锁并发布不可变快照：避免多个协程同时写 mutableMap
            // 让 UI 读到半成品，同时保留「单条完成即写入」的浮层体验与原顺序。
            // 重测轮次：先把上一轮结果继承进来，本轮逐条覆盖，未完成的条目在 UI 上仍保留旧值。
            val results = LinkedHashMap<String, Long?>(state.live.lineLatencies)
            val resultsMutex = Mutex()
            fun publish(snapshot: Map<String, Long?>) {
                // 切台/换源后的迟到结果一律丢弃，不覆盖新频道数据。
                if (liveLineTestGeneration != generation || liveLineTestIdentity != identity) return
                _state.update { current ->
                    if (liveLineTestGeneration != generation || liveLineTestIdentity != identity ||
                        currentLiveLineTestIdentity(current) != identity
                    ) current
                    else current.copy(live = current.live.copy(lineLatencies = snapshot))
                }
            }
            try {
                coroutineScope {
                    lines.map { line ->
                        async {
                            val (latency, confidence) = try {
                                // 测速请求与播放共用同一 UA 口径：线路头部 > 用户自定义 UA。
                                probeLiveLineLatencyWithConfidence(
                                    line.url,
                                    line.headers.withFallbackUserAgent(
                                        com.tvbox.app.domain.UserAgentSetting.resolve(
                                            _state.value.appSettings.customUserAgent,
                                        ),
                                    ),
                                )
                            } catch (cancelled: CancellationException) {
                                // 切台/离页取消必须立即透传，否则迟到的 null 会污染新频道结果。
                                throw cancelled
                            } catch (_: Throwable) {
                                null to com.tvbox.app.domain.DurationConfidence.NONE
                            }
                            val snapshot = resultsMutex.withLock {
                                results[line.url] = latency
                                results.toMap()
                            }
                            publish(snapshot)
                            // T1-04：将时长可信度写入健康记录（幂等，不影响 UI）。
                            // 必须在测速 job 的作用域内并带 generation 校验，否则切台后的迟到结果
                            // 会脱离 liveLineTestJob 取消，污染新频道的线路质量徽章。
                            if (latency != null && liveLineTestGeneration == generation &&
                                liveLineTestIdentity == identity
                            ) {
                                val key = "live_${channel.key()}|${line.url}"
                                runCatching {
                                    playbackHealthRepository.recordSuccessWithConfidence(
                                        key = key,
                                        latencyMs = latency,
                                        durationConfidence = confidence,
                                        nowMs = System.currentTimeMillis(),
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                val snapshot = resultsMutex.withLock { results.toMap() }
                TLog.i(VM_LIVE_LOG_TAG) { "【线路测速】完成 channel=${channel.name}, " +
                        snapshot.entries.joinToString { (url, ms) -> "${logSafeUrl(url)}=${ms?.let { "${it}ms" } ?: "超时"}" } }
                if (liveLineTestGeneration == generation && liveLineTestIdentity == identity) {
                    _state.update { current ->
                        if (currentLiveLineTestIdentity(current) != identity) current
                        else current.copy(live = current.live.copy(lineLatencies = snapshot, lineTesting = false))
                    }
                }
            } finally {
                // 只清理本轮代次，避免新测速刚启动就被旧协程的 finally 误清。
                if (liveLineTestGeneration == generation && liveLineTestIdentity == identity) {
                    liveLineTestJob = null
                    liveLineTestIdentity = null
                }
            }
        }
    }

    /** 线路浮层关闭/切台即停：取消未完成的测速，并清掉属于旧频道的结果。 */
    fun cancelLiveChannelLineTest() {
        liveLineTestGeneration++
        liveLineTestJob?.cancel()
        liveLineTestJob = null
        liveLineTestIdentity = null
        val live = _state.value.live
        if (live.lineTesting || live.lineLatencies.isNotEmpty()) {
            _state.update { it.copy(live = it.live.copy(lineTesting = false, lineLatencies = emptyMap())) }
        }
    }

    fun selectLiveLine(lineIndex: Int) {
        val state = _state.value
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return
        if (lineIndex !in channel.lines.indices) return
        if (lineIndex == state.live.lineIndex) return
        liveStallRetries = 0
        autoSwitchThrottle = resetAutoSwitchThrottle()
        TLog.i(VM_LIVE_LOG_TAG) { "【手动切换线路】select channel=${channel.name}, from=${state.live.lineIndex}, to=$lineIndex, url=${logSafeUrl(channel.lines[lineIndex].url)}" }
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
                TLog.w(VM_LIVE_LOG_TAG) { "【自动容错】reason=$reason 第 $liveStallRetries/$LIVE_STALL_RETRY_BEFORE_SWITCH 次原地重连当前线路 ${state.live.lineIndex + 1}" }
                _state.update { it.copy(live = it.live.copy(reconnectNonce = it.live.reconnectNonce + 1)) }
                return true
            }
        }
        liveStallRetries = 0
        // 抖动抑制：播放器连续报错时，退避窗口内不再连切线路，只原地重连；
        // 仍返回 true 让播放页继续尝试，避免误触发「所有线路不可用」的阻断态。
        val autoSwitchGate = gateAutoSwitch(autoSwitchThrottle, System.currentTimeMillis())
        if (autoSwitchGate is AutoSwitchGate.Defer) {
            TLog.i(VM_LIVE_LOG_TAG) { "【自动容错】退避窗口内暂不切线路，${autoSwitchGate.retryAfterMs}ms 后可再切，先原地重连" }
            _state.update { it.copy(live = it.live.copy(reconnectNonce = it.live.reconnectNonce + 1)) }
            return true
        }
        autoSwitchThrottle = (autoSwitchGate as AutoSwitchGate.Allow).nextState
        // 域名评分：本次失败先记到当前线路的域名上，下次进台排序时会被压低。
        recordLiveLineFailure(state)
        val nextIndex = state.live.lineIndex + 1
        TLog.w(VM_LIVE_LOG_TAG) { "【自动容错】reason=$reason channel=${channel.name}, currentLine=${state.live.lineIndex}, nextLine=$nextIndex, totalLines=${lines.size}" }
        if (nextIndex !in lines.indices) {
            // 所有线路均已尝试过，停止循环并提示用户。
            val channelNumber = channel.number
            if (lastExhaustedChannelNumber != channelNumber) {
                lastExhaustedChannelNumber = channelNumber
                _state.update {
                    it.copy(live = it.live.copy(notice = "频道 ${channelNumber} 所有线路均不可用，请换台或检查直播源"))
                }
            }
            TLog.w(VM_LIVE_LOG_TAG) { "【自动容错】频道 ${channel.name} 所有线路已用尽，停止切换" }
            return false
        }
        _state.update { it.copy(live = it.live.copy(lineIndex = nextIndex)) }
        if (reason == LiveLineFallbackReason.Error) {
            liveLinePrefs?.saveLineIndex(channel.key(), nextIndex)
        }
        TLog.i(VM_LIVE_LOG_TAG) { "【自动切换成功】切换到线路 ${nextIndex + 1}/${lines.size}, url=${logSafeUrl(lines[nextIndex].url)}" }
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

    /** 通知权限被拒：记录一次性提示，UI 据此给出「重试授权」或「去设置」入口。 */
    fun reportNotificationPermissionDenied(permanent: Boolean) {
        _state.update { it.copy(notificationPermissionDenial = NotificationPermissionDenial(permanent)) }
    }

    /** 通知权限提示已展示/关闭后清空，避免重复弹出。 */
    fun consumeNotificationPermissionDenial() {
        if (_state.value.notificationPermissionDenial == null) return
        _state.update { it.copy(notificationPermissionDenial = null) }
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
            TLog.d(VM_LIVE_LOG_TAG) { "【直播就绪】画面就绪，清零卡顿重试计数（原 $liveStallRetries）" }
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
        // 线路级质量：以「发起选线 → 画面就绪」的耗时作为起播速度，供下次进台择优。
        val channel = state.live.channels.getOrNull(state.live.channelIndex)
        val lineIndex = state.live.lineIndex
        val startedAt = liveLineStartAtMs
        liveLineStartAtMs = null
        if (channel != null && startedAt != null && url != null) {
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            liveLineQualityRecords?.record(lineQualityKey("${channel.groupName}#${channel.name}", lineIndex), elapsed, true)
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
        rememberPlatformLiveMemory(roomId = room.id)
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

    /**
     * 播放页「节目列表」选房间：只换 roomIndex 并重新解析。
     * 不能复用 [openPlatformLiveRoom]——它会把 playerReturnDestination 改写成当前的 Player，
     * 导致之后按返回键退不出播放页。
     */
    fun switchPlatformLiveRoom(index: Int) {
        val current = _state.value
        val rooms = current.platformLive.rooms
        if (index !in rooms.indices || index == current.platformLive.roomIndex) return
        platformLiveResolveJob?.cancel()
        platformLiveRecoveryInProgress = false
        platformLiveSameLineRetries = 0
        platformLiveRecoveryWindowAttempts = 0
        rememberPlatformLiveMemory(roomId = rooms[index].id)
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                roomIndex = index,
                stream = null,
                error = null,
                notice = null,
                resolving = false,
            ))
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
        TLog.d(VM_LIVE_LOG_TAG) { "【两级交互】进入分类 index=$safeIndex, name=${groups.getOrNull(safeIndex - 1) ?: "全部"}" }
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
        val legacyKey = legacyLiveFavoriteKey(channel.groupName, channel.name)
        val repository = liveFavoritesRepository
        if (repository == null) {
            _state.update { state ->
                val favorites = state.live.favoriteKeys.toMutableSet()
                val removedNew = favorites.remove(key)
                val removedLegacy = favorites.remove(legacyKey)
                if (!removedNew && !removedLegacy) favorites += key
                state.copy(live = state.live.copy(favoriteKeys = favorites))
            }
            return
        }
        viewModelScope.launch {
            liveFavoritesMutex.withLock {
                runCatching { repository.toggleFavorite(channel.groupName, channel.name) }
                    .onSuccess { keys -> _state.update { it.copy(live = it.live.copy(favoriteKeys = keys)) } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        TLog.w(VM_LIVE_LOG_TAG, error) { "【收藏】保存失败" }
                        _state.update { it.copy(live = it.live.copy(notice = "收藏保存失败，请重试")) }
                    }
            }
        }
    }

    /** 某频道是否已收藏（兼容新旧收藏键）。 */
    fun isLiveFavorite(channel: LiveChannel): Boolean =
        containsLiveFavorite(_state.value.live.favoriteKeys, channel.groupName, channel.name)

    /** 某频道的完整节目单（已按开始时间升序）；未配置/未命中返回空列表。 */
    fun liveEpgPrograms(channel: LiveChannel): List<EpgProgram> =
        findEpgPrograms(
            map = _state.value.live.epgByChannel,
            groupName = channel.groupName,
            channelName = channel.name,
            // 台标 URL 不可能是 XMLTV 频道 id，改传源里声明的 tvg-id。
            logoId = channel.tvgId,
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

    /**
     * S3-19：节目单是否已过期（仍在渲染缓存旧数据）。
     * UI 在节目名上叠加「~」角标提示；过期时 [loadLiveEpg] 会静默后台重拉，成功后替换。
     */
    fun liveEpgStale(): Boolean {
        val live = _state.value.live
        if (live.epgByChannel.isEmpty()) return false
        return EpgCachePolicy.renderState(
            EpgData(live.epgByChannel, live.epgFetchedAtMs),
            System.currentTimeMillis(),
        ) == EpgRenderState.STALE
    }

    /** 保存/清除节目单接口地址：立即持久化，并清缓存后重新拉取。 */
    fun updateEpgUrl(url: String) {
        val trimmed = url.trim()
        if (_state.value.appSettings.epgUrl == trimmed) return
        val settings = _state.value.appSettings.copy(epgUrl = trimmed)
        _state.update {
            it.copy(
                appSettings = settings,
                // S3-19：EPG 地址变了，旧缓存的时间戳一并失效。
                live = it.live.copy(epgByChannel = emptyMap(), epgLoaded = false, epgFetchedAtMs = 0L),
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
        // 切订阅后旧源的容错/换台记忆不应继续生效，否则新源同号频道会被误判为已耗尽。
        liveStallRetries = 0
        lastExhaustedChannelNumber = null
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
        // 强制重新解析意味着开启新一轮候选轮换，旧失败记录不再适用。
        platformLiveTriedCandidates.clear()
        platformLiveRotationKey = null
        platformLiveManualFallbackIndex = null
        TLog.w(VM_LIVE_LOG_TAG) { "【平台重新解析】forceRefresh=true" }
        resolvePlatformLiveRoom(forceRefresh = true)
    }

    fun switchPlatformLiveCandidate(index: Int) {
        val stream = _state.value.platformLive.stream ?: return
        val candidate = stream.candidates.getOrNull(index) ?: return
        if (platformLiveRecoveryInProgress) {
            // 用户明确的手动选择优先于自动恢复，不再静默丢弃。
            TLog.w(VM_LIVE_LOG_TAG) { "【平台手动切换候选】自动恢复进行中，仍以用户选择为准切换 to=$index" }
        }
        TLog.i(VM_LIVE_LOG_TAG) { "【平台手动切换候选】from=${stream.safeActiveCandidateIndex}, to=$index, cdn=${candidate.cdn}" }
        // 用户手动指定线路后，重置自动恢复计数与已失败候选，按用户选择开启新一轮轮换。
        platformLiveRecoveryInProgress = false
        platformLiveTriedCandidates.clear()
        platformLiveRotationKey = null
        platformLiveSameLineRetries = 0
        platformLiveRecoveryWindowAttempts = 0
        platformLiveRecoveryWindowStartMs = 0L
        // #60：记录切换前的原线路，新线路失败时回退到该线路并提示。
        val previousCandidate = stream.safeActiveCandidateIndex
        platformLiveManualFallbackIndex = if (previousCandidate != index) previousCandidate else null
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

    /**
     * 线路浮层「测速」按钮：并发测全部候选线路（含第三方代理候选）的首字节延迟，
     * 每条测完立即回写 UI，逐条实时显示，而不是等全部测完才出结果。
     *
     * 只更新延迟、不切换线路、不触发重连；第三方候选原先 latencyMs=-1 不显示，
     * 测速后与官方候选一样显示实时延迟。
     */
    fun remeasurePlatformLiveLatencies() {
        val state = _state.value
        val stream = state.platformLive.stream ?: return
        val room = state.platformLive.rooms.getOrNull(state.platformLive.roomIndex) ?: return
        val candidates = stream.candidates
        if (candidates.isEmpty()) return
        if (platformLiveLatencyMeasuring) return  // 防重入
        platformLiveLatencyMeasuring = true
        _state.update { it.copy(platformLive = it.platformLive.copy(latencyMeasuring = true)) }
        viewModelScope.launch {
            val baseHeaders = stream.headers
            coroutineScope {
                candidates.forEachIndexed { index, candidate ->
                    launch(Dispatchers.IO) {
                        val (latency, confidence) = probeLiveLineLatencyWithConfidence(candidate.url, baseHeaders)
                        val latencyInt = latency?.toInt()?.coerceAtLeast(1) ?: -1
                        // 只在房间/线路未变时回写，避免用户切台后旧结果覆盖新流。
                        _state.update { s ->
                            val cur = s.platformLive
                            val curStream = cur.stream ?: return@update s
                            if (cur.roomIndex != state.platformLive.roomIndex ||
                                cur.selectedSite?.id != state.platformLive.selectedSite?.id ||
                                cur.rooms.getOrNull(cur.roomIndex)?.id != room.id
                            ) return@update s
                            val updated = curStream.candidates.mapIndexed { i, c ->
                                if (i == index) c.copy(latencyMs = latencyInt) else c
                            }
                            s.copy(platformLive = cur.copy(
                                stream = curStream.copy(candidates = updated),
                            ))
                        }
                        // T1-04：写入时长可信度到健康记录。
                        if (latency != null) {
                            viewModelScope.launch {
                                runCatching {
                                    playbackHealthRepository.recordSuccessWithConfidence(
                                        key = "platform_${candidate.url}",
                                        latencyMs = latency,
                                        durationConfidence = confidence,
                                        nowMs = System.currentTimeMillis(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            platformLiveLatencyMeasuring = false
            _state.update { it.copy(platformLive = it.platformLive.copy(latencyMeasuring = false)) }
        }
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

    // ── 虎牙解码丢帧看门狗（自动降码率） ──────────────────────────────
    //
    // 真机实测：弱设备（如 p230）解不动蓝光 8M 1080p60，丢帧率 40%+，表现为持续
    // 卡顿 + 画面落后声音的音画不同步。播放器每 10s 上报一次窗口内渲染/丢弃帧增量；
    // 连续 3 个样本丢帧率 ≥30% 即认为解码能力不足，按 8000→2000→550 阶梯降低
    // 码率上限并强制重解析（Go 侧 pickHuyaRate 按「≤上限的最高码率」选流）。
    private var huyaHighDropStreak = 0
    private var huyaLastDowngradeAtMs = 0L

    /**
     * 播放器健康采样回调：[renderedDelta]/[droppedDelta] 为两次采样间的
     * 渲染帧/丢弃帧增量（不一定是虎牙调用，按站点过滤）。
     */
    fun notifyPlatformLiveDecodeHealth(renderedDelta: Long, droppedDelta: Long) {
        val siteId = _state.value.platformLive.selectedSite?.id ?: return
        if (siteId != "huya") return
        val total = renderedDelta + droppedDelta
        if (total <= 0) return
        val dropRatio = droppedDelta.toDouble() / total
        if (dropRatio < HUYA_DOWNGRADE_DROP_RATIO) {
            // 单次达标不算，恢复即清零；避免瞬时卡顿误降档。
            if (huyaHighDropStreak != 0) {
                huyaHighDropStreak = 0
            }
            return
        }
        huyaHighDropStreak++
        val now = System.currentTimeMillis()
        if (huyaHighDropStreak < HUYA_DOWNGRADE_STREAK ||
            now - huyaLastDowngradeAtMs < HUYA_DOWNGRADE_COOLDOWN_MS
        ) {
            TLog.w(VM_LIVE_LOG_TAG) { "【解码降档】丢帧率=${"%.0f".format(dropRatio * 100)}%，streak=$huyaHighDropStreak（等待连续${HUYA_DOWNGRADE_STREAK}个样本）" }
            return
        }
        huyaHighDropStreak = 0
        val currentCap = com.tvbox.app.data.LiveTuningDebugPrefs.loadHuyaMaxBitRate(appContext)
        val ladder = com.tvbox.app.data.LiveTuningDebugPrefs.HUYA_BITRATE_LADDER
        // 当前有效档位：0（不设上限）视同最高档；找阶梯中「严格低于当前」的下一档。
        val currentIndex = ladder.indexOfFirst { it >= (if (currentCap == 0L) Long.MAX_VALUE else currentCap) }
            .let { if (it < 0) 0 else it }
        val nextCap = ladder.getOrNull(currentIndex + 1) ?: run {
            TLog.w(VM_LIVE_LOG_TAG) { "【解码降档】已到最低档，不再降（当前上限=$currentCap）" }
            return
        }
        huyaLastDowngradeAtMs = now
        TLog.w(VM_LIVE_LOG_TAG) { "【解码降档】丢帧率=${"%.0f".format(dropRatio * 100)}% 持续${HUYA_DOWNGRADE_STREAK}个样本，码率上限 $currentCap→$nextCap，触发重解析" }
        com.tvbox.app.data.LiveTuningDebugPrefs.saveHuyaMaxBitRate(appContext, nextCap)
        com.tvbox.app.data.LiveTuningDebugPrefs.applyToBridge(appContext, com.tvbox.app.data.LiveTuningDebugPrefs.Site.HUYA)
        _state.update {
            it.copy(platformLive = it.platformLive.copy(
                notice = "解码跟不上，已自动切换到${com.tvbox.app.data.LiveTuningDebugPrefs.huyaBitRateLabel(nextCap)}画质",
            ))
        }
        reconnectPlatformLiveChannel()
    }

    private companion object {
        /** 单样本丢帧率阈值。 */
        const val HUYA_DOWNGRADE_DROP_RATIO = 0.30
        /** 连续达标样本数（每样本 10s）。 */
        const val HUYA_DOWNGRADE_STREAK = 3
        /** 两次降档最小间隔。 */
        const val HUYA_DOWNGRADE_COOLDOWN_MS = 90_000L
    }

    /**
     * 平台直播画面恢复（READY）后调用：清零同线路重连计数。
     *
     * 虎牙 FLV 每 ~125 秒被服务端正常关闭一次，属于常态而非线路故障。若不清零，
     * 连续两次关流就会把健康房间切到别的 CDN，制造"隔几分钟自动换线路"的现象。
     */
    fun notifyPlatformLivePlaybackReady() {
        if (platformLiveSameLineRetries != 0) {
            TLog.d(VM_LIVE_LOG_TAG) { "【平台恢复】画面就绪，清零同线路重连计数（原 $platformLiveSameLineRetries）" }
            platformLiveSameLineRetries = 0
        }
        // #60：画面就绪说明手动切换的线路稳定，清除失败回退记录。
        if (platformLiveManualFallbackIndex != null) {
            TLog.d(VM_LIVE_LOG_TAG) { "【平台恢复】画面就绪，清除手动切换回退记录" }
            platformLiveManualFallbackIndex = null
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
            TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】无当前流，改为重新解析" }
            reconnectPlatformLiveChannel()
            return
        }
        if (platformLiveRecoveryInProgress || state.platformLive.resolving) {
            TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复跳过】恢复进行中或正在解析中" }
            return
        }
        val now = System.currentTimeMillis()
        if (now - platformLiveRecoveryWindowStartMs > PLATFORM_LIVE_RECOVERY_WINDOW_MS) {
            platformLiveRecoveryWindowStartMs = now
            platformLiveRecoveryWindowAttempts = 0
        }
        if (platformLiveRecoveryWindowAttempts >= PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS) {
            TLog.e(VM_LIVE_LOG_TAG) { "【平台恢复熔断】${PLATFORM_LIVE_RECOVERY_WINDOW_MS / 1000}s 内已尝试 $platformLiveRecoveryWindowAttempts 次仍未恢复，停止自动恢复, room=${roomTag()}" }
            _state.update {
                it.copy(platformLive = it.platformLive.copy(error = "该房间线路连续失败，已停止自动重连。可手动切换线路或稍后重试。"))
            }
            return
        }
        platformLiveRecoveryWindowAttempts++
        platformLiveRecoveryInProgress = true
        TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】reason=$reason, attempt=$platformLiveRecoveryWindowAttempts/$PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS, active=${stream.safeActiveCandidateIndex}, total=${stream.candidates.size}, cdn=${stream.activeCandidateOrNull?.cdn.orEmpty()}, protocol=${stream.activeCandidateOrNull?.protocol.orEmpty()}" }
        when (reason) {
            LiveLineFallbackReason.Ended, LiveLineFallbackReason.Stall -> {
                // 服务端正常关流 / 卡死：优先沿用当前候选重新解析（换地址不换 CDN）。
                if (platformLiveSameLineRetries < PLATFORM_LIVE_SAME_LINE_RETRY_MAX) {
                    platformLiveSameLineRetries++
                    TLog.i(VM_LIVE_LOG_TAG) { "【平台恢复】保持候选 ${stream.activeCandidateIndex} 重新解析地址（第 $platformLiveSameLineRetries 次）" }
                    // 任务2/3：退避交给 RecoveryBackoff 策略（按 reason 差异化基线 + 指数 + 抖动）。
                    val delayMs = platformLiveBackoff.nextDelayMs(reason, platformLiveSameLineRetries)
                    schedulePlatformLiveReResolve(delayMs = delayMs)
                    return
                }
                TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】当前候选重连已用尽，改为切换下一条候选" }
                platformLiveSameLineRetries = 0
                rotatePlatformLiveCandidate(stream)
            }
            LiveLineFallbackReason.Error -> {
                val fallback = platformLiveManualFallbackIndex
                val active = stream.safeActiveCandidateIndex
                if (fallback != null && fallback != active) {
                    // #60：手动切换的新线路失败，回退到原线路并提示。
                    platformLiveManualFallbackIndex = null
                    platformLiveSameLineRetries = 0
                    TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】候选 $active 切换失败，回退原线路 $fallback（共 ${stream.candidates.size}）" }
                    _state.update {
                        it.copy(platformLive = it.platformLive.copy(
                            error = null,
                            notice = "线路切换失败，已回退到原线路 ${fallback + 1}/${stream.candidates.size}",
                            stream = stream.withActiveCandidate(fallback)))
                    }
                    return
                }
                platformLiveManualFallbackIndex = null
                platformLiveSameLineRetries = 0
                rotatePlatformLiveCandidate(stream)
            }
        }
    }

    /** 候选轮换身份：房间 + 候选 URL 列表；仅切换 activeIndex 不改变身份。 */
    private fun platformLiveStreamKey(stream: ResolvedPlatformLiveStream): String = buildString {
        append(stream.channelId).append('\u0000')
        stream.candidates.forEach { append(it.url).append('\u0000') }
    }

    /** 切换到下一条未失败的候选 CDN；全部试过才强制重新解析。 */
    private fun rotatePlatformLiveCandidate(stream: ResolvedPlatformLiveStream) {
        // 重新解析/换房（候选列表变化）才重置；自动切换 activeIndex 产生的副本保留已失败集合。
        val key = platformLiveStreamKey(stream)
        if (platformLiveRotationKey != key) {
            platformLiveRotationKey = key
            platformLiveTriedCandidates.clear()
        }
        platformLiveTriedCandidates += stream.safeActiveCandidateIndex
        val nextIdx = nextRotatedPlatformLiveCandidate(stream, platformLiveTriedCandidates)
        val nextCandidate = nextIdx?.let { stream.candidates.getOrNull(it) }
        if (nextIdx == null || nextCandidate == null) {
            TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】候选线路已轮完一圈或无可用候选，强制重新解析" }
            platformLiveTriedCandidates.clear()
            platformLiveRotationKey = null
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
        TLog.i(VM_LIVE_LOG_TAG) { "【平台恢复】切换到候选线路 $nextIdx, cdn=${nextCandidate.cdn}" }
        platformLiveRecoveryInProgress = false
    }

    /** 退避后重新解析当前房间，保持当前候选下标。 */
    private fun schedulePlatformLiveReResolve(delayMs: Long) {
        val keepCandidate = _state.value.platformLive.stream?.safeActiveCandidateIndex ?: 0
        platformLiveResolveJob = relaunch(platformLiveResolveJob) {
            kotlinx.coroutines.delay(delayMs)
            platformLiveRecoveryInProgress = false
            TLog.w(VM_LIVE_LOG_TAG) { "【平台恢复】退避 ${delayMs}ms 后重新解析（候选 $keepCandidate）" }
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
        // T3-23：记录换源操作面包屑
        GlobalPlayState.breadcrumb.record(
            System.currentTimeMillis(),
            "source_switch",
            "from=${current.playerSourceIndex} to=$index"
        )
        return true
    }

    fun updatePlaybackSpeed(speed: Float) {
        val clamped = PlayerSpeedSetting.clamp(speed)
        val current = _state.value
        if (current.playerSpeed == clamped && current.appSettings.playerSpeed == clamped) return
        // 倍速属于持久化偏好：统一走 AppSettings，再由既有的单写者队列落盘。
        val settings = current.appSettings.copy(playerSpeed = clamped)
        _state.update { it.copy(playerSpeed = clamped, appSettings = settings) }
        saveSettings(settings)
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
            // 回写前把进度钳制到本集片长，避免退出时记下超过片长的无效位置。
            positionMs = resolveResumePosition(
                savedPositionMs = positionMs,
                playerPositionMs = 0L,
                durationMs = durationMs,
                isPrepared = false,
                sourceChanged = false,
            ).positionMs,
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAtEpochMs = System.currentTimeMillis(),
            // 记录当时的线路总集数：历史列表据此提示「还有 N 集」，
            // 用户不用再进详情页确认是不是更新了。
            episodeCount = source.episodes.size,
        )
        val maxItems = com.tvbox.app.domain.MemoryGovernor.effectivePosterLimit(
            _state.value.appSettings.historyMaxItems,
            currentMemoryTier(),
        )
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
            // 详情加载失败时 detailMovie 为空，退回最近一次请求的影片与来源重试。
            TvScreen.Detail -> _state.value.detailMovie
                ?.let { openDetail(it.id, it.apiLineId) }
                ?: lastDetailRequest?.let { (movieId, lineId) -> openDetail(movieId, lineId) }
            TvScreen.Player -> Unit
            TvScreen.Live -> refreshLive()
            TvScreen.PlatformLive -> retryPlatformLiveChannel()
            TvScreen.Settings -> checkForAppUpdate(showError = true)
            TvScreen.AiSearch -> Unit
            TvScreen.SourceManager, TvScreen.LiveSourceManager, TvScreen.VideoSourceManager -> Unit
        }
    }

    fun goBack(): Boolean {
        // 热播解析会在数秒后强制跳详情页，用户主动返回时必须撤销，避免「返回了又被拉回去」。
        doubanHotResolveJob?.cancel()
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
            TvScreen.History, TvScreen.Settings, TvScreen.AiSearch -> {
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
        // 删除/屏蔽当前源后的兜底切换：旧源的分类、选中分类与分页必须一起清掉，
        // 否则新源会沿用旧分类 id 请求影片，出现空列表或错分页。
        _state.update {
            it.copy(
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
            // 单条完成即在锁内发布「延迟 + 进度」的原子合并，回调骨架见 measureSourceLatencies。
            measureSourceLatencies(srcLines.map { it.id }, test = { repository.testSpeed(it) }) { lineId, latency, completed, _ ->
                _state.update {
                    it.copy(
                        source = it.source.copy(
                            latencies = it.source.latencies + (lineId to latency),
                            testCompleted = completed,
                        ),
                    )
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

    /**
     * 视频源健康管理循环：启动后先等 [SourceHealthSetting.InitialDelayMs] 做首查，
     * 之后按 [SourceHealthSetting.IntervalMs] 周期复查。开关关闭时循环只空转，
     * 用户重新打开后最迟一个周期内恢复体检。
     */
    private fun startSourceHealthMonitor() {
        sourceHealthJob?.cancel()
        sourceHealthJob = viewModelScope.launch {
            while (isActive) {
                delay(SourceHealthSetting.InitialDelayMs)
                runBackgroundSourceHealthCheck()
                delay(SourceHealthSetting.IntervalMs)
            }
        }
    }

    /**
     * 单轮后台体检：静默并发测速全部视频接口（不复用用户测速的进度条状态），
     * 结果合并进延迟表并持久化；随后按延迟自动排序（快源靠前、失效靠后）。
     * 用户正在手动测速时跳过本轮，避免两路测速互相覆盖进度与结论。
     * [force] 为 true 时（T2-18 体检卡「立即体检」）跳过「开关必须开启」的门控。
     */
    private suspend fun runBackgroundSourceHealthCheck(force: Boolean = false) {
        val current = _state.value
        if (!current.appSettings.sourceHealthCheckEnabled && !force) return
        if (current.source.testing) return
        val lines = current.apiLines
        if (lines.isEmpty()) return
        _state.update {
            it.copy(source = it.source.copy(healthCheckRunning = true))
        }
        // T2-16：只探测「不在冷却窗口内」的源，避免把活源自己探死。
        val nowMs = System.currentTimeMillis()
        val probeable = lines.map { it.id }.filter { id ->
            com.tvbox.app.domain.ProbeCooldown.shouldProbe(probeCooldownStates[id], nowMs)
        }
        // T2-16：探针走专用 UA，与播放 UA 区分；租约保证并发探针不会提前把 UA 还原。
        val probeUa = com.tvbox.app.domain.UaScope.resolve(
            "probe",
            current.appSettings,
        )
        val results = try {
            if (probeable.isEmpty()) {
                emptyMap()
            } else {
                measureSourceLatencies(
                    probeable,
                    parallelism = SourceHealthSetting.Parallelism,
                    test = { id ->
                        com.tvbox.app.domain.ProbeUaLease.acquire(probeUa)
                        try {
                            repository.testSpeed(id)
                        } finally {
                            com.tvbox.app.domain.ProbeUaLease.release()
                        }
                    },
                    onResult = { id, latency, _, _ ->
                        // T2-16：探测成功回 ALIVE、失败降 SLOW，绝不直接落 DEAD。
                        probeCooldownStates[id] = if (latency == null) {
                            com.tvbox.app.domain.ProbeCooldown.onProbeFailure(probeCooldownStates[id], nowMs)
                        } else {
                            com.tvbox.app.domain.ProbeCooldown.onProbeSuccess(probeCooldownStates[id], nowMs)
                        }
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            _state.update { state ->
                if (state.source.healthCheckRunning) {
                    state.copy(source = state.source.copy(healthCheckRunning = false))
                } else {
                    state
                }
            }
        }
        _state.update {
            it.copy(source = it.source.copy(latencies = it.source.latencies + results))
        }
        persistSourceLatencies()
        // 自动排序：健康线路按延迟升序在前，失效线路保持原相对顺序靠后。
        val sorted = orderLinesByLatency(lines, results)
        val sortedIds = sorted.map { it.id }
        val okCount = results.count { it.value != null }
        val summary = "上次后台体检 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}：" +
            "正常 $okCount / 共 ${lines.size}" +
            if (sortedIds != lines.map { it.id }) "，已自动排序" else "，顺序无变化"
        if (sortedIds != lines.map { it.id }) {
            val settings = _state.value.appSettings.copy(sourceOrder = sortedIds)
            saveSettings(settings)
            _state.update {
                it.copy(
                    apiLines = sorted,
                    allSourceLines = orderApiLines(repository.allApiLines, settings.sourceOrder),
                    appSettings = settings,
                )
            }
        }
        _state.update {
            it.copy(source = it.source.copy(lastHealthCheckSummary = summary))
        }
        TLog.i("TVBoxSourceHealth") { summary }
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
            measureSourceLatencies(lineIds, test = { repository.testSpeed(it) }) { lineId, latency, completed, _ ->
                _state.update {
                    it.copy(
                        source = it.source.copy(
                            latencies = it.source.latencies + (lineId to latency),
                            detailTestCompleted = completed,
                        ),
                    )
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
            // 订阅下载、解码、解析与直播源 dry-run 都是阻塞/CPU 工作，统一放到 IO，
            // 只在 _state.update 时回到 UI 侧（StateFlow 本身线程安全），避免大订阅导入卡住主线程。
            withContext(Dispatchers.IO) {
                val configFetcher = SourceConfigFetcher()
                val isUrl = text.startsWith("http://", ignoreCase = true) ||
                    text.startsWith("https://", ignoreCase = true)
                val payload = if (isUrl) configFetcher.fetch(text) else text
                val decoded = try {
                    SubscriptionDecoder.decode(url = if (isUrl) text else "", payload)
                } catch (error: IllegalArgumentException) {
                    val message = "订阅解码失败：${error.message ?: "格式不合法"}"
                    _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
                    return@withContext message
                }
                val result = SourceConfigCodec.parseImport(decoded)
                val imported = result.imported
                if (result.error != null || imported == null) {
                    val message = result.error ?: "配置解析失败"
                    _state.update { it.copy(source = it.source.copy(importing = false, importMessage = message)) }
                    return@withContext message
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
                // 导入前 dry-run：本地再解析一次直播源，把"能导入多少频道、哪些字段缺失"直接说出来。
                // 并发校验并给每个源单独超时，避免大量直播源串行请求拖慢导入、或一条死链拖住全部。
                val liveDryRuns: List<Pair<String, LiveSourceDryRun>> = coroutineScope {
                    newLiveUrls.map { url ->
                        async {
                            val body = try {
                                withTimeout(LIVE_SOURCE_DRY_RUN_TIMEOUT_MS) { configFetcher.fetch(url) }
                            } catch (timeout: TimeoutCancellationException) {
                                // 单个源超时只跳过自己的摘要，不能取消整组 dry-run。
                                null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            } ?: return@async null
                            url to dryRunLiveSource(body)
                        }
                    }.awaitAll().filterNotNull()
                }
                // 订阅 lives[].epg 声明的节目单：用户没配过 EPG 时自动启用第一个。
                val adoptImportedEpg = current.appSettings.epgUrl.isBlank() && imported.liveEpgUrls.isNotEmpty()
                val settings = current.appSettings.copy(
                    customVideoApiLines = mergedCustom,
                    liveSourceUrls = current.appSettings.liveSourceUrls + newLiveUrls,
                    epgUrl = if (adoptImportedEpg) imported.liveEpgUrls.first() else current.appSettings.epgUrl,
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
                    if (adoptImportedEpg) append("，已启用订阅节目单地址")
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
                // 订阅 lives[].epg 启用后立即拉取节目单（状态已含新 epgUrl，否则要等下次进入直播页）。
                if (adoptImportedEpg) {
                    _state.update {
                        it.copy(
                            live = it.live.copy(epgByChannel = emptyMap(), epgLoaded = false, epgFetchedAtMs = 0L),
                        )
                    }
                    loadLiveEpg(force = true)
                }
                message
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "导入失败：${logSafeText(error.message)}"
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
        val effectiveDefaultIndex = if (defaultIndex >= 0) {
            // 调用方传入的下标也要按最终列表校验，避免持久化越界下标。
            defaultIndex.takeIf { idx -> idx in normalized.indices } ?: -1
        } else {
            current.appSettings.liveSourceDefaultIndex
                .takeIf { idx -> idx in normalized.indices } ?: -1
        }
        val settings = current.appSettings.copy(
            liveSourceUrls = normalized,
            liveSourceDefaultGroup = defaultGroup,
            liveSourceDefaultIndex = effectiveDefaultIndex,
        )
        applyLiveSourceConfig(settings)
        commitSettings(settings)
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

    /**
     * 从用户当前编辑区设默认直播源（不要求该 URL 已持久化）：
     * 把 [defaultIndex] 应用到「当前 URL 文本」对应的归一化列表，并连同现有其他字段一起保存。
     * 若 defaultIndex 越界或为空则忽略；仅当 effective 下标相比当前已保存值发生变化时才触发刷新。
     */
    fun setLiveSourceDefaultIndexFromDraft(urlsText: String, defaultIndex: Int) {
        val urls = urlsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        val normalizedUrls = urls.distinct()
        val effectiveIdx = if (defaultIndex in normalizedUrls.indices) defaultIndex else -1
        val current = _state.value
        val currentSavedUrls = current.appSettings.liveSourceUrls
        val currentDefault = current.appSettings.liveSourceDefaultIndex
        // 若当前编辑区内容与已保存的 URL 完全一致且 defaultIndex 无变化，跳过重复写入。
        if (normalizedUrls == currentSavedUrls && effectiveIdx == currentDefault) return
        val settings = current.appSettings.copy(
            liveSourceUrls = normalizedUrls,
            liveSourceDefaultGroup = current.appSettings.liveSourceDefaultGroup,
            liveSourceDefaultIndex = effectiveIdx,
        )
        _state.update { it.copy(appSettings = settings) }
        saveSettings(settings)
        applyLiveSourceConfig(settings)
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
    /** 读取详情页选集滚动位置；没有记录返回 -1（调用方回落到"滚到选中集"）。 */
    fun detailScrollIndexFor(movieId: Int, sourceIndex: Int): Int =
        detailEpisodeScrollIndex["$movieId:$sourceIndex"] ?: -1

    /** 记录详情页选集滚动位置；离开详情页/切换线路时都会调用。 */
    fun rememberDetailScrollIndex(movieId: Int, sourceIndex: Int, index: Int) {
        val key = "$movieId:$sourceIndex"
        if (index <= 0) detailEpisodeScrollIndex.remove(key) else detailEpisodeScrollIndex[key] = index
    }

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
        commitSettings(settings)
        refreshHome()
    }

    fun clearBlockedVideoCategories() {
        val current = _state.value
        if (current.appSettings.blockedVideoCategories.isEmpty()) return
        val settings = current.appSettings.copy(blockedVideoCategories = emptyList())
        commitSettings(settings)
        refreshHome()
    }

    fun updateIntroSkipSeconds(seconds: Int) {
        val settings = _state.value.appSettings.copy(introSkipSeconds = seconds.coerceIn(0, 600))
        commitSettings(settings)
    }

    fun updateOutroSkipSeconds(seconds: Int) {
        val settings = _state.value.appSettings.copy(outroSkipSeconds = seconds.coerceIn(0, 600))
        commitSettings(settings)
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
                        // 成功加载后清掉上一轮（含加载更多失败）的提示，避免常驻。
                        homeNotice = null,
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
                        // 加载更多失败不能整页替换列表：保留已加载内容与滚动位置，只给轻提示。
                        homeError = if (!reset) null else message,
                        homeNotice = if (!reset) "加载更多失败，稍后重试" else it.homeNotice,
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

    private fun loadHistory(triggerUpdateCheck: Boolean = false) {
        viewModelScope.launch {
            // 同样串行化：若此前有进度保存在排队，先等它写完再读，
            // 避免历史页短暂显示缺一条/旧位置的列表。
            historyWriteMutex.withLock {
                runCatching { historyRepository.getHistory() }
                    .onSuccess { history ->
                        val maxItems = com.tvbox.app.domain.MemoryGovernor.effectivePosterLimit(
                            _state.value.appSettings.historyMaxItems,
                            currentMemoryTier(),
                        )
                        _state.update { it.copy(historyItems = history.take(maxItems)) }
                    }
                    .onFailure { error ->
                        TLog.w("TVBoxHistory") { "【历史读取失败】${error::class.simpleName}" }
                    }
            }
            // 无论成功与否都标记完成，首页据此一次性渲染「继续观看」，避免列表下移。
            _state.update { it.copy(historyLoaded = true) }
            if (triggerUpdateCheck) {
                checkHistoryUpdates()
            }
        }
    }

    private fun checkHistoryUpdates(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryUpdateCheckAtEpochMs < HISTORY_UPDATE_GLOBAL_COOLDOWN_MS) return
        val snapshot = _state.value.historyItems
        if (snapshot.isEmpty()) return
        val targets = snapshot
            .sortedByDescending { it.updatedAtEpochMs }
            .filter { item ->
                item.movieId > 0 && item.apiLineId.isNotBlank() &&
                    (force || item.lastUpdateCheckEpochMs <= 0L ||
                        now - item.lastUpdateCheckEpochMs > HISTORY_UPDATE_ITEM_COOLDOWN_MS)
            }
            .take(HISTORY_UPDATE_MAX_ITEMS)
        if (targets.isEmpty()) return
        lastHistoryUpdateCheckAtEpochMs = now
        historyUpdateCheckJob?.cancel()
        historyUpdateCheckJob = viewModelScope.launch {
            _state.update { it.copy(historyUpdateChecking = true) }
            val semaphore = Semaphore(HISTORY_UPDATE_PARALLELISM)
            try {
                // 整批总预算：单条超时之外，还要给整批兜底，避免源全挂时检查串行拖到 20s+ 的 spinner。
                withTimeoutOrNull(HISTORY_UPDATE_BATCH_TIMEOUT_MS) {
                    coroutineScope {
                    targets.map { item ->
                        async {
                            semaphore.withPermit {
                                ensureActive()
                                // 跨源探测用「最快响应的源」：并发扇出到全部已启用源，任一源返回有效集数即采纳，
                                // 不被原记录源（慢/挂）阻塞。这样即使原源不可用，只要还有可用源就能判断是否更新。
                                val latest = withTimeoutOrNull(HISTORY_UPDATE_TIMEOUT_MS) {
                                    repository.probeLatestEpisodeCount(item.apiLineId, item.movieId, item.movieName)
                                } ?: 0
                                if (latest <= 0) return@withPermit
                                val checkedAt = System.currentTimeMillis()
                                historyWriteMutex.withLock {
                                    runCatching {
                                        historyRepository.updateEpisodeCheck(
                                            apiLineId = item.apiLineId,
                                            movieId = item.movieId,
                                            latestEpisodeCount = latest,
                                            checkedAtEpochMs = checkedAt,
                                        )
                                    }.onSuccess { updated ->
                                        // 用 repository 权威结果 patch「本条」，而非整体替换 historyItems：
                                        // 多条历史并发检查时，各自只更新自己的条目，避免旧表覆盖刚写入的其他条。
                                        _state.update { state ->
                                            val maxItems = com.tvbox.app.domain.MemoryGovernor.effectivePosterLimit(
                                                state.appSettings.historyMaxItems,
                                                currentMemoryTier(),
                                            )
                                            val patched = state.historyItems.map { h ->
                                                if (h.apiLineId == item.apiLineId && h.movieId == item.movieId) {
                                                    val newest = updated.firstOrNull {
                                                        it.apiLineId == item.apiLineId && it.movieId == item.movieId
                                                    }
                                                    if (newest == null) h else newest.copy(
                                                        latestEpisodeCount = maxOf(h.latestEpisodeCount, newest.latestEpisodeCount),
                                                        lastUpdateCheckEpochMs = maxOf(h.lastUpdateCheckEpochMs, newest.lastUpdateCheckEpochMs),
                                                    )
                                                } else h
                                            }
                                            state.copy(
                                                historyItems = patched.take(maxItems),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                    }
                } // withTimeoutOrNull(HISTORY_UPDATE_BATCH_TIMEOUT_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 单次跨源检查失败不影响本地历史展示与后续重试；但至少留日志便于排查“历史没更新”的原因。
                TLog.w("TVBoxHistory", t) { "checkHistoryUpdates 整批中止：${t.message}" }
            } finally {
                _state.update { it.copy(historyUpdateChecking = false) }
            }
        }
    }

    private fun refreshPlaybackHealth() {
        viewModelScope.launch {
            runCatching { playbackHealthRepository.getSnapshot() }
                .onSuccess { snapshot ->
                    _state.update { it.copy(playbackHealth = snapshot) }
                }
            runCatching { playbackHealthRepository.getEvidence(50) }
                .onSuccess { evidence ->
                    _state.update { it.copy(playbackEvidence = evidence) }
                }
        }
    }

    /** 设置变更后既落盘又同步内存态；收敛原先 33 处相同的两行样板。 */
    private fun commitSettings(settings: AppSettings) {
        saveSettings(settings)
        _state.update { it.copy(appSettings = settings) }
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
            val now = System.currentTimeMillis()
            runCatching {
                playbackHealthRepository.recordIssue(
                    key = key,
                    issueType = issueType,
                    nowMs = now,
                )
            }.onSuccess { snapshot ->
                _state.update { it.copy(playbackHealth = snapshot) }
            }
            val evidence = com.tvbox.app.domain.PlaybackEvidence(
                atMs = now,
                source = key,
                event = when (issueType) {
                    com.tvbox.app.domain.PlaybackIssueType.SlowBuffer ->
                        com.tvbox.app.domain.PlaybackEvidenceEvent.BufferStall
                    else -> com.tvbox.app.domain.PlaybackEvidenceEvent.PlaybackError
                },
                detail = issueType.name,
            )
            runCatching { playbackHealthRepository.recordEvidence(evidence) }
                .onSuccess { list ->
                    _state.update { it.copy(playbackEvidence = list) }
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
        val now = System.currentTimeMillis()
        val scores = liveDomainScores?.snapshot().orEmpty()
        if (scores.isNotEmpty()) {
            val ranked = rankLiveLineIndicesByDomain(channel.lines, scores, now)
            // 只在"确有可用依据"时改顺序：最优线路评分为 0（全无记录）时保持源内顺序。
            val best = ranked.firstOrNull() ?: return 0
            val bestDomain = liveLineDomain(channel.lines[best].url) ?: return 0
            val bestScore = scores[bestDomain]?.score(now) ?: 0.0
            if (bestScore > 0.0) return best
        }
        // 域名无记录时的第三级依据：该频道各线路自身的起播质量历史。
        // 同样只在有正分记录时才改顺序，避免把"从未成功"的线路误判为最优。
        val lineRecords = liveLineQualityRecords?.snapshot().orEmpty()
        if (lineRecords.isEmpty()) return 0
        val channelKey = "${channel.groupName}#${channel.name}"
        val byQuality = rankLinesByQuality(channel.lines, lineRecords, channelKey, now)
        val bestByQuality = byQuality.firstOrNull() ?: return 0
        val record = lineRecords[lineQualityKey(channelKey, bestByQuality)]
        return if (record != null && record.score(now) > 0.0) bestByQuality else 0
    }

    /** 记录当前线路的一次播放失败到域名评分表（错误/卡死/结束都会走到这里）。 */
    private fun recordLiveLineFailure(state: TvBoxUiState) {
        val channel = state.live.channels.getOrNull(state.live.channelIndex) ?: return
        val lineIndex = state.live.lineIndex
        val url = channel.lines.getOrNull(lineIndex)?.url ?: return
        val domain = liveLineDomain(url) ?: return
        liveDomainScores?.record(domain, success = false)
        liveLineQualityRecords?.record(lineQualityKey("${channel.groupName}#${channel.name}", lineIndex), null, false)
        liveLineStartAtMs = null
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
                    // 加载失败时丢弃待恢复分组，避免下一次成功加载误用旧订阅的分类。
                    pendingLiveGroupRestore = null
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
                    // S3-19：换源即旧 EPG 时间戳失效。
                    epgFetchedAtMs = 0L,
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
    /** T3-21：只保留预算时间窗内开始的节目，避免低内存档仍把全天 XMLTV 装进状态。 */
    private fun trimEpgToBudget(
        programs: Map<String, List<EpgProgram>>,
        hours: Int,
        nowMs: Long,
    ): Map<String, List<EpgProgram>> {
        val windowEnd = nowMs + hours * 3_600_000L
        return programs.mapValues { (_, list) -> list.filter { it.startMs < windowEnd } }
    }

    private fun loadLiveEpg(force: Boolean = false) {
        val url = effectiveEpgUrl(_state.value)
        if (url.isBlank()) return
        // S3-19 stale-while-revalidate：仅 STALE（缓存超出 FRESH 窗口，默认 6 小时）时保持旧节目单继续展示
        // （UI 叠加「~」角标）并落入下方后台静默重拉；FRESH 直接复用，
        // LOADING（上次拉取失败）维持既有「不再反复重试慢源」语义。
        // force=true（改地址/换源自带 EPG）无条件重拉。
        if (!force && _state.value.live.epgLoaded) {
            val renderState = EpgCachePolicy.renderState(
                EpgData(_state.value.live.epgByChannel, _state.value.live.epgFetchedAtMs),
                System.currentTimeMillis(),
            )
            if (renderState != EpgRenderState.STALE) return
        }
        // T3-21：CRITICAL 档预算为 0 小时，直接跳过全量节目单拉取（EPG 本就是增强项）。
        val epgHours = com.tvbox.app.domain.MemoryGovernor.effectiveEpgHours(
            _state.value.appSettings.epgCacheHours,
            currentMemoryTier(),
        )
        if (epgHours <= 0) return
        epgJob = relaunch(epgJob) {
            // 取消必须透传：否则被取消的旧请求会以空节目单写回 epgLoaded=true。
            val now = System.currentTimeMillis()
            // EPG UA 作用域与 EPG 时区来自设置页（此前是死配置）。
            val epgSettings = _state.value.appSettings
            val programs = try {
                epgRepository.fetch(
                    url,
                    com.tvbox.app.domain.UaScope.resolve("epg", epgSettings),
                    epgSettings.epgTimezone,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyMap()
            }
            val bounded = trimEpgToBudget(programs, epgHours, now)
            _state.update { state -> state.applyEpgResult(url, bounded, now) }
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
                        it.copy(platformLive = it.platformLive.copy(
                            loading = true, loadingMore = false, loadingMoreError = null, error = null))
                    } else {
                        it.copy(platformLive = it.platformLive.copy(
                            loadingMore = true, loadingMoreError = null, error = null))
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
                                // 用「本次请求的页码」而不是服务端返回值，避免异常 page 造成跳页/重复请求。
                                roomPage = if (result.rooms.isEmpty() && !reset) it.platformLive.roomPage else page,
                                roomPageCount = result.pageCount,
                                loading = false,
                                loadingMore = false,
                                loadingMoreError = null,
                                error = if (rooms.isEmpty()) "这个分类暂时没有正在直播的房间" else null),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    // 切分类/返回时旧请求被取消，不能被当成失败写进错误态。
                    if (error is CancellationException) throw error
                    _state.update {
                        if (it.platformLive.selectedCategory?.id != category.id) it else {
                            it.copy(platformLive = if (reset) {
                                // 首屏加载失败：整页错误态 + 重试。
                                it.platformLive.copy(
                                    loading = false,
                                    loadingMore = false,
                                    loadingMoreError = null,
                                    error = error.userMessage(),
                                )
                            } else {
                                // 分页失败：已加载的房间保持展示，只在列表底部提示并提供重试，
                                // 页码不推进，重试仍从同一页继续。
                                it.platformLive.copy(
                                    loadingMore = false,
                                    loadingMoreError = error.userMessage(),
                                )
                            })
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
            // 换房/手动重连后丢帧样本与新房间无关，重置降档看门狗计数。
            huyaHighDropStreak = 0
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
                .onSuccess { resolved ->
                    // 虎牙/斗鱼：若启用并配置了第三方代理，把第三方候选排在官方解析候选之前，
                    // 播放时优先用第三方地址加载；多个第三方按配置顺序互为备用，官方候选退为兜底。
                    val stream = resolved.withThirdPartyProxyPrepended(
                        site = room.site,
                        roomId = room.roomId,
                        enabled = state.appSettings.platformThirdPartyProxyEnabled,
                        templatesRaw = state.appSettings.platformThirdPartyProxyTemplates,
                    )
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
// 电视直播：缓冲/卡死类失败在同一线路上原地重连达到该次数后，才切下一条线路。
private const val LIVE_STALL_RETRY_BEFORE_SWITCH = 2
// 平台直播恢复熔断：该时间窗内最多自动恢复次数（虎牙 FLV 约 125s 关流一次，正常播放不该触顶）。
private const val PLATFORM_LIVE_RECOVERY_WINDOW_MS = 120_000L
private const val PLATFORM_LIVE_RECOVERY_MAX_ATTEMPTS = 6
// 平台直播：同一条候选线路原地重连（重新解析地址）的最大次数，超过后换 CDN。
private const val PLATFORM_LIVE_SAME_LINE_RETRY_MAX = 2
private const val VM_LIVE_LOG_TAG = "TVBoxLiveVM"

/** 详情加载诊断日志：只输出影片 id 与异常类型，不输出任何源地址。 */
private const val DETAIL_LOG_TAG = "TVBoxDetail"
private const val HISTORY_UPDATE_MAX_ITEMS = 12
private const val HISTORY_UPDATE_PARALLELISM = 2
private const val HISTORY_UPDATE_ITEM_COOLDOWN_MS = 30 * 60 * 1000L
private const val HISTORY_UPDATE_GLOBAL_COOLDOWN_MS = 10 * 60 * 1000L
private const val HISTORY_UPDATE_TIMEOUT_MS = 12_000L
/** 整批历史更新检查的总兜底预算：即使多条历史、源全挂也不让 spinner 拖过该时长。 */
private const val HISTORY_UPDATE_BATCH_TIMEOUT_MS = 15_000L

/**
 * 搜索防抖窗口（S5-27）：TV 遥控输入是逐字符的，280ms 刚好不吃手感。
 * 用户快速输入时只保留最后一个字符序列的搜索，避免逐字符发出三个并发请求。
 */
private const val SEARCH_DEBOUNCE_MS = 280L

/**
 * 一次搜索触发请求：query 为已 trim 的关键词，immediate 为 true 表示用户显式提交
 * （回车/搜索按钮/热词点击），应跳过防抖立即执行；false 表示输入过程中的自动搜索。
 */
private data class SearchRequest(
    val query: String,
    val immediate: Boolean,
)

private class DefaultAppUpdateRepositoryPlaceholder : AppUpdateRepository {
    override suspend fun checkForUpdate(
        currentVersionCode: Long,
        updateChannel: com.tvbox.app.domain.UpdateChannel,
    ): AppUpdate? = null
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

    /** T1-04：占位实现，不做持久化。 */
    override suspend fun recordSuccessWithConfidence(
        key: String,
        latencyMs: Long,
        durationConfidence: com.tvbox.app.domain.DurationConfidence,
        nowMs: Long,
    ): PlaybackHealthSnapshot = PlaybackHealthSnapshot()

    override suspend fun clear(): PlaybackHealthSnapshot = PlaybackHealthSnapshot()
}
