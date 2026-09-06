package dev.andrii.headroom.schedule

import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.TriggerSettings
import dev.andrii.headroom.domain.UsageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextAlarmTest {

    private fun bucket(kind: BucketKind, resetsAt: Long) =
        LimitBucket(kind, kind.wireName, kind.title, 10.0, resetsAt)

    private fun snapshot(vararg buckets: LimitBucket) = UsageSnapshot(buckets.toList(), 0)

    @Test
    fun `picks the earliest future reset`() {
        val result = nextAlarmAt(
            snapshot(
                bucket(BucketKind.WEEKLY_ALL, 9_000),
                bucket(BucketKind.SESSION, 5_000),
            ),
            TriggerSettings(),
            nowEpochSeconds = 1_000,
        )
        assertEquals(5_000, result)
    }

    @Test
    fun `ignores resets already in the past`() {
        val result = nextAlarmAt(
            snapshot(bucket(BucketKind.SESSION, 500), bucket(BucketKind.WEEKLY_ALL, 9_000)),
            TriggerSettings(),
            nowEpochSeconds = 1_000,
        )
        assertEquals(9_000, result)
    }

    @Test
    fun `returns null when every reset has passed`() {
        assertNull(
            nextAlarmAt(
                snapshot(bucket(BucketKind.SESSION, 500)),
                TriggerSettings(),
                nowEpochSeconds = 1_000,
            ),
        )
    }

    @Test
    fun `returns null for a null snapshot`() {
        assertNull(nextAlarmAt(null, TriggerSettings(), 1_000))
    }

    @Test
    fun `skips buckets whose trigger is disabled`() {
        val result = nextAlarmAt(
            snapshot(
                bucket(BucketKind.SESSION, 5_000),
                bucket(BucketKind.WEEKLY_ALL, 9_000),
            ),
            TriggerSettings(sessionReset = false),
            nowEpochSeconds = 1_000,
        )
        assertEquals(9_000, result)
    }

    @Test
    fun `per model weekly buckets are scheduled like any other weekly`() {
        val result = nextAlarmAt(
            snapshot(bucket(BucketKind.WEEKLY_SCOPED, 4_000)),
            TriggerSettings(),
            nowEpochSeconds = 1_000,
        )
        assertEquals(4_000, result)
    }

    @Test
    fun `returns null when all reset triggers are disabled`() {
        assertNull(
            nextAlarmAt(
                snapshot(bucket(BucketKind.SESSION, 5_000)),
                TriggerSettings(sessionReset = false, weeklyReset = false),
                nowEpochSeconds = 1_000,
            ),
        )
    }

    @Test
    fun `ignores unknown buckets`() {
        val unknown = LimitBucket(BucketKind.UNKNOWN, "future", "future", 1.0, 5_000)
        assertNull(nextAlarmAt(snapshot(unknown), TriggerSettings(), 1_000))
    }

    @Test
    fun `ignores buckets with no reset time`() {
        // The parser yields 0 for a reset time it could not read. Scheduling an
        // alarm for the epoch would fire immediately and repeatedly.
        assertNull(
            nextAlarmAt(
                snapshot(bucket(BucketKind.SESSION, 0)),
                TriggerSettings(),
                nowEpochSeconds = -10,
            ),
        )
    }
}
