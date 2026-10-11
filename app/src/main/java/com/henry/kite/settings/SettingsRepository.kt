package com.henry.kite.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mirror_settings",
)

/**
 * Persists [MirrorSettings].
 *
 * Enums are stored by name rather than ordinal so reordering the declarations
 * cannot silently change what a stored value means. An unknown name — from a
 * downgrade, or an option that was removed — falls back to the default.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MirrorSettings> = context.settingsDataStore.data.map { prefs ->
        MirrorSettings(
            quality = prefs[KEY_QUALITY].toQuality(),
            frameRate = prefs[KEY_FRAME_RATE].toFrameRate(),
            bitRateMbps = prefs[KEY_BIT_RATE_MBPS] ?: MirrorSettings.BIT_RATE_AUTO,
        )
    }

    /**
     * Reads the current values once.
     *
     * Callers that only need a snapshot — starting a session, say — use this
     * rather than a StateFlow's `value`, which is only populated while something
     * is actively collecting it.
     */
    suspend fun current(): MirrorSettings = settings.first()

    suspend fun setQuality(value: Quality) = edit { it[KEY_QUALITY] = value.name }

    suspend fun setFrameRate(value: FrameRate) = edit { it[KEY_FRAME_RATE] = value.name }

    suspend fun setBitRateMbps(value: Int) = edit { it[KEY_BIT_RATE_MBPS] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun String?.toQuality(): Quality = Quality.entries.firstOrNull { it.name == this } ?: Quality.P1080

    private fun String?.toFrameRate(): FrameRate = FrameRate.entries.firstOrNull { it.name == this } ?: FrameRate.FPS30

    private companion object {
        val KEY_QUALITY = stringPreferencesKey("quality")
        val KEY_FRAME_RATE = stringPreferencesKey("frameRate")
        val KEY_BIT_RATE_MBPS = intPreferencesKey("bitRateMbps")
    }
}
