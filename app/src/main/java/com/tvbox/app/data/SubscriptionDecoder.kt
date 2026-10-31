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
 */
object SubscriptionDecoder {

    private const val BLOCK = 16

    fun decode(url: String, raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空")
        if (text.startsWith("\uFEFF")) text = text.drop(1)
        if (text.startsWith("{")) {
            return fix(url, text)
        }
        if (text.contains("**")) text = base64Shell(text)
        if (text.startsWith("2423")) text = cbcShell(text.filterNot { it.isWhitespace() })
        return fix(url, text)
    }

    private fun base64Shell(data: String): String {
        val index = data.indexOf("**")
        if (index < 8) return data
        val encoded = data.substring(index + 2).trim()
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Base64 解码失败：${error.message ?: "格式不合法"}")
        }
    }

    private fun cbcShell(data: String): String {
        val decoded = String(hex2Byte(data), StandardCharsets.UTF_8).lowercase()
        val keyStart = decoded.indexOf("$#")
        val keyEnd = decoded.indexOf("#$")
        if (keyStart < 0 || keyEnd < keyStart) throw IllegalArgumentException("未找到密钥标记")
        val key = padEnd(decoded.substring(keyStart + 2, keyEnd))
        val iv = padEnd(decoded.takeLast(13))
        val bodyStart = data.indexOf("2324")
        if (bodyStart < 0) throw IllegalArgumentException("未找到密文体标记 2324")
        val hexBody = data.substring(bodyStart + 4, data.length - 26)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
        )
        val plain = cipher.doFinal(hex2Byte(hexBody))
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun padEnd(key: String): String {
        return if (key.length >= BLOCK) {
            key.substring(0, BLOCK)
        } else {
            key + "0000000000000000".substring(key.length)
        }
    }

    private fun hex2Byte(source: String): ByteArray {
        val length = source.length / 2
        val bytes = ByteArray(length)
        for (index in 0 until length) {
            bytes[index] = source.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun fix(url: String, data: String): String {
        var fixed = data
        if (fixed.contains("../")) fixed = fixed.replace("../", resolve(url, "../"))
        if (fixed.contains("./")) fixed = fixed.replace("./", resolve(url, "./"))
        return fixed.replace("__JS1__", "./").replace("__JS2__", "../")
    }

    private fun resolve(base: String, ref: String): String {
        if (base.isBlank()) return ref
        return try {
            URL(URL(base), ref).toString()
        } catch (error: Exception) {
            ref
        }
    }
}
