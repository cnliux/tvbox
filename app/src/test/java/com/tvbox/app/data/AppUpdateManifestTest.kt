package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestTest {

    private val apkUrl = "https://github.com/cnliux/tvbox/releases/download/v1.0.0/juhe-video-v1.0.0.apk"

    private fun legacyManifest() = """
        {
          "versionCode": 20000,
          "versionName": " 1.0.0 ",
          "apkUrl": "$apkUrl",
          "apkSha256": " abc123 ",
          "apkSize": 19903256,
          "force": true,
          "changelog": [" 修复 OTA ", "", "   "]
        }
    """.trimIndent()

    @Test
    fun legacyEightFieldManifestParsesWithEmptyMirrors() {
        val update = requireNotNull(parseAppUpdateManifest(legacyManifest(), currentVersionCode = 19999L))
        assertEquals(20000L, update.versionCode)
        assertEquals("1.0.0", update.versionName)
        assertEquals(apkUrl, update.apkUrl)
        assertEquals("abc123", update.apkSha256)
        assertEquals(19903256L, update.apkSize)
        assertTrue(update.force)
        assertEquals(listOf("修复 OTA"), update.changelog)
        assertTrue(update.apkMirrors.isEmpty())
    }

    @Test
    fun optionalApkMirrorsKeepOnlyHttpsAndDeduplicate() {
        val raw = """
            {
              "versionCode": 20000,
              "versionName": "1.0.0",
              "apkUrl": "$apkUrl",
              "apkSha256": "abc",
              "apkSize": 1,
              "force": false,
              "changelog": [],
              "apkMirrors": [
                "https://mirror.example.com/tvbox.apk",
                "http://insecure.example.com/tvbox.apk",
                "   ",
                "https://mirror.example.com/tvbox.apk"
              ]
            }
        """.trimIndent()
        val update = requireNotNull(parseAppUpdateManifest(raw, currentVersionCode = 19999L))
        assertEquals(listOf("https://mirror.example.com/tvbox.apk"), update.apkMirrors)
        assertFalse(update.apkMirrors.any { it.startsWith("http://") })
        // 既有字段语义不受新字段影响
        assertEquals(apkUrl, update.apkUrl)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val raw = """
            {
              "versionCode": 20000,
              "versionName": "1.0.0",
              "apkUrl": "$apkUrl",
              "apkSha256": "abc",
              "apkSize": 1,
              "force": false,
              "changelog": [],
              "futureField": {"nested": [1, 2, 3]}
            }
        """.trimIndent()
        val update = requireNotNull(parseAppUpdateManifest(raw, currentVersionCode = 19999L))
        assertEquals(apkUrl, update.apkUrl)
        assertTrue(update.apkMirrors.isEmpty())
    }

    @Test
    fun sameOrOlderVersionIsNotAnUpdate() {
        assertNull(parseAppUpdateManifest(legacyManifest(), currentVersionCode = 20000L))
        assertNull(parseAppUpdateManifest(legacyManifest(), currentVersionCode = 20001L))
    }

    @Test
    fun bomWhitespaceAndNegativeSizeAreNormalized() {
        val raw = "\uFEFF  " + legacyManifest().replace("19903256", "-5")
        val update = requireNotNull(parseAppUpdateManifest(raw, currentVersionCode = 19999L))
        assertEquals(0L, update.apkSize)
    }
}
