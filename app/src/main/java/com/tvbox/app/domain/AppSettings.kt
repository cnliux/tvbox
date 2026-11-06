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
    /** 直播源（常用分类直达）卡片显示数量；0 表示跟随设备默认。 */
    val liveSourceCardCount: Int = 0,
    /** 直播平台卡片显示数量；0 表示跟随设备默认。 */
    val platformLiveCardCount: Int = 0,
)

/** 卡片区最多展示的行数，超出部分折叠为「更多」入口。 */
const val CARD_AREA_MAX_ROWS = 3

/** 订阅源卡片折叠上限（单列列表，单位为卡片数）。 */
const val SOURCE_CARD_MAX_COUNT = 10

/**
 * 把卡片数量收敛到当前布局可容纳的整行槽位数：
 * - [requested] <= 0 时使用 [defaultSlots]（设备默认值）；
 * - 结果按 [columns] 取整，避免出现半行或空位；
 * - 下限一行，上限 [maxRows] 行。
 */
fun resolveCardSlotCount(
    requested: Int,
    columns: Int,
    defaultSlots: Int,
    maxRows: Int = CARD_AREA_MAX_ROWS,
): Int {
    val cols = columns.coerceAtLeast(1)
    val maxSlots = cols * maxRows.coerceAtLeast(1)
    val target = (if (requested > 0) requested else defaultSlots).coerceIn(cols, maxSlots)
    return (target / cols) * cols
}

/** 卡片区的折叠结果：可见内容数量与末尾是否用「更多」补满最后一个槽位。 */
data class CardAreaLayout(
    /** 直接渲染的内容卡片数量（不含「更多」入口）。 */
    val visibleCount: Int,
    /** 内容超出槽位时，用最后一个槽位显示「更多」。 */
    val showMore: Boolean,
) {
    /** 内容卡片 + 「更多」入口共占用的槽位数。 */
    val slotCount: Int get() = visibleCount + if (showMore) 1 else 0
}

/** 按布局槽位计算卡片区渲染方式；内容不超出时全部展示。 */
fun resolveCardAreaLayout(
    total: Int,
    requested: Int,
    columns: Int,
    defaultSlots: Int,
    maxRows: Int = CARD_AREA_MAX_ROWS,
): CardAreaLayout {
    val slots = resolveCardSlotCount(requested, columns, defaultSlots, maxRows)
    val safeTotal = total.coerceAtLeast(0)
    return if (safeTotal <= slots) {
        CardAreaLayout(visibleCount = safeTotal, showMore = false)
    } else {
        // 「更多」占用最后一个整行槽位，因此内容少显示一张，保证末行仍然铺满。
        CardAreaLayout(visibleCount = (slots - 1).coerceAtLeast(0), showMore = true)
    }
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
