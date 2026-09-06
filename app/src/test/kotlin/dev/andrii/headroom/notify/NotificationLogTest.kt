package dev.andrii.headroom.notify

import dev.andrii.headroom.domain.EventKey
import dev.andrii.headroom.domain.TriggerType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryNotificationLog : NotificationLog {
    private val keys = mutableSetOf<EventKey>()
    override suspend fun fired(): Set<EventKey> = keys.toSet()
    override suspend fun record(keys: Collection<EventKey>) { this.keys += keys }
    override suspend fun prune(beforeResetsAt: Long) {
        keys.removeAll { it.resetsAt < beforeResetsAt }
    }
}

class NotificationLogTest {

    private val key = EventKey("session", 2_000, TriggerType.SESSION_RESET)

    @Test
    fun `round trips a key through serialisation`() {
        assertEquals(key, parseEventKey(key.serialise()))
    }

    @Test
    fun `a scoped bucket identity round trips`() {
        // Per-model weekly identities carry a colon, which is exactly the sort
        // of character a naive separator would break on.
        val scoped = EventKey("weekly_scoped:Example Model", 5, TriggerType.WEEKLY_RESET)
        assertEquals(scoped, parseEventKey(scoped.serialise()))
    }

    @Test
    fun `serialisation survives a raw kind containing separators`() {
        val awkward = EventKey("weird|kind:name", 5, TriggerType.WALL_HIT)
        assertEquals(awkward, parseEventKey(awkward.serialise()))
    }

    @Test
    fun `unparseable text yields null rather than throwing`() {
        assertNull(parseEventKey("nonsense"))
        assertNull(parseEventKey(""))
    }

    @Test
    fun `unknown trigger type yields null`() {
        assertNull(parseEventKey(listOf("session", "2000", "NOT_A_TRIGGER").joinToString("\u001F")))
    }

    @Test
    fun `recorded keys are reported as fired`() = runTest {
        val log = InMemoryNotificationLog()
        log.record(listOf(key))
        assertTrue(key in log.fired())
    }

    @Test
    fun `recording twice is idempotent`() = runTest {
        val log = InMemoryNotificationLog()
        log.record(listOf(key))
        log.record(listOf(key))
        assertEquals(1, log.fired().size)
    }

    @Test
    fun `keys differing only by trigger type are distinct`() = runTest {
        val log = InMemoryNotificationLog()
        log.record(listOf(key, key.copy(type = TriggerType.APPROACHING_LIMIT)))
        assertEquals(2, log.fired().size)
    }

    @Test
    fun `keys differing only by window are distinct`() = runTest {
        val log = InMemoryNotificationLog()
        log.record(listOf(key, key.copy(resetsAt = 3_000)))
        assertEquals(2, log.fired().size)
    }

    @Test
    fun `keys differing only by bucket identity are distinct`() = runTest {
        // Two models' weekly buckets share a kind and a reset time; only the
        // identity separates them, and collapsing them silences one.
        val log = InMemoryNotificationLog()
        log.record(
            listOf(
                key.copy(bucketIdentity = "weekly_scoped:Opus"),
                key.copy(bucketIdentity = "weekly_scoped:Sonnet"),
            ),
        )
        assertEquals(2, log.fired().size)
    }

    @Test
    fun `prune drops keys for windows already past`() = runTest {
        val log = InMemoryNotificationLog()
        log.record(listOf(key, key.copy(resetsAt = 10_000)))
        log.prune(beforeResetsAt = 5_000)
        assertFalse(key in log.fired())
        assertTrue(key.copy(resetsAt = 10_000) in log.fired())
    }
}
