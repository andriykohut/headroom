package dev.andrii.headroom.credential

import dev.andrii.headroom.domain.Credential
import dev.andrii.headroom.store.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The credential the user scanned, held encrypted and handed back on request.
 *
 * Storage and nothing else. The refresh machinery this used to carry died with
 * the arrangement it served: it renewed a copy of Claude Code's OAuth
 * credential against the provider's token endpoint, and rotating that chain
 * from two clients at once is what made the phone ask to be re-linked daily.
 *
 * The stored shape still carries all six fields of the scanned payload, and
 * that is deliberate. Three of them - `refreshToken`, `clientId`,
 * `tokenEndpoint` - are unused here, but they are part of the wire contract
 * with the generator and with app versions already installed. Dropping them
 * would be a format change, not a cleanup.
 */
class ImportedCredentialStore(
    private val secureStore: SecureStore,
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

    private companion object {
        /** Frozen: renaming it would orphan the value in every install. */
        const val KEY = "credential"
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
