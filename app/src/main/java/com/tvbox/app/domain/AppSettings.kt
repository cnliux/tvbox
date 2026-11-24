package com.tvbox.app.domain

import kotlinx.serialization.Serializable

data class AppSettings(
    val homeApiLineId: String = "",
    val customVideoApiLines: List<CustomVideoApiLine> = emptyList(),
    val sourceOrder: List<String> = emptyList(),
    val blockedSourceIds: List<String> = emptyList(),
    val liveSourceUrls: List<String> = emptyList(),
    /** 已删除（屏蔽）的平台直播站点 ID，例如斗鱼、虎牙。 */
    val blockedPlatformSiteIds: List<String> = emptyList(),
    /** 已屏蔽的视频源分类（MacCms 分类名），例如“伦理片”。 */
    val blockedVideoCategories: List<String> = emptyList(),
    /** 开始播放时自动跳过的片头秒数；0 表示关闭。 */
    val introSkipSeconds: Int = 0,
    /** 临近片尾提前多少秒自动进入下一集；0 表示关闭。 */
    val outroSkipSeconds: Int = 0,
    val homeContinueWatchingEnabled: Boolean = false,
    val theme: TvTheme = TvTheme.Default,
    val fontScale: TvFontScale = TvFontScale.Normal,
    val checkUpdatesOnStartup: Boolean = true,
    val playbackAgentAutoSwitchEnabled: Boolean = true,
    val videoScaleMode: VideoScaleMode = VideoScaleMode.Fit,
    /** 播放倍速（持久化）：默认 1.0，区间见 [PlayerSpeedSetting]。 */
    val playerSpeed: Float = PlayerSpeedSetting.Default,
    /**
     * 按内容记忆的画面比例覆盖：key 由 [vodVideoScaleKey]/[liveVideoScaleKey]/[roomVideoScaleKey] 生成，
     * value 为 [VideoScaleMode.storageKey]。命中时优先于全局默认 [videoScaleMode]。
     */
    val contentVideoScaleModes: Map<String, String> = emptyMap(),
    /** 按内容记忆的字幕轨道：key 由影片维度生成，value 为轨道 id（空串表示关闭字幕）。 */
    val contentSubtitleTracks: Map<String, String> = emptyMap(),
    /** 设备模式偏好：Auto 为默认；Television/Mobile 为手动覆盖值，优先级高于自动识别。 */
    val deviceMode: DeviceMode = DeviceMode.Auto,
    /** 每个直播源地址默认添加到的频道组合名。空字符串表示不自动加入任何组合。 */
    val liveSourceDefaultGroup: String = "",
    /** 手机模式首次启动引导 Banner 是否已展示过。 */
    val mobileOnboarded: Boolean = false,
    /** 电视直播默认源在 liveSourceUrls 中的下标；-1 表示无默认，按顺序尝试。 */
    val liveSourceDefaultIndex: Int = -1,
    /** 剧集排序顺序：false=正序（1,2,3…），true=倒序（…,3,2,1）。 */
    val episodeSortReverse: Boolean = false,
    /** 电视直播节目单 XMLTV 接口地址；空表示未配置。 */
    val epgUrl: String = "",
    /** 电视直播 EPG 窄卡展示的节目条数。 */
    val epgCardRows: Int = QuantitySettings.EpgCardRows.default,
    /** 数字选台浮层展示的候选行数。 */
    val liveNumberMatchRows: Int = QuantitySettings.LiveNumberMatchRows.default,
    /** 数字选台前缀匹配的频道上限。 */
    val liveNumberSearchLimit: Int = QuantitySettings.LiveNumberSearchLimit.default,
    /** 手机直播页「相关频道」数量。 */
    val relatedChannelCount: Int = QuantitySettings.RelatedChannelCount.default,
    /** 搜索热词条数。 */
    val hotWordCount: Int = QuantitySettings.HotWordCount.default,
    /** 拼音首字母扩展出的候选片名数量。 */
    val searchSuggestionCount: Int = QuantitySettings.SearchSuggestionCount.default,
    /** 豆瓣热播每页条数。 */
    val doubanHotPageSize: Int = QuantitySettings.DoubanHotPageSize.default,
    /** 打开豆瓣热播时依次尝试的视频源数量。 */
    val doubanHotFallbackLines: Int = QuantitySettings.DoubanHotFallbackLines.default,
    /** 本地观看历史保留条数。 */
    val historyMaxItems: Int = QuantitySettings.HistoryMaxItems.default,
    /** 多源搜索/详情同时请求的源数量。 */
    val maxParallelSourceRequests: Int = QuantitySettings.MaxParallelSourceRequests.default,
    /** 解码器策略：默认硬解优先、失败自动回落；也可强制只选硬件解码器。 */
    val decoderStrategy: DecoderStrategy = DecoderStrategy.Auto,
    /** 播放器/直播代理详细日志开关；默认关闭，调试排查时开启。 */
    val playerLogEnabled: Boolean = false,
    /** 更新渠道：stable 指向 update.json（正式发布），beta 指向 update-beta.json（预发布）。 */
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
    /** #277 直播切台频道名横幅显示时长（毫秒）；0 表示自动（电视 4000ms / 手机 2000ms）。 */
    val liveInfoBannerDurationMs: Long = LiveInfoBannerDuration.Auto,
    /** #94 多源搜索/详情的单源超时（毫秒）；默认沿用既有 6s 预算。 */
    val perSourceTimeoutMs: Long = PerSourceTimeout.DefaultMs,
    /** #318 历史隐私模式：开启后不再写入新的观看历史，已有记录保留。 */
    val historyPrivacyMode: Boolean = false,
    /** #54/#266 硬解设备白名单：逗号分隔的 Build.MODEL 前缀，命中时强制硬解；空表示不启用。 */
    val decoderDeviceWhitelist: String = "",
    /**
     * 自定义 User-Agent：应用于视频接口（MacCms）请求、直播订阅拉取与直播播放兜底 UA。
     * 空字符串表示使用内置默认 UA（[UserAgentSetting.Default]）。
     * 直播订阅地址还可追加 `|User-Agent=xxx` 参数按源覆盖（见 [parseLiveSourceEndpoint]）。
     */
    val customUserAgent: String = "",
    /**
     * 视频源健康管理：开启后应用运行期间在后台按固定周期静默测速全部视频接口，
     * 结果持久化并按延迟自动排序（快源靠前、失效源靠后），不影响用户当前观看。
     */
    val sourceHealthCheckEnabled: Boolean = true,
    /** 源失败熔断：连续 5 次超时的源自动休眠 10 分钟（对照设置页方案「源失败熔断」）。 */
    val sourceCircuitBreakerEnabled: Boolean = true,
    /** 画面亮度覆盖：0=跟随系统，1..100=按百分比叠加亮度层。 */
    val playerBrightness: Int = PlayerBrightnessSetting.Default,
    /** 片尾自动进入下一集：开启后配合 [outroSkipSeconds] 在片尾倒计时自动续播。 */
    val autoNextEpisodeEnabled: Boolean = true,
    /** 手机端播放器手势：左右调亮度/音量、横滑进度。 */
    val playerGesturesEnabled: Boolean = true,
    /** 快进/快退步长（秒），供播放器 seek 按钮与长按加速使用。 */
    val fastSeekStepSeconds: Int = FastSeekSetting.DefaultStepSeconds,
    /** 历史页布局模式：卡片 / 紧凑 / 网格。 */
    val historyLayout: HistoryLayout = HistoryLayout.Card,
    /** 历史封面「更新角标」：有更新剧集的条目在封面右上角显示角标。 */
    val historyCoverBadgeEnabled: Boolean = true,
    /** 崩溃日志只落本地，不上报远端。 */
    val crashLogLocalOnly: Boolean = true,
)

