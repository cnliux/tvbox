package com.tvbox.app.data

import android.util.Base64
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TVBox 订阅内容解码（行为对齐 TVBox 宿主 Decoder / jar-dump 的 DecoderLite）：
 * 1. JSON 明文直通；
 * 2. "XXXXXXXX**" 前缀 Base64 壳；
 * 3. "2423..." Hex/AES-CBC 壳（key 在 `$#..#$` 之间、iv 取末尾 13 个字符，不足 16 位补 0）；
 * 4. `./ ../` 相对路径修正（jx/.ext 常见写法），`__JS1__/__JS2__` 还原为 `./ ../`。
 *
 * 容错约定：任何一步失败都只抛固定文案的 [IllegalArgumentException]，
 * 不把底层异常原文（可能含密文/密钥片段/URL）回传给 UI；
 * 相对路径修正逐行隔离，单行异常不会影响其余行。
 */
object SubscriptionDecoder {

    private const val BLOCK = 16
    private const val EMPTY_ERROR = "订阅内容为空"
    private const val BASE64_ERROR = "订阅 Base64 解码失败"
    private const val HEX_ERROR = "订阅 Hex 解码失败"
    private const val CIPHER_FORMAT_ERROR = "订阅密文格式不合法"
    private const val CIPHER_ERROR = "订阅解密失败，请检查密钥或内容格式"

    /** 先匹配 `../` 再匹配 `./`，一次 replace 不会把替换结果里的 `./` 再处理一遍。 */
    private val relativeRef = Regex("""\.\./|\./""")

    fun decode(url: String, raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) throw IllegalArgumentException(EMPTY_ERROR)
        if (text.startsWith("\uFEFF")) text = text.drop(1).trim()
        if (text.isEmpty()) throw IllegalArgumentException(EMPTY_ERROR)
        if (text.startsWith("{") || text.startsWith("[")) {
            return fix(url, text)
        }
        if (text.contains("**")) text = base64Shell(text)
        if (text.startsWith("2423")) text = cbcShell(text.filterNot { it.isWhitespace() })
        if (text.isBlank()) throw IllegalArgumentException(EMPTY_ERROR)
        return fix(url, text)
    }

    private fun base64Shell(data: String): String {
        val index = data.indexOf("**")
        if (index < 8) return data
        val encoded = data.substring(index + 2).filterNot { it.isWhitespace() }
        if (encoded.isEmpty()) throw IllegalArgumentException(BASE64_ERROR)
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { throw IllegalArgumentException(BASE64_ERROR) }
        val decoded = runCatching { String(bytes, StandardCharsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException(BASE64_ERROR) }
        if (decoded.isBlank()) throw IllegalArgumentException(BASE64_ERROR)
        return decoded
    }

    private fun cbcShell(data: String): String {
        val decoded = runCatching { String(hex2Byte(data), StandardCharsets.UTF_8).lowercase() }
            .getOrElse { throw IllegalArgumentException(HEX_ERROR) }
        val keyStart = decoded.indexOf("\$#")
        if (keyStart < 0) throw IllegalArgumentException(CIPHER_FORMAT_ERROR)
        val keyEnd = decoded.indexOf("#\$", startIndex = keyStart + 2)
        if (keyEnd < keyStart) throw IllegalArgumentException(CIPHER_FORMAT_ERROR)

        val key = padEnd(decoded.substring(keyStart + 2, keyEnd))
        val iv = padEnd(decoded.takeLast(13))
        val bodyStart = data.indexOf("2324")
        val bodyEnd = data.length - 26
        if (bodyStart < 0 || bodyEnd <= bodyStart + 4) throw IllegalArgumentException(CIPHER_FORMAT_ERROR)
        val body = runCatching { hex2Byte(data.substring(bodyStart + 4, bodyEnd)) }
            .getOrElse { throw IllegalArgumentException(HEX_ERROR) }

        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
            )
            cipher.doFinal(body)
        }.getOrElse { throw IllegalArgumentException(CIPHER_ERROR) }
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun padEnd(value: String): String = value.take(BLOCK).padEnd(BLOCK, '0')

    /** 严格逐字节解析；非法字符只抛固定文案，避免 toInt(16) 把原文带进异常消息。 */
    private fun hex2Byte(source: String): ByteArray {
        if (source.length % 2 != 0) throw IllegalArgumentException(HEX_ERROR)
        val bytes = ByteArray(source.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(source[index * 2], 16)
            val low = Character.digit(source[index * 2 + 1], 16)
            if (high < 0 || low < 0) throw IllegalArgumentException(HEX_ERROR)
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun fix(url: String, data: String): String {
        val tokens = data.replace("__JS1__", "./").replace("__JS2__", "../")
        // 逐行处理：某一行的相对地址非法或含特殊字符时保留该行原文，不影响其它配置。
        return tokens.lineSequence().joinToString("\n") { line ->
            runCatching { relativeRef.replace(line) { match -> resolve(url, match.value) } }
                .getOrDefault(line)
        }
    }

    private fun resolve(base: String, ref: String): String {
        if (base.isBlank()) return ref
        return runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)
    }
}
