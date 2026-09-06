package dev.andrii.headroom.data

import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.credential.RefreshFailedException
import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.Credential
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real wire shape: an object with the buckets under `limits` (spec §2). */
private const val BODY =
    """{"limits":[{"kind":"session","group":"session","percent":42,""" +
        """"resets_at":"2026-09-06T18:00:00+00:00"}]}"""

class FakeCredentialStore(
    private var credential: Credential?,
    private val onRefresh: () -> Credential? = { credential },
) : CredentialStore {
    var refreshCount = 0
    override suspend fun current() = credential
    override suspend fun save(credential: Credential) { this.credential = credential }
    override suspend fun refresh(): Credential {
        refreshCount++
        return onRefresh() ?: throw RefreshFailedException("no")
    }
    override suspend fun clear() { credential = null }
}

class UsageApiTest {

    // A distinctive token: asserting that a message omits "at" would pass on
    // almost any string, and would not catch a real leak.
    private val credential = Credential(
        "tok-abc123", "rt", 9_999_999_999,
        "cid", "https://example.test/oauth/token", "https://example.test/api/oauth/usage",
    )

    private fun jsonEngine(
        body: String = BODY,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: ((io.ktor.client.request.HttpRequestData) -> Unit)? = null,
    ) = MockEngine { request ->
        capture?.invoke(request)
        respond(
            body, status,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun api(engine: MockEngine, store: CredentialStore) =
        UsageApi(store, HttpClient(engine), now = { 1_787_000_000 })

    @Test
    fun `fetches and parses a snapshot`() = runTest {
        val snapshot = api(jsonEngine(), FakeCredentialStore(credential)).fetch()
        assertNotNull(snapshot.bucket(BucketKind.SESSION))
        assertEquals(1_787_000_000, snapshot.fetchedAt)
    }

    @Test
    fun `calls the endpoint from the credential`() = runTest {
        var url: String? = null
        api(jsonEngine(capture = { url = it.url.toString() }), FakeCredentialStore(credential))
            .fetch()
        assertEquals("https://example.test/api/oauth/usage", url)
    }

    @Test
    fun `sends the access token as a bearer header`() = runTest {
        var auth: String? = null
        api(
            jsonEngine(capture = { auth = it.headers[HttpHeaders.Authorization] }),
            FakeCredentialStore(credential),
        ).fetch()
        assertEquals("Bearer tok-abc123", auth)
    }

    @Test
    fun `at wall adds the documented query parameters`() = runTest {
        var url: String? = null
        api(jsonEngine(capture = { url = it.url.toString() }), FakeCredentialStore(credential))
            .fetch(atWall = true)
        assertTrue(url!!.contains("at_wall=1"))
        assertTrue(url!!.contains("skip_spend=1"))
    }

    @Test
    fun `unlinked store raises NotLinkedException`() = runTest {
        assertFailsWith<NotLinkedException> {
            api(jsonEngine(), FakeCredentialStore(null)).fetch()
        }
    }

    @Test
    fun `a 401 triggers exactly one refresh and one retry`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respondError(HttpStatusCode.Unauthorized)
            else respond(
                BODY, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val store = FakeCredentialStore(credential)
        val snapshot = api(engine, store).fetch()
        assertEquals(1, store.refreshCount)
        assertEquals(2, calls)
        assertNotNull(snapshot.bucket(BucketKind.SESSION))
    }

    @Test
    fun `a second 401 after refresh gives up rather than looping`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respondError(HttpStatusCode.Unauthorized) }
        val store = FakeCredentialStore(credential)
        assertFailsWith<UsageFetchException> { api(engine, store).fetch() }
        assertEquals(1, store.refreshCount)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed refresh surfaces as RefreshFailedException`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val store = FakeCredentialStore(credential, onRefresh = { null })
        assertFailsWith<RefreshFailedException> { api(engine, store).fetch() }
    }

    @Test
    fun `a server error raises UsageFetchException naming the status`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<UsageFetchException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertTrue(e.message!!.contains("503"))
    }

    @Test
    fun `error messages never contain the token`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<UsageFetchException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertFalse(e.message!!.contains("tok-abc123"))
    }
}
