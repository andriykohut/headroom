package dev.andrii.headroom.schedule

import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.domain.UsageSnapshot

private val RESET_KINDS = setOf(
    BucketKind.SESSION,
    BucketKind.WEEKLY_ALL,
    BucketKind.WEEKLY_SCOPED,
)

/**
 * The next moment worth waking for: the earliest future reset among enabled
 * buckets. Pure, so the selection logic is unit-tested (spec §6).
 */
fun nextAlarmAt(
    snapshot: UsageSnapshot?,
    settings: TriggerSettings,
    nowEpochSeconds: Long,
): Long? = snapshot?.buckets
    ?.asSequence()
    ?.filter { it.kind in RESET_KINDS }
    ?.filter {
        if (it.kind == BucketKind.SESSION) settings.sessionReset else settings.weeklyReset
    }
    ?.map { it.resetsAt }
    // A reset time the parser could not read comes through as 0. Without this
    // it would look like a valid alarm any time "now" is negative, and more
    // importantly it is not a time at all.
    ?.filter { it > 0 }
    ?.filter { it > nowEpochSeconds }
    ?.minOrNull()

/**
 * Wakes the app at a reset.
 *
 * The Android implementation lives with the worker it starts (Task 13), since
 * its PendingIntent has to name the receiver that enqueues that worker.
 */
interface AlarmScheduler {
    fun schedule(atEpochSeconds: Long)
    fun cancel()
}
