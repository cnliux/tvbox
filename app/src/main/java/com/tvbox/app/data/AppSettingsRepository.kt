package com.tvbox.app.data

import com.tvbox.app.domain.AppSettings

interface AppSettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings): AppSettings

    /** 测速延迟持久化：value 为 null 表示测速失败，缺失的 key 表示未测速。 */
    suspend fun getSpeedLatencies(): Map<String, Long?> = emptyMap()
    suspend fun saveSpeedLatencies(latencies: Map<String, Long?>) {}
}
