package dev.npn.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether [written], a record another build may have written, says something this build would drop
 * without a word: a key that [read] (what this build decoded from it, encoded back with every
 * default) lacks, holding more than an empty default, at any depth.
 *
 * [NpnJson.Persisted] ignores unknown keys, which is right for state only this device reads back,
 * but a synced record that this build rewrites (a renamed saved view, an edited rule list) would
 * then lose what a newer build put there, on every device. A caller treats such a record as one it
 * cannot read. A key holding null, false, "" or an empty list or object is an unused default and
 * does not count, so a newer build's new option that nobody set changes nothing here. [retired]
 * names the keys older builds wrote that this one dropped on purpose; they never count.
 */
fun carriesUnknownData(
    written: JsonElement,
    read: JsonElement,
    retired: Set<String> = emptySet(),
): Boolean =
    when {
        written is JsonObject && read is JsonObject -> {
            written.any { (key, value) ->
                when (val known = read[key]) {
                    null -> key !in retired && !value.isEmptyDefault()
                    else -> carriesUnknownData(value, known, retired)
                }
            }
        }

        written is JsonArray && read is JsonArray && written.size == read.size -> {
            written.indices.any { carriesUnknownData(written[it], read[it], retired) }
        }

        else -> {
            false
        }
    }

private fun JsonElement.isEmptyDefault(): Boolean =
    when (this) {
        is JsonNull -> true
        is JsonObject -> isEmpty()
        is JsonArray -> isEmpty()
        is JsonPrimitive -> (isString && content.isEmpty()) || (!isString && booleanOrNull == false)
    }
