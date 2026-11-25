package com.tvbox.app.domain

import java.security.MessageDigest

/**
 * T6-39：免责声明的**可证明首次展示**证据链。
 *
 * README 末尾写一句免责在法律上≈没写：用户从 Release 页拿到包根本不经过 README，
 * 真出事时也拿不出「他看过」的证据。这里给出条款文本、版本号、hash 与 ack 判定；
 * UI 层首次启动做不可跳过的确认页，落盘 `legal.ack_version` + `legal.ack_hash` 即可。
 *
 * 条款文本同时补上当前缺的三句：不规避技术措施 / 数据处理说明 / 下游分发约束。
 */
object LegalTerms {

    const val TERMS_VERSION: Int = 1

    /** prefs 注册表键名（与 T7-45 的命名风格一致），由设置层持久化。 */
    const val VERSION_KEY = "legal.ack_version"
    const val HASH_KEY = "legal.ack_hash"

    val TEXT: String = """
聚合视频（原 TVBox）合规使用声明

1. 能力边界：本应用只提供本地播放客户端能力，不内置、不托管、不推荐任何公开订阅地址。
   源内容由第三方提供，请确保你对该内容拥有所在地区的合法授权。

2. 不规避技术措施：本应用不含 DRM 绕过、不含去广告注入、不伪造第三方平台的登录态。
   需要平台登录态的房间会明确提示并不予播放。

3. 数据处理说明：本地仅留存你主动产生的观看历史、源配置与域名评分；
   不上传片名、搜索词与源 URL。可在「设置 → 数据管理」一键导出或全部删除。

4. 合规使用指引：需要自建源请使用本地 HTTP / 局域网 NAS / 私有 Gist，
   本应用不提供也不推荐任何公开订阅地址。

5. 下游分发约束：再分发必须保留本声明与 NOTICE，不得改名冒用，不得预置任何源。
""".trimIndent()

    /** 条款 hash 取 SHA-256 前 16 个十六进制字符即可定位版本，避免整段进遥测。 */
    fun hash(text: String = TEXT): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }.take(16)
    }

    val CURRENT_HASH: String by lazy { hash() }

    data class Ack(val termsVersion: Int, val termsHash: String, val acceptedAtMs: Long)

    /** 版本或 hash 任一变化都必须重新展示条款。 */
    fun isCurrent(ack: Ack?): Boolean =
        ack != null && ack.termsVersion == TERMS_VERSION &&
            ack.termsHash.equals(CURRENT_HASH, ignoreCase = true)

    fun requiresAcknowledgement(ack: Ack?): Boolean = !isCurrent(ack)
}
