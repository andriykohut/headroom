package dev.andrii.headroom.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanResultTest {

    private fun golden(): String =
        checkNotNull(javaClass.getResourceAsStream("/payload_golden.txt"))
            .bufferedReader().readText().trim()

    @Test
    fun `a valid payload links`() {
        assertTrue(interpretScan(golden()) is ScanOutcome.Linked)
    }

    @Test
    fun `a foreign QR code is ignored rather than shown as an error`() {
        // The camera sees every code in frame; a wifi QR should not raise an
        // alarming message, it should simply not match.
        assertTrue(interpretScan("WIFI:S:somenetwork;;") is ScanOutcome.NotOurs)
        assertTrue(interpretScan("https://example.test") is ScanOutcome.NotOurs)
    }

    @Test
    fun `a damaged headroom code reports a fixable message`() {
        val outcome = interpretScan(golden().dropLast(4) + "AAAA")
        assertTrue(outcome is ScanOutcome.Rejected)
        assertTrue((outcome as ScanOutcome.Rejected).message.isNotBlank())
    }

    @Test
    fun `rejection messages never contain a token`() {
        val outcome = interpretScan(golden().dropLast(4) + "AAAA")
        assertFalse((outcome as ScanOutcome.Rejected).message.contains("at-123"))
    }

    @Test
    fun `surrounding whitespace from a paste is tolerated`() {
        assertTrue(interpretScan("  ${golden()}  \n") is ScanOutcome.Linked)
    }

    @Test
    fun `empty input is not ours`() {
        assertTrue(interpretScan("") is ScanOutcome.NotOurs)
        assertTrue(interpretScan("   ") is ScanOutcome.NotOurs)
    }
}
