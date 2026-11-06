package com.tvbox.app.ui

import com.tvbox.app.domain.AppUpdate

/** 应用内更新 UI 状态（从 [TvBoxUiState] 拆出，降低单一状态体积；字段语义与原 `update*` 保持一致）。 */
data class UpdateUiState(
    val availableUpdate: AppUpdate? = null,
    val dialogVisible: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Int? = null,
    val downloadedApkPath: String? = null,
    val autoInstallPath: String? = null,
    val awaitingInstallPermission: Boolean = false,
    val exporting: Boolean = false,
    val exportMessage: String? = null,
    val error: String? = null,
)
