package com.tvbox.app.domain

import java.text.Normalizer

/**
 * T2-15：M3U 的「频道」与「线路」拆成两个类型 + 不用正则的 EXTINF 属性词法器。
 *
 * 野生 M3U 里 tvg-id 缺失/重复/带 BOM/带全角空格、group-title 带未转义引号、
 * 同一台有多个 CDN 线路。按 name 去重会失去换线能力；不去重则要按 5 下才换台。
 * 这里频道按 tvg-id（缺失回落名字）折叠，每条 URL 独立成为一条 Line。
 */
data class M3uChannel(
    val key: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
)

data class M3uLine(val channelKey: String, val url: String, val origin: String = "m3u")

data class M3uParseResult(
    val channels: List<M3uChannel>,
    val lines: List<M3uLine>,
    val skipped: Int = 0,
)

object M3uTagLexer {

    private const val BOM = '\uFEFF'
    private const val FULLWIDTH_SPACE = '\u3000'

    /** 解析整个文本行序列；任何畸形行只计入 skipped，绝不抛异常。 */
    fun parse(rawLines: List<String>): M3uParseResult {
        val channels = LinkedHashMap<String, M3uChannel>()
        val lines = mutableListOf<M3uLine>()
        var current: M3uChannel? = null
        var skipped = 0

        rawLines.forEachIndexed { index, rawLine ->
            val line = if (index == 0) rawLine.removePrefix(BOM.toString()) else rawLine
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                trimmed.startsWith("#EXTINF:", ignoreCase = true) ||
                    trimmed.startsWith("#EXTINF=", ignoreCase = true) -> {
                    // 兼容 `#EXTINF=` 等号变体：collectM3uLineExtras 只判 `#EXTINF` 前缀
                    // （LiveParser.kt），lexer 若漏认等号变体，extras 与 lexed.lines 的
                    // URL 归属会错位，把台标/tvg-id/请求头挂到别的线路上。
                    val body = when {
                        trimmed.startsWith("#EXTINF:", ignoreCase = true) -> trimmed.substringAfter(":", "")
                        else -> trimmed.substringAfter("=", "")
                    }
                    val attrs = parseAttributes(body)
                    val name = (attrs["name"] ?: "").let(::normalizeText)
                    if (name.isBlank()) {
                        skipped++
                        current = null
                        return@forEachIndexed
                    }
                    val tvgId = attrs["tvg-id"]?.let(::normalizeId)
                    val key = tvgId?.takeIf { it.isNotBlank() } ?: normalizeKey(name)
                    val channel = M3uChannel(
                        key = key,
                        name = name,
                        group = attrs["group-title"]?.let(::normalizeText).orEmpty(),
                        logo = attrs["tvg-logo"],
                        tvgId = tvgId,
                    )
                    channels.putIfAbsent(key, channel) // 重名折叠：5 条 CCTV-1 → 1 频道 5 线路
                    current = channels.getValue(key)
                }
                trimmed.startsWith("#") -> Unit // 其他扩展 tag 不影响频道/线路
                else -> {
                    val url = trimmed.replace(FULLWIDTH_SPACE, ' ')
                    val ch = current
                    if (ch == null || !looksLikeUrl(url)) {
                        skipped++
                    } else {
                        lines += M3uLine(ch.key, url)
                    }
                }
            }
        }
        return M3uParseResult(channels.values.toList(), lines, skipped)
    }

    private fun looksLikeUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("rtmp://", ignoreCase = true) ||
            value.startsWith("rtsp://", ignoreCase = true)

    private fun normalizeText(raw: String): String = raw.replace(FULLWIDTH_SPACE, ' ').trim()

    private fun normalizeId(raw: String): String = normalizeKey(raw)

    /** NFKC 把全角字母/数字（如 ＣＣＴＶ　１）折成半角，保证 tvg-id 身份稳定。 */
    private fun normalizeKey(raw: String): String =
        Normalizer.normalize(normalizeText(raw), Normalizer.Form.NFKC)
            .lowercase().replace(Regex("""\s+"""), " ").trim()

    /**
     * 手写状态机解析 EXTINF 属性：正确吃下 `key="value with \" escape"`、
     * 未转义的 `|`、以及逗号后的显示名；不依赖正则，避免引号截断成 `央视|高`。
     */
    fun parseAttributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var key: String? = null
        var inQuote = false
        var escaped = false
        var index = 0

        while (index < raw.length) {
            val ch = raw[index]
            if (inQuote) {
                when {
                    escaped -> { sb.append(ch); escaped = false }
                    ch == '\\' -> escaped = true
                    ch == '"' -> {
                        inQuote = false
                        key?.let { result[it] = sb.toString() }
                        key = null
                        sb.setLength(0)
                    }
                    else -> sb.append(ch)
                }
                index++
                continue
            }
            when (ch) {
                '=' -> {
                    // EXTINF 开头的时长（如 `-1`）与第一个 key 之间只有空格，
                    // 只取最后一个 token 作为 key，避免把 `-1 tvg-id` 当成键。
                    // 先 trim 再按最后空格切：`key = "..."`（= 两侧有空格）时
                    // sb 尾部是空格，直接 substringAfterLast 会取到空串。
                    // 先 trim 掉首尾空白，再取最后一个空格分隔 token。
                    val token = sb.toString().trim()
                    key = token.substringAfterLast(' ', token).lowercase()
                    sb.setLength(0)
                }
                '"' -> { inQuote = true; sb.setLength(0) }
                ',' -> {
                    val currentKey = key
                    if (currentKey != null) {
                        result[currentKey] = sb.toString().trim()
                        key = null
                        sb.setLength(0)
                    } else {
                        val title = raw.substring(index + 1).trim()
                        if (title.isNotEmpty()) result.putIfAbsent("name", title)
                        // 清掉 EXTINF 的时长 token（`-1`），否则 `#EXTINF:-1,` 会被
                        // 当成名字为 -1 的频道，产生幽灵台。
                        sb.setLength(0)
                        break
                    }
                }
                else -> sb.append(ch)
            }
            index++
        }
        val finalKey = key
        if (finalKey != null) {
            result[finalKey] = sb.toString().trim()
        } else if (sb.isNotBlank()) {
            result.putIfAbsent("name", sb.toString().trim())
        }
        return result
    }
}
