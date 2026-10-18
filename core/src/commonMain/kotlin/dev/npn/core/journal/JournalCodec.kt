package dev.npn.core.journal

import dev.npn.core.fs.SHA256_HEX
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Writes the JSON-lines journal format, one [JournalEvent] per line, and holds the pinned [json]
 * every reader decodes it with ([EventDecoderRegistry]).
 */
object JournalCodec {
    /**
     * Pinned here rather than taken from [dev.npn.core.util.NpnJson]: this is the synced wire
     * format, and every replica must encode and decode an event exactly alike.
     */
    @Suppress("ForbiddenMethodCall") // The journal's own, as the comment above says.
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(event: JournalEvent): String = json.encodeToString(JournalEventSerializer, event)

    fun encodeLines(events: List<JournalEvent>): String = events.joinToString("\n") { encode(it) }
}

class UnsupportedEventVersionException(
    val version: Int,
) : IllegalArgumentException("Unsupported journal event version: $version")

data class CanonicalJournalEvent(
    val event: JournalEvent,
    val canonicalJson: String,
)

/** Explicit event decoder registry. Add migrations here before adding a new writer version. */
object EventDecoderRegistry {
    val supportedVersions: Set<Int> = setOf(JournalEventVersions.V1)

    fun decode(
        line: String,
        allowMissingV1: Boolean,
    ): CanonicalJournalEvent {
        rejectDuplicateObjectKeys(line)
        val raw = JournalCodec.json.parseToJsonElement(line).jsonObject
        validateEnvelopeTypes(raw)
        val versionElement = raw["v"]
        val version = eventVersion(versionElement, allowMissingV1)
        val versioned =
            if (versionElement == null) {
                JsonObject(linkedMapOf<String, JsonElement>("v" to JsonPrimitive(version)).apply { putAll(raw) })
            } else {
                raw
            }
        val event = JournalCodec.json.decodeFromJsonElement(JournalEventSerializer, versioned)
        validateEvent(event)
        val normalized = JournalCodec.json.encodeToJsonElement(JournalEventSerializer, event)
        return CanonicalJournalEvent(event, canonicalJson(normalized))
    }

    /**
     * The canonical form of an event this device built, without the string round trip [decode]
     * makes: the event is encoded straight to its canonical JSON. It is checked like a decoded one,
     * so a malformed local event still fails before it is recorded.
     */
    fun canonical(event: JournalEvent): CanonicalJournalEvent {
        requireSupportedVersion(event.version)
        validateEvent(event)
        val normalized = JournalCodec.json.encodeToJsonElement(JournalEventSerializer, event)
        return CanonicalJournalEvent(event, canonicalJson(normalized))
    }

    /**
     * An event read back from the index's event table. [decode] or [canonical] produced its
     * canonical JSON when it was recorded, after every check they make, so it is parsed straight
     * into the event: no duplicate-key scan, validation, normalization or re-encoding.
     */
    fun decodeStored(canonicalJson: String): JournalEvent =
        JournalCodec.json.decodeFromString(JournalEventSerializer, canonicalJson)

    private fun validateEnvelopeTypes(raw: JsonObject) {
        fun integer(
            name: String,
            value: JsonElement?,
        ) {
            val primitive = value as? JsonPrimitive
            require(primitive != null && !primitive.isString && primitive.content.toLongOrNull() != null) {
                "$name must be a JSON integer"
            }
        }

        fun string(
            name: String,
            value: JsonElement?,
        ) {
            val primitive = value as? JsonPrimitive
            require(primitive != null && primitive.isString) { "$name must be a JSON string" }
        }
        raw["v"]?.let { integer("v", it) }
        integer("seq", raw["seq"])
        val hlc = raw["hlc"] as? JsonObject ?: error("hlc must be an object")
        integer("hlc.t", hlc["t"])
        integer("hlc.c", hlc["c"])
        string("hlc.d", hlc["d"])
        string("dev", raw["dev"])
        string("op", raw["op"])
        require(raw["doc"] == null || raw["doc"] is JsonNull || (raw["doc"] as? JsonPrimitive)?.isString == true) {
            "doc must be null or a JSON string"
        }
        require(raw["p"] == null || raw["p"] is JsonObject) { "p must be an object" }
    }

