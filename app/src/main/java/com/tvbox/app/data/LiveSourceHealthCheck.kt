package com.tvbox.app.data

import com.tvbox.app.crash.AnrWatchdog
import com.tvbox.app.domain.AnrConservativePolicy
import com.tvbox.app.domain.dryRunLiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * #282：单个直播源健康检查超时，避免一条死链拖住整组检测。
 * S5-29：超时不再写死，按 AnrWatchdog 是否处于保守降级模式经 [AnrConservativePolicy.probeTimeoutMs] 取值
 * （正常 8s，降级 3s：主线程卡过时尽快放弃死链、把线程让回去）。
 */
private fun liveSourceHealthTimeoutMs(): Long =
    AnrConservativePolicy.probeTimeoutMs(AnrWatchdog.isConservativeMode)

/**
 * 健康探针并发上限：一次性把几十~上百个源全部 `async` 会挤占连接池/带宽并占满低端盒子的线程，
 * 与 [AnrConservativePolicy] 的 ANR 降级目标相悖。这里用信号量做闸门，只限并发、不改结果聚合语义。
 *
 * 档位沿用策略对象口径：正常 8，降级（[AnrWatchdog.isConservativeMode]）压到 3，与
 * [AnrConservativePolicy.searchParallelism] 的降级档（6→3）保持一致，避免自造另一套重复逻辑。
 */
private const val MAX_CONCURRENT_HEALTH_PROBES = 8
private const val MAX_CONCURRENT_HEALTH_PROBES_CONSERVATIVE = 3

private fun healthProbePermits(): Int =
    if (AnrWatchdog.isConservativeMode) MAX_CONCURRENT_HEALTH_PROBES_CONSERVATIVE
    else MAX_CONCURRENT_HEALTH_PROBES

/**
 * #282：一键检测所有直播源。
 * 复用导入流程里的 [dryRunLiveSource]：先取源正文，再做结构校验并汇总每个源的结论。
 * 并发 + 单源超时，结果与现有「导入摘要」口径一致（频道/线路/分组数 + 警告）。
 *
 * 原先放在 TV 设置 UI 文件里，手机/TV 两个设置页都在调用；按分层归属移入 data 层，
 * 订阅拉取走 [SourceConfigFetcher]（支持 `|User-Agent=` 行内参数与全局自定义 UA）。
 */
internal suspend fun checkAllLiveSources(urls: List<String>): String = withContext(Dispatchers.IO) {
    val targets = urls.map { it.trim() }.filter { it.isNotEmpty() }
    if (targets.isEmpty()) return@withContext "尚未配置直播源，请先在「IPTV 直播源」中添加。"

    val fetcher = SourceConfigFetcher()
    val permits = healthProbePermits()
    val semaphore = Semaphore(permits)
    val results = coroutineScope {
        targets.map { url ->
            async {
                semaphore.withPermit {
                    val body = try {
                        withTimeout(liveSourceHealthTimeoutMs()) { fetcher.fetch(url) }
                    } catch (_: TimeoutCancellationException) {
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    url to body?.let(::dryRunLiveSource)
                }
            }
        }.awaitAll()
    }
    val okCount = results.count { (_, dryRun) -> dryRun?.ok == true }
    buildString {
        append("直播源健康检查完成：成功 $okCount 个，失败 ${results.size - okCount} 个。")
        results.forEach { (url, dryRun) ->
            append('\n').append("· ")
            if (dryRun == null) {
                append(url.takeLast(40)).append("：连接失败或超时")
            } else {
                append(dryRun.headline())
                val warnings = dryRun.warnings()
                if (warnings.isNotEmpty()) append("；").append(warnings.joinToString("，"))
                if (!dryRun.ok) append("（").append(url.takeLast(40)).append("）")
            }
        }
    }
}
