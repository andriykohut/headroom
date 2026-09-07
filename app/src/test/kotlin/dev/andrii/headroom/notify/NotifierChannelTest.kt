package dev.andrii.headroom.notify

import dev.andrii.headroom.domain.EventKey
import dev.andrii.headroom.domain.NotificationEvent
import dev.andrii.headroom.domain.TriggerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared test double for the coordinator tests in Task 13. */
class RecordingNotifier : Notifier {
    val events = mutableListOf<NotificationEvent>()
    val rejectionMessages = mutableListOf<String>()
    override fun notify(event: NotificationEvent) { events += event }
    override fun notifyRelayRejected(message: String) { rejectionMessages += message }
}

class NotifierChannelTest {

    @Test
    fun `every trigger type has its own channel`() {
        val ids = TriggerType.entries.map(::channelIdFor)
        assertEquals(TriggerType.entries.size, ids.toSet().size)
    }

    @Test
    fun `channel ids are stable strings`() {
        // Changing a channel id orphans the user's existing preference for it,
        // so these are effectively frozen once shipped.
        assertEquals("session_reset", channelIdFor(TriggerType.SESSION_RESET))
        assertEquals("weekly_reset", channelIdFor(TriggerType.WEEKLY_RESET))
        assertEquals("approaching_limit", channelIdFor(TriggerType.APPROACHING_LIMIT))
        assertEquals("wall_hit", channelIdFor(TriggerType.WALL_HIT))
    }

    @Test
    fun `channel names are human readable`() {
        TriggerType.entries.forEach { type ->
            val name = channelNameFor(type)
            assertTrue(name.isNotBlank())
            assertTrue(!name.contains('_'), "channel name should read as prose: $name")
        }
    }

    @Test
    fun `relink channel is distinct from every trigger channel`() {
        assertTrue(RELAY_REJECTED_CHANNEL_ID !in TriggerType.entries.map(::channelIdFor))
    }

    @Test
    fun `notification id is stable per event key`() {
        val key = EventKey("session", 2_000, TriggerType.SESSION_RESET)
        assertEquals(notificationIdFor(key), notificationIdFor(key.copy()))
    }

    @Test
    fun `notification ids differ across windows so a new reset does not replace the old`() {
        val key = EventKey("session", 2_000, TriggerType.SESSION_RESET)
        assertTrue(notificationIdFor(key) != notificationIdFor(key.copy(resetsAt = 3_000)))
    }

    @Test
    fun `notification ids differ across models sharing a window`() {
        // Two per-model weekly buckets reset together; if their ids collided,
        // the second notification would silently replace the first.
        val key = EventKey("weekly_scoped:Opus", 2_000, TriggerType.WEEKLY_RESET)
        assertTrue(
            notificationIdFor(key) != notificationIdFor(key.copy(bucketIdentity = "weekly_scoped:Sonnet")),
        )
    }

    @Test
    fun `recording notifier captures events`() {
        val notifier = RecordingNotifier()
        notifier.notify(
            NotificationEvent(
                EventKey("session", 1, TriggerType.SESSION_RESET), "t", "b",
            ),
        )
        assertEquals(1, notifier.events.size)
    }
}
