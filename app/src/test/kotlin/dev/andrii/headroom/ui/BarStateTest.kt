package dev.andrii.headroom.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BarStateTest {

    @Test
    fun `a full window is at the wall`() {
        assertEquals(BarState.WALL, barState(percentUsed = 100.0, thresholdPercent = 80.0))
    }

    @Test
    fun `past the threshold is approaching`() {
        assertEquals(BarState.APPROACHING, barState(percentUsed = 94.0, thresholdPercent = 80.0))
    }

    @Test
    fun `below the threshold is fine`() {
        assertEquals(BarState.FINE, barState(percentUsed = 12.0, thresholdPercent = 80.0))
    }

    @Test
    fun `a window that has rolled over reads as reset, not as the wall`() {
        // The bug this exists to stop: a 100% reading whose window ended while
        // the reporting machine was asleep was drawn as "Limit reached", so
        // someone waking up was told they were blocked when they had a full
        // fresh window. Reset has to win over every other state.
        assertEquals(
            BarState.RESET,
            barState(percentUsed = 100.0, thresholdPercent = 80.0, hasReset = true),
        )
    }

    @Test
    fun `a window that has rolled over reads as reset, not as approaching`() {
        assertEquals(
            BarState.RESET,
            barState(percentUsed = 94.0, thresholdPercent = 80.0, hasReset = true),
        )
    }
}
