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
        secure: InMemorySecureStore = InMemorySecureStore(),
    ) = ImportedCredentialStore(secure)

    @Test
    fun `current returns null before anything is saved`() = runTest {
        assertNull(store().current())
    }

    @Test
    fun `save then current round trips every field`() = runTest {
        val subject = store()
        subject.save(credential)
        assertEquals(credential, subject.current())
    }

    @Test
    fun `clear removes the credential`() = runTest {
        val subject = store()
        subject.save(credential)
        subject.clear()
        assertNull(subject.current())
    }
}
