package com.tvbox.app.domain

import com.tvbox.app.util.logSafeText
import com.tvbox.app.util.logSafeUrl

/** 诊断信封协议版本；跨 Kotlin/Go 双端演进时同步递增。 */
const val DIAGNOSTIC_ENVELOPE_VERSION = 1

/**
 * 诊断包统一信封：崩溃日志、设备信息、存储统计等 section 共用同一结构，
 * 便于用户在导出前审查字段（预览）并随时取消。
 */
data class DiagnosticEnvelope(
    val schemaVersion: Int = DIAGNOSTIC_ENVELOPE_VERSION,
    val generatedAtMs: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val sections: Map<String, DiagnosticSection>,
) {
    /** 复制并整体脱敏所有 section，供导出前最后一道防线。 */
    fun redacted(): DiagnosticEnvelope =
        copy(sections = sections.mapValues { DiagnosticRedactor.redactSection(it.value) })
}

/** 单个诊断分区；redacted=true 表示 lines 已经过脱敏，预览时需明确标注。 */
data class DiagnosticSection(
    val title: String,
    val redacted: Boolean = false,
    val lines: List<String> = emptyList(),
)

/** 逐行脱敏：完全复用 util/LogRedaction 的 logSafeText/logSafeUrl，不另造正则。 */
object DiagnosticRedactor {

    private val TOKEN_KEYS = listOf("token=", "access_token=", "api_key=", "apikey=", "password=")

    /** 对单行做脱敏：先整体跑 logSafeText，再显式抹掉 token=xxx 这类键值。 */
    fun redactLine(line: String): String {
        var safe = logSafeText(line)
        for (key in TOKEN_KEYS) {
            val idx = safe.indexOf(key, ignoreCase = true)
            if (idx < 0) continue
            val valueStart = idx + key.length
            val valueEnd = safe.indexOfAny(charArrayOf('&', ' ', '"')).let { end ->
                if (end >= valueStart) end else safe.length
            }
            safe = safe.replaceRange(valueStart, valueEnd, "***")
        }
        return safe
    }

    /** 对 section 的所有 lines 逐行脱敏，返回 redacted=true 的新 section。 */
    fun redactSection(section: DiagnosticSection): DiagnosticSection = section.copy(
        title = section.title,
        redacted = true,
        lines = section.lines.map { redactLine(it) },
    )
}

/** 用户预览：在导出前展示字段标题、少量示例行与脱敏标记。 */
object DiagnosticPreview {

    private const val PREVIEW_LINES_PER_SECTION = 3

    /** 生成用户可见预览行；空 envelope 也安全返回占位提示。 */
    fun preview(envelope: DiagnosticEnvelope): List<String> = buildList {
        add("诊断包预览（协议版本 v${envelope.schemaVersion}）")
        add("生成时间戳：${envelope.generatedAtMs}")
        add("App：${envelope.appVersion} (${envelope.appVersionCode})")
        add("设备：${envelope.deviceModel} · Android ${envelope.androidVersion} (SDK ${envelope.sdkInt})")
        if (envelope.sections.isEmpty()) {
            add("（暂无分区）")
            return@buildList
        }
        for ((key, section) in envelope.sections) {
            val tag = if (section.redacted) "【已脱敏】" else "【未脱敏】"
            add("$tag ${section.title} (#$key，共 ${section.lines.size} 行)")
            section.lines.take(PREVIEW_LINES_PER_SECTION).forEach { add("    $it") }
        }
    }
}

/**
 * 序列化为 JSON。纯 Kotlin 手写拼接，避免单测环境 org.json 不可用；
 * 所有字符串字段经 escape，URL 一律走 logSafeUrl 脱敏后再落盘。
 */
fun DiagnosticEnvelope.toJson(): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"schemaVersion\":").append(schemaVersion).append(',')
    sb.append("\"generatedAtMs\":").append(generatedAtMs).append(',')
    sb.append("\"appVersion\":\"").append(escapeJson(appVersion)).append("\",")
    sb.append("\"appVersionCode\":").append(appVersionCode).append(',')
    sb.append("\"deviceModel\":\"").append(escapeJson(logSafeUrl(deviceModel))).append("\",")
    sb.append("\"androidVersion\":\"").append(escapeJson(androidVersion)).append("\",")
    sb.append("\"sdkInt\":").append(sdkInt).append(',')
    sb.append("\"sections\":{")
    sb.append(sections.entries.joinToString(",") { (key, section) ->
        buildString {
            append('"').append(escapeJson(key)).append("\":{")
            append("\"title\":\"").append(escapeJson(section.title)).append("\",")
            append("\"redacted\":").append(section.redacted).append(',')
            append("\"lines\":[")
            append(section.lines.joinToString(",") { "\"" + escapeJson(logSafeText(it)) + "\"" })
            append("]")
            append('}')
        }
    })
    sb.append("}}")
    return sb.toString()
}

/** JSON 字符串转义：引号、反斜杠与控制字符。 */
private fun escapeJson(raw: String): String = buildString(raw.length + 2) {
    for (ch in raw) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '' -> append("\\f")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}
