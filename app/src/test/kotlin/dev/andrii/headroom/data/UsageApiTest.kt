package dev.andrii.headroom.data

import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.credential.RefreshFailedException
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

    private fun api(
        engine: MockEngine,
        store: CredentialStore,
        gate: InMemoryRateLimitGate = InMemoryRateLimitGate(),
        now: Long = 1_787_000_000,
    ) = UsageApi(store, HttpClient(engine), gate, now = { now })

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

    // --- rate limiting ---

    @Test
    fun `a 429 raises RateLimitedException rather than a generic failure`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        assertFailsWith<RateLimitedException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
    }

    @Test
    fun `a 429 honours Retry-After`() = runTest {
        val engine = MockEngine {
            respond(
                "", HttpStatusCode.TooManyRequests,
                headersOf(KtorHeaders.RetryAfter, "120"),
            )
        }
        val e = assertFailsWith<RateLimitedException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertEquals(1_787_000_000 + 120, e.retryAtEpochSeconds)
    }

    @Test
    fun `a 429 without Retry-After backs off past the next poll`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val e = assertFailsWith<RateLimitedException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        // The observed penalty for over-polling this endpoint is around a day,
        // and it blocks Claude Code and the web UI too - so a hold measured in
        // minutes would spend the whole ban retrying into it.
        assertTrue(e.retryAtEpochSeconds - 1_787_000_000 >= 6 * 3_600)
    }

    @Test
    fun `a held gate makes no request at all`() = runTest {
        // The whole point of a hold: not a polite request that gets refused,
        // no request.
        var calls = 0
        val engine = MockEngine { calls++; respondError(HttpStatusCode.ServiceUnavailable) }
        val gate = InMemoryRateLimitGate(retryAt = 1_787_000_500)
        assertFailsWith<RateLimitedException> {
            api(engine, FakeCredentialStore(credential), gate).fetch()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `the hold survives being read back by a later process`() = runTest {
        // The poll worker runs in a fresh process; an in-memory gate would
        // forget exactly when it mattered.
        val gate = InMemoryRateLimitGate()
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        runCatching { api(engine, FakeCredentialStore(credential), gate).fetch() }

        var calls = 0
        val later = MockEngine { calls++; respond(BODY, HttpStatusCode.OK) }
        assertFailsWith<RateLimitedException> {
            api(later, FakeCredentialStore(credential), gate).fetch()
        }
        assertEquals(0, calls)
    }

    @Test
    fun `the hold expires`() = runTest {
        val gate = InMemoryRateLimitGate(retryAt = 1_787_000_100)
        val snapshot = api(
            jsonEngine(), FakeCredentialStore(credential), gate, now = 1_787_000_200,
        ).fetch()
        assertNotNull(snapshot.bucket(BucketKind.SESSION))
    }

    @Test
    fun `a success clears the hold`() = runTest {
        val gate = InMemoryRateLimitGate(retryAt = 1_787_000_100)
        api(jsonEngine(), FakeCredentialStore(credential), gate, now = 1_787_000_200).fetch()
        assertEquals(0L, gate.retryAt())
    }

    @Test
    fun `a non-429 failure does not clear an existing hold`() = runTest {
        // A 503 says nothing about whether the rate limit has expired.
        val gate = InMemoryRateLimitGate()
        gate.hold(1_787_000_100)
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        runCatching {
            api(engine, FakeCredentialStore(credential), gate, now = 1_787_000_200).fetch()
        }
        assertEquals(1_787_000_100, gate.retryAt())
    }

    @Test
    fun `error messages never contain the token`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<UsageFetchException> {
            api(engine, FakeCredentialStore(credential)).fetch()
        }
        assertFalse(e.message!!.contains("tok-abc123"))
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

/** Shared test double; the real one is DataStore-backed. */
class InMemoryRateLimitGate(private var retryAt: Long = 0L) : RateLimitGate {
    override suspend fun retryAt(): Long = retryAt
    override suspend fun hold(untilEpochSeconds: Long) { retryAt = untilEpochSeconds }
    override suspend fun clear() { retryAt = 0L }
}
