package dev.andrii.headroom.data

import dev.andrii.headroom.credential.RefreshFailedException
import dev.andrii.headroom.domain.UsageSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SnapshotCache {
    suspend fun load(): UsageSnapshot?
    suspend fun store(snapshot: UsageSnapshot)
}

/**
 * Owns the current usage state and how old it is.
 *
 * Takes `fetch` as a function rather than a UsageApi so the failure paths can
 * be tested without a mock HTTP engine.
 */
class UsageRepository(
    private val fetch: suspend (Boolean) -> UsageSnapshot,
    private val cache: SnapshotCache,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val _state = MutableStateFlow<UsageState>(UsageState.Loading)
    val state: StateFlow<UsageState> = _state.asStateFlow()

    suspend fun lastSnapshot(): UsageSnapshot? =
        (_state.value as? UsageState.Ready)?.snapshot ?: cache.load()

    suspend fun refresh(atWall: Boolean = false): UsageState {
        val next = try {
            val snapshot = fetch(atWall)
            cache.store(snapshot)
            // Computed, not assumed false: a fetch can return a reading the
            // server itself dated a while ago, and the UI dims a stale one.
            UsageState.Ready(snapshot, stale = isStale(snapshot))
        } catch (_: NotLinkedException) {
            UsageState.NotLinked
        } catch (e: RefreshFailedException) {
            UsageState.Failed(
                snapshot = cache.load(),
                message = e.message ?: "Couldn't refresh your credentials.",
                needsRelink = true,
            )
        } catch (e: RateLimitedException) {
            // Said plainly, and with when it lifts: "HTTP 429" tells the user
            // nothing they can act on, and the app is going to stay quiet for a
            // while either way.
            UsageState.Failed(
                snapshot = cache.load(),
                message = rateLimitMessage(e.retryAtEpochSeconds),
                needsRelink = false,
            )
        } catch (e: UsageFetchException) {
            UsageState.Failed(
                snapshot = cache.load(),
                message = e.message ?: "Couldn't read usage.",
                needsRelink = false,
            )
        }
        _state.value = next
        return next
    }

    private fun rateLimitMessage(retryAt: Long): String {
        val minutes = ((retryAt - now()) / 60).coerceAtLeast(1)
        return "The server is asking for fewer requests. Headroom will try " +
            "again in about $minutes min."
    }

    /** Age of a snapshot in seconds, for the "updated N ago" line. */
    fun ageSeconds(snapshot: UsageSnapshot): Long = now() - snapshot.fetchedAt

    fun isStale(snapshot: UsageSnapshot): Boolean =
        ageSeconds(snapshot) > STALE_AFTER_SECONDS

    companion object {
        /** Beyond this, the reading is old enough to say so in the UI. */
        const val STALE_AFTER_SECONDS = 3_600L
    }
}
