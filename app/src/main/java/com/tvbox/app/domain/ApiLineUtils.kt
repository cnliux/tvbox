package com.tvbox.app.domain

fun orderApiLines(allLines: List<ApiLine>, preferredOrder: List<String>): List<ApiLine> {
    val indexMap = preferredOrder.mapIndexed { i, id -> id to i }.toMap()
    return allLines.sortedBy { line -> indexMap[line.id] ?: Int.MAX_VALUE }
}

fun orderLinesByLatency(lines: List<ApiLine>, latencies: Map<String, Long?>): List<ApiLine> {
    return lines.sortedWith(
        compareBy<ApiLine> { latencies[it.id] == null }
            .thenBy { latencies[it.id] ?: 0L },
    )
}

fun normalizeMacCmsApiUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val withoutQuery = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
    if (withoutQuery.length <= "http://".length) return null
    return "$withoutQuery/"
}

fun customVideoApiLineId(baseUrl: String): String {
    return "custom_" + Integer.toUnsignedString(baseUrl.lowercase().hashCode(), 36)
}
