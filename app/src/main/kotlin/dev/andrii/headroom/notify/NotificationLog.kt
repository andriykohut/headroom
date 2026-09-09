package dev.andrii.headroom.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.andrii.headroom.domain.EventKey
import dev.andrii.headroom.domain.TriggerType
import kotlinx.coroutines.flow.first

/**
 * What the notification rules remember between cycles, so they stay idempotent
 * across overlapping polls, process death and reboots (spec §5).
 */
interface NotificationLog {
    suspend fun fired(): Set<EventKey>
    suspend fun record(keys: Collection<EventKey>)
    /** Forget keys old enough that no rule can produce them again. */
    suspend fun prune(beforeResetsAt: Long)

    /**
     * When a cycle last completed, or null if none ever has.
     *
     * Null is a fresh install, and the reset rule reads it as such: with
     * nothing to place a rollover against, it announces none.
     */
    suspend fun lastCycleAt(): Long?
    suspend fun recordCycle(atEpochSeconds: Long)
}

/**
 * ASCII unit separator. Bucket identities are undocumented server strings and
 * already contain a colon for per-model buckets, so the separator has to be a
 * character no plausible name would carry.
 */
private const val SEPARATOR = "\u001F"

fun EventKey.serialise(): String = listOf(bucketIdentity, resetsAt.toString(), type.name)
    .joinToString(SEPARATOR)

fun parseEventKey(text: String): EventKey? {
    val parts = text.split(SEPARATOR)
    if (parts.size != 3) return null
    val resetsAt = parts[1].toLongOrNull() ?: return null
    val type = TriggerType.entries.firstOrNull { it.name == parts[2] } ?: return null
    return EventKey(parts[0], resetsAt, type)
}

class DataStoreNotificationLog(
    private val dataStore: DataStore<Preferences>,
) : NotificationLog {

    override suspend fun fired(): Set<EventKey> =
        (dataStore.data.first()[KEY] ?: emptySet()).mapNotNull(::parseEventKey).toSet()

    override suspend fun record(keys: Collection<EventKey>) {
        if (keys.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) + keys.map { it.serialise() }
        }
    }

    override suspend fun prune(beforeResetsAt: Long) {
        dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()).filter { entry ->
                parseEventKey(entry)?.let { it.resetsAt >= beforeResetsAt } ?: false
            }.toSet()
        }
    }

    override suspend fun lastCycleAt(): Long? = dataStore.data.first()[CYCLE_KEY]

    override suspend fun recordCycle(atEpochSeconds: Long) {
        dataStore.edit { prefs -> prefs[CYCLE_KEY] = atEpochSeconds }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("fired_event_keys")
        val CYCLE_KEY = longPreferencesKey("last_cycle_at")
    }
}
