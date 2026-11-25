package com.tvbox.app.domain

import kotlinx.serialization.Serializable

/**
 * T7-44：四个 JSON（source_config / remote_profile / update_manifest / go_session）
 * 共用的 schema 信封。未知字段一律忽略 —— 加 `delta{}` 那天老客户端不能全体 OTA 失联。
 */
@Serializable
data class DataEnvelope<T>(
    val schema: Int,
    val kind: String,
    /** 低于此版本的读者必须拒绝并提示升级，不能瞎猜。 */
    val minReader: Int,
    /** versionName+versionCode+flavor，出事一眼定位。 */
    val createdBy: String,
    val checksum: String? = null,
    val payload: T,
)

/** 唯一真源：新增 schema 只在这里 +1，禁止在各自的 Codec 里写魔法数字。 */
object Schemas {
    const val SOURCE_CONFIG: Int = 1
    const val REMOTE_PROFILE: Int = 2
    const val UPDATE_MANIFEST: Int = 1
    const val GO_SESSION: Int = 1

    /** 当前客户端能读到的最大 schema。 */
    const val MAX_READER: Int = 2

    enum class Kind(val id: String) {
        SourceConfig("source_config"),
        RemoteProfile("remote_profile"),
        UpdateManifest("update_manifest"),
        GoSession("go_session"),
        ;
    }

    fun current(kind: Kind): Int = when (kind) {
        Kind.SourceConfig -> SOURCE_CONFIG
        Kind.RemoteProfile -> REMOTE_PROFILE
        Kind.UpdateManifest -> UPDATE_MANIFEST
        Kind.GoSession -> GO_SESSION
    }
}

enum class EnvelopeVerdict { Accept, Migrate, RejectTooNew, RejectTooOld }

/**
 * 读信封前的三向判定：新于读者 → 提示升级；reader 太老 → 拒绝；
 * 旧于当前模板 → 走逐步迁移链（禁止跨步跳）；相等 → 直接用。
 */
fun inspectEnvelope(schema: Int, minReader: Int, kind: Schemas.Kind): EnvelopeVerdict = when {
    schema > Schemas.MAX_READER -> EnvelopeVerdict.RejectTooNew
    minReader > Schemas.MAX_READER -> EnvelopeVerdict.RejectTooOld
    schema < Schemas.current(kind) -> EnvelopeVerdict.Migrate
    else -> EnvelopeVerdict.Accept
}
