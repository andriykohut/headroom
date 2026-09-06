package dev.andrii.headroom.credential

import dev.andrii.headroom.domain.Credential

class RefreshFailedException(message: String) : Exception(message)

/**
 * Owns the linked credential and keeps it valid.
 *
 * An interface because spec §3 keeps the door open for a second
 * implementation (a registered OAuth client) without touching any consumer.
 */
interface CredentialStore {
    suspend fun current(): Credential?
    suspend fun save(credential: Credential)
    suspend fun refresh(): Credential
    suspend fun clear()
}
