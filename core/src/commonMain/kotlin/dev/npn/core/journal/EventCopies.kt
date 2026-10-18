package dev.npn.core.journal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Two canonical forms of one event identity (device, seq). A build drops the payload keys it does not
 * know from every event it encodes again, so its copy of an event can lack keys the original holds.
 * That copy is still the same event, not a collision.
 *
 * Only compaction encoded an event again, and this build never compacts, but the snapshots earlier
 * builds wrote stay in libraries and keep arriving: one from a build that did not know a key (the
 * field definitions' `perDocument`) beside the object it superseded, or beside a richer snapshot of
 * the same event from another device. The index keeps the richer copy, whichever it read first.
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
