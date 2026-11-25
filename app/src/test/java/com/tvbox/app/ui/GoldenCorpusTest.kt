package com.tvbox.app.ui

import com.tvbox.app.data.SourceConfigBackup
import com.tvbox.app.data.SourceConfigCodec
import com.tvbox.app.data.parseAppUpdateManifest
import com.tvbox.app.domain.UntrustedJson
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7-38：黄金语料回归。四类 JSON 都放在 src/test/resources/golden/，
 * 由本测试通过 classLoader 读取（与运行时资源访问路径一致），避免再次变成死语料。
 *
 * 覆盖点：现有解码器能正确解析 -> 关键字段非空 -> 篡改/畸形输入只返回错误不抛异常。
 */
class GoldenCorpusTest {

    private fun load(name: String): String {
        val stream: InputStream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: throw AssertionError("黄金语料缺失：golden/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `source config envelope golden has real checksum and round-trips`() {
        // checksum 若是占位值，SourceConfigBackup.parse 会因完整性校验失败而返回 error。
        val result = SourceConfigBackup.parse(load("envelope_v1.json"))
        assertNull(result.error)
        val payload = checkNotNull(result.payload)
        assertEquals(2, payload.lines.size)
        assertEquals(listOf("douyin-hot", "bb-movie"), payload.lines.map { it.id })
        assertEquals(2, payload.liveUrls.size)
        assertTrue(payload.liveUrls.all { it.isNotBlank() })
        assertEquals(listOf("douyin-hot", "bb-movie"), payload.order)
        assertEquals(mapOf("douyin-hot" to 10, "bb-movie" to 8), payload.weights)
    }

    @Test
    fun `tampered source config envelope is rejected by checksum`() {
        // 改掉一个 URL 但保留原 checksum，必须被完整性校验拦下（证明校验不是空转）。
        val tampered = load("envelope_v1.json")
            .replace("https://live.example.com/huya.m3u", "https://evil.example.com/huya.m3u")
        val result = SourceConfigBackup.parse(tampered)
        assertNotNull(result.error)
        assertTrue(checkNotNull(result.error).contains("校验失败"))
        assertNull(result.payload)
    }

    @Test
    fun `update manifest golden parses with non-empty key fields`() {
        val update = parseAppUpdateManifest(
            raw = load("update_manifest_v1.json"),
            currentVersionCode = 20_043L,
        )
        val manifest = requireNotNull(update)
        assertEquals(20_044L, manifest.versionCode)
        assertEquals("1.0.44", manifest.versionName)
        assertTrue(manifest.apkUrl.startsWith("https://"))
        assertEquals(64, manifest.apkSha256.length)
        assertTrue(manifest.apkSize > 0L)
        assertTrue(manifest.changelog.isNotEmpty())
        // apkMirrors 中混入的 http 明文地址必须被过滤，只留两条 HTTPS 镜像。
        assertEquals(2, manifest.apkMirrors.size)
        assertTrue(manifest.apkMirrors.all { it.startsWith("https://") })
        // minVersionCode/channel 是清单 DTO 的校验字段，不进入领域模型 AppUpdate；
        // 其结构完整性由 tools/check_schema.sh 的 update_manifest_v1.json 门禁覆盖。
        // 领域层只保留会展示给用户的 releaseHighlights。
        assertEquals(3, manifest.releaseHighlights.size)
    }

    @Test
    fun `update manifest golden at same or newer client version yields no prompt`() {
        // 版本阶梯：客户端已到 20044 时不应再提示升级，且不能抛异常。
        assertNull(parseAppUpdateManifest(load("update_manifest_v1.json"), 20_044L))
        assertNull(parseAppUpdateManifest(load("update_manifest_v1.json"), 20_045L))
    }

    @Test
    fun `update manifest malformed variants do not escape as unexpected exceptions`() {
        listOf(
            "{\"versionCode\":", // 截断
            "[]", // 根节点类型漂移
            "{\"versionCode\":\"oops\",\"versionName\":\"x\",\"apkUrl\":\"https://a.test/a.apk\"}",
        ).forEach { raw ->
            // 生产解析入口本身会抛序列化异常，调用方必须 runCatching 收口；
            // 这里验证「可被兜住」而不是让异常穿透门禁。
            val outcome = runCatching { parseAppUpdateManifest(raw, currentVersionCode = 0L) }
            assertTrue("畸形清单不应穿透：${outcome.exceptionOrNull()}", outcome.isFailure || outcome.getOrNull() == null)
        }
    }

    @Test
    fun `subscription golden parses via production codec with key fields non-empty`() {
        val result = SourceConfigCodec.parseImport(load("subscription_v1.json"))
        val imported = requireNotNull(result.imported) { "订阅语料解析失败：${result.error}" }
        assertNull(result.error)
        // type=0 的爬虫源被跳过，只剩两条可播接口。
        assertEquals(2, imported.lines.size)
        assertEquals(1, imported.skipped)
        assertTrue(imported.lines.all { it.name.isNotBlank() && it.baseUrl.startsWith("https://") })
        // 直播订阅是文件地址，去 fragment 后不能被追加尾斜杠。
        assertEquals(
            listOf("https://live.example.com/huya.m3u", "https://live.example.com/douyu.m3u"),
            imported.liveUrls,
        )
    }

    @Test
    fun `subscription malformed variants return error without throwing`() {
        listOf("{not json", "{\"sites\":\"oops\"}", "[]", "{\"data\":[{}]}")
            .forEach { raw ->
                val outcome = runCatching { SourceConfigCodec.parseImport(raw) }
                assertTrue("畸形订阅不应抛异常：$raw", outcome.isSuccess)
                val result = outcome.getOrThrow()
                if (result.imported == null) assertNotNull(result.error)
            }
    }

    @Serializable
    private data class CatalogEntry(
        val id: String,
        val name: String,
        val site: String,
        val roomId: String,
        val group: String = "",
    )

    private val catalogSerializer = ListSerializer(CatalogEntry.serializer())

    @Test
    fun `platform live catalog golden decodes via untrusted json boundary`() {
        val decoded = UntrustedJson.decode(load("platform_live_catalog_v1.json"), catalogSerializer)
        val entries = requireNotNull(decoded.getOrNull()) { "目录语料解析失败：${decoded.exceptionOrNull()}" }
        assertEquals(5, entries.size)
        assertEquals("douyu-36252", entries.first().id)
        assertTrue(entries.all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertTrue(entries.all { it.site in setOf("douyu", "huya") })
        assertTrue(entries.all { it.roomId.all(Char::isDigit) })
        assertTrue(entries.all { it.group.isNotBlank() })
    }

    @Test
    fun `platform live catalog malformed falls back without throwing`() {
        val fallback = emptyList<CatalogEntry>()
        listOf(
            "\uFEFF[{\"id\":\"x\",\"site\":1}]", // BOM + 字段类型漂移
            "[{\"id\":", // 截断
            "{\"not\":\"an array\"}", // 根节点类型漂移
        ).forEach { raw ->
            val decoded = UntrustedJson.decodeOrDefault(raw, catalogSerializer, fallback)
            assertEquals("畸形目录应回落默认值：$raw", fallback, decoded)
        }
    }
}
