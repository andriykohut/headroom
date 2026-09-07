package dev.andrii.headroom.data

import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun snapshot(at: Long, utilization: Double = 10.0) = UsageSnapshot(
    listOf(
        LimitBucket(
            BucketKind.SESSION, "session", "Current session", utilization, 2_000,
        ),
    ),
    fetchedAt = at,
)

class InMemorySnapshotCache(private var snapshot: UsageSnapshot? = null) : SnapshotCache {
    override suspend fun load(): UsageSnapshot? = snapshot
    override suspend fun store(snapshot: UsageSnapshot) { this.snapshot = snapshot }
}

class UsageRepositoryTest {

    private fun repository(
        fetch: suspend () -> UsageSnapshot,
        cache: InMemorySnapshotCache = InMemorySnapshotCache(),
        now: Long = 1_000,
    ) = UsageRepository(
        fetch = fetch,
        cache = cache,
        now = { now },
    )

    @Test
    fun `successful refresh yields Ready`() = runTest {
        val state = repository({ snapshot(1_000) }).refresh()
        assertTrue(state is UsageState.Ready)
        assertEquals(false, (state as UsageState.Ready).stale)
    }

    @Test
    fun `successful refresh caches the snapshot`() = runTest {
        val cache = InMemorySnapshotCache()
        repository({ snapshot(1_000) }, cache).refresh()
        assertEquals(1_000, cache.load()!!.fetchedAt)
    }

    @Test
    fun `state flow reflects the latest refresh`() = runTest {
        val repo = repository({ snapshot(1_000) })
        repo.refresh()
        assertTrue(repo.state.value is UsageState.Ready)
    }

    @Test
    fun `failure keeps the cached snapshot and marks it failed`() = runTest {
        // Spec §7: keep showing the last snapshot with its age; do not zero it.
        val cache = InMemorySnapshotCache(snapshot(500))
        val state = repository(
            { throw UsageFetchException("Couldn't reach the server (IOException).") },
            cache,
        ).refresh()
        assertTrue(state is UsageState.Failed)
        assertEquals(500, (state as UsageState.Failed).snapshot!!.fetchedAt)
    }

    @Test
    fun `failure with no cache reports a null snapshot rather than zeros`() = runTest {
        val state = repository({ throw UsageFetchException("boom") }).refresh()
        assertNull((state as UsageState.Failed).snapshot)
    }

    @Test
    fun `unlinked yields NotLinked`() = runTest {
        val state = repository({ throw NotLinkedException() }).refresh()
        assertTrue(state is UsageState.NotLinked)
    }

    @Test
    fun `a rejected key flags that a new code is needed`() = runTest {
        val state = repository({ throw RelayRejectedException("rejected") }).refresh()
        assertTrue((state as UsageState.Failed).needsNewCode)
    }

    @Test
    fun `an ordinary fetch failure does not demand a re-link`() = runTest {
        val state = repository({ throw UsageFetchException("HTTP 503") }).refresh()
        assertFalse((state as UsageState.Failed).needsNewCode)
    }

    @Test
    fun `a reading older than the staleness window is marked stale`() = runTest {
        // The UI dims a stale reading, so the flag has to be computed rather
        // than assumed false. now is far past the snapshot's fetchedAt.
        val state = repository({ snapshot(0) }, now = UsageRepository.STALE_AFTER_SECONDS + 1)
            .refresh()
        assertTrue((state as UsageState.Ready).stale)
    }

    @Test
    fun `a reading inside the staleness window is not stale`() = runTest {
        val state = repository({ snapshot(0) }, now = UsageRepository.STALE_AFTER_SECONDS - 1)
            .refresh()
        assertFalse((state as UsageState.Ready).stale)
    }

    @Test
    fun `ageSeconds reports how old a reading is`() = runTest {
        assertEquals(600, repository({ snapshot(400) }, now = 1_000).ageSeconds(snapshot(400)))
    }

    @Test
    fun `lastSnapshot reads through to the cache before any refresh`() = runTest {
        val repo = repository({ snapshot(1_000) }, InMemorySnapshotCache(snapshot(42)))
        assertEquals(42, repo.lastSnapshot()!!.fetchedAt)
    }
}
