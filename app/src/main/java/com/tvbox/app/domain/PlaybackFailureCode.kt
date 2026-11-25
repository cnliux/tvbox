package com.tvbox.app.domain

/**
 * T3-22：播放失败的**结构化错误码**。用户贴 `E-202 · 月光▪4f2a · 线路2 · RK3368 · 1.0.42`
 * 时不需要第二句追问，因此码必须同时进 OSD / 遥测 tag / 导出日志 / PlaybackException。
 *
 * 本文件只提供可单测的 domain 契约（sealed 的等价物 = enum + exhaustive `when`）：
 * 新增一个码时 [describeFailure] 的 `when` 没有 else，编译期强制补齐四处展示逻辑。
 */

/** 失败来源：远端（源站）与本地（设备/代理）分开，决定提示语气与自愈动作。 */
enum class FailureOrigin { REMOTE, LOCAL }

/** 自动补救动作；UI 层按它决定是否弹「换线」按钮，仲裁器按它计数。 */
enum class FailureRemedy {
    SwitchLine,
    RetryWithUa,
    CooldownSource,
    FallbackAudio,
    RequireLogin,
    CheckNetwork,
    CheckStorage,
    CheckProxy,
    None,
}

enum class PlaybackFailureCode(
    val code: String,
    val origin: FailureOrigin,
    val title: String,
    val remedy: FailureRemedy,
) {
    ParseFailed("E-101", FailureOrigin.REMOTE, "解析失败", FailureRemedy.None),
    NoCandidate("E-102", FailureOrigin.REMOTE, "无候选线路", FailureRemedy.SwitchLine),
    ConnectionRefused("E-201", FailureOrigin.REMOTE, "连接被拒", FailureRemedy.SwitchLine),
    ForbiddenOrGone("E-202", FailureOrigin.REMOTE, "403/404（Referer/UA 或资源下架）", FailureRemedy.RetryWithUa),
    RateLimited("E-203", FailureOrigin.REMOTE, "429 被源站限流", FailureRemedy.CooldownSource),
    ContainerUnsupported("E-301", FailureOrigin.LOCAL, "容器/编码不支持", FailureRemedy.None),
    AudioPassthrough("E-302", FailureOrigin.LOCAL, "音频直通失败", FailureRemedy.FallbackAudio),
    DrmLocked("E-401", FailureOrigin.REMOTE, "需要平台登录态", FailureRemedy.RequireLogin),
    LocalOom("E-501", FailureOrigin.LOCAL, "本地内存不足", FailureRemedy.None),
    LocalDiskFull("E-502", FailureOrigin.LOCAL, "本地磁盘已满", FailureRemedy.CheckStorage),
    LocalProxy("E-503", FailureOrigin.LOCAL, "代理不通", FailureRemedy.CheckProxy),
    ;

    companion object {
        /** HTTP 状态码 → 失败码；未知状态返回 null，由调用方按 Throwable 再判。 */
        fun fromHttpStatus(status: Int): PlaybackFailureCode? = when (status) {
            403, 404 -> ForbiddenOrGone
            429 -> RateLimited
            in 500..599 -> ConnectionRefused
            else -> null
        }
    }
}

/** 用户可读的一行展示，OSD / 设置页 / 导出三处共用同一份文案。 */
data class PlaybackFailurePresentation(
    val code: String,
    val title: String,
    val origin: FailureOrigin,
    val remedy: FailureRemedy,
    val userHint: String,
)

/** exhaustive `when`：新增 [PlaybackFailureCode] 不补这里就编译不过。 */
fun describeFailure(failure: PlaybackFailureCode): PlaybackFailurePresentation {
    val hint = when (failure) {
        PlaybackFailureCode.ParseFailed -> "源返回了无法识别的数据，换一条线路试试"
        PlaybackFailureCode.NoCandidate -> "这个源没有可用线路，请换源"
        PlaybackFailureCode.ConnectionRefused -> "源站主动断开，常见于防盗链"
        PlaybackFailureCode.ForbiddenOrGone -> "先用备用 UA 重试一次，仍失败则换源"
        PlaybackFailureCode.RateLimited -> "我们被这个源限流了，15 分钟后再试"
        PlaybackFailureCode.ContainerUnsupported -> "这台盒子解码不了该格式，换低码率线路"
        PlaybackFailureCode.AudioPassthrough -> "已回落到立体声重新准备播放器"
        PlaybackFailureCode.DrmLocked -> "这个房间需要平台登录态，本应用刻意不支持"
        PlaybackFailureCode.LocalOom -> "本机内存不足，请重启应用或减少后台程序"
        PlaybackFailureCode.LocalDiskFull -> "本机存储空间不足，清理后再试"
        PlaybackFailureCode.LocalProxy -> "检查代理地址与端口是否可达"
    }
    return PlaybackFailurePresentation(
        code = failure.code,
        title = failure.title,
        origin = failure.origin,
        remedy = failure.remedy,
        userHint = hint,
    )
}

/**
 * 一次失败的「可复制报告」：只有枚举码 + 短指纹 + 线路名 + 设备标签，
 * 绝不携带片名 / 搜索词 / 完整源 URL（对齐 T3-19 编译期禁 PII）。
 */
data class PlaybackFailureReport(
    val code: PlaybackFailureCode,
    val siteFingerprint: String? = null,
    val lineLabel: String? = null,
    val deviceTag: String? = null,
) {
    val shareText: String
        get() = buildList {
            add(code.code)
            siteFingerprint?.takeIf { it.isNotBlank() }?.let { add("▪${safeFingerprint(it)}") }
            lineLabel?.takeIf { it.isNotBlank() }?.let { add(safeToken(it)) }
            deviceTag?.takeIf { it.isNotBlank() }?.let { add(safeToken(it)) }
        }.joinToString(" · ")

    companion object {
        /** 指纹只保留 8 个十六进制字符，避免把订阅 URL 误贴进来。 */
        fun safeFingerprint(raw: String): String =
            raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(8).lowercase()

        /** 线路/设备标签限长并去分隔符，保证一行可贴。 */
        fun safeToken(raw: String): String = raw.trim().take(24).replace(Regex("[\\r\\n·]"), " ")
    }
}

/**
 * T3-22：失败报告「一键复制/分享」文本的纯函数出口。
 *
 * 复用 [PlaybackFailureReport.shareText] 的结构化拼接（E-xxx + 短指纹 + 线路 + 设备），
 * 不依赖 Android / 剪贴板；UI 拿到字符串后自行复制，单测可直接锁定脱敏契约。
 */
fun PlaybackFailureCode.shareText(
    siteFingerprint: String? = null,
    lineLabel: String? = null,
    deviceTag: String? = null,
): String = PlaybackFailureReport(
    code = this,
    siteFingerprint = siteFingerprint,
    lineLabel = lineLabel,
    deviceTag = deviceTag,
).shareText