/**
 * 自定义 User-Agent 的默认值、清洗规则与常用预设。
 * UA 会随请求头发给远端，禁止换行等控制字符注入；保存与读取统一走 [sanitize]。
 */
object UserAgentSetting {
    /** 内置默认 UA：与订阅拉取既有口径一致（TVBox-Android 标识 + Android 客户端特征）。 */
    const val Default = "Mozilla/5.0 (Linux; Android 12) TVBox-Android/1.3"

    /** 常用预设（展示名 → UA），供设置页快捷选择；最后一项为内置默认。 */
    val presets: List<Pair<String, String>> = listOf(
        "内置默认" to Default,
        "okhttp" to "okhttp/4.12.0",
        "Chrome 移动版" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
    )

    /** 清洗用户输入：去首尾空白并剔除换行符（HTTP 头不允许）；结果可以为空串（表示用默认）。 */
    fun sanitize(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()

    /** 计算生效 UA：用户配置非空用清洗后的用户值，否则回退内置默认。 */
    fun resolve(configured: String): String = sanitize(configured).ifBlank { Default }
}

/**
 * 视频源健康管理的节奏常量：启动后先等首查延迟（避开启动高峰），之后按固定周期复查。
 */
object SourceHealthSetting {
    /** 启动后首次后台体检的延迟：等首屏与延迟启动任务都跑完。 */
    const val InitialDelayMs = 90_000L
    /** 复查周期：30 分钟足够发现源失效，又不至于频繁打接口。 */
    const val IntervalMs = 30L * 60_000L
    /** 单轮体检的并发上限，与用户手动一键测速一致。 */
    const val Parallelism = 3
}

/**
 * 计算生效的自定义 UA：订阅 URL 参数 > 用户配置 > 内置默认的「用户配置 → 内置默认」部分。
 * 供视频接口请求、订阅拉取与直播播放兜底共用同一口径。
 */
fun AppSettings.effectiveUserAgent(): String = UserAgentSetting.resolve(customUserAgent)

/** 线路头部缺 UA 时补上兜底 UA；已有 UA（含大小写变体）则原样保留，头部不复制多余键。 */
fun Map<String, String>.withFallbackUserAgent(ua: String): Map<String, String> {
    if (ua.isBlank()) return this
    if (keys.any { it.equals("User-Agent", ignoreCase = true) }) return this
    return this + ("User-Agent" to ua)
}

/**
 * 解码器策略。三档（对照设置页方案）：
 * - [Auto]：自适应（默认「硬解优先」）：硬解优先，解码失败时由 [setEnableDecoderFallback] 自动回落到软解；
 * - [HardwareOnly]：强制硬解：选择器只保留硬件解码器；设备确实没有硬解时仍回退可用解码器，避免直接黑屏；
 * - [SoftwareOnly]：强制软解：选择器只保留非硬件加速的解码器；无可用软解时回退，避免黑屏。
 *
 * 工程未集成 FFmpeg 扩展解码器，软解/硬解均基于系统 MediaCodec 的解码器集合。
 */
enum class DecoderStrategy(val storageKey: String, val displayName: String, val description: String) {
    Auto("auto", "自适应", "硬解优先，解码失败自动回落到软解。"),
    HardwareOnly("hardware_only", "硬解", "只选择硬件解码器；设备无可用硬解时回退，避免黑屏。"),
    SoftwareOnly("software_only", "软解", "只选择软件解码器；硬件解码异常或黑屏时可切换，避免黑屏。"),
    ;

