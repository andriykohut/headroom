package dev.andrii.headroom.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import dev.andrii.headroom.domain.TriggerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SettingsStore {
    val flow: Flow<TriggerSettings>
    suspend fun current(): TriggerSettings
    suspend fun update(settings: TriggerSettings)
}

class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    override val flow: Flow<TriggerSettings> = dataStore.data.map { prefs ->
        val defaults = TriggerSettings()
        TriggerSettings(
            sessionReset = prefs[SESSION] ?: defaults.sessionReset,
            weeklyReset = prefs[WEEKLY] ?: defaults.weeklyReset,
            approachingLimit = prefs[APPROACHING] ?: defaults.approachingLimit,
            wallHit = prefs[WALL] ?: defaults.wallHit,
            thresholdPercent = prefs[THRESHOLD] ?: defaults.thresholdPercent,
        )
    }

    override suspend fun current(): TriggerSettings = flow.first()

    override suspend fun update(settings: TriggerSettings) {
        dataStore.edit { prefs ->
            prefs[SESSION] = settings.sessionReset
            prefs[WEEKLY] = settings.weeklyReset
            prefs[APPROACHING] = settings.approachingLimit
            prefs[WALL] = settings.wallHit
            prefs[THRESHOLD] = settings.thresholdPercent
        }
    }

    private companion object {
        val SESSION = booleanPreferencesKey("trigger_session_reset")
        val WEEKLY = booleanPreferencesKey("trigger_weekly_reset")
        val APPROACHING = booleanPreferencesKey("trigger_approaching_limit")
        val WALL = booleanPreferencesKey("trigger_wall_hit")
        val THRESHOLD = doublePreferencesKey("threshold_percent")
    }
}
