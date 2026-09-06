package dev.andrii.headroom.credential

import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.store.InMemorySecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedCredentialStoreTest {

    private val credential = Credential(
        accessToken = "at-old",
        refreshToken = "rt-old",
        expiresAt = 1_000,
        clientId = "cid",
        tokenEndpoint = "https://example.test/oauth/token",
        usageEndpoint = "https://example.test/api/oauth/usage",
    )

    private fun jsonClient(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

    private fun store(
        client: HttpClient,
        secure: InMemorySecureStore = InMemorySecureStore(),
        now: Long = 2_000,
    ) = ImportedCredentialStore(secure, client, now = { now })

    @Test
    fun `current returns null before anything is saved`() = runTest {
        assertNull(store(jsonClient("{}")).current())
    }

    @Test
    fun `save then current round trips every field`() = runTest {
        val subject = store(jsonClient("{}"))
        subject.save(credential)
        assertEquals(credential, subject.current())
    }

    @Test
    fun `refresh posts to the credential's own token endpoint`() = runTest {
        var requestedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                """{"access_token":"at-new","refresh_token":"rt-new","expires_in":3600}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val subject = store(client)
        subject.save(credential)
        subject.refresh()
        assertEquals("https://example.test/oauth/token", requestedUrl)
    }

    @Test
    fun `refresh stores the new tokens`() = runTest {
        val client = jsonClient("""{"access_token":"at-new","refresh_token":"rt-new","expires_in":3600}""")
        val subject = store(client, now = 2_000)
        subject.save(credential)
        val refreshed = subject.refresh()
        assertEquals("at-new", refreshed.accessToken)
        assertEquals("rt-new", refreshed.refreshToken)
        assertEquals("at-new", subject.current()!!.accessToken)
    }

    @Test
    fun `refresh converts expires_in into an absolute expiry`() = runTest {
        val client = jsonClient("""{"access_token":"a","refresh_token":"r","expires_in":3600}""")
        val subject = store(client, now = 2_000)
        subject.save(credential)
        assertEquals(2_000 + 3_600, subject.refresh().expiresAt)
    }

    @Test
    fun `refresh keeps the old refresh token when the server omits a new one`() = runTest {
        val client = jsonClient("""{"access_token":"a","expires_in":60}""")
        val subject = store(client)
        subject.save(credential)
        assertEquals("rt-old", subject.refresh().refreshToken)
    }

    @Test
    fun `refresh preserves the provider fields which the server never returns`() = runTest {
        val client = jsonClient("""{"access_token":"a","expires_in":60}""")
        val subject = store(client)
        subject.save(credential)
        val refreshed = subject.refresh()
        assertEquals("cid", refreshed.clientId)
        assertEquals(credential.usageEndpoint, refreshed.usageEndpoint)
    }

    @Test
    fun `refresh without a stored credential fails clearly`() = runTest {
        val e = assertFailsWith<RefreshFailedException> { store(jsonClient("{}")).refresh() }
        assertTrue(e.message!!.contains("link", ignoreCase = true))
    }

    @Test
    fun `a rejected refresh raises RefreshFailedException`() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.BadRequest) })
        val subject = store(client)
        subject.save(credential)
        assertFailsWith<RefreshFailedException> { subject.refresh() }
    }

    @Test
    fun `refresh failure messages never contain a token`() = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.BadRequest) })
        val subject = store(client)
        subject.save(credential)
        val e = assertFailsWith<RefreshFailedException> { subject.refresh() }
        assertFalse(e.message!!.contains("rt-old"))
        assertFalse(e.message!!.contains("at-old"))
    }

    @Test
    fun `a rejected refresh does not destroy the stored credential`() = runTest {
        // The user may simply be offline behind a captive portal; discarding
        // the credential would force a needless re-link.
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.BadRequest) })
        val subject = store(client)
        subject.save(credential)
        runCatching { subject.refresh() }
        assertEquals(credential, subject.current())
    }

    @Test
    fun `clear removes the credential`() = runTest {
        val subject = store(jsonClient("{}"))
        subject.save(credential)
        subject.clear()
        assertNull(subject.current())
    }
}