    companion object {
        fun fromStorageKey(value: String?): DecoderStrategy =
            entries.firstOrNull { it.storageKey == value } ?: Auto
    }
}

/** 播放倍速的默认值与合法区间；写入前统一收敛，避免旧数据或异常输入越界。 */
object PlayerSpeedSetting {
    const val Default = 1.0f
    const val Min = 0.5f
    const val Max = 2.0f

    fun clamp(value: Float): Float = value.coerceIn(Min, Max)
}

/** 画面亮度覆盖的默认值与合法区间；0=跟随系统。 */
object PlayerBrightnessSetting {
    const val Default = 0
    const val Min = 0
    const val Max = 100

    fun clamp(value: Int): Int = value.coerceIn(Min, Max)
}

/** 快进/快退步长（秒）的默认值与可选档位。 */
object FastSeekSetting {
    const val DefaultStepSeconds = 10
    val choices: List<Int> = listOf(5, 10, 15, 30)

    fun clamp(value: Int): Int = if (value in choices) value else DefaultStepSeconds
}

/**
 * 历史页布局模式。仅影响手机端历史页的封面展示密度；
 * 电视端历史页保持既有卡片布局。
 */
enum class HistoryLayout(val storageKey: String, val displayName: String) {
    Card("card", "卡片"),
    Compact("compact", "紧凑"),
    Grid("grid", "网格"),
    ;

