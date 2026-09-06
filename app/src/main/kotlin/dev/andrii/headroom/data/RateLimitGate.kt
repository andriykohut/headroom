package dev.andrii.headroom.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Remembers that the server asked us to stop calling, and until when.
 *
 * Persisted rather than held in memory: the poll worker runs in a fresh
 * process each time, so an in-memory gate would forget the moment it mattered
 * and the next poll would walk straight back into the limit.
 */
interface RateLimitGate {
    /** Epoch seconds before which no request may be made; 0 when clear. */
    suspend fun retryAt(): Long
    suspend fun hold(untilEpochSeconds: Long)
    suspend fun clear()
}

class DataStoreRateLimitGate(
    private val dataStore: DataStore<Preferences>,
) : RateLimitGate {

    override suspend fun retryAt(): Long = dataStore.data.first()[KEY] ?: 0L

    override suspend fun hold(untilEpochSeconds: Long) {
        dataStore.edit { it[KEY] = untilEpochSeconds }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = longPreferencesKey("rate_limit_retry_at")
    }
}
