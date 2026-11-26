package com.tvbox.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tvbox.app.domain.AppSettings
import com.tvbox.app.domain.KidsLockPolicy
import com.tvbox.app.domain.ParentalLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6-42：少儿锁摘要 / 就寝锁时长 / 车载模式的持久化与默认值回归。 */
class HumanFactorsSettingsPersistenceTest {

    @Test
    fun `keys follow snake_case convention`() {
        assertEquals("kids_lock_pin_hash", HumanFactorsPrefsKeys.KIDS_LOCK_PIN_HASH)
        assertEquals("screen_time_limit_minutes", HumanFactorsPrefsKeys.SCREEN_TIME_LIMIT_MINUTES)
        assertEquals("driving_mode", HumanFactorsPrefsKeys.DRIVING_MODE)
        assertEquals("parental_lock_enabled", HumanFactorsPrefsKeys.PARENTAL_LOCK_ENABLED)
        assertEquals("parental_lock_pin", HumanFactorsPrefsKeys.PARENTAL_LOCK_PIN)
    }

    @Test
    fun `missing keys fall back to disabled defaults`() {
        assertEquals(AppSettings(), AppSettings().withHumanFactorsFrom(emptyMap()))
    }

    @Test
    fun `read clamps minutes and preserves stored pin hash`() {
        val hash = KidsLockPolicy.hashPin("135790")
        val values = mapOf<String, Any?>(
            HumanFactorsPrefsKeys.KIDS_LOCK_PIN_HASH to "  $hash  ",
            HumanFactorsPrefsKeys.SCREEN_TIME_LIMIT_MINUTES to -7,
            HumanFactorsPrefsKeys.DRIVING_MODE to true,
        )
        val loaded = AppSettings().withHumanFactorsFrom(values)
        assertEquals(hash, loaded.kidsLockPinHash)
        assertEquals(0, loaded.screenTimeLimitMinutes)
        assertTrue(loaded.drivingMode)
        assertTrue(KidsLockPolicy.matches("135790", loaded.kidsLockPinHash))
    }

    @Test
    fun `write to preferences then read back through codec primitives`() {
        val original = AppSettings(
            kidsLockPinHash = KidsLockPolicy.hashPin("123456"),
            screenTimeLimitMinutes = 60,
            drivingMode = true,
        )
        val prefs = mutablePreferencesOf()
        prefs.writeHumanFactors(original)

        val values = prefs.toSettingsValues()
        assertEquals(original.kidsLockPinHash, values[HumanFactorsPrefsKeys.KIDS_LOCK_PIN_HASH])
        assertEquals(60, values[HumanFactorsPrefsKeys.SCREEN_TIME_LIMIT_MINUTES])
        assertEquals(true, values[HumanFactorsPrefsKeys.DRIVING_MODE])

        val restored = AppSettings().withHumanFactorsFrom(values)
        assertEquals(original.kidsLockPinHash, restored.kidsLockPinHash)
        assertEquals(60, restored.screenTimeLimitMinutes)
        assertTrue(restored.drivingMode)
        assertTrue(KidsLockPolicy.matches("123456", restored.kidsLockPinHash))
    }

    @Test
    fun `write clamps out of range minutes`() {
        val prefs = mutablePreferencesOf()
        prefs.writeHumanFactors(AppSettings(screenTimeLimitMinutes = 9_999))
        assertEquals(1_440, prefs.toSettingsValues()[HumanFactorsPrefsKeys.SCREEN_TIME_LIMIT_MINUTES])
    }

    @Test
    fun `repository round trips human factor settings`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, EmptyLegacySource())
        val settings = AppSettings(
            kidsLockPinHash = KidsLockPolicy.hashPin("246810"),
            screenTimeLimitMinutes = 90,
            drivingMode = true,
        )

        repository.saveSettings(settings)
        val loaded = repository.getSettings()

        assertEquals(settings.kidsLockPinHash, loaded.kidsLockPinHash)
        assertEquals(90, loaded.screenTimeLimitMinutes)
        assertTrue(loaded.drivingMode)
        assertTrue(KidsLockPolicy.matches("246810", loaded.kidsLockPinHash))
        assertFalse(KidsLockPolicy.matches("246811", loaded.kidsLockPinHash))
    }

    @Test
    fun `repository round trips parental lock fields`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreAppSettingsRepository(store, EmptyLegacySource())
        // T6-42：家长锁只持久化开关 + PIN 摘要（ParentalLock.hashPin），默认全关。
        val settings = AppSettings(
            parentalLockEnabled = true,
            parentalLockPin = ParentalLock.hashPin("1234"),
        )

        repository.saveSettings(settings)
        val loaded = repository.getSettings()

        assertTrue(loaded.parentalLockEnabled)
        assertEquals(settings.parentalLockPin, loaded.parentalLockPin)
        assertTrue(ParentalLock.verifyPin("1234", loaded.parentalLockPin))
        assertFalse(ParentalLock.verifyPin("0000", loaded.parentalLockPin))
    }

    private class EmptyLegacySource : LegacySettingsSource {
        override suspend fun read(): LegacySettingsSnapshot = LegacySettingsSnapshot(emptyMap())
    }

    /** 与仓库测试同款的内存 DataStore，规避 Windows 上文件 rename 差异。 */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }
}
