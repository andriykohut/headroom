package dev.andrii.headroom.store

/**
 * Encrypted key-value storage for credential material.
 *
 * Deliberately tiny: the only consumer is the credential store, and a narrow
 * surface keeps the Keystore implementation reviewable.
 */
interface SecureStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}
