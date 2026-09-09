package dev.andrii.headroom.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitBucketTest {

    private fun bucket(resetsAt: Long, utilization: Double = 94.0) = LimitBucket(
        kind = BucketKind.SESSION,
        rawKind = BucketKind.SESSION.wireName,
        title = BucketKind.SESSION.title,
        utilization = utilization,
        resetsAt = resetsAt,
    )

    @Test
    fun `a window whose reset time has passed has reset`() {
        assertTrue(bucket(resetsAt = 1_000).hasReset(now = 1_001))
    }

    @Test
    fun `a window whose reset time is still ahead has not reset`() {
        assertFalse(bucket(resetsAt = 2_000).hasReset(now = 1_999))
    }

    @Test
    fun `the reset second itself counts as reset`() {
        assertTrue(bucket(resetsAt = 1_000).hasReset(now = 1_000))
    }

    @Test
    fun `a window with no known reset time never claims to have reset`() {
        // The parser writes 0 when the server sent no reset time. Treating that
        // as "long past" would blank every bar the moment a reset time went
        // missing, which is the failure this whole idea exists to avoid.
        assertFalse(bucket(resetsAt = 0).hasReset(now = 1_700_000_000))
    }

    private fun weekly(resetsAt: Long) = LimitBucket(
        kind = BucketKind.WEEKLY_ALL,
        rawKind = BucketKind.WEEKLY_ALL.wireName,
        title = BucketKind.WEEKLY_ALL.title,
        utilization = 50.0,
        resetsAt = resetsAt,
    )

    private val week = 604_800L

    @Test
    fun `halfway through the window is half elapsed`() {
        val resetsAt = 1_000_000L
        assertEquals(0.5, weekly(resetsAt).elapsedFraction(now = resetsAt - week / 2)!!, 0.001)
    }

    @Test
    fun `the start of the window is nothing elapsed`() {
        val resetsAt = 1_000_000L
        assertEquals(0.0, weekly(resetsAt).elapsedFraction(now = resetsAt - week)!!, 0.001)
    }

    @Test
    fun `a window past its reset is fully elapsed rather than over one`() {
        val resetsAt = 1_000_000L
        assertEquals(1.0, weekly(resetsAt).elapsedFraction(now = resetsAt + 5_000)!!, 0.001)
    }

    @Test
    fun `a session window has no pace to keep`() {
        // Sessions are bursts. An even pace is not a goal anyone has for them,
        // so there is no honest mark to draw.
        assertEquals(null, bucket(resetsAt = 1_000_000).elapsedFraction(now = 999_000))
    }

    @Test
    fun `a window with no known reset time has no pace`() {
        assertEquals(null, weekly(resetsAt = 0).elapsedFraction(now = 1_700_000_000))
    }
}
