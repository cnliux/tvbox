package com.tvbox.app.ui.settings.remote

import com.tvbox.app.domain.RemoteBindingConflict
import com.tvbox.app.domain.RemoteKeyAction
import com.tvbox.app.domain.RemoteKeyBindings
import com.tvbox.app.domain.remoteBindingConflicts

/**
 * 遥控器自定义键位绑定的**共享状态层**（P2-13）。
 *
 * 手机版 LazyColumn 与 TV 版焦点网格共用同一份「已绑键清单」模型，
 * 避免 v1.0.41 那种"手机改了 TV 忘了"的漂移——两侧永远渲染同一数据源。
 * 布局（点击行 vs 遥控器焦点行）由各端 Composable 分叉，但行模型与摘要文本在此单点派生。
 *
 * P1-09：行模型附带**冲突标记**（同一物理键被多个动作认领），
 * 两侧都从同一个 [remoteBindingConflicts] 派生，红字与"保留哪个"入口不会漂移。
 *
 * @param bindings 当前生效的绑定表（默认 + 用户自定义合并后的结果）。
 */
data class RemoteBindingRow(
    val action: RemoteKeyAction,
    val displayName: String,
    /** 已绑键摘要文本（如 `0x055 / 0x07F`），两侧必须渲染同一份。 */
    val boundKeysSummary: String,
    /** 该动作是否有用户自定义键（决定是否显示「清除」入口）。 */
    val hasCustomBinding: Boolean,
    /** P1-09：该动作参与的按键冲突（空列表表示无冲突）。 */
    val conflicts: List<RemoteBindingConflict> = emptyList(),
)

/** 由绑定表派生所有动作的共享行模型；手机/TV 两侧消费同一结果，保证摘要文本一致。 */
fun remoteBindingRows(bindings: RemoteKeyBindings): List<RemoteBindingRow> {
    val conflicts = remoteBindingConflicts(bindings)
    return RemoteKeyAction.entries.map { action ->
        val keys = bindings.keysFor(action)
        val customKeys = bindings.custom[action.id].orEmpty()
        RemoteBindingRow(
            action = action,
            displayName = action.displayName,
            boundKeysSummary = if (keys.isEmpty()) {
                "未绑定"
            } else {
                keys.sorted().joinToString(" / ") { "0x%03X".format(it) }
            },
            hasCustomBinding = customKeys.isNotEmpty(),
            conflicts = conflicts.filter { action in it.actions },
        )
    }
}
