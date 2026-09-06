package dev.andrii.headroom.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/usage_response.json"))
            .bufferedReader().readText()

    @Test
    fun `parses the real captured response`() {
        val snapshot = UsageParser.parse(fixture(), fetchedAt = 1_787_000_000)
        assertEquals(3, snapshot.buckets.size)
        assertEquals(1_787_000_000, snapshot.fetchedAt)
    }

    @Test
    fun `reads the session bucket out of the real fixture`() {
        val bucket = assertNotNull(UsageParser.parse(fixture(), 0).bucket(BucketKind.SESSION))
        assertEquals(23.0, bucket.utilization)
        assertEquals(1_788_717_600L, bucket.resetsAt)
        assertEquals("Current session", bucket.title)
        assertEquals("session", bucket.group)
        assertEquals("normal", bucket.severity)
        assertTrue(bucket.isActive)
    }

    @Test
    fun `titles match the usage panel`() {
        val titles = UsageParser.parse(fixture(), 0).buckets.map { it.title }
        assertEquals(
            listOf("Current session", "Current week (all models)", "Current week (Example Model)"),
            titles,
        )
    }

    @Test
    fun `weekly scoped bucket takes its title from the model display name`() {
        val json = """{"limits":[{
            "kind":"weekly_scoped","group":"weekly","percent":10,
            "resets_at":"2026-09-09T00:00:00+00:00",
            "scope":{"model":{"display_name":"Opus"}}
        }]}"""
        assertEquals("Current week (Opus)", UsageParser.parse(json, 0).buckets.single().title)
    }

    @Test
    fun `resets_at is read as an ISO-8601 timestamp`() {
        val json = """{"limits":[
            {"kind":"session","percent":1,"resets_at":"2026-09-06T18:00:00.876755+00:00"}
        ]}"""
        assertEquals(1_788_717_600L, UsageParser.parse(json, 0).buckets.single().resetsAt)
    }

    @Test
    fun `resets_at is also accepted as epoch seconds`() {
        // The spec originally claimed epoch seconds. It was wrong, but a server
        // that ever sends them should not produce a bucket dated 1970.
        val json = """{"limits":[{"kind":"session","percent":1,"resets_at":1788717600}]}"""
        assertEquals(1_788_717_600L, UsageParser.parse(json, 0).buckets.single().resetsAt)
    }

    @Test
    fun `unparseable reset time yields zero rather than failing the whole response`() {
        val json = """{"limits":[{"kind":"session","percent":1,"resets_at":"whenever"}]}"""
        assertEquals(0L, UsageParser.parse(json, 0).buckets.single().resetsAt)
    }

    @Test
    fun `unknown kind renders generically instead of being dropped`() {
        val json = """{"limits":[{"kind":"some_future_bucket","percent":5,"resets_at":1}]}"""
        val bucket = UsageParser.parse(json, 0).buckets.single()
        assertEquals(BucketKind.UNKNOWN, bucket.kind)
        assertEquals("some_future_bucket", bucket.rawKind)
        assertTrue(bucket.title.isNotBlank())
    }

    @Test
    fun `unknown kind does not hide known ones`() {
        val json = """{"limits":[
            {"kind":"some_future_bucket","percent":5,"resets_at":1},
            {"kind":"session","percent":7,"resets_at":2}
        ]}"""
        assertNotNull(UsageParser.parse(json, 0).bucket(BucketKind.SESSION))
    }

    @Test
    fun `the limits key wins over other arrays in the response`() {
        // The real response is an object with many keys; only `limits` holds
        // the buckets, and picking "the first array" is not good enough.
        val json = """{
            "amber_ladder":[{"kind":"session","percent":99,"resets_at":1}],
            "limits":[{"kind":"session","percent":11,"resets_at":2}]
        }"""
        assertEquals(11.0, UsageParser.parse(json, 0).bucket(BucketKind.SESSION)!!.utilization)
    }

    @Test
    fun `a bare array is still accepted`() {
        val json = """[{"kind":"session","percent":4,"resets_at":1}]"""
        assertNotNull(UsageParser.parse(json, 0).bucket(BucketKind.SESSION))
    }

    @Test
    fun `missing bucket returns null rather than a zero bucket`() {
        val json = """{"limits":[{"kind":"session","percent":1,"resets_at":1}]}"""
        assertNull(UsageParser.parse(json, 0).bucket(BucketKind.WEEKLY_ALL))
    }

    @Test
    fun `utilization is a percent out of one hundred, not a fraction`() {
        val json = """{"limits":[{"kind":"session","percent":100,"resets_at":1}]}"""
        assertEquals(100.0, UsageParser.parse(json, 0).buckets.single().utilization)
    }

    @Test
    fun `utilization is accepted as an alias for percent`() {
        val json = """{"limits":[{"kind":"session","utilization":33,"resets_at":1}]}"""
        assertEquals(33.0, UsageParser.parse(json, 0).buckets.single().utilization)
    }

    @Test
    fun `malformed json raises UsageParseException`() {
        try {
            UsageParser.parse("{not json", 0)
            error("expected UsageParseException")
        } catch (e: UsageParseException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `an object with no limits array raises rather than reporting no usage`() {
        try {
            UsageParser.parse("""{"spend":{"percent":1}}""", 0)
            error("expected UsageParseException")
        } catch (e: UsageParseException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
