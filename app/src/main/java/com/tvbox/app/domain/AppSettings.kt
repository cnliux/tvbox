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
)

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
 * 默认值必须等于原写死值，保证升级后行为不变。
 */
object QuantitySettings {
    val ContinueWatchingCount = QuantitySetting(6, 1, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    val EpgCardRows = QuantitySetting(4, 2, 8, listOf(2, 3, 4, 5, 6, 8))
    val LiveNumberMatchRows = QuantitySetting(6, 2, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    val LiveNumberSearchLimit = QuantitySetting(
        LIVE_NUMBER_SEARCH_LIMIT,
        10,
        200,
        listOf(20, 30, LIVE_NUMBER_SEARCH_LIMIT, 100, 200),
    )
    val RelatedChannelCount = QuantitySetting(12, 4, 24, listOf(6, 8, 10, 12, 16, 20, 24))
    val HotWordCount = QuantitySetting(18, 6, 30, listOf(12, 15, 18, 21, 24, 30))
    val SearchSuggestionCount = QuantitySetting(3, 1, 8, listOf(1, 2, 3, 4, 5, 6, 8))
    val HomeHotContentCount = QuantitySetting(20, 4, 40, listOf(4, 6, 8, 10, 12, 16, 20, 30, 40))
    val DoubanHotPageSize = QuantitySetting(20, 5, 40, listOf(10, 15, 20, 25, 30, 40))
    val DoubanHotFallbackLines = QuantitySetting(6, 1, 12, listOf(3, 4, 5, 6, 8, 10, 12))
    val HistoryMaxItems = QuantitySetting(100, 20, 500, listOf(50, 100, 200, 300, 500))
    val MaxParallelSourceRequests = QuantitySetting(3, 1, 8, listOf(1, 2, 3, 4, 6, 8))
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

enum class VideoScaleMode(val storageKey: String, val displayName: String, val description: String) {
    Fit("fit", "自适应", "保持原始比例并完整显示；影片与屏幕比例不同时可能留黑边。"),
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
