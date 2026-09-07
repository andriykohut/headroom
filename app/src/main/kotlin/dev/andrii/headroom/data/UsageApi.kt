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

class NotLinkedException :
    Exception("This phone isn't linked to a relay yet. Scan a code to link it.")

class UsageFetchException(message: String) : Exception(message)

/**
 * The relay would not accept this phone's key.
 *
 * Kept separate from [UsageFetchException] because it is not transient and the
 * user has to act: scan a new code. It is the only credential-shaped failure
 * that survives talking to a relay - a relay issues nothing, expires nothing
 * and refreshes nothing, so a rejection means the shared secret is simply
 * wrong, or has been rotated on the relay since this phone was linked.
 */
class RelayRejectedException(message: String) : Exception(message)

/**
 * Reads the usage endpoint carried in the scanned code (spec §2).
 *
 * The URL comes from the credential rather than from a constant, because the
 * app ships no provider identifiers.
 *
 * There is no token refresh here and no rate-limit backoff, and their absence
 * is the design rather than an omission: this talks to a relay, which holds no
 * credential, mints no tokens and imposes no quota. Everything that used to
 * make this class complicated belonged to the arrangement where the phone
 * called the provider directly with a copy of Claude Code's credential.
 */
class UsageApi(
    private val credentialStore: CredentialStore,
    private val httpClient: HttpClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun fetch(): UsageSnapshot {
        val credential = credentialStore.current() ?: throw NotLinkedException()
        val response = request(credential)

        if (response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden
        ) {
            throw RelayRejectedException(
                "Your relay would not accept this phone's key. " +
                    "Scan a new code to link it again.",
            )
        }
        if (!response.status.isSuccess()) {
            throw UsageFetchException("Couldn't read usage (HTTP ${response.status.value}).")
        }
        return try {
            UsageParser.parse(response.bodyAsText(), fetchedAt = readingTakenAt(response))
        } catch (e: UsageParseException) {
            throw UsageFetchException("Usage response wasn't in the expected format: ${e.message}")
        }
    }

    /**
     * When the reading was actually taken - not when we asked for it.
     *
     * A relay serves a reading that was pushed to it earlier and reports how
     * old it is in `X-Headroom-Age`. Dating the snapshot from the moment of
     * the request would make an hour-old reading fetched a second ago render
     * as "just now", and never mark it stale. That is precisely the failure
     * this app exists not to have: showing a number without showing that it is
     * old.
     *
     * Absent or unreadable, the response is assumed to be live.
     */
    private fun readingTakenAt(response: HttpResponse): Long {
        val age = response.headers[HEADROOM_AGE]?.trim()?.toLongOrNull() ?: return now()
        // A negative age means the two clocks disagree, not that the reading
        // comes from the future. Treat it as live rather than dating it ahead,
        // which would make the reading look fresh forever.
        return now() - age.coerceAtLeast(0)
    }

    private suspend fun request(credential: Credential): HttpResponse = try {
        httpClient.get(credential.usageEndpoint) {
            header(HttpHeaders.Authorization, "Bearer ${credential.accessToken}")
            header(HttpHeaders.ContentType, "application/json")
        }
    } catch (e: Exception) {
        throw UsageFetchException("Couldn't reach your relay (${e::class.simpleName}).")
    }

    private companion object {
        /** How old the served reading is, in seconds. Sent by a relay. */
        const val HEADROOM_AGE = "X-Headroom-Age"
    }
}
