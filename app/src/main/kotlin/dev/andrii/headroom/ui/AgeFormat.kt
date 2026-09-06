package dev.andrii.headroom.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "updated 5 min ago". Clock skew reads as fresh rather than as the future. */
fun formatAge(seconds: Long): String = when {
    seconds < 30 -> "just now"
    seconds < 3_600 -> "${seconds / 60} min ago"
    seconds < 86_400 -> "${seconds / 3_600} hr ago"
    else -> "${seconds / 86_400} days ago"
}

/** "resets in 4h 20m". */
fun formatCountdown(secondsUntil: Long): String {
    if (secondsUntil <= 0) return "now"
    val days = secondsUntil / 86_400
    val hours = (secondsUntil % 86_400) / 3_600
    val minutes = (secondsUntil % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

private val RESET_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

/**
 * "Wed 02:00", in the phone's own zone.
 *
 * A weekly window is days away, where a wall-clock time reads better than a
 * countdown; the session, hours away, keeps [formatCountdown]. Returns null
 * when there is no instant to show, so a bucket whose reset time the parser
 * could not read shows nothing rather than a date in 1970.
 */
fun formatResetTime(resetsAtEpochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (resetsAtEpochSeconds <= 0) return null
    return RESET_TIME.format(Instant.ofEpochSecond(resetsAtEpochSeconds).atZone(zone))
}
