package com.tvbox.app.domain

/**
 * 直播换线三态：把「用户主动切 / 异常自动切 / 起播超时切」收敛到同一个判定入口，
 * 避免两条路径同时发请求，也避免失败时无限轮换线路。
 */
enum class LineSwitchTrigger {
    /** 遥控器上下键主动切线路，优先级最高。 */
    User,

    /** 播放器硬错误等异常触发的自动换线。 */
    AutoFailure,

    /** 起播超时触发的自动换线。 */
    StartTimeout,
}

data class LineSwitchRequest(
    val trigger: LineSwitchTrigger,
    val lineCount: Int,
    val currentLine: Int,
    val generation: Long,
    val inFlight: Boolean,
    val autoSwitchCount: Int,
)

sealed interface LineSwitchDecision {
    /** [delayMs] 为退避等待；单线路时 targetLine 保持 0，代表原地重解析而非换线。 */
    data class Switch(val targetLine: Int, val generation: Long, val delayMs: Long) : LineSwitchDecision
    data class Ignored(val reason: String) : LineSwitchDecision
    data object Exhausted : LineSwitchDecision
}

/**
 * 判定一次换线请求。
 *
 * - 用户主动切：即使有在途请求也允许（新意图应覆盖旧请求），且不占用自动换线次数；
 * - 自动切：有在途请求时直接忽略（消除重复请求与抖动），连续失败按指数退避；
 * - 单候选线路：自动切退化为「同线路退避重解析」，避免重新解析导致起播延迟翻倍；
 * - 自动次数用尽：返回 Exhausted，由 UI 给出「重试 / 下一台」出口。
 */
fun decideLineSwitch(
    request: LineSwitchRequest,
    maxAutoSwitches: Int = 3,
    backoffBaseMs: Long = 300L,
): LineSwitchDecision {
    if (request.lineCount <= 0) {
        return LineSwitchDecision.Ignored("无可用线路")
    }
    if (request.trigger == LineSwitchTrigger.User) {
        if (request.lineCount == 1) {
            return LineSwitchDecision.Ignored("只有一条线路")
        }
        return LineSwitchDecision.Switch(
            targetLine = (request.currentLine + 1) % request.lineCount,
            generation = request.generation + 1,
            delayMs = 0L,
        )
    }
    if (request.inFlight) {
        return LineSwitchDecision.Ignored("已有在途请求，避免重复换线")
    }
    if (request.autoSwitchCount >= maxAutoSwitches) {
        return LineSwitchDecision.Exhausted
    }
    val shift = request.autoSwitchCount.coerceAtLeast(0).coerceAtMost(6)
    val delayMs = backoffBaseMs shl shift
    // 单线路不换下标：仅退避后原地重解析，复用「单候选线路退避重解析」的既有结论。
    val targetLine = if (request.lineCount == 1) {
        0
    } else {
        (request.currentLine + 1) % request.lineCount
    }
    return LineSwitchDecision.Switch(targetLine, request.generation + 1, delayMs)
}
