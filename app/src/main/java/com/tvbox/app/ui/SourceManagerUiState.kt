package com.tvbox.app.ui

/** 源管理/测速/导入 UI 状态（从 [TvBoxUiState] 拆出；字段语义与原 `source*`/`detailSource*` 保持一致）。 */
data class SourceManagerUiState(
    val latencies: Map<String, Long?> = emptyMap(),
    val testing: Boolean = false,
    val testCompleted: Int = 0,
    val testTotal: Int = 0,
    val managerMessage: String? = null,
    val detailTesting: Boolean = false,
    val detailTestCompleted: Int = 0,
    val detailTestTotal: Int = 0,
    val detailMessage: String? = null,
    val importing: Boolean = false,
    val importMessage: String? = null,
    /** 视频源健康管理：后台体检进行中（静默，不阻塞用户手动测速入口）。 */
    val healthCheckRunning: Boolean = false,
    /** 最近一轮后台体检的摘要文本；null 表示尚未体检过。 */
    val lastHealthCheckSummary: String? = null,
)
