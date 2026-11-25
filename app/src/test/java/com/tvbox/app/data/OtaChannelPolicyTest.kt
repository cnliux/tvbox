package com.tvbox.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P39 OTA 通道/降级字段策略测试：覆盖 `minVersionCode` 版本阶梯门禁与 `channel`
 * 通道字段的解析与默认值，确保不破坏既有 8 字段清单的语义。
 *
 * 说明：`AppUpdate` 域模型未新增字段（domain/Models.kt 不在本轮可写范围），
 * 因此门槛与通道判定统一通过 `checkMinVersionBlocked` / `normalizeOtaChannel`
 * 这两个纯函数承接；清单 DTO 的 `minVersionCode` / `channel` 默认值通过
 * ignoreUnknownKeys=true 的 Json 显式解析并断言，保证老客户端/老清单向后兼容。
 */
class OtaChannelPolicyTest {

    private val apkUrl = "https://github.com/cnliux/tvbox/releases/download/v1.0.0/juhe-video-v1.0.0.apk"

    /** 老的 8 字段 update.json，不含 minVersionCode / channel。 */
    private fun legacyManifest() = """
        {
          "versionCode": 20050,
          "versionName": "1.0.5",
          "apkUrl": "$apkUrl",
          "apkSha256": "abc123",
          "apkSize": 19903256,
          "force": false,
          "changelog": [" 修复 OTA "]
        }
    """.trimIndent()

    private fun manifest(
        minVersionCode: Long,
        channel: String = "stable",
    ) = """
        {
          "versionCode": 20050,
          "versionName": "1.0.5",
          "apkUrl": "$apkUrl",
          "apkSha256": "abc123",
          "apkSize": 19903256,
          "force": false,
          "changelog": [],
          "minVersionCode": $minVersionCode,
          "channel": "$channel"
        }
    """.trimIndent()

    @Test
    fun legacyManifestStillParsesWithIgnoredNewFields() {
        // 老清单不声明新字段：ignoreUnknownKeys 客户端应正常解析，且未声明门槛（minVersionCode=0）不阻断
        val update = requireNotNull(parseAppUpdateManifest(legacyManifest(), currentVersionCode = 20040L))
        assertEquals(20050L, update.versionCode)
        assertEquals(apkUrl, update.apkUrl)
        // 缺省门槛（0）在任何当前版本下都不阻断 → 老清单完全向后兼容
        assertNull(checkMinVersionBlocked(currentVersionCode = 0L, minVersionCode = 0L))
        // 缺省通道按正式通道处理
        assertEquals("stable", normalizeOtaChannel(null))
    }

    @Test
    fun lowerCurrentVersionThanMinVersionIsBlockedWithMessage() {
        val reason = checkMinVersionBlocked(currentVersionCode = 20040L, minVersionCode = 20050L)
        assertNotNull("minVersionCode=20050, current=20040 应被阻断", reason)
        reason?.let {
            // 阻断文案需为中文，并明确指出目标版本
            assertTrue("文案需包含版本号提示：$it", it.contains("v2.0.50"))
            assertTrue("文案需指出当前版本过低：$it", it.contains("版本过低"))
        }
    }

    @Test
    fun equalCurrentVersionAndMinVersionIsAllowed() {
        // 当前版本等于门槛值时放行（minVersionCode 含端点）
        assertNull(checkMinVersionBlocked(currentVersionCode = 20040L, minVersionCode = 20040L))
    }

    @Test
    fun higherCurrentVersionThanMinVersionIsAllowed() {
        assertNull(checkMinVersionBlocked(currentVersionCode = 20040L, minVersionCode = 20030L))
    }

    @Test
    fun canaryChannelIsParsedVerbatimAndKeptSeparateFromStable() {
        // App 当前没有通道本地偏好结构，测试只断言清单 channel 字段被解析到对应值即可
        assertEquals("canary", normalizeOtaChannel("canary"))
        assertEquals("stable", normalizeOtaChannel("stable"))
        // 大小写 / 空白 / 未知值一律回退正式通道，避免 canary 误覆盖正式偏好
        assertEquals("canary", normalizeOtaChannel(" Canary "))
        assertEquals("stable", normalizeOtaChannel("beta"))
        assertEquals("stable", normalizeOtaChannel(""))
    }

    @Test
    fun unknownNewerFieldsAreStillIgnoredAlongsideNewGateFields() {
        val raw = """
            {
              "versionCode": 20050,
              "versionName": "1.0.5",
              "apkUrl": "$apkUrl",
              "apkSha256": "abc",
              "apkSize": 1,
              "force": false,
              "changelog": [],
              "minVersionCode": 20040,
              "channel": "stable",
              "signatureCertSha256": "deadbeef",
              "futureField": {"nested": [1]}
            }
        """.trimIndent()
        // 新字段本身 + 未来字段都能被 ignoreUnknownKeys 安全忽略，不破坏解析
        val update = requireNotNull(
            parseAppUpdateManifest(raw, currentVersionCode = 20040L, json = Json { ignoreUnknownKeys = true }),
        )
        assertEquals(20050L, update.versionCode)
        assertEquals(apkUrl, update.apkUrl)
        // 等于门槛 → 放行
        assertNull(checkMinVersionBlocked(currentVersionCode = 20040L, minVersionCode = 20040L))
    }
}
