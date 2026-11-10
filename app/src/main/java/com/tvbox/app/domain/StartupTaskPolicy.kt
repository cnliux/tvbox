package com.tvbox.app.domain

/**
 * 冷启动任务分级（第 81 轮）。
 *
 * 现状：`TvBoxViewModel.init{}` 在同一个协程里依次做设置读取、历史、首页、
 * OTA 检查与直播 EPG 加载；OTA / EPG 并非首屏必需，却与首页取数竞争协程与网络。
 *
 * 这里只定义「哪个阶段执行、延后多久」的策略，具体调度由调用方的
 * `viewModelScope.launch` + `delay` 实现，便于 JVM 单测直接覆盖。
 */
enum class StartupTaskPhase {
    /** 首屏渲染前必须完成：设置、首页首帧数据。 */
    BeforeFirstFrame,

    /** 首帧之后即可执行，短延后，不能阻塞首屏。 */
    AfterFirstFrame,

    /** 空闲时执行，允许较长延后。 */
    Idle,
}

enum class StartupTask {
    Settings,
    PlaybackHealth,
    SpeedLatencies,
    History,
    HomeFeed,
    OtaCheck,
    LiveEpg,
    PlatformLiveInit,
}

fun startupPhaseOf(task: StartupTask): StartupTaskPhase = when (task) {
    StartupTask.Settings,
    StartupTask.History,
    StartupTask.HomeFeed,
    -> StartupTaskPhase.BeforeFirstFrame

    StartupTask.PlaybackHealth,
    StartupTask.SpeedLatencies,
    StartupTask.OtaCheck,
    -> StartupTaskPhase.AfterFirstFrame

    StartupTask.LiveEpg,
    StartupTask.PlatformLiveInit,
    -> StartupTaskPhase.Idle
}

/** 相对首帧的延后毫秒；首屏前任务为 0。 */
fun startupDeferralMs(task: StartupTask): Long = when (startupPhaseOf(task)) {
    StartupTaskPhase.BeforeFirstFrame -> 0L
    StartupTaskPhase.AfterFirstFrame -> 800L
    StartupTaskPhase.Idle -> 3_000L
}
