package com.tvbox.app.util

/**
 * S1-03 / S1-07：崩溃日志与日志行的脱敏纯函数。
 *
 * 源 URL 常带 `token=`/`sign=`/`expire=` 等签名参数，崩溃导出一旦落入公共 Downloads
 * 或被随手分享，等于把可用的源凭据一并交出去。这里只做**不可逆的遮盖**，
 * 保留 scheme/host/path 供排障，不保留任何查询参数值。
 */
object CrashLogRedactor {
    private val SECRET_QUERY = Regex(
        "([?&](?:token|key|sign|signature|expire|expires|auth|authorization|access[_-]?token)=)[^&#\\s]+",
        RegexOption.IGNORE_CASE,
    )
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}")
    private val SK_KEY = Regex("(?i)\\b(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,})\\b")

    /** 脱敏单行：查询参数值、Bearer token、API Key 一律替换为 `***`。 */
    fun redact(text: String): String = text
        .let { SECRET_QUERY.replace(it, "$1***") }
        .let { BEARER.replace(it, "$1***") }
        .let { SK_KEY.replace(it, "***") }

    /** 脱敏整份日志（逐行处理，避免跨行正则误伤）。 */
    fun redactAll(text: String): String = buildString {
        text.lineSequence().forEach { line ->
            append(redact(line)).append('\n')
        }
    }
}
