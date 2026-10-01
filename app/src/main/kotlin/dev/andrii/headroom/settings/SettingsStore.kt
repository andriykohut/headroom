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
            sessionResetOnlyIfUsed = prefs[SESSION_GATED] ?: defaults.sessionResetOnlyIfUsed,
            sessionResetMinUsage = prefs[SESSION_MIN_USAGE] ?: defaults.sessionResetMinUsage,
            weeklyResetOnlyIfUsed = prefs[WEEKLY_GATED] ?: defaults.weeklyResetOnlyIfUsed,
            weeklyResetMinUsage = prefs[WEEKLY_MIN_USAGE] ?: defaults.weeklyResetMinUsage,
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
            prefs[SESSION_GATED] = settings.sessionResetOnlyIfUsed
            prefs[SESSION_MIN_USAGE] = settings.sessionResetMinUsage
            prefs[WEEKLY_GATED] = settings.weeklyResetOnlyIfUsed
            prefs[WEEKLY_MIN_USAGE] = settings.weeklyResetMinUsage
        }
    }

    private companion object {
        val SESSION = booleanPreferencesKey("trigger_session_reset")
        val WEEKLY = booleanPreferencesKey("trigger_weekly_reset")
        val APPROACHING = booleanPreferencesKey("trigger_approaching_limit")
        val WALL = booleanPreferencesKey("trigger_wall_hit")
        val THRESHOLD = doublePreferencesKey("threshold_percent")
        val SESSION_GATED = booleanPreferencesKey("session_reset_only_if_used")
        val SESSION_MIN_USAGE = doublePreferencesKey("session_reset_min_usage")
        val WEEKLY_GATED = booleanPreferencesKey("weekly_reset_only_if_used")
        val WEEKLY_MIN_USAGE = doublePreferencesKey("weekly_reset_min_usage")
    }
}
