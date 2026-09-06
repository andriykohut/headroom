package dev.andrii.headroom.domain

/** A single reading of the usage endpoint. [fetchedAt] is epoch seconds. */
data class UsageSnapshot(
    val buckets: List<LimitBucket>,
    val fetchedAt: Long,
) {
    fun bucket(kind: BucketKind): LimitBucket? =
        buckets.firstOrNull { it.kind == kind && kind != BucketKind.UNKNOWN }
}
