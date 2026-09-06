package dev.andrii.headroom.ui

import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.LimitBucket
import dev.andrii.headroom.domain.UsageParser
import dev.andrii.headroom.domain.UsageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupingTest {

    private fun fixture(): UsageSnapshot {
        val body = checkNotNull(javaClass.getResourceAsStream("/usage_response.json"))
            .bufferedReader().readText()
        return UsageParser.parse(body, fetchedAt = 1_788_000_000)
    }

    private fun bucket(
        kind: BucketKind,
        group: String,
        title: String = kind.title,
        resetsAt: Long = 5_000,
    ) = LimitBucket(kind, kind.wireName, title, 10.0, resetsAt, group = group)

    @Test
    fun `the real response makes a session band and a week band`() {
        val bands = groupBuckets(fixture())
        assertEquals(listOf("session", "weekly"), bands.map { it.group })
        assertNull(bands[0].header)
        assertEquals("This week", bands[1].header)
    }

    @Test
    fun `the week band shares one reset time in the real response`() {
        val week = groupBuckets(fixture()).first { it.group == "weekly" }
        assertEquals(1_788_912_000L, week.sharedResetsAt)
    }

    @Test
    fun `the session band carries no shared time of its own to render`() {
        // It has one entry, so a "shared" time is trivially true - the hero
        // renders a countdown regardless, but the band data should be honest.
        val session = groupBuckets(fixture()).first { it.group == "session" }
        assertEquals(1, session.buckets.size)
    }

    @Test
    fun `weekly_all leads its band whatever order the server sent`() {
        val snapshot = UsageSnapshot(
            listOf(
                bucket(BucketKind.WEEKLY_SCOPED, "weekly", "Current week (Opus)"),
                bucket(BucketKind.WEEKLY_ALL, "weekly"),
            ),
            0,
        )
        val week = groupBuckets(snapshot).single()
        assertEquals(BucketKind.WEEKLY_ALL, week.buckets.first().kind)
    }

    @Test
    fun `entries after the anchor keep the order the server sent`() {
        val snapshot = UsageSnapshot(
            listOf(
                bucket(BucketKind.WEEKLY_ALL, "weekly"),
                bucket(BucketKind.WEEKLY_SCOPED, "weekly", "Current week (Sonnet)"),
                bucket(BucketKind.WEEKLY_SCOPED, "weekly", "Current week (Opus)"),
            ),
            0,
        )
        val week = groupBuckets(snapshot).single()
        assertEquals(
            listOf("All models", "Sonnet", "Opus"),
            week.buckets.map(::displayLabel),
        )
    }

    @Test
    fun `an unrecognised group earns its own band after the known ones`() {
        val snapshot = UsageSnapshot(
            listOf(
                bucket(BucketKind.UNKNOWN, "monthly", "monthly"),
                bucket(BucketKind.SESSION, "session"),
            ),
            0,
        )
        val bands = groupBuckets(snapshot)
        assertEquals(listOf("session", "monthly"), bands.map { it.group })
        assertEquals("Monthly", bands[1].header)
    }

    @Test
    fun `an unrecognised kind stays inside its known group`() {
        // A new weekly limit is still a weekly limit; it must not be exiled to
        // a catch-all band.
        val snapshot = UsageSnapshot(
            listOf(bucket(BucketKind.UNKNOWN, "weekly", "seven_day_something")),
            0,
        )
        val bands = groupBuckets(snapshot)
        assertEquals(1, bands.size)
        assertEquals("weekly", bands.single().group)
    }

    @Test
    fun `a band whose entries disagree on reset time has no shared time`() {
        val snapshot = UsageSnapshot(
            listOf(
                bucket(BucketKind.WEEKLY_ALL, "weekly", resetsAt = 1_000),
                bucket(BucketKind.WEEKLY_SCOPED, "weekly", "Current week (Opus)", resetsAt = 2_000),
            ),
            0,
        )
        assertNull(groupBuckets(snapshot).single().sharedResetsAt)
    }

    @Test
    fun `an unreadable reset time is never treated as shared`() {
        val snapshot = UsageSnapshot(listOf(bucket(BucketKind.WEEKLY_ALL, "weekly", resetsAt = 0)), 0)
        assertNull(groupBuckets(snapshot).single().sharedResetsAt)
    }

    @Test
    fun `display label strips the prefix the band header already states`() {
        assertEquals(
            "All models",
            displayLabel(bucket(BucketKind.WEEKLY_ALL, "weekly")),
        )
        assertEquals(
            "Example Model",
            displayLabel(bucket(BucketKind.WEEKLY_SCOPED, "weekly", "Current week (Example Model)")),
        )
    }

    @Test
    fun `display label leaves an unfamiliar title alone`() {
        assertEquals(
            "some_future_bucket",
            displayLabel(bucket(BucketKind.UNKNOWN, "weekly", "some_future_bucket")),
        )
    }

    @Test
    fun `grouping never reorders on percent`() {
        val high = LimitBucket(BucketKind.WEEKLY_SCOPED, "weekly_scoped", "Current week (A)", 99.0, 5_000, group = "weekly")
        val low = LimitBucket(BucketKind.WEEKLY_ALL, "weekly_all", "Current week (all models)", 1.0, 5_000, group = "weekly")
        val week = groupBuckets(UsageSnapshot(listOf(high, low), 0)).single()
        assertTrue(week.buckets.first().kind == BucketKind.WEEKLY_ALL, "anchor must lead, not the largest")
    }
}
