package com.tvbox.app.domain

/**
 * 全平台「第三方代理解析」候选生成器。
 *
 * 需求：当用户为某平台配置了第三方代理地址时，优先用第三方地址加载直播数据，
 * 并支持为同一平台配置多个第三方作为彼此备用；Go 侧官方解析候选退为兜底。
 * 不同平台接入不同的第三方接口，**以房间号为主键**展开各自模板。
 *
 * 配置格式（A 方案：单一文本按站点分组）——用 `# 站点名` 注释头划分平台分组，
 * 分组下的每一行都是该平台的第三方模板；未归属任何分组的模板归入「默认组」，
 * 作为所有平台共用的兜底（向后兼容旧的单份全局配置）。
 * ```
 * # douyu
 * https://cfss.example/cdn/dy/{room}.flv
 * # huya
 * https://host.example/{siteAlias}/{room}.flv
 * https://mursor.example/{site}/{room}
 * # 默认组（无分组头，所有平台共用兜底）
 * https://fallback.example/{site}/{room}
 * ```
 *
 * 地址模板占位符：`{room}` 房间号、`{site}` 站点全名（如 douyu）、
 * `{siteAlias}` 站点短码（见 [siteAliases]），三者可在同一条模板里组合。
 * 具体第三方地址不写死进代码，由用户按各自写法配置。
 */
object ThirdPartyLiveProxy {
    /**
     * 支持第三方代理的平台清单（与 Go 侧 adapters 注册一致）。
     * 用户可为其中任意平台单独配置模板；未单独配置的平台回落到「默认组」。
     */
    val supportedSites: Set<String> = setOf(
        "douyu", "huya", "bilibili", "cc", "douyin",
        "kuaishou", "inke", "showroom", "weibo", "missevan",
    )

    /**
     * 站点短码别名表：部分第三方代理用短码而非全名拼地址。
     * `{site}` 展开成全名，`{siteAlias}` 展开成短码。短码集中在此表维护，
     * 未登记短码的平台 `{siteAlias}` 展开为空串（此时不要在模板里依赖 `{siteAlias}`）。
     */
    val siteAliases: Map<String, String> = mapOf(
        "douyu" to "dy",
        "huya" to "hy",
        "bilibili" to "bl",
        "cc" to "cc",
        "douyin" to "dyin",
        "kuaishou" to "ks",
        "inke" to "inke",
        "showroom" to "sr",
        "weibo" to "wb",
        "missevan" to "mse",
    )

    /**
     * 内置默认第三方代理模板（作为「默认组」的兜底）。{room} 运行时替换为真实房间号。
     * 仅当某平台既无专属分组、默认组也未命中时才用内置默认，避免无谓请求。
     * 多条备用：cfss.cc 优先（带 .flv、走续流代理），mursor 作备用（无 .flv 后缀、直连）。
     */
    val defaultTemplates: List<String> = listOf(
        "https://cfss.cc/cdn/{site}/{room}.flv",
        "https://mursor.ottiptv.cc/{site}/{room}",
    )

    /** 分组头标记：以 `#` 开头即视为站点分组（`# douyu`、`#斗鱼(douyu)` 均可）。 */
    private const val GROUP_PREFIX = "#"

    fun isSupported(site: String): Boolean = site.trim().lowercase() in supportedSites

    /** 站点短码别名；未登记的平台返回空串。 */
    fun siteAlias(site: String): String = siteAliases[site.trim().lowercase()].orEmpty()

