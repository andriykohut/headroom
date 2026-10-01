package dev.andrii.headroom.domain

data class TriggerSettings(
    val sessionReset: Boolean = true,
    val weeklyReset: Boolean = true,
    val approachingLimit: Boolean = true,
    val wallHit: Boolean = true,
    val thresholdPercent: Double = 90.0,
    /** Skip the session reset when the window that ended stayed under [sessionResetMinUsage]. */
    val sessionResetOnlyIfUsed: Boolean = false,
    val sessionResetMinUsage: Double = 80.0,
    /** Skip the weekly reset when the week that ended stayed under [weeklyResetMinUsage]. */
    val weeklyResetOnlyIfUsed: Boolean = false,
    val weeklyResetMinUsage: Double = 80.0,
)
