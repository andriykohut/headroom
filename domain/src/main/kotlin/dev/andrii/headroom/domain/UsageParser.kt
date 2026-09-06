package dev.andrii.headroom.domain

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class UsageParseException(message: String) : Exception(message)

/**
 * Sole owner of the usage endpoint's wire format (spec §2).
 *
 * Written defensively on purpose: the endpoint is undocumented, so field
 * aliases are tolerated and unrecognised buckets are surfaced rather than
 * dropped. That tolerance is not theoretical - the first version of this
 * parser was written against a shape the server does not send.
 *
 * java.time is a JVM type, not an Android one, so :domain stays framework-free
 * (spec §4). It is available from API 26 and minSdk is 31.
 */
object UsageParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `percent` is what the server sends; the rest are defensive. */
    private val UTILIZATION_KEYS = listOf("percent", "utilization", "used_percentage")

    fun parse(body: String, fetchedAt: Long): UsageSnapshot {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw UsageParseException("usage response is not valid JSON: ${e::class.simpleName}")
        }
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root.limitsArray()
            else -> throw UsageParseException("usage response was neither array nor object")
        }
        return UsageSnapshot(entries.mapNotNull { toBucket(it) }, fetchedAt)
    }

    /**
     * The real response is an object of ~20 keys, of which `limits` holds the
     * buckets. Taking "the first array" would be a coin toss, so the known key
     * wins; the fallback only exists for a server that renames it.
     */
    private fun JsonObject.limitsArray(): JsonArray =
        this["limits"] as? JsonArray
            ?: values.firstOrNull { it is JsonArray } as? JsonArray
            ?: throw UsageParseException("usage response object contained no array of limits")

    private fun toBucket(element: JsonElement): LimitBucket? {
        val obj = element as? JsonObject ?: return null
        val rawKind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = BucketKind.fromWire(rawKind)
        val utilization = UTILIZATION_KEYS
            .firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.doubleOrNull } ?: 0.0
        val scopeLabel = scopeLabel(obj)
        return LimitBucket(
            kind = kind,
            rawKind = rawKind,
            title = titleFor(kind, rawKind, scopeLabel),
            utilization = utilization,
            resetsAt = resetsAt(obj["resets_at"]),
            group = obj["group"]?.jsonPrimitive?.contentOrNull ?: "",
            severity = obj["severity"]?.jsonPrimitive?.contentOrNull ?: "",
            isActive = obj["is_active"]?.jsonPrimitive?.booleanOrNull ?: false,
            scopeLabel = scopeLabel,
        )
    }

    /**
     * The server sends an ISO-8601 timestamp. Epoch seconds are accepted too,
     * because the spec claimed them for months and a server that ever sends
     * them should not produce a bucket that resets in 1970.
     *
     * An unreadable value yields 0 rather than throwing: one malformed reset
     * time should cost that bucket its countdown, not cost the user the whole
     * screen.
     */
    private fun resetsAt(element: JsonElement?): Long {
        val primitive = element?.jsonPrimitive ?: return 0L
        primitive.longOrNull?.let { return it }
        val text = primitive.contentOrNull ?: return 0L
        return try {
            OffsetDateTime.parse(text).toEpochSecond()
        } catch (_: DateTimeParseException) {
            0L
        }
    }

    /**
     * The model a scoped bucket applies to, or blank.
     *
     * Every step is a safe cast rather than `.jsonObject`, which throws on a
     * JSON null. The server sends `"scope": null` on unscoped buckets, so the
     * throwing version fails on two thirds of a real response.
     */
    private fun scopeLabel(obj: JsonObject): String =
        (obj["scope"] as? JsonObject)
            ?.let { it["model"] as? JsonObject }
            ?.get("display_name")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    /** `weekly_scoped` entries name themselves from the model (spec §2). */
    private fun titleFor(kind: BucketKind, rawKind: String, scopeLabel: String): String = when {
        scopeLabel.isNotBlank() -> "Current week ($scopeLabel)"
        kind != BucketKind.UNKNOWN -> kind.title
        else -> rawKind
    }
}