    companion object {
        fun fromStorageKey(value: String?): HistoryLayout =
            entries.firstOrNull { it.storageKey == value } ?: Card
    }
}

/**
 * #277 直播切台频道名横幅时长。0 为「自动」：电视上停留更久（4s），手机 2s；
 * 非 0 值由用户指定，统一收敛到 [MinMs]..[MaxMs]。
 */
object LiveInfoBannerDuration {
    const val Auto = 0L
    const val TvDefaultMs = 4_000L
    const val MobileDefaultMs = 2_000L
    const val MinMs = 500L
    const val MaxMs = 10_000L
    val choices: List<Long> = listOf(Auto, 1_500L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L)

    fun clamp(value: Long): Long = value.coerceIn(Auto, MaxMs)

    /** 自动档按设备模式取默认时长；手动档仅在合法区间内生效。 */
    fun resolve(configured: Long, isTelevision: Boolean): Long =
        configured.takeIf { it >= MinMs }?.coerceIn(MinMs, MaxMs)
            ?: if (isTelevision) TvDefaultMs else MobileDefaultMs
}

/** #94 多源搜索/详情的单源超时，默认 8s（对照设置页方案，调小更快但易误杀）。 */
object PerSourceTimeout {
    const val DefaultMs = 8_000L
    const val MinMs = 1_000L
    const val MaxMs = 30_000L
    val choices: List<Long> = listOf(2_000L, 3_000L, 4_000L, 6_000L, DefaultMs, 10_000L, 15_000L)

