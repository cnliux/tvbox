package com.tvbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1-03 / S1-07：导出与日志脱敏的纯函数回归（语料含真实形式的签名参数）。 */
class CrashLogRedactorTest {

    @Test
    fun redactsEveryKnownSecretShape() {
        val raw = listOf(
            "https://cdn.example.com/live.flv?token=abc123DEF456&expire=1799999999",
            "https://api.example.com/vod?sign=9f8e7d6c5b4a3210&key=secret",
            "Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz",
            "api key AIzaSyA1234567890abcdefghijklmnopqrstuvw leaked",
            "nested?a=1&auth=tokenvalue&b=2",
        ).joinToString("\n")

        val redacted = CrashLogRedactor.redactAll(raw)
        assertFalse("导出的文件不得残留任何 token= 取值", redacted.contains("token=abc"))
        assertFalse(redacted.contains("sign=9f8e"))
        assertFalse(redacted.contains("expire=1799"))
        assertFalse(redacted.contains("Bearer sk-"))
        assertFalse(redacted.contains("AIzaSy"))
        assertFalse(redacted.contains("auth=tokenvalue"))

        assertTrue(redacted.contains("cdn.example.com/live.flv"))
        assertTrue(redacted.contains("token=***"))
        assertTrue(redacted.contains("&b=2"))
    }

    @Test
    fun redactsPlatformLiveSignatureParams() {
        val raw = "live https://example.com/flv?wsSecret=deadbeef&wsTime=1799999999&secret=cafebabe&session=s1&cookie=c1"
        val out = CrashLogRedactor.redact(raw)
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("1799999999"))
        assertFalse(out.contains("cafebabe"))
        assertFalse(out.contains("session=s1"))
        assertFalse(out.contains("cookie=c1"))
        assertTrue(out.contains("wsSecret=***"))
        assertTrue(out.contains("wsTime=***"))
        assertTrue(out.contains("secret=***"))
    }

    @Test
    fun keepsHostAndPathForTriage() {
        val line = "play https://source.example.com/api.php/provide/vod/?ac=videolist&token=huge-secret"
        val out = CrashLogRedactor.redact(line)
        assertEquals(
            "play https://source.example.com/api.php/provide/vod/?ac=videolist&token=***",
            out,
        )
    }

    @Test
    fun idempotentAndSafeOnEmptyInput() {
        val once = CrashLogRedactor.redact("?token=abc&key=def")
        assertEquals(once, CrashLogRedactor.redact(once))
        assertEquals("", CrashLogRedactor.redact(""))
        assertEquals("\n", CrashLogRedactor.redactAll(""))
    }
}
