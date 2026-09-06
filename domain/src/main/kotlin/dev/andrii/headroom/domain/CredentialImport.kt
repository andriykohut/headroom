package dev.andrii.headroom.domain

import java.util.Base64
import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class PayloadException(message: String) : Exception(message)

/**
 * Decodes the QR payload produced by `tools/headroom-link`.
 *
 * Wire format: "headroom1:" + base64url(zlib(json)), unpadded. The prefix lets
 * a foreign QR code be rejected before decoding; zlib's adler32 catches
 * corruption from a bad scan.
 */
object CredentialImport {

    const val SCHEME = "headroom1:"

    private val json = Json { ignoreUnknownKeys = true }

    private val REQUIRED = listOf(
        "access_token", "refresh_token", "expires_at",
        "client_id", "token_endpoint", "usage_endpoint",
    )

    fun looksLikePayload(text: String): Boolean = text.trim().startsWith(SCHEME)

    fun decode(payload: String): Credential {
        val trimmed = payload.trim()
        if (!looksLikePayload(trimmed)) {
            throw PayloadException("That isn't a Headroom code. Scan the code from headroom-link.")
        }
        val packed = trimmed.removePrefix(SCHEME)
        val body = try {
            inflate(Base64.getUrlDecoder().decode(padded(packed)))
        } catch (e: Exception) {
            throw PayloadException("That code is damaged — try scanning again (${e::class.simpleName}).")
        }
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw PayloadException("That code's contents could not be read (${e::class.simpleName}).")
        }
        val missing = REQUIRED.filter { obj[it] == null }
        if (missing.isNotEmpty()) {
            throw PayloadException("That code is missing: ${missing.joinToString(", ")}.")
        }
        fun str(key: String) = obj[key]!!.jsonPrimitive.contentOrNull
            ?: throw PayloadException("That code's $key is not text.")
        return Credential(
            accessToken = str("access_token"),
            refreshToken = str("refresh_token"),
            expiresAt = obj["expires_at"]!!.jsonPrimitive.longOrNull
                ?: throw PayloadException("That code's expires_at is not a number."),
            clientId = str("client_id"),
            tokenEndpoint = str("token_endpoint"),
            usageEndpoint = str("usage_endpoint"),
        )
    }

    private fun padded(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val buffer = ByteArray(4096)
        val out = StringBuilder()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.append(String(buffer, 0, n))
            }
        } finally {
            inflater.end()
        }
        return out.toString()
    }
}
