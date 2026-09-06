package dev.andrii.headroom.domain

/**
 * Everything needed to read usage and to refresh.
 *
 * The endpoints and [clientId] travel with the tokens because the app ships no
 * provider identifiers of its own (spec §3) — which also means a server-side
 * endpoint move is fixed by re-linking rather than by a release.
 */
data class Credential(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val clientId: String,
    val tokenEndpoint: String,
    val usageEndpoint: String,
) {
    fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAt

    /** Never let a credential reach a log or a crash report through toString(). */
    override fun toString(): String =
        "Credential(expiresAt=$expiresAt, clientId=<redacted>, tokens=<redacted>)"
}
