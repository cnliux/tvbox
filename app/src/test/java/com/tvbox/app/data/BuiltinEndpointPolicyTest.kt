package com.tvbox.app.data

import com.tvbox.app.domain.BuiltinEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S1-02：枚举与 NSC 白名单不脱钩 + OTA 明文地址硬拒绝。 */
class BuiltinEndpointPolicyTest {

    private val nsc by lazy { File("src/main/res/xml/network_security_config.xml").readText() }

    @Test
    fun everyEnumDomainIsPinnedInNetworkSecurityConfig() {
        BuiltinEndpoint.entries.forEach { endpoint ->
            assertTrue(
                "内置域 ${endpoint.host} 未登记在 network_security_config.xml（请用 tools/gen_nsc.py 生成）",
                nsc.contains("<domain includeSubdomains=\"true\">${endpoint.host}</domain>"),
            )
        }
    }

    @Test
    fun builtinHostMatchingHandlesSubdomainsAndCase() {
        assertTrue(BuiltinEndpoint.isBuiltin("api.douban.com"))
        assertTrue(BuiltinEndpoint.isBuiltin("DOUBAN.COM."))
        assertTrue(BuiltinEndpoint.isBuiltin("raw.githubusercontent.com"))
        assertFalse(BuiltinEndpoint.isBuiltin("evil-douban.com"))
        assertFalse(BuiltinEndpoint.isBuiltin("user-defined.example.org"))
    }

    @Test
    fun otaRejectsCleartextExceptLoopback() {
        assertTrue(isSecureOtaUrl("https://github.com/cnliux/tvbox/releases/download/a.apk"))
        assertTrue(isSecureOtaUrl("http://127.0.0.1:8080/local.apk"))
        assertFalse(isSecureOtaUrl("http://gh-proxy.com/update.json"))
        assertFalse(isSecureOtaUrl("file:///sdcard/update.apk"))
        assertFalse(isSecureOtaUrl("https:///missing-host.apk"))
        assertTrue("scheme 大小写不敏感", isSecureOtaUrl("HTTPS://EXAMPLE.COM/a.apk"))
    }
}
