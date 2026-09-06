package dev.andrii.headroom.domain

data class TriggerSettings(
    val sessionReset: Boolean = true,
    val weeklyReset: Boolean = true,
    val approachingLimit: Boolean = true,
    val wallHit: Boolean = true,
    val thresholdPercent: Double = 90.0,
)
