package dev.andrii.headroom.notify

import dev.andrii.headroom.domain.EventKey
import dev.andrii.headroom.domain.NotificationEvent
import dev.andrii.headroom.domain.TriggerType

/** The id string is frozen: changing it orphans the user's channel settings. */
const val RELAY_REJECTED_CHANNEL_ID = "relink_needed"

interface Notifier {
    fun notify(event: NotificationEvent)
    /** Spec §7: a relay that stops accepting this phone must not be silent. */
    fun notifyRelayRejected(message: String)
}

/**
 * One channel per trigger so the user can silence approaching-limit warnings
 * without losing reset notifications. These ids are frozen once shipped:
 * changing one orphans the preference the user set against it.
 */
fun channelIdFor(type: TriggerType): String = when (type) {
    TriggerType.SESSION_RESET -> "session_reset"
    TriggerType.WEEKLY_RESET -> "weekly_reset"
    TriggerType.APPROACHING_LIMIT -> "approaching_limit"
    TriggerType.WALL_HIT -> "wall_hit"
}

fun channelNameFor(type: TriggerType): String = when (type) {
    TriggerType.SESSION_RESET -> "Session reset"
    TriggerType.WEEKLY_RESET -> "Weekly reset"
    TriggerType.APPROACHING_LIMIT -> "Approaching limit"
    TriggerType.WALL_HIT -> "Limit reached"
}

/**
 * Stable per window, so a notification for a new window appears alongside
 * rather than replacing the previous one.
 *
 * The key includes the bucket identity, which is what keeps two models'
 * weekly notifications apart when they share a reset time.
 */
fun notificationIdFor(key: EventKey): Int = key.hashCode()
