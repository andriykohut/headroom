package dev.andrii.headroom.data

import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.domain.BucketKind
import dev.andrii.headroom.domain.Credential
import io.ktor.http.HttpHeaders as KtorHeaders
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

class FakeCredentialStore(private var credential: Credential?) : CredentialStore {
    override suspend fun current() = credential
    override suspend fun save(credential: Credential) { this.credential = credential }
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

    private fun api(
        engine: MockEngine,
        store: CredentialStore,
        now: Long = 1_787_000_000,
    ) = UsageApi(store, HttpClient(engine), now = { now })

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
    fun `unlinked store raises NotLinkedException`() = runTest {
        assertFailsWith<NotLinkedException> {
            api(jsonEngine(), FakeCredentialStore(null)).fetch()
        }
    }

    @Test
    fun `a server error raises UsageFetchException naming the status`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<UsageFetchException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertTrue(e.message!!.contains("503"))
    }

    // --- rate limiting ---

    @Test
    fun `error messages never contain the token`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<UsageFetchException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertFalse(e.message!!.contains("tok-abc123"))
    }

    // --- a relay that will not accept this phone ---

    /**
     * The only credential-shaped failure left. A relay mints nothing and
     * expires nothing, so a 401 means the secret is wrong or was rotated -
     * which the user can fix, and must therefore be told about distinctly.
     */
    @Test
    fun `a 401 raises RelayRejectedException, not a generic failure`() = runTest {
        val api = api(jsonEngine(status = HttpStatusCode.Unauthorized), FakeCredentialStore(credential))
        val error = assertFailsWith<RelayRejectedException> { api.fetch() }
        assertTrue(error.message!!.contains("Scan a new code"), "must say what to do: ${error.message}")
    }

    @Test
    fun `a 403 is treated the same as a 401`() = runTest {
        val api = api(jsonEngine(status = HttpStatusCode.Forbidden), FakeCredentialStore(credential))
        assertFailsWith<RelayRejectedException> { api.fetch() }
    }

    @Test
    fun `a rejection does not retry, because there is nothing to renew`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.Unauthorized)
        }
        assertFailsWith<RelayRejectedException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertEquals(1, calls, "a relay has no refresh to attempt")
    }

    // --- how old the reading is (X-Headroom-Age) ---

    /**
     * A relay serves a reading pushed to it earlier. Dating the snapshot from
     * the moment of the request would make an hour-old number render as "just
     * now" and never go stale.
     */
    @Test
    fun `a relay's age header dates the reading, not the request`() = runTest {
        val now = 1_787_000_000L
        val engine = MockEngine {
            respond(
                BODY, HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "X-Headroom-Age" to listOf("3600"),
                ),
            )
        }
        val snapshot = api(engine, FakeCredentialStore(credential), now = now).fetch()
        assertEquals(now - 3_600, snapshot.fetchedAt)
    }

    @Test
    fun `a reading with no age header is treated as live`() = runTest {
        // Anything that is not a relay answers this way, and it is answering
        // in real time.
        val now = 1_787_000_000L
        val snapshot = api(jsonEngine(), FakeCredentialStore(credential), now = now).fetch()
        assertEquals(now, snapshot.fetchedAt)
    }

    @Test
    fun `an unreadable age header does not blank the reading`() = runTest {
        val now = 1_787_000_000L
        val engine = MockEngine {
            respond(
                BODY, HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "X-Headroom-Age" to listOf("not a number"),
                ),
            )
        }
        val snapshot = api(engine, FakeCredentialStore(credential), now = now).fetch()
        assertEquals(now, snapshot.fetchedAt, "a bad header must cost the age, not the reading")
    }

    @Test
    fun `a negative age is not a reading from the future`() = runTest {
        // Two clocks disagreeing. Dating the snapshot ahead of now would make
        // it look fresh indefinitely.
        val now = 1_787_000_000L
        val engine = MockEngine {
            respond(
                BODY, HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "X-Headroom-Age" to listOf("-500"),
                ),
            )
        }
        val snapshot = api(engine, FakeCredentialStore(credential), now = now).fetch()
        assertEquals(now, snapshot.fetchedAt)
    }

    @Test
    fun `an old reading from a relay is stale even though the fetch just happened`() = runTest {
        // The end of the chain that matters: UsageRepository derives staleness
        // from fetchedAt, so the header has to reach it for the UI to say so.
        val now = 1_787_000_000L
        val engine = MockEngine {
            respond(
                BODY, HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    "X-Headroom-Age" to listOf("7200"),
                ),
            )
        }
        val snapshot = api(engine, FakeCredentialStore(credential), now = now).fetch()
        val age: Long = now - snapshot.fetchedAt
        assertTrue(
            age > UsageRepository.STALE_AFTER_SECONDS,
            "a two-hour-old relay reading must read as stale",
        )
    }
}

