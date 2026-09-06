package dev.andrii.headroom.ui

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class AgeFormatTest {

    @Test
    fun `just now for a fresh reading`() {
        assertEquals("just now", formatAge(0))
        assertEquals("just now", formatAge(29))
    }

    @Test
    fun `minutes for a recent reading`() {
        assertEquals("1 min ago", formatAge(60))
        assertEquals("5 min ago", formatAge(5 * 60))
    }

    @Test
    fun `hours for an older reading`() {
        assertEquals("1 hr ago", formatAge(3_600))
        assertEquals("3 hr ago", formatAge(3 * 3_600))
    }

    @Test
    fun `days for a stale reading`() {
        assertEquals("2 days ago", formatAge(2 * 86_400))
    }

    @Test
    fun `negative age from clock skew reads as just now rather than in the future`() {
        assertEquals("just now", formatAge(-500))
    }

    @Test
    fun `countdown renders hours and minutes`() {
        assertEquals("4h 20m", formatCountdown(4 * 3_600 + 20 * 60))
    }

    @Test
    fun `countdown renders minutes alone under an hour`() {
        assertEquals("20m", formatCountdown(20 * 60))
    }

    @Test
    fun `countdown under a minute reads as less than a minute`() {
        assertEquals("<1m", formatCountdown(30))
    }

    @Test
    fun `countdown in the past reads as now`() {
        assertEquals("now", formatCountdown(-10))
        assertEquals("now", formatCountdown(0))
    }

    @Test
    fun `countdown renders days for a weekly window`() {
        assertEquals("2d 3h", formatCountdown(2 * 86_400 + 3 * 3_600))
    }

    // A weekly window is days away, where a clock time reads better than a
    // countdown - the design's band header carries this instead of "2d 3h".

    @Test
    fun `reset time renders as weekday and 24-hour clock`() {
        // 2026-09-09T00:00:00Z is a Wednesday.
        assertEquals("Wed 00:00", formatResetTime(1_788_912_000, ZoneId.of("UTC")))
    }

    @Test
    fun `reset time is rendered in the phone's own zone`() {
        // The same instant, two hours east, is still Wednesday but at 02:00 -
        // which is exactly the "Resets Wed 02:00" the design shows. A fixed
        // offset rather than a named zone, so the test does not depend on
        // which side of a daylight-saving change the date falls.
        assertEquals(
            "Wed 02:00",
            formatResetTime(1_788_912_000, ZoneOffset.ofHours(2)),
        )
    }

    @Test
    fun `reset time crossing midnight lands on the local day, not the UTC one`() {
        // 23:00 UTC on Tuesday is already Wednesday for a phone far enough east.
        assertEquals("Wed 01:00", formatResetTime(1_788_908_400, ZoneOffset.ofHours(2)))
    }

    @Test
    fun `reset time with no known instant renders nothing`() {
        assertEquals(null, formatResetTime(0, ZoneId.of("UTC")))
    }
}
