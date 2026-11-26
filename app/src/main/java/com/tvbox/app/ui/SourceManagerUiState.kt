package com.tvbox.app.ui

import androidx.compose.runtime.Immutable
import com.tvbox.app.domain.ResolvedSite
import com.tvbox.app.domain.SiteCandidate
import com.tvbox.app.domain.SiteResolver

/**
 * 源管理/测速/导入 UI 状态（从 [TvBoxUiState] 拆出；字段语义与原 `source*`/`detailSource*` 保持一致）。
 * S5-26：字段全 val（latencies 为不可变 Map），标注 [Immutable]，避免该切片参与整棵子树重组。
 */
@Immutable
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
    /** T2-13：展开态——当前展开查看 shadowed 来源的 resolved site winner name；null 表示全部折叠。 */
    val expandedConflictName: String? = null,
)

/** T2-11/T2-13：按显示名把 [allSourceLines] 解析成候选集并去重冲突。 */
fun resolveSourceCandidates(allSourceLines: List<com.tvbox.app.domain.ApiLine>): List<ResolvedSite> {
    val candidates = allSourceLines.mapIndexed { index, line ->
        // subscriptionId="custom" 表示自定义/手动添加源；有订阅元数据时可替换为真实 id。
        SiteCandidate(
            name = line.name,
            subscriptionId = "custom",
            siteKey = line.id,
            order = index,
        )
    }
    return SiteResolver.resolve(candidates)
}
