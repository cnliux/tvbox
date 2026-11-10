package com.tvbox.app.domain

/**
 * 启动与体积度量的纯计算层（第 108/109/147/148/149/150 轮）。
 * 时间线、帧时间指标与治理清单与采集脚本解耦，采集端只负责打点。
 */

enum class StartupPhase { ProcessStart, ApplicationInit, FirstFrame, Interactive, Idle }

data class StartupTimeline(val marks: Map<StartupPhase, Long> = emptyMap()) {
    fun record(phase: StartupPhase, atMs: Long): StartupTimeline =
        copy(marks = marks + (phase to atMs))
}

/** 相邻阶段耗时；缺失的阶段不产出数据，避免用 0 掩盖未打点。 */
fun StartupTimeline.phaseDurations(): Map<StartupPhase, Long> {
    val phases = StartupPhase.entries
    return buildMap {
        phases.zipWithNext().forEach { (from, to) ->
            val start = marks[from] ?: return@forEach
            val end = marks[to] ?: return@forEach
            if (end >= start) put(to, end - start)
        }
    }
}

fun startupTotalMs(timeline: StartupTimeline): Long? {
    val start = timeline.marks[StartupPhase.ProcessStart] ?: return null
    val end = timeline.marks[StartupPhase.Interactive] ?: return null
    return (end - start).takeIf { it >= 0L }
}

data class FrameTimingSample(val frameCount: Int, val jankyFrames: Int)

fun jankRatio(sample: FrameTimingSample): Double =
    if (sample.frameCount <= 0) 0.0
    else sample.jankyFrames.coerceAtLeast(0).toDouble() / sample.frameCount

/** Compose 编译器指标开启后需要逐一复核的不稳定参数类（按现状清单，后续按指标结果裁剪）。 */
val COMPOSE_STABILITY_CHECKLIST: List<String> = listOf(
    "ui/TvBoxUiState.kt: 超大聚合 State，任一字段变化触发整棵首页子树重组",
    "ui/PlayerChromeData.kt: 集合类型参数未标注 @Immutable/@Stable",
    "ui/LiveChannel.kt: 线路列表为普通 List，建议稳定快照",
    "ui/settings 分组数据：lambda 捕获 ViewModel 时非稳定",
)

/** proguard 全量 keep 的收紧路径：每一步都要求 Release 回归与 Baseline Profile 复采。 */
val PROGUARD_TIGHTENING_STEPS: List<String> = listOf(
    "① 把 -keep class com.tvbox.app.ui.** { *; } 收窄到被反射/Baseline Profile 引用的类",
    "② apkanalyzer / R8 报告对比收紧前后 dex 与资源体积",
    "③ 重新采集 Baseline Profile，确认首屏类仍在 profile 中",
    "④ CI Release 冒烟：冷启动、播放器、直播、设置页四路径无 NoSuchMethod/ClassNotFound",
)

val STARTUP_MEASURE_COMMANDS: List<String> = listOf(
    "adb shell am start -W -S <pkg>/.MainActivity   # 冷启动 TotalTime / WaitTime",
    "adb shell dumpsys gfxinfo <pkg> framestats     # 首帧与滚动帧时间",
    "apkanalyzer apk compare --different-only old.apk new.apk",
)
