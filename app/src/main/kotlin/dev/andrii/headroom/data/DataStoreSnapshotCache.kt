package dev.andrii.headroom.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BucketJson(
    val rawKind: String,
    val title: String,
    val utilization: Double,
    val resetsAt: Long,
    val group: String = "",
    val severity: String = "",
    val isActive: Boolean = false,
)

@Serializable
private data class SnapshotJson(val buckets: List<BucketJson>, val fetchedAt: Long)

class DataStoreSnapshotCache(
    private val dataStore: DataStore<Preferences>,
) : SnapshotCache {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): UsageSnapshot? {
        val stored = dataStore.data.first()[KEY] ?: return null
        val decoded = runCatching {
            json.decodeFromString(SnapshotJson.serializer(), stored)
        }.getOrNull() ?: return null
        return UsageSnapshot(
            buckets = decoded.buckets.map {
                LimitBucket(
                    kind = BucketKind.fromWire(it.rawKind),
                    rawKind = it.rawKind,
                    title = it.title,
                    utilization = it.utilization,
                    resetsAt = it.resetsAt,
                    group = it.group,
                    severity = it.severity,
                    isActive = it.isActive,
                )
            },
            fetchedAt = decoded.fetchedAt,
        )
    }

    override suspend fun store(snapshot: UsageSnapshot) {
        val encoded = json.encodeToString(
            SnapshotJson.serializer(),
            SnapshotJson(
                buckets = snapshot.buckets.map {
                    BucketJson(
                        rawKind = it.rawKind,
                        title = it.title,
                        utilization = it.utilization,
                        resetsAt = it.resetsAt,
                        group = it.group,
                        severity = it.severity,
                        isActive = it.isActive,
                    )
                },
                fetchedAt = snapshot.fetchedAt,
            ),
        )
        dataStore.edit { it[KEY] = encoded }
    }

    private companion object {
        val KEY = stringPreferencesKey("last_snapshot")
    }
}
