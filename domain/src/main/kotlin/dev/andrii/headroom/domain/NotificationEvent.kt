package dev.andrii.headroom.domain

enum class TriggerType { SESSION_RESET, WEEKLY_RESET, APPROACHING_LIMIT, WALL_HIT }

/**
 * De-duplication identity for a notification (spec §5).
 *
 * Keyed on the *window* rather than on wall-clock time, which is what makes
 * the rules idempotent across overlapping polls, process death and reboots.
 */
data class EventKey(val rawKind: String, val resetsAt: Long, val type: TriggerType)

data class NotificationEvent(val key: EventKey, val title: String, val body: String)
