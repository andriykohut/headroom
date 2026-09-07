package dev.andrii.headroom.credential

import dev.andrii.headroom.domain.Credential

/**
 * Owns what the phone was linked with: a relay address and its shared secret.
 *
 * There is no `refresh`. A relay secret does not expire, and nothing in this
 * app can mint one - which is the entire reason the phone no longer needs
 * re-linking every day. When a relay rejects the key, the answer is to scan a
 * new code, not to renew anything.
 */
interface CredentialStore {
    suspend fun current(): Credential?
    suspend fun save(credential: Credential)
    suspend fun clear()
}
