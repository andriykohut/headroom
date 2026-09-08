package dev.andrii.headroom.domain

/**
 * The limit windows the usage endpoint reports (spec §2).
 *
 * These are the kinds a live response actually carries. An earlier version of
 * the spec listed `five_hour`, `seven_day`, `seven_day_sonnet` and friends -
 * those names exist, but as keys of an older shape sitting beside `limits`,
 * which the app does not read. See docs/discovery-notes.md.
 */
enum class BucketKind(val wireName: String, val title: String) {
    SESSION("session", "Current session"),
    WEEKLY_ALL("weekly_all", "Current week (all models)"),
    WEEKLY_SCOPED("weekly_scoped", "Current week"),
    UNKNOWN("", "");

    companion object {
        fun fromWire(name: String): BucketKind =
            entries.firstOrNull { it.wireName == name && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * One limit window.
 *
 * [utilization] is a percentage **used**, 0-100 - not a 0-1 fraction. The wire
 * field is an integer named `percent`; it is a Double here so the UI can
 * interpolate without widening later.
 *
 * [rawKind] is retained even for known kinds so an unknown bucket can still be
 * displayed, per spec §7: new server buckets must not crash or hide known ones.
 *
 * [group] (`session` or `weekly`) is sent by the server, so grouping bars is a
 * lookup rather than a guess about what a kind name means.
 *
 * [severity] is the server's own assessment of this bucket, and [isActive]
 * marks the limit currently binding. Both are carried through unread by the
 * parser; whether the UI trusts them is a display decision, not a wire one.
 *
 * There is deliberately no `rejected` flag. Nobody has yet observed a response
 * from an account that is actually at its limit, so any mapping from
 * [severity] to "at the wall" would be exactly the kind of inference that made
 * the first version of spec §2 wrong.
 */
data class LimitBucket(
    val kind: BucketKind,
    val rawKind: String,
    val title: String,
    val utilization: Double,
    val resetsAt: Long,
    val group: String = "",
    val severity: String = "",
    val isActive: Boolean = false,
    val scopeLabel: String = "",
) {
    /**
     * What makes this bucket distinct from its siblings.
     *
     * [rawKind] alone is not enough. Every per-model weekly bucket arrives as
     * `weekly_scoped`, so two models sharing a reset time would collapse onto
     * one de-duplication key and one of them would go unnotified. The scope
     * label is the only thing that separates them.
     */
    val identity: String
        get() = if (scopeLabel.isBlank()) rawKind else "$rawKind:$scopeLabel"

    /**
     * Whether [now] is past this window's rollover, making [utilization] a
     * figure for a window that has ended.
     *
     * A reading can outlive its window: the machine that reports it is asleep,
     * so the last number keeps describing a window that has since rolled over.
     * Drawing it as current tells someone they are at a limit that has lifted.
     *
     * False when [resetsAt] is 0, which is how the parser records a reset time
     * the server did not send - an unknown time is not a past one.
     */
    fun hasReset(now: Long): Boolean = resetsAt > 0 && now >= resetsAt

    /**
     * How much of this window has elapsed, 0.0 to 1.0, or null when the
     * question does not apply.
     *
     * Compared against [utilization] this is the whole of "burn rate" for a
     * weekly window: fill ahead of the mark means spending faster than an even
     * pace would. Null for a session, where bursts are the point and an even
     * pace is nobody's goal, and null without a reset time to measure from.
     */
    fun elapsedFraction(now: Long): Double? {
        if (resetsAt <= 0) return null
        val window = when (kind) {
            BucketKind.WEEKLY_ALL, BucketKind.WEEKLY_SCOPED -> SEVEN_DAYS
            else -> return null
        }
        return ((window - (resetsAt - now)).toDouble() / window).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val SEVEN_DAYS = 604_800L
    }
}
