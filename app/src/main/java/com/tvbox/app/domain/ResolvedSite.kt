package com.tvbox.app.domain

import java.security.MessageDigest

/**
 * T2-13：多订阅按站点显示名合并时，保留全量并显式标记胜出者。
 *
 * 旧实现 `associateBy { it.key }` 让后导入者覆盖前者，而 UI 仍按订阅序显示：
 * 用户点的是「月光(A)」，请求却打给「月光(B)」。这里不再丢弃被覆盖项，
 * 而是把「谁赢了、谁被挡在后面」变成可见数据（设置页可展开手动改胜出者）。
 */
data class SiteCandidate(
    /** 展示名；同名冲突的判定键。 */
    val name: String,
    /** 订阅来源 id，用于生成 siteId 指纹。自定义源时使用 "custom"。 */
    val subscriptionId: String,
    /** 源内 key（siteKey），仅作为展示与兼容，不再作为身份主键。 */
    val siteKey: String,
    /** 订阅内序号，越小越靠前。 */
    val order: Int,
) {
    /** 完整 SHA-256 十六进制哈希（64 字符），供调试用。 */
    val rawHash: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("$subscriptionId|$siteKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    /**
     * 16 字符稳定标识，用作偏好/评分/屏蔽地图的主键。
     * 跨会话不变（只要同一订阅下的同一 siteKey），且不会暴露原始订阅 ID。
     * 对应任务 T2-11：所有以 siteKey 为 map key 的地方改为使用此字段。
     */
    val siteId: String get() = rawHash.take(16)

    /** 8 字符短指纹：稳定、可贴 issue，避免用户误认同名源。 */
    val fingerprint: String get() = rawHash.take(8)
}

/** [winner] 是本次真正发起请求的源；[shadowed] 是被同名覆盖但仍保留的源。 */
data class ResolvedSite(val winner: SiteCandidate, val shadowed: List<SiteCandidate>) {
    val hasConflict: Boolean get() = shadowed.isNotEmpty()
}

object SiteResolver {

    /**
     * 按首次出现顺序返回 [ResolvedSite]；同名源只产出一条，
     * 胜出者默认取第一个，除非 [winnerFingerprints] 里显式指定了别的指纹。
     */
    fun resolve(
        candidates: List<SiteCandidate>,
        winnerFingerprints: Set<String> = emptySet(),
    ): List<ResolvedSite> {
        val grouped = LinkedHashMap<String, MutableList<SiteCandidate>>()
        candidates.forEach { grouped.getOrPut(it.name) { mutableListOf() } += it }
        return grouped.values.map { group ->
            val winner = group.firstOrNull { it.fingerprint in winnerFingerprints } ?: group.first()
            ResolvedSite(winner, group.filter { it !== winner })
        }
    }

    /** 设置页一句话：「本次合并 37 个源，2 个同名冲突（已按你上次的选择）」。 */
    fun summarize(resolved: List<ResolvedSite>): String {
        val total = resolved.sumOf { 1 + it.shadowed.size }
        val conflicts = resolved.count { it.hasConflict }
        return if (conflicts == 0) {
            "本次合并 $total 个源，无同名冲突"
        } else {
            "本次合并 $total 个源，$conflicts 个同名冲突（已按你上次的选择）"
        }
    }
}
