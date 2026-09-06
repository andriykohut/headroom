package dev.andrii.headroom.ui

import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageSnapshot

/**
 * One band of the Usage screen.
 *
 * [sharedResetsAt] is set only when every entry in the band resets at the same
 * instant, which is when the header carries the time and the rows do not.
 */
data class Band(
    val group: String,
    val header: String?,
    val buckets: List<LimitBucket>,
    val sharedResetsAt: Long?,
)

private const val GROUP_SESSION = "session"
private const val GROUP_WEEKLY = "weekly"

/** Kinds that lead their band; everything else keeps the order the server sent. */
private val ANCHOR_KINDS = setOf(BucketKind.SESSION, BucketKind.WEEKLY_ALL)

/**
 * Bands in a fixed order: session, weekly, then any unrecognised group in the
 * order it first appeared.
 *
 * Nothing here sorts by percent, severity or is_active. A glanceable meter has
 * to be the same shape every time it is opened, so the layout must not move
 * with the data.
 */
fun groupBuckets(snapshot: UsageSnapshot): List<Band> {
    val byGroup = LinkedHashMap<String, MutableList<LimitBucket>>()
    snapshot.buckets.forEach { bucket ->
        // An entry whose group the server omitted still has to land somewhere;
        // its kind is the best available answer.
        val group = bucket.group.ifBlank { groupFromKind(bucket.kind) }
        byGroup.getOrPut(group) { mutableListOf() } += bucket
    }

    val ordered = buildList {
        byGroup[GROUP_SESSION]?.let { add(GROUP_SESSION to it) }
        byGroup[GROUP_WEEKLY]?.let { add(GROUP_WEEKLY to it) }
        byGroup.forEach { (group, buckets) ->
            if (group != GROUP_SESSION && group != GROUP_WEEKLY) add(group to buckets)
        }
    }

    return ordered.map { (group, buckets) ->
        val sorted = buckets.sortedBy { if (it.kind in ANCHOR_KINDS) 0 else 1 }
        Band(
            group = group,
            header = headerFor(group),
            buckets = sorted,
            sharedResetsAt = sharedResetsAt(sorted),
        )
    }
}

private fun groupFromKind(kind: BucketKind) = when (kind) {
    BucketKind.SESSION -> GROUP_SESSION
    BucketKind.WEEKLY_ALL, BucketKind.WEEKLY_SCOPED -> GROUP_WEEKLY
    BucketKind.UNKNOWN -> ""
}

/**
 * The session band is headed by its hero's own title, so it has none of its
 * own. An unrecognised group is headed by the group string as it came.
 */
private fun headerFor(group: String): String? = when (group) {
    GROUP_SESSION -> null
    GROUP_WEEKLY -> "This week"
    "" -> "Other"
    else -> group.replaceFirstChar { it.uppercase() }
}

private fun sharedResetsAt(buckets: List<LimitBucket>): Long? {
    if (buckets.isEmpty()) return null
    val first = buckets.first().resetsAt
    if (first <= 0) return null
    return if (buckets.all { it.resetsAt == first }) first else null
}

/**
 * What a compact row is called.
 *
 * Inside "This week", a title of the form `Current week (X)` shows as `X` —
 * the band header already says the rest. Every other title shows verbatim.
 * [LimitBucket.title] itself is untouched, so notification copy is unaffected.
 */
fun displayLabel(bucket: LimitBucket): String {
    val match = WEEKLY_TITLE.matchEntire(bucket.title) ?: return bucket.title
    // "Current week (all models)" becomes "All models": it starts a line now,
    // where it used to sit mid-sentence inside the title.
    return match.groupValues[1].replaceFirstChar { it.titlecase() }
}

private val WEEKLY_TITLE = Regex("""Current week \((.+)\)""")
