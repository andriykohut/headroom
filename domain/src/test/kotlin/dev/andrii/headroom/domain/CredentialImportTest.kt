package dev.andrii.headroom.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialImportTest {

    private fun golden(): String =
        checkNotNull(javaClass.getResourceAsStream("/payload_golden.txt"))
            .bufferedReader().readText().trim()

    @Test
    fun `decodes the generator's golden fixture`() {
        // The wire contract with tools/. If this fails, the app can no longer
        // read codes the generator produces - fix both sides together.
        val credential = CredentialImport.decode(golden())
        assertEquals("at-123", credential.accessToken)
        assertEquals("rt-456", credential.refreshToken)
        assertEquals(1_787_262_000L, credential.expiresAt)
        assertEquals("cid-789", credential.clientId)
        assertEquals("https://example.test/oauth/token", credential.tokenEndpoint)
        assertEquals("https://example.test/api/oauth/usage", credential.usageEndpoint)
    }

    @Test
    fun `rejects a foreign scheme`() {
        val e = assertFailsWith<PayloadException> { CredentialImport.decode("otherapp1:abcd") }
        assertTrue(e.message!!.contains("Headroom"))
    }

    @Test
    fun `rejects a corrupted body`() {
        val corrupted = golden().dropLast(4) + "AAAA"
        assertFailsWith<PayloadException> { CredentialImport.decode(corrupted) }
    }

    @Test
    fun `rejects a payload missing a required field`() {
        val partial = CredentialImportTestSupport.encode(mapOf("access_token" to "a"))
        val e = assertFailsWith<PayloadException> { CredentialImport.decode(partial) }
        assertTrue(e.message!!.contains("missing"))
    }

    @Test
    fun `error messages never contain a token value`() {
        val partial = CredentialImportTestSupport.encode(
            mapOf("access_token" to "super-secret-value"),
        )
        val e = assertFailsWith<PayloadException> { CredentialImport.decode(partial) }
        assertFalse(e.message!!.contains("super-secret-value"))
    }

    @Test
    fun `toString never exposes a token`() {
        // A data class's generated toString is the easiest way for a
        // credential to reach a crash report.
        val rendered = CredentialImport.decode(golden()).toString()
        assertFalse(rendered.contains("at-123"))
        assertFalse(rendered.contains("rt-456"))
        assertFalse(rendered.contains("cid-789"))
    }

    @Test
    fun `whitespace around a pasted payload is tolerated`() {
        assertEquals("at-123", CredentialImport.decode("  ${golden()}\n").accessToken)
    }

    @Test
    fun `looksLikePayload distinguishes ours from other QR codes`() {
        assertTrue(CredentialImport.looksLikePayload(golden()))
        assertTrue(CredentialImport.looksLikePayload("  ${golden()}  "))
        assertFalse(CredentialImport.looksLikePayload("https://example.test"))
        assertFalse(CredentialImport.looksLikePayload(""))
    }

    @Test
    fun `isExpired compares against the injected clock`() {
        val credential = CredentialImport.decode(golden())
        assertTrue(credential.isExpired(credential.expiresAt))
        assertTrue(credential.isExpired(credential.expiresAt + 1))
        assertFalse(credential.isExpired(credential.expiresAt - 1))
    }
}
