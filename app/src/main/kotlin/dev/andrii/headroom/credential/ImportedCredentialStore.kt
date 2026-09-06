package dev.andrii.headroom.credential

import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.store.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The credential the user scanned, refreshed against its own token endpoint.
 *
 * Provider fields (client id, endpoints) are never returned by a refresh, so
 * they are carried forward from the stored credential.
 */
class ImportedCredentialStore(
    private val secureStore: SecureStore,
    private val httpClient: HttpClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : CredentialStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun current(): Credential? = withContext(Dispatchers.IO) {
        val stored = secureStore.get(KEY) ?: return@withContext null
        runCatching { json.decodeFromString(CredentialJson.serializer(), stored) }
            .getOrNull()
            ?.toCredential()
    }

    override suspend fun save(credential: Credential) = withContext(Dispatchers.IO) {
        secureStore.put(KEY, json.encodeToString(CredentialJson.serializer(), credential.toJson()))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { secureStore.remove(KEY) }

    override suspend fun refresh(): Credential {
        val existing = current()
            ?: throw RefreshFailedException("No account is linked. Scan a code to link Headroom.")
        val response = try {
            httpClient.submitForm(
                url = existing.tokenEndpoint,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", existing.refreshToken)
                    append("client_id", existing.clientId)
                },
            )
        } catch (e: Exception) {
            throw RefreshFailedException("Could not reach the server to refresh (${e::class.simpleName}).")
        }
        if (!response.status.isSuccess()) {
            // Deliberately does not clear the credential: this may be a captive
            // portal, and discarding it would force a needless re-link.
            throw RefreshFailedException("The server rejected the refresh (HTTP ${response.status.value}).")
        }
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw RefreshFailedException("The refresh response could not be read.") }
        val accessToken = body["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw RefreshFailedException("The refresh response contained no access token.")
        val refreshed = existing.copy(
            accessToken = accessToken,
            refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: existing.refreshToken,
            expiresAt = body["expires_in"]?.jsonPrimitive?.longOrNull?.let { now() + it }
                ?: (now() + DEFAULT_LIFETIME),
        )
        save(refreshed)
        return refreshed
    }

    private companion object {
        const val KEY = "credential"
        const val DEFAULT_LIFETIME = 3_600L
    }
}

@kotlinx.serialization.Serializable
private data class CredentialJson(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val clientId: String,
    val tokenEndpoint: String,
    val usageEndpoint: String,
) {
    fun toCredential() = Credential(
        accessToken, refreshToken, expiresAt, clientId, tokenEndpoint, usageEndpoint,
    )
}

private fun Credential.toJson() = CredentialJson(
    accessToken, refreshToken, expiresAt, clientId, tokenEndpoint, usageEndpoint,
)
