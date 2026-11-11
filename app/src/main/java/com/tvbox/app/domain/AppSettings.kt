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
    /** 首页视频卡片每行数量；0 表示跟随布局自适应。 */
    val videoGridColumns: Int = 0,
    /** 电视直播节目单 XMLTV 接口地址；空表示未配置。 */
    val epgUrl: String = "",
    /** 平台直播卡片每行数量；0 表示跟随布局默认（触屏 4 / 遥控 6）。 */
    val platformLiveGridColumns: Int = 0,
    /** 首页「继续观看」卡片数量。 */
    val continueWatchingCount: Int = QuantitySettings.ContinueWatchingCount.default,
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
    /** 首页「豆瓣热播」区域显示的条目数上限。 */
    val homeHotContentCount: Int = QuantitySettings.HomeHotContentCount.default,
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
)

/**
 * 解码器策略。只区分两档：
 * - [Auto]：默认硬解优先，解码失败时由 [setEnableDecoderFallback] 自动回落到软件解码；
 * - [HardwareOnly]：选择器只返回硬件解码器；设备确实没有硬解时仍回退可用解码器，避免直接黑屏。
 *
 * 工程未集成 FFmpeg 扩展解码器，因此不提供「强制软解」档。
 */
enum class DecoderStrategy(val storageKey: String, val displayName: String, val description: String) {
    Auto("auto", "自动", "硬解优先，失败时自动回落到软解。"),
    HardwareOnly("hardware_only", "强制硬解", "只选择硬件解码器；设备无可用硬解时回退，避免黑屏。"),
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
    /** 多源搜索并发：默认拉满 12（上限同值），修复「多个源只搜到 1~2 个内容」。 */
    val MaxParallelSourceRequests = QuantitySetting(12, 1, 12, listOf(2, 3, 4, 6, 8, 10, 12))
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
            val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager
            val isLeanback = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            detect(
                isLeanback = isLeanback,
                screenWidthDp = context.resources.configuration.screenWidthDp,
                hasTouch = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN),
            )
        } catch (_: Exception) {
            // 判定异常时保守回退电视布局（10-foot UI 在手机上仍可用，反之不可）。
            Television
        }

        /**
         * 纯函数判定（便于单元测试），判定优先级：
         * Leanback/电视系统 > 无触摸或大屏（≥720dp）→ 电视；
         * 有触摸 + 小屏（<600dp）→ 手机；600–719dp 中间地带（平板/折叠屏）保守取电视。
         */
        internal fun detect(isLeanback: Boolean, screenWidthDp: Int, hasTouch: Boolean): DeviceMode = when {
            isLeanback -> Television
            !hasTouch || screenWidthDp >= 720 -> Television
            screenWidthDp < 600 -> Mobile
            else -> Television
        }
    }
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