    private fun validateEvent(event: JournalEvent) {
        require(event.seq > 0L) { "Event sequence must be positive" }
        require(event.dev == event.hlc.deviceId) { "Event dev must equal hlc.d" }
        require(event.hlc.physicalMillis >= 0L && event.hlc.counter >= 0) { "HLC values must be non-negative" }
        val documentOperation: Boolean? =
            when (event.op) {
                is JournalOp.TagAdd, is JournalOp.TagRemove, is JournalOp.PropSet, is JournalOp.FieldSet,
                is JournalOp.DocTrash, JournalOp.DocRestore, JournalOp.DocDelete, JournalOp.DocUndelete,
                is JournalOp.Relink,
                -> true

                is JournalOp.Unknown -> null

                is JournalOp.FieldDefSet, is JournalOp.TagRename, is JournalOp.TagMerge, is JournalOp.TagStyle,
                is JournalOp.SettingSet,
                -> false
            }
        if (documentOperation != null) {
            require((event.doc != null) == documentOperation) { "Operation has invalid document scope" }
        }
        event.doc?.let { require(SHA256_HEX.matches(it)) { "Invalid document id" } }
        (event.op as? JournalOp.Relink)?.let { require(SHA256_HEX.matches(it.newDoc)) { "Invalid relink target" } }
        (event.op as? JournalOp.TagRemove)?.dots?.forEach { require(it.seq > 0L) { "Invalid removed dot" } }
    }

    private fun eventVersion(
        versionElement: JsonElement?,
        allowMissingV1: Boolean,
    ): Int {
        val version =
            when {
                versionElement != null -> {
                    versionElement.jsonPrimitive.content.toIntOrNull()
                        ?: throw IllegalArgumentException("Event version must be an integer")
                }

                allowMissingV1 -> {
                    JournalEventVersions.V1
                }

                else -> {
                    throw IllegalArgumentException("Event version is required")
                }
            }
        return requireSupportedVersion(version)
    }

    private fun requireSupportedVersion(version: Int): Int {
        if (version !in supportedVersions) throw UnsupportedEventVersionException(version)
        return version
    }

    private fun canonicalJson(element: JsonElement): String =
        when (element) {
            is JsonObject -> {
                element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
                    val encodedKey = JournalCodec.json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key))
                    "$encodedKey:${canonicalJson(value)}"
                }
            }

            is JsonArray -> {
                element.joinToString(",", "[", "]") { canonicalJson(it) }
            }

            is JsonPrimitive -> {
                element.toString()
            }
        }

    private fun rejectDuplicateObjectKeys(input: String) {
        data class ObjectContext(
            val keys: MutableSet<String> = HashSet(),
            var expectingKey: Boolean = true,
        )
        val stack = ArrayList<Any>()
        var index = 0
        while (index < input.length) {
            when (input[index]) {
                '{' -> {
                    stack += ObjectContext()
                }

                '[' -> {
                    stack += Unit
                }

                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }

                ',' -> {
                    (stack.lastOrNull() as? ObjectContext)?.expectingKey = true
                }

                ':' -> {
                    (stack.lastOrNull() as? ObjectContext)?.expectingKey = false
                }

                '"' -> {
                    val start = index
                    index++
                    var escaped = false
                    while (index < input.length) {
                        val char = input[index]
                        if (!escaped && char == '"') break
                        escaped = !escaped && char == '\\'
                        if (char != '\\') escaped = false
                        index++
                    }
                    require(index < input.length) { "Unterminated JSON string" }
                    val context = stack.lastOrNull() as? ObjectContext
                    if (context?.expectingKey == true) {
                        val token = input.substring(start, index + 1)
                        val key =
                            JournalCodec.json
                                .parseToJsonElement(token)
                                .jsonPrimitive.content
                        require(context.keys.add(key)) { "Duplicate JSON object key: $key" }
                    }
                }
            }
            index++
        }
    }
}