    /**
     * 解析分组后的模板配置，返回 站点 -> 该平台模板列表（保持配置顺序、去重）。
     *
     * 规则：
     * - `# xxx` 行开启一个分组，`xxx` 归一化为站点小写全名；无法识别成受支持站点的分组被忽略。
     * - 分组内的模板行归属该站点；未在任何分组下的模板行归属 [DEFAULT_GROUP]（所有平台共用兜底）。
     * - 同一站点内去空、去重、忽略纯注释行。
     */
    fun parseGroupedTemplates(raw: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        var currentGroup = DEFAULT_GROUP
        fun bucket(group: String): MutableList<String> = result.getOrPut(group) { mutableListOf() }

        raw.split('\n', ';').forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith(GROUP_PREFIX)) {
                currentGroup = normalizeGroupKey(line.removePrefix(GROUP_PREFIX))
                return@forEach
            }
            // 未归属任何受支持分组的模板（含 `#` 头之前的默认行、无分组头的旧配置）
            // 一律收进默认组，作为所有平台的兜底——必须在分组支持性校验之前建桶，
            // 否则默认组模板会被整体丢弃，破坏向后兼容。
            val target = if (isSupported(currentGroup)) currentGroup else DEFAULT_GROUP
            val bucketList = bucket(target)
            if (!bucketList.contains(line)) bucketList.add(line)
        }
        return result
    }

    /**
     * 向后兼容：返回「全部模板的扁平列表」（不分站点），供旧调用与默认组兜底使用。
     * 旧配置里 `# xxx` 行原被当注释忽略，这里统一忽略分组头只取模板行。
     */
    fun parseTemplates(raw: String): List<String> =
        raw.split('\n', ';', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith(GROUP_PREFIX) }
            .distinct()

    /**
     * 取出某平台应使用的第三方模板：专属分组优先，其后追加默认组兜底。
     * 站点不受支持时返回空列表（不生成第三方候选）。
     */
    fun templatesForSite(raw: String, site: String): List<String> {
        val normalized = site.trim().lowercase()
        if (!isSupported(normalized)) return emptyList()
        val grouped = parseGroupedTemplates(raw)
        val siteTemplates = grouped[normalized].orEmpty()
        val defaultGroup = grouped[DEFAULT_GROUP].orEmpty()
        return (siteTemplates + defaultGroup).distinct()
    }

    /**
     * 展开模板为真实地址。
     *
     * [template] 含 `{room}` 用房间号替换；含 `{site}` 用站点小写全名替换；
     * 含 `{siteAlias}` 用站点短码替换；都不含时按 `模板 + "/" + site + "/" + room` 追加。
     * 返回空串表示该模板无效（缺少房间号）。
     */
    fun expand(template: String, site: String, roomId: String): String {
        val room = roomId.trim()
        if (room.isEmpty()) return ""
        val normalizedSite = site.trim().lowercase()
        val base = template.trim()
        if (base.isEmpty()) return ""
        return when {
            base.contains("{room}") -> base.replace("{room}", room)
                .replace("{siteAlias}", siteAlias(normalizedSite))
                .replace("{site}", normalizedSite)
            base.contains("{site}") -> base.replace("{siteAlias}", siteAlias(normalizedSite))
                .replace("{site}", normalizedSite) + "/" + room
            else -> base.trimEnd('/') + "/" + normalizedSite + "/" + room
        }
    }

    /**
     * 为一个房间生成该平台全部第三方代理候选地址（保持配置备用顺序，去重）。
     * 站点不受支持或无有效模板时返回空列表。
     */
    fun candidateUrls(templates: List<String>, site: String, roomId: String): List<String> {
        if (!isSupported(site)) return emptyList()
        return templates.mapNotNull { template ->
            expand(template, site, roomId).takeIf { it.isNotBlank() }
        }.distinct()
    }

    /**
     * 按分组配置为一个房间生成候选地址（A 方案主入口）。
     * 内部先取该平台的模板（专属分组 + 默认组兜底），再逐条展开。
     */
    fun candidateUrlsFromGrouped(raw: String, site: String, roomId: String): List<String> =
        candidateUrls(templatesForSite(raw, site), site, roomId)

    /** 归一化分组头为站点小写全名；支持中文站名或全名/短码，命中站点表才有效。 */
    private fun normalizeGroupKey(raw: String): String {
        val key = raw.trim().lowercase()
        if (key.isEmpty()) return ""
        if (isSupported(key)) return key
        // 允许用短码 / 中文站名指代，例如 `# hy`、`# 斗鱼`。
        val byAlias = siteAliases.entries.firstOrNull { it.value == key }?.key
        if (byAlias != null && isSupported(byAlias)) return byAlias
        val byName = supportedSites.firstOrNull { site ->
            siteChineseNames[site]?.contains(key) == true
        }
        return byName.orEmpty()
    }

    /** 默认分组键：未归属任何 `# 站点` 分组的模板归入此组，所有平台共用兜底。 */
    const val DEFAULT_GROUP = "__default__"

    /** 中文站名映射：允许用户在分组头里写中文站名，命中后归一到站点全名。 */
    private val siteChineseNames: Map<String, Set<String>> = mapOf(
        "douyu" to setOf("斗鱼"),
        "huya" to setOf("虎牙"),
        "bilibili" to setOf("哔哩哔哩", "b站", "bilibili"),
        "cc" to setOf("网易cc", "网易", "cc"),
        "douyin" to setOf("抖音"),
        "kuaishou" to setOf("快手"),
        "inke" to setOf("映客"),
        "showroom" to setOf("showroom"),
        "weibo" to setOf("微博"),
        "missevan" to setOf("猫耳", "猫耳fm", "missevan"),
    )
}
