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
)
