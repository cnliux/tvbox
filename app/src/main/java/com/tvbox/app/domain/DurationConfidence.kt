package com.tvbox.app.domain

/**
 * T1-04：时长可信度分层。
 *
 * 测速/探活不能把 `Content-Length` 当真时长：chunked / 伪静态 / 分片会让
 * 22 分钟短片显示成 3 小时。真实时长只信播放器 `onRenderedFirstFrame` 后的
 * `durationUs != C.TIME_UNSET`；这里只决定「探测数据能否进评分」。
 */
enum class DurationConfidence { HIGH, LOW, NONE }

/** 一次 HEAD / Range 响应的形状；字段缺省即未知，不做猜测。 */
data class ResponseShape(
    val status: Int,
    val contentRange: String? = null,
    val contentLength: Long? = null,
    val transferEncoding: String? = null,
)

/** 只认 `bytes 0-N/TOTAL` 形式的 Content-Range；非法值退化为 LOW/NONE，绝不猜。 */
fun durationConfidence(shape: ResponseShape): DurationConfidence {
    if (shape.status == 206) {
        val total = contentRangeTotal(shape.contentRange)
        if (total != null && total > 0L) return DurationConfidence.HIGH
    }
    val chunked = shape.transferEncoding?.contains("chunked", ignoreCase = true) == true
    if (!chunked && shape.contentLength != null && shape.contentLength!! > 0L) {
        return DurationConfidence.LOW
    }
    return DurationConfidence.NONE
}

/** NONE 不进评分；LOW 只能用于估算与排序，不得写成「时长」。 */
fun contributesToScore(confidence: DurationConfidence): Boolean = confidence != DurationConfidence.NONE

private fun contentRangeTotal(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val total = raw.substringAfterLast('/', missingDelimiterValue = "").trim()
    return total.toLongOrNull()
}
