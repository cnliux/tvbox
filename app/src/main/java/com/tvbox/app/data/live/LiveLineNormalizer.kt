package com.tvbox.app.data.live

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 播放线路的修复与去重（纯函数，便于单元测试）。
 *
 * 这些修复针对实测确认的三类「无法连接 / 卡顿」根因，全部在客户端完成，
 * 不依赖重新编译 Go 侧的 AAR：
 *
 *  1. **缺 `codec=264`**：虎牙 FLV 不指定编码时可能下发 HEVC，部分盒子/手机的
 *     MediaCodec 不支持 HEVC 或只支持到 1080p，表现为「有声音没画面」或反复卡顿。
 *     显式要 H.264 是兼容性最好的选择（pure_live 的 `mediaQuery` 同款做法）。
 *  2. **重复拼接查询参数**：实测在已带 `codec` 的地址后再接一次 `&codec=264`，
 *     虎牙 CDN 直接返回 HTML（403 页面），播放器表现为「无法连接」。
 *     因此这里一律**先删同名参数再写入唯一值**，绝不追加。
 *  3. **候选线路重复**：解析层把「移动端」候选按字符串替换生成，而页面本身的
 *     antiCode 就是 `ctype=tars_mobile&t=103`，替换不生效 → 两个候选完全相同，
 *     等于少一条备用线。这里按「规范化 URL」去重，保证每条候选真的不同。
 */
object LiveLineNormalizer {

    /** 虎牙 FLV 强制 AVC。HLS 不加（其编码由 master playlist 决定）。 */
    private const val HUYA_CODEC_AVC = "264"

    /** 会被平台签名绑定的参数：必须与请求头 UA/Referer 保持一致。 */
    private const val PARAM_CTYPE = "ctype"
    private const val PARAM_T = "t"

    /**
     * 修复单条线路地址。[protocol] 为平台给出的容器类型（flv/hls/…）。
     * 返回 null 表示这条地址不可用（非 http(s) 或缺少主机）。
     */
    fun normalizeUrl(site: String, protocol: String, url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        val isFlv = protocol.equals("flv", true) || uri.path.orEmpty().endsWith(".flv", true)
        if (!isFlv) return trimmed

        val params = parseQuery(uri.rawQuery)
        val result = ArrayList<Pair<String, String>>(params.size + 1)
        for (entry in params) {
            // 去掉重复键：同名参数只保留第一次出现的值，避免下游再次拼接。
            if (result.any { it.first == entry.first }) continue
            result.add(entry)
        }
        if (site.lowercase() == "huya") {
            setParam(result, "codec", HUYA_CODEC_AVC)
        }
        return rebuild(uri, result)
    }

    /**
     * 修复并去重候选列表。
     *
     * [candidates] 按平台优先级排列，输出保持相对顺序（第一条仍是最优线路）。
     * 完全相同的地址只保留第一条；去重键用「主机 + 路径 + 关键签名参数」，
     * 避免同一路流因参数顺序不同被当成两条线。
     */
    fun normalizeCandidates(
        site: String,
        protocol: String,
        candidates: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val output = ArrayList<Pair<String, String>>(candidates.size)
        for ((cdn, url) in candidates) {
            val fixed = normalizeUrl(site, protocol, url) ?: continue
            if (!seen.add(dedupeKey(fixed))) continue
            output.add(cdn to fixed)
        }
        return output
    }

    /**
     * 按线路的实际签名参数给出配套请求头。
     *
     * 虎牙会把 `ctype`/`t` 算进 `wsSecret`：`ctype=tars_mobile&t=103` 必须配移动端
     * UA + `m.huya.com` 的 Referer，`ctype=huya_pc_exe` 必须配 HYSDK UA +
     * `www.huya.com` 的 Referer。两者错配时 CDN 会返回 403，或只吐十几秒就掐流
     * （这正是「能连上但很快断」的一种成因）。
     */
    fun headersFor(
        site: String,
        url: String,
        baseHeaders: Map<String, String>,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        baseHeaders.forEach { (key, value) -> headers[key] = value }
        if (site.lowercase() != "huya") return headers

        val params = parseQuery(runCatching { URI(url).rawQuery }.getOrNull())
        val ctype = params.firstOrNull { it.first == PARAM_CTYPE }?.second.orEmpty()
        val platform = params.firstOrNull { it.first == PARAM_T }?.second.orEmpty()
        val isWap = platform == "103" || ctype.contains("mobile", true)

        if (isWap) {
            putIgnoreCase(headers, "User-Agent", HUYA_MOBILE_UA)
            putIgnoreCase(headers, "Referer", "https://m.huya.com/")
            putIgnoreCase(headers, "Origin", "https://m.huya.com")
        } else {
            putIgnoreCase(headers, "User-Agent", HUYA_PC_UA)
            putIgnoreCase(headers, "Referer", "https://www.huya.com/")
            putIgnoreCase(headers, "Origin", "https://www.huya.com")
        }
        return headers
    }

    /** 与 [headersFor] 配套的 UA 常量（与 platform_live 侧保持一致）。 */
    const val HUYA_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"
    const val HUYA_PC_UA = "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"

    // ── 内部工具 ────────────────────────────────────────────────

    private fun dedupeKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val params = parseQuery(uri.rawQuery)
            .filter { it.first.equals("wsSecret", true) || it.first.equals("wsTime", true) || it.first.equals("ratio", true) }
            .sortedBy { it.first }
        return (uri.host.orEmpty() + uri.rawPath.orEmpty() + params.joinToString("&") { "${it.first}=${it.second}" })
    }

    private fun setParam(target: MutableList<Pair<String, String>>, key: String, value: String) {
        target.removeAll { it.first == key }
        target.add(key to value)
    }

    private fun putIgnoreCase(target: MutableMap<String, String>, key: String, value: String) {
        val existing = target.keys.firstOrNull { it.equals(key, true) }
        if (existing != null) target.remove(existing)
        target[key] = value
    }

    /** 保留原始编码解析查询串；解码失败时退回原样。 */
    private fun parseQuery(rawQuery: String?): MutableList<Pair<String, String>> {
        val output = ArrayList<Pair<String, String>>()
        if (rawQuery.isNullOrBlank()) return output
        for (segment in rawQuery.split('&')) {
            if (segment.isEmpty()) continue
            val index = segment.indexOf('=')
            val rawKey = if (index < 0) segment else segment.substring(0, index)
            val rawValue = if (index < 0) "" else segment.substring(index + 1)
            output.add(decode(rawKey) to decode(rawValue))
        }
        return output
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun rebuild(uri: URI, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (key, value) ->
            if (value.isEmpty()) encode(key) else encode(key) + "=" + encode(value)
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port${uri.rawPath.orEmpty()}" + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
