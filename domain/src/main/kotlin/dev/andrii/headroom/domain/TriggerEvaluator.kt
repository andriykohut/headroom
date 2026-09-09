package dev.andrii.headroom.domain

/**
 * All notification rules (spec §5), as a pure function.
 *
 * Deliberately free of Android types and of any clock: `nowEpochSeconds` is
 * injected, so every rule is a plain table-driven unit test.
 */
class TriggerEvaluator {

    fun evaluate(
        previous: UsageSnapshot?,
        current: UsageSnapshot,
        settings: TriggerSettings,
        alreadyFired: Set<EventKey>,
        nowEpochSeconds: Long,
    ): List<NotificationEvent> {
        val events = mutableListOf<NotificationEvent>()
        for (bucket in current.buckets) {
            if (bucket.kind == BucketKind.UNKNOWN) continue
            val before = previous?.buckets?.firstOrNull { it.identity == bucket.identity }
            resetEvent(bucket, current, settings, nowEpochSeconds)?.let(events::add)
            thresholdEvent(bucket, before, settings)?.let(events::add)
            wallEvent(bucket, before, settings)?.let(events::add)
        }
        return events.filter { it.key !in alreadyFired }
    }

    private fun resetEvent(
        bucket: LimitBucket,
        current: UsageSnapshot,
        settings: TriggerSettings,
        now: Long,
    ): NotificationEvent? {
        val type = when (bucket.kind) {
            BucketKind.SESSION -> TriggerType.SESSION_RESET
            BucketKind.WEEKLY_ALL, BucketKind.WEEKLY_SCOPED -> TriggerType.WEEKLY_RESET
            else -> return null
        }
        val enabled = if (type == TriggerType.SESSION_RESET) settings.sessionReset
        else settings.weeklyReset
        if (!enabled) return null
        if (now < bucket.resetsAt) return null
        // Spec §7: a reset far in the past means stale data or clock skew, not
        // an event worth waking someone for.
        if (now - bucket.resetsAt > windowLength(bucket.kind)) return null
        return NotificationEvent(
            key = EventKey(bucket.identity, bucket.resetsAt, type),
            title = "${bucket.title} reset",
            body = "Your ${bucket.title.lowercase()} limit has reset — you have capacity again." +
                weeklyContext(bucket, current),
        )
    }

    /**
     * Fires once per window while above the threshold.
     *
     * It deliberately does **not** require an upward crossing against the
     * previous snapshot. That rule looked equivalent and was not: the UI's
     * refresh writes to the same cache the coordinator reads as `previous`, so
     * a user who opened the app while over their line poisoned the baseline and
     * the next poll saw "above, and above before too" — no crossing, no
     * notification, silently. Once-per-window is what was actually wanted, and
     * the notification log already provides it, keyed on the window.
     *
     * It also means lowering the threshold below current usage notifies at the
     * next poll rather than staying quiet until the window rolls over, which is
     * what someone who just moved their warning line expects.
     */
    private fun thresholdEvent(
        bucket: LimitBucket,
        before: LimitBucket?,
        settings: TriggerSettings,
    ): NotificationEvent? {
        if (!settings.approachingLimit) return null
        val threshold = settings.thresholdPercent
        if (bucket.utilization < threshold) return null
        return NotificationEvent(
            key = EventKey(bucket.identity, bucket.resetsAt, TriggerType.APPROACHING_LIMIT),
            title = "${bucket.title} at ${bucket.utilization.toInt()}%",
            body = "You're approaching your ${bucket.title.lowercase()} limit.",
        )
    }

    /**
     * The wall, from the only signal the app can actually see.
     *
     * The response carries no rejection flag - the old `status: "rejected"`
     * field does not exist in the live shape, and Headroom never makes a
     * request that could be refused, so it cannot observe a rejection either.
     * Fully-used is what is observable, so that is the trigger.
     *
     * The copy is worded to match: it says the limit is used up and when it
     * lifts, and does not assert that requests are being rejected, which would
     * be an inference of exactly the kind that made the first spec wrong. If a
     * real at-the-wall response is ever captured and carries a genuine flag,
     * this is the one place that changes.
     */
    private fun wallEvent(
        bucket: LimitBucket,
        before: LimitBucket?,
        settings: TriggerSettings,
    ): NotificationEvent? {
        if (!settings.wallHit) return null
        if (bucket.utilization < FULLY_USED) return null
        if (before != null && before.utilization >= FULLY_USED) return null
        return NotificationEvent(
            key = EventKey(bucket.identity, bucket.resetsAt, TriggerType.WALL_HIT),
            title = "${bucket.title} limit reached",
            body = "You've used all of your ${bucket.title.lowercase()} limit. " +
                "It lifts when the window resets.",
        )
    }

    /**
     * What is left of the week, appended to a session reset.
     *
     * A session reset arrives in the middle of the night and the useful
     * question is whether the fresh session is worth spending, which the week
     * answers and the session cannot. Blank for a weekly reset, which has no
     * larger window to report, and blank when there is no weekly bucket.
     */
    private fun weeklyContext(bucket: LimitBucket, current: UsageSnapshot): String {
        if (bucket.kind != BucketKind.SESSION) return ""
        val weekly = current.bucket(BucketKind.WEEKLY_ALL) ?: return ""
        return " Week at ${weekly.utilization.toInt()}%."
    }

    private fun windowLength(kind: BucketKind): Long = when (kind) {
        BucketKind.SESSION -> FIVE_HOURS
        else -> LONGEST_WINDOW
    }

    companion object {
        /**
         * The longest a reset can still be notified for. The notification log
         * has to outlast it, or a key it prunes lets the same reset fire again.
         */
        const val LONGEST_WINDOW = 604_800L
        private const val FIVE_HOURS = 18_000L
        private const val FULLY_USED = 100.0
    }
}