    fun clamp(value: Long): Long = value.coerceIn(MinMs, MaxMs)
}

/** 一个数量类设置项：默认值、合法区间与可选档位。 */
data class QuantitySetting(
    val default: Int,
    val min: Int,
    val max: Int,
    val choices: List<Int>,
) {
    /** 越界值收敛到合法区间。 */
    fun clamp(value: Int): Int = value.coerceIn(min, max)
}

/**
 * 数量类设置项的默认值与可选档位。
 *
 * 默认值按「1080p 电视 + 现代手机」的实测体验重新调优，而不是沿用改造前的写死值：
 * 首屏内容量、搜索并发、热词/建议条数都取一档更充分的档位，用户仍可在设置里逐项改小。
 */
object QuantitySettings {
    /** 继续观看：横滑一行放不下时多给两条，避免刚看过的剧被挤到第二屏。 */
    val ContinueWatchingCount = QuantitySetting(8, 1, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    /** EPG 节目单：1080p 下 5 行仍可一屏看全，比 4 行多一档频道。 */
    val EpgCardRows = QuantitySetting(5, 2, 8, listOf(2, 3, 4, 5, 6, 8))
    /** 数字换台匹配：给足 8 行，覆盖「输入两个数字」时的常见命中范围。 */
    val LiveNumberMatchRows = QuantitySetting(8, 2, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    /** 数字换台搜索上限：一次匹配到 100 个频道足够，再大只是白算。 */
    val LiveNumberSearchLimit = QuantitySetting(
        LIVE_NUMBER_SEARCH_LIMIT,
        10,
        200,
        listOf(20, 30, 50, LIVE_NUMBER_SEARCH_LIMIT, 200),
    )
    val RelatedChannelCount = QuantitySetting(16, 4, 24, listOf(6, 8, 10, 12, 16, 20, 24))
    /** 搜索热词：24 条把三列铺满且不触发额外请求（并发拉取，条数上限不变）。 */
    val HotWordCount = QuantitySetting(24, 6, 30, listOf(12, 15, 18, 21, 24, 30))
    /** 搜索联想：6 条正好铺满下拉区，再多会盖住键盘上方的结果。 */
    val SearchSuggestionCount = QuantitySetting(6, 1, 8, listOf(1, 2, 3, 4, 5, 6, 8))
    /** 首页热播首屏：30 条（约 1.5 个豆瓣分页），滑到底会继续自动加载，不再是硬上限。 */
    val HomeHotContentCount = QuantitySetting(30, 4, 40, listOf(4, 6, 8, 10, 12, 16, 20, 30, 40))
    /** 豆瓣热播分页：30 条/页，兼顾首屏速度与加载更多的颗粒度。 */
    val DoubanHotPageSize = QuantitySetting(30, 5, 40, listOf(10, 15, 20, 25, 30, 40))
    /** 豆瓣不可用时的回退线路数：多试两条，提升首屏出片率。 */
    val DoubanHotFallbackLines = QuantitySetting(8, 1, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    /** 观看历史：200 条足够回溯，存储与内存开销可忽略。 */
    val HistoryMaxItems = QuantitySetting(200, 20, 500, listOf(50, 100, 200, 300, 500))
    /** 多源搜索并发：默认 6（对照设置页方案；弱盒建议 4，增量去重后收益递减）。 */
    val MaxParallelSourceRequests = QuantitySetting(6, 1, 12, listOf(2, 3, 4, 6, 8, 10, 12))
}

enum class DeviceMode(val storageKey: String, val displayName: String, val description: String) {
    /** 自动识别：按系统特征（Leanback/屏幕尺寸/触摸）判定，可被手动覆盖。 */
    Auto("auto", "自动识别", "根据设备特征自动切换电视/手机布局，也可手动覆盖。"),
    Television("tv", "强制电视", "电视和盒子保持横屏，竖屏短剧在横屏窗口中显示。"),
    Mobile("mobile", "强制手机", "应用默认竖屏；点播按影片比例切换横竖屏，电视直播可手动横屏。"),
    ;

    companion object {
        fun fromStorageKey(value: String?): DeviceMode =
            entries.firstOrNull { it.storageKey == value } ?: Auto

        /**
         * 根据系统特征计算自动识别结果，仅在 Auto 模式下参与计算，
         * 结果永不写回用户保存的 deviceMode（手动值优先级最高）。
         */
        fun detectFromSystem(context: android.content.Context): DeviceMode = try {
            when (readDeviceForm(context)) {
                DeviceForm.Television, DeviceForm.Tablet -> Television
                DeviceForm.Mobile -> Mobile
            }
        } catch (_: Exception) {
            // 判定异常时保守回退电视布局（10-foot UI 在手机上仍可用，反之不可）。
            Television
        }

        /**
         * 纯函数判定（便于单元测试），判定优先级：
         * Leanback/电视系统 > 无触摸或大屏（≥720dp）→ 电视；
         * 有触摸 + 小屏（<600dp）→ 手机；600–719dp 中间地带（平板/折叠屏）保守取电视。
         */
        internal fun detect(isLeanback: Boolean, screenWidthDp: Int, hasTouch: Boolean): DeviceMode =
            when (classifyDeviceForm(isLeanback, screenWidthDp, hasTouch)) {
                DeviceForm.Television, DeviceForm.Tablet -> Television
                DeviceForm.Mobile -> Mobile
            }
    }
}

/**
 * 设备形态原始分档：设备模式识别与平台直播 Go 桥接的 clientType 共用同一套系统特征判定，
 * 避免两处各自维护一份判定矩阵。[DeviceForm.Tablet] 是 600–719dp 有触摸的中间地带：
 * 设备模式按电视处理，Go 侧保留历史 web 档。
 */
internal enum class DeviceForm { Television, Mobile, Tablet }

internal fun classifyDeviceForm(
    isLeanback: Boolean,
    screenWidthDp: Int,
    hasTouch: Boolean,
): DeviceForm = when {
    isLeanback -> DeviceForm.Television
    !hasTouch || screenWidthDp >= 720 -> DeviceForm.Television
    screenWidthDp < 600 -> DeviceForm.Mobile
    else -> DeviceForm.Tablet
}

/** 读取系统特征并归并为设备形态；异常由调用方按各自兜底策略处理。 */
private fun readDeviceForm(context: android.content.Context): DeviceForm {
    val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE)
        as? android.app.UiModeManager
    val isLeanback = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    return classifyDeviceForm(
        isLeanback = isLeanback,
        screenWidthDp = context.resources.configuration.screenWidthDp,
        hasTouch = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN),
    )
}

/** 平台直播 Go 桥接的 clientType：与设备模式共用系统特征判定，保持原有 tv/mobile/web 三档。 */
fun detectClientType(context: android.content.Context): String = try {
    when (readDeviceForm(context)) {
        DeviceForm.Television -> "tv"
        DeviceForm.Mobile -> "mobile"
        DeviceForm.Tablet -> "web"
    }
} catch (_: Exception) {
    "web"
}

/** 计算生效的设备模式（纯函数）：手动值优先，Auto 时采用系统识别结果。 */
fun DeviceMode.resolveEffective(detected: DeviceMode): DeviceMode =
    when (this) {
        DeviceMode.Auto -> detected.takeIf { it != DeviceMode.Auto } ?: DeviceMode.Television
        else -> this
    }

/** 计算生效的设备模式：手动值优先，Auto 时读取系统识别结果。 */
fun AppSettings.effectiveMode(context: android.content.Context): DeviceMode =
    deviceMode.resolveEffective(DeviceMode.detectFromSystem(context))

/**
 * #54/#266 计算生效的解码策略：白名单按 Build.MODEL 前缀（逗号分隔、忽略大小写）匹配，
 * 命中时强制 [DecoderStrategy.HardwareOnly]；名单为空时保持用户选择的策略不变。
 */
fun AppSettings.effectiveDecoderStrategy(deviceModel: String = android.os.Build.MODEL): DecoderStrategy {
    val whitelist = decoderDeviceWhitelist
    if (whitelist.isBlank()) return decoderStrategy
    val matched = whitelist.split(',')
        .map { it.trim() }
        .any { prefix -> prefix.isNotEmpty() && deviceModel.startsWith(prefix, ignoreCase = true) }
    return if (matched) DecoderStrategy.HardwareOnly else decoderStrategy
}

enum class VideoScaleMode(
    val storageKey: String,
    val displayName: String,
    val description: String,
    /** 固定显示宽高比；null 表示沿用视频原始比例。 */
    val aspectRatio: Float? = null,
) {
    Fit("fit", "原始比例", "保持视频原始比例并完整显示；比例与屏幕不同时可能留黑边。"),
    Ratio16_9("ratio_16_9", "16:9", "按 16:9 比例显示，画面缩放到该比例。", 16f / 9f),
    Ratio4_3("ratio_4_3", "4:3", "按 4:3 比例显示，画面缩放到该比例。", 4f / 3f),
    Ratio1_1("ratio_1_1", "1:1", "按 1:1 比例显示，画面缩放到该比例。", 1f),
    Ratio21_9("ratio_21_9", "21:9", "按 21:9 比例显示，画面缩放到该比例。", 21f / 9f),
    Ratio9_16("ratio_9_16", "9:16 竖版", "按 9:16 竖版比例显示，画面缩放到该比例。", 9f / 16f),
    Zoom("zoom", "裁剪铺满", "保持原始比例铺满屏幕；画面边缘可能被裁剪。"),
    Fill("fill", "拉伸铺满", "铺满屏幕并完整显示；人物和画面比例可能变形。"),
    ;

    companion object {
        fun fromStorageKey(value: String?): VideoScaleMode =
            entries.firstOrNull { it.storageKey == value } ?: Fit
    }
}

enum class TvTheme(
    val storageKey: String,
    val displayName: String,
) {
    Default("default", "默认主题"),
    Cinema("cinema", "影院主题"),
    ;

    companion object {
        fun fromStorageKey(value: String?): TvTheme =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

enum class TvFontScale(
    val storageKey: String,
    val displayName: String,
    val typographyScale: Float,
) {
    Normal("normal", "正常", 1f),
    Large("large", "大", 1.18f),
    ExtraLarge("extra_large", "超大", 1.36f),
    ;

    companion object {
        fun fromStorageKey(value: String?): TvFontScale =
            entries.firstOrNull { it.storageKey == value } ?: Normal
    }
}

@Serializable
data class CustomVideoApiLine(
    val id: String,
    val name: String,
    val baseUrl: String,
) {
    fun toApiLine(): ApiLine {
        return ApiLine(
            id = id,
            name = name,
            baseUrls = listOf(baseUrl),
        )
    }
}

fun List<CustomVideoApiLine>.toApiLines(): List<ApiLine> {
    return map { it.toApiLine() }
}

/**
 * OTA 更新渠道：决定客户端拉取哪个 update.json 版本。
 * - [Stable]：正式版路径 update.json，对应 release.yml 每次发版后写入的 manifest。
 * - [Beta]：测试版路径 update-beta.json，测试者自行放置，用于预发布尝鲜。
 * - [Close]：关闭自动更新检查。
 */
enum class UpdateChannel(
    val storageKey: String,
    val displayName: String,
    val manifestPath: String?,
) {
    Stable("stable", "正式版", "update.json"),
    Beta("beta", "尝鲜", "update-beta.json"),
    Close("close", "关闭", null),
    ;

    companion object {
        fun fromStorageKey(value: String?): UpdateChannel =
            entries.firstOrNull { it.storageKey == value } ?: Stable
    }
}
