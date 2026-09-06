package dev.andrii.headroom.data

import dev.andrii.headroom.credential.CredentialStore
import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.domain.UsageParseException
import dev.andrii.headroom.domain.UsageParser
import dev.andrii.headroom.domain.UsageSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

class NotLinkedException : Exception("No account is linked. Scan a code to link Headroom.")

class UsageFetchException(message: String) : Exception(message)

/**
 * The server asked us to stop calling until [retryAtEpochSeconds].
 *
 * Distinct from [UsageFetchException] because it is not a failure to report and
 * forget: it has to change what the app does next.
 */
class RateLimitedException(val retryAtEpochSeconds: Long) :
    Exception("Rate limited until $retryAtEpochSeconds")

/**
 * Reads the usage endpoint (spec §2).
 *
 * The endpoint URL comes from the credential, not from a constant, because the
 * app ships no provider identifiers. Refresh-once-retry-once is implemented
 * explicitly rather than via Ktor's Auth plugin so the retry budget is
 * visible and testable.
 *
 * Every caller - the UI, the periodic worker, the reset alarm - comes through
 * `fetch`, which makes it the one place a rate limit can be honoured for all
 * of them.
 */
class UsageApi(
    private val credentialStore: CredentialStore,
    private val httpClient: HttpClient,
    private val gate: RateLimitGate,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun fetch(atWall: Boolean = false): UsageSnapshot {
        // Asked before anything else, including before reading the credential:
        // the point of a hold is to make no request at all.
        val heldUntil = gate.retryAt()
        if (now() < heldUntil) throw RateLimitedException(heldUntil)

        var credential = credentialStore.current() ?: throw NotLinkedException()
        var response = request(credential, atWall)

        if (response.status == HttpStatusCode.Unauthorized) {
            // Exactly one refresh and one retry (spec §7). A failed refresh
            // propagates as RefreshFailedException so the caller can raise the
            // re-link notification.
            credential = credentialStore.refresh()
            response = request(credential, atWall)
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            val until = now() + retryAfterSeconds(response)
            gate.hold(until)
            throw RateLimitedException(until)
        }

        if (!response.status.isSuccess()) {
            throw UsageFetchException("Couldn't read usage (HTTP ${response.status.value}).")
        }
        val snapshot = try {
            UsageParser.parse(response.bodyAsText(), fetchedAt = now())
        } catch (e: UsageParseException) {
            throw UsageFetchException("Usage response wasn't in the expected format: ${e.message}")
        }
        // Only a success lifts a hold. An error of any other kind says nothing
        // about whether the limit has expired.
        if (heldUntil != 0L) gate.clear()
        return snapshot
    }

    /**
     * How long to wait, from the `Retry-After` header.
     *
     * Only the delta-seconds form is read. The HTTP-date form is legal but was
     * never observed here, and guessing at a date format to derive a wait is a
     * worse failure than falling back to a fixed one.
     */
    private fun retryAfterSeconds(response: HttpResponse): Long {
        val header = response.headers[HttpHeaders.RetryAfter]?.trim()
        val parsed = header?.toLongOrNull()
        return when {
            parsed == null -> DEFAULT_BACKOFF
            parsed <= 0 -> DEFAULT_BACKOFF
            else -> parsed.coerceAtMost(MAX_BACKOFF)
        }
    }

    private suspend fun request(credential: Credential, atWall: Boolean): HttpResponse {
        val url = if (atWall) {
            val separator = if (credential.usageEndpoint.contains('?')) "&" else "?"
            "${credential.usageEndpoint}${separator}at_wall=1&skip_spend=1"
        } else {
            credential.usageEndpoint
        }
        return try {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer ${credential.accessToken}")
                header(HttpHeaders.ContentType, "application/json")
            }
        } catch (e: Exception) {
            throw UsageFetchException("Couldn't reach the server (${e::class.simpleName}).")
        }
    }

    private companion object {
        /**
         * Six hours, not the half hour this used to be.
         *
         * The penalty for hitting this endpoint too hard has been reported at
         * around twenty-four hours, and it does not only affect this app - it
         * blocks usage in Claude Code and on the web too. Retrying hourly into
         * a day-long ban buys nothing and risks extending it, so when the
         * server does not say how long to wait, waiting properly is cheaper
         * than guessing short.
         */
        const val DEFAULT_BACKOFF = 6 * 3_600L
        /** A server asking for longer than a day is treated as asking for a day. */
        const val MAX_BACKOFF = 24 * 3_600L
    }
}
