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
 * Reads the usage endpoint (spec §2).
 *
 * The endpoint URL comes from the credential, not from a constant, because the
 * app ships no provider identifiers. Refresh-once-retry-once is implemented
 * explicitly rather than via Ktor's Auth plugin so the retry budget is
 * visible and testable.
 */
class UsageApi(
    private val credentialStore: CredentialStore,
    private val httpClient: HttpClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun fetch(atWall: Boolean = false): UsageSnapshot {
        var credential = credentialStore.current() ?: throw NotLinkedException()
        var response = request(credential, atWall)

        if (response.status == HttpStatusCode.Unauthorized) {
            // Exactly one refresh and one retry (spec §7). A failed refresh
            // propagates as RefreshFailedException so the caller can raise the
            // re-link notification.
            credential = credentialStore.refresh()
            response = request(credential, atWall)
        }

        if (!response.status.isSuccess()) {
            throw UsageFetchException("Couldn't read usage (HTTP ${response.status.value}).")
        }
        return try {
            UsageParser.parse(response.bodyAsText(), fetchedAt = now())
        } catch (e: UsageParseException) {
            throw UsageFetchException("Usage response wasn't in the expected format: ${e.message}")
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
}
