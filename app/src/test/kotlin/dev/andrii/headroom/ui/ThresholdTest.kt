package dev.andrii.headroom.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThresholdTest {

    @Test
    fun `keeps a sensible value unchanged`() {
        assertEquals(90.0, clampThreshold(90.0))
        assertEquals(50.0, clampThreshold(50.0))
    }

    @Test
    fun `clamps below the floor`() {
        // A threshold near zero would fire immediately and constantly.
        assertEquals(50.0, clampThreshold(0.0))
        assertEquals(50.0, clampThreshold(-10.0))
    }

    @Test
    fun `clamps above the ceiling`() {
        // A threshold of 100 would only fire once the wall was already hit.
        assertEquals(99.0, clampThreshold(100.0))
        assertEquals(99.0, clampThreshold(250.0))
    }

    @Test
    fun `rounds to a whole percent`() {
        assertEquals(87.0, clampThreshold(87.4))
        assertEquals(88.0, clampThreshold(87.6))
    }

    @Test
    fun `the clamped range is always drawable as a notch`() {
        // The bar places the notch at threshold/100 of its width. A value
        // outside 0..100 would put it off the end of the track.
        listOf(-50.0, 0.0, 49.9, 90.0, 100.0, 1_000.0).forEach {
            val clamped = clampThreshold(it)
            assertEquals(true, clamped in 0.0..100.0, "notch would fall off the track: $clamped")
        }
    }
}
