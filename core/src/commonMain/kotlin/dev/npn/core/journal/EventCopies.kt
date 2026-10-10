package dev.npn.core.journal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Two canonical forms of one event identity (device, seq). A build drops the payload keys it does not
 * know from every event it encodes again, its compaction included, so its copy of an event can lack
 * keys the original holds. That copy is still the same event, not a collision.
 */
internal object EventCopies {
    /**
     * Of [a] and [b], two canonical forms of one event identity, the one that holds the other: every
     * key of the other's objects, recursively, with an equal value, and arrays equal. Null when neither
     * does, or either is no JSON: they are different events under one identity.
     */
    fun richer(
        a: String,
        b: String,
    ): String? {
        val first = parse(a) ?: return null
        val second = parse(b) ?: return null
        return when {
            first.holds(second) -> a
            second.holds(first) -> b
            else -> null
        }
    }

    private fun parse(canonical: String): JsonElement? =
        try {
            JournalCodec.json.parseToJsonElement(canonical)
        } catch (_: SerializationException) {
            null
        }

    /** True when this is [other], or an object holding each of [other]'s keys with a value that holds its. */
    private fun JsonElement.holds(other: JsonElement): Boolean =
        if (this is JsonObject && other is JsonObject) {
            other.all { (key, value) -> this[key]?.holds(value) == true }
        } else {
            this == other
        }
}
