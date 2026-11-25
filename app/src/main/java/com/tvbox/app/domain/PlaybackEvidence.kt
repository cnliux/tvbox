package com.tvbox.app.domain

/**
 * 播放管家的证据事件模型：一次可解释的播放决策留下一条记录。
 *
 * 纯 domain 模型：不依赖 Android / Compose，便于 JVM 单测。
 * 时间轴展示由 UI 层负责，这里只保存原始时间戳 [atMs]。
 */
data class PlaybackEvidence(
    val atMs: Long,
    /** 线路标识（例如线路名或播放源 id）。 */
    val source: String,
    val event: PlaybackEvidenceEvent,
    /** 事件详情，例如耗时、错误码。 */
    val detail: String = "",
    /** 该事件采取的动作及结果，例如「已切换到线路 B」。 */
    val actionResult: String = "",
)

/** 证据事件类型：覆盖选线、播放、缓冲、失败与人工干预。 */
enum class PlaybackEvidenceEvent(val label: String) {
    SourceSelected("选线"),
    PlaybackStarted("开始播放"),
    FirstFrame("首帧"),
    BufferStall("卡顿缓冲"),
    PlaybackError("播放失败"),
    AutoSwitch("自动换线"),
    ManualSwitch("手动换线"),
    Seek("拖动进度"),
    SourceLocked("手动锁线"),
    QualityDowngraded("画质降级"),
    ;
}

/** 证据环形缓冲上限：只保留最近 50 条。 */
const val PLAYBACK_EVIDENCE_MAX_ENTRIES = 50

/**
 * 追加一条证据；保持「最新在前」顺序并按 [limit] 环形截断。纯函数，返回新列表。
 *
 * 证据只走播放管家 Repository 的内存结构，不写入 Dropbox，也不上报远端。
 */
fun appendEvidence(
    current: List<PlaybackEvidence>,
    evidence: PlaybackEvidence,
    limit: Int = PLAYBACK_EVIDENCE_MAX_ENTRIES,
): List<PlaybackEvidence> {
    val capacity = limit.coerceAtLeast(0)
    if (capacity == 0) return emptyList()
    return (listOf(evidence) + current).take(capacity)
}

/** 取最近 [limit] 条证据（已是最新在前）。纯函数。 */
fun recentEvidence(
    all: List<PlaybackEvidence>,
    limit: Int = PLAYBACK_EVIDENCE_MAX_ENTRIES,
): List<PlaybackEvidence> = all.take(limit.coerceAtLeast(0))
