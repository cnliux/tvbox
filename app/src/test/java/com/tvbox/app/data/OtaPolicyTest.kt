package com.tvbox.app.data

import com.tvbox.app.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OtaPolicyTest {

    @Test
    fun wifiOnlyBlocksCellularOrMeteredNetworks() {
        assertEquals(WIFI_ONLY_BLOCK_MESSAGE, otaWifiBlockReason(AppSettings(), false))
        assertNull(otaWifiBlockReason(AppSettings(), true))
        assertNull(otaWifiBlockReason(AppSettings(updateWifiOnly = false), false))
    }

    @Test
    fun rollbackTimestampParsesOnlyGeneratedApkNames() {
        assertEquals(1_700_000_000_000L, rollbackFileTimestampMillis("JuheVideo-123-1700000000000.apk"))
        assertEquals(1_700_000_000_000L, rollbackFileTimestampMillis("JuheVideo-123-1700000000000.apk.part"))
        assertEquals(1_700_000_000_000L, rollbackFileTimestampMillis("JuheVideo-123-1700000000000-cdn1.apk.fallback"))
        assertNull(rollbackFileTimestampMillis("JuheVideo-123-1700000000000.apk.fallback.apk"))
        assertNull(rollbackFileTimestampMillis("my-update.apk"))
    }

    @Test
    fun purgeDeletesOnlyExpiredFilesAndSkipsActiveTask() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ota-rollback-${System.nanoTime()}")
            .apply { mkdirs() }
        val now = 1_700_500_000_000L
        val expired = File(dir, "JuheVideo-1-${now - 48 * 3_600_000L}.apk").apply { createNewFile() }
        val recent = File(dir, "JuheVideo-1-${now - 2 * 3_600_000L}.apk").apply { createNewFile() }
        val active = File(dir, "JuheVideo-1-${now - 72 * 3_600_000L}.apk").apply { createNewFile() }
        val unrelated = File(dir, "my-backup.apk").apply { createNewFile() }

        val deleted = purgeExpiredRollbackFiles(
            files = listOf(expired, recent, active, unrelated),
            retentionHours = 24,
            keepPath = active.absolutePath,
            nowMs = now,
        )

        assertEquals(1, deleted)
        assertTrue(!expired.exists())
        assertTrue(recent.exists())
        assertTrue(active.exists())
        assertTrue(unrelated.exists())
        listOf(recent, active, unrelated).forEach { it.delete() }
        dir.delete()
    }
}
