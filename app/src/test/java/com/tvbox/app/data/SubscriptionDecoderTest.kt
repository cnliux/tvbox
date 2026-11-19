package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * SubscriptionDecoder 的 JVM 单测（通过 decodeWith 注入 java.util.Base64，
 * 避免依赖 android.util.Base64，纯 JVM 可直接运行）。
 *
 * 覆盖：base64 壳分支、JSON 明文直通、错误路径；AES/CBC（2423）壳因涉及
 * android.util.Base64 之外的完整解密链路，此处仅验证壳识别与解码，不覆盖密文。
 */
class SubscriptionDecoderTest {

    private fun decoder(decoder: (String) -> ByteArray): (String, String) -> String =
        { url, raw -> SubscriptionDecoder.decodeWith(url, raw, decoder) }

    private val jvmBase64: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    @Test
    fun jsonPlainTextPassesThrough() {
        val json = """{"sites":[{"name":"源A"}]}"""
        assertEquals(json, decoder(jvmBase64)("https://x.example", json))
    }

    @Test
    fun base64ShellIsDecoded() {
        // "XXXXXXXX**" 前缀 Base64 壳；壳内为 JSON。
        val payload = "{\"sites\":[{\"name\":\"源A\"}]}"
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val raw = "XXXXXXXX**$encoded"
        assertEquals(payload, decoder(jvmBase64)("https://x.example", raw))
    }

    @Test
    fun base64ShellEmptyAfterPrefixThrowsFixedError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            decoder(jvmBase64)("https://x.example", "XXXXXXXX**   ")
        }
        assertEquals("订阅 Base64 解码失败", error.message)
    }

    @Test
    fun invalidBase64PayloadThrowsFixedError() {
        // 非合法 base64 输入应抛固定文案，不把底层异常原文透传给调用方。
        val error = assertThrows(IllegalArgumentException::class.java) {
            decoder(jvmBase64)("https://x.example", "XXXXXXXX**!!!not-base64!!!")
        }
        assertEquals("订阅 Base64 解码失败", error.message)
    }

    @Test
    fun emptyInputThrowsFixedError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            decoder(jvmBase64)("https://x.example", "   ")
        }
        assertEquals("订阅内容为空", error.message)
    }
}
