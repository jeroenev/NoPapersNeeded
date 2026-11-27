package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.SystemProperty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Emits and parses the exact envelope described in [JournalEvent].
 *
 * Field order on write: v, seq, hlc, dev, doc, op, p. Storage readers select and validate the
 * event version before invoking this v1 serializer.
 */
internal object JournalEventSerializer : KSerializer<JournalEvent> {
    private val hlcSerializer = Hlc.serializer()

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.npn.core.journal.JournalEvent")

    override fun serialize(
        encoder: Encoder,
        value: JournalEvent,
    ) {
        val json =
            (encoder as? JsonEncoder)
                ?: error("JournalEventSerializer only supports JSON")
        val obj =
            buildJsonObject {
                put("v", JsonPrimitive(value.version))
                put("seq", JsonPrimitive(value.seq))
                put("hlc", json.json.encodeToJsonElement(hlcSerializer, value.hlc))
                put("dev", JsonPrimitive(value.dev))
                put("doc", value.doc?.let { JsonPrimitive(it) } ?: JsonNull)
                put("op", JsonPrimitive(value.op.wire))
                put("p", encodePayload(value.op))
            }
        json.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): JournalEvent {
        val json =
            (decoder as? JsonDecoder)
                ?: error("JournalEventSerializer only supports JSON")
        val obj = json.decodeJsonElement().jsonObject
        val version =
            obj["v"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: error("Missing event version")
        require(version == JournalEventVersions.V1) { "Unsupported event version: $version" }
        val seq =
            obj
                .required("seq")
                .jsonPrimitive.content
                .toLong()
        val hlc = json.json.decodeFromJsonElement(hlcSerializer, obj.required("hlc"))
        val dev = obj.required("dev").jsonPrimitive.content
        val doc = obj["doc"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val opWire = obj.required("op").jsonPrimitive.content
        val payload = obj["p"] as? JsonObject ?: JsonObject(emptyMap())
        val op = decodePayload(opWire, payload)
        return JournalEvent(version, seq, hlc, dev, doc, op)
    }

    private fun encodePayload(op: JournalOp): JsonObject =
        when (op) {
            is JournalOp.TagAdd -> {
                obj("tag" to JsonPrimitive(op.tag))
            }

            is JournalOp.TagRemove -> {
                encodeTagRemove(op)
            }

            is JournalOp.PropSet -> {
                keyValue(op.key.wire, op.value)
            }

            is JournalOp.FieldSet -> {
                keyValue(op.key, op.value)
            }

            is JournalOp.DocTrash -> {
                buildJsonObject {
                    put("origPath", JsonPrimitive(op.origPath))
                    op.stat?.let {
                        put(
                            "stat",
                            obj(
                                "size" to JsonPrimitive(it.size),
                                "mtime" to JsonPrimitive(it.mtimeMillis),
                            ),
                        )
                    }
                }
            }

            JournalOp.DocRestore -> {
                JsonObject(emptyMap())
            }

            JournalOp.DocDelete -> {
                JsonObject(emptyMap())
            }

            JournalOp.DocUndelete -> {
                JsonObject(emptyMap())
            }

            is JournalOp.Relink -> {
                obj("newDoc" to JsonPrimitive(op.newDoc))
            }

            is JournalOp.TagRename -> {
                fromTo(op.from, op.to)
            }

            is JournalOp.TagMerge -> {
                fromTo(op.from, op.to)
            }

            is JournalOp.TagStyle -> {
                encodeTagStyle(op)
            }

            is JournalOp.SettingSet -> {
                keyValue(op.key, op.value)
            }

            is JournalOp.FieldDefSet -> {
                obj(
                    "key" to JsonPrimitive(op.key),
                    "def" to (op.def?.let(::encodeFieldDef) ?: JsonNull),
                )
            }

            is JournalOp.Unknown -> {
                op.payload
            }
        }

    private fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*entries))

    private fun nullable(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun keyValue(
        key: String,
        value: String?,
    ): JsonObject = obj("key" to JsonPrimitive(key), "value" to nullable(value))

    private fun fromTo(
        from: String,
        to: String,
    ): JsonObject = obj("from" to JsonPrimitive(from), "to" to JsonPrimitive(to))

    private fun encodeTagRemove(op: JournalOp.TagRemove): JsonObject =
        buildJsonObject {
            put("tag", JsonPrimitive(op.tag))
            put(
                "dots",
                buildJsonArray {
                    op.dots.forEach { dot ->
                        add(
                            buildJsonArray {
                                add(JsonPrimitive(dot.deviceId))
                                add(JsonPrimitive(dot.seq))
                            },
                        )
                    }
                },
            )
        }

    private fun encodeTagStyle(op: JournalOp.TagStyle): JsonObject =
        obj("tag" to JsonPrimitive(op.tag), "color" to nullable(op.color), "icon" to nullable(op.icon))

    private fun encodeFieldDef(def: FieldDef): JsonObject =
        buildJsonObject {
            put("label", JsonPrimitive(def.label))
            put("type", JsonPrimitive(def.type.wire))
            put("showByDefault", JsonPrimitive(def.showByDefault))
            put("aiEnabled", JsonPrimitive(def.aiEnabled))
            put("position", JsonPrimitive(def.position))
            def.hint?.let { put("hint", JsonPrimitive(it)) }
            // Written only when set, so every other definition encodes as it did before the flag.
            if (def.perDocument) put("perDocument", JsonPrimitive(true))
        }

    private fun decodeFieldDef(
        key: String,
        elem: JsonElement,
    ): FieldDef? {
        val obj = elem as? JsonObject ?: return null
        val type = FieldType.fromWire(obj["type"]?.jsonPrimitive?.content) ?: return null
        val label = obj["label"]?.jsonPrimitive?.content ?: return null
        return FieldDef(
            key = key,
            label = label,
            type = type,
            showByDefault = obj["showByDefault"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            aiEnabled = obj["aiEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            position = obj["position"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            hint = obj["hint"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
            perDocument = obj["perDocument"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun decodePayload(
        opWire: String,
        p: JsonObject,
    ): JournalOp =
        when (opWire) {
            JournalOp.TagAdd.WIRE -> {
                JournalOp.TagAdd(p.string("tag"))
            }

            JournalOp.TagRemove.WIRE -> {
                JournalOp.TagRemove(tag = p.string("tag"), dots = decodeDots(p["dots"]))
            }

            JournalOp.PropSet.WIRE -> {
                decodePropSet(p) ?: JournalOp.Unknown(opWire, p)
            }

            JournalOp.FieldSet.WIRE -> {
                p.stringOrNull("key")?.let { JournalOp.FieldSet(it, p.nullableString("value")) }
                    ?: JournalOp.Unknown(opWire, p)
            }

            JournalOp.DocTrash.WIRE -> {
                JournalOp.DocTrash(p.string("origPath"), decodeFileStat(p["stat"]))
            }

            JournalOp.DocRestore.WIRE -> {
                JournalOp.DocRestore
            }

            JournalOp.DocDelete.WIRE -> {
                JournalOp.DocDelete
            }

            JournalOp.DocUndelete.WIRE -> {
                JournalOp.DocUndelete
            }

            JournalOp.Relink.WIRE -> {
                JournalOp.Relink(p.string("newDoc"))
            }

            JournalOp.TagRename.WIRE -> {
                JournalOp.TagRename(p.string("from"), p.string("to"))
            }

            JournalOp.TagMerge.WIRE -> {
                JournalOp.TagMerge(p.string("from"), p.string("to"))
            }

            JournalOp.TagStyle.WIRE -> {
                JournalOp.TagStyle(p.string("tag"), p.nullableString("color"), p.nullableString("icon"))
            }

            JournalOp.SettingSet.WIRE -> {
                JournalOp.SettingSet(p.string("key"), p.nullableString("value"))
            }

            JournalOp.FieldDefSet.WIRE -> {
                decodeFieldDefSet(p) ?: JournalOp.Unknown(opWire, p)
            }

            else -> {
                JournalOp.Unknown(opWire, p)
            }
        }

    /** A required string payload member; a malformed line fails fast and is skipped by the codec. */
    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.content

    /** A required member; a line without it fails fast and is skipped by the codec. */
    private fun JsonObject.required(name: String): JsonElement = requireNotNull(this[name]) { "Missing member: $name" }

    private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.content

    /** A member that may be absent or JSON null; both read as null. */
    private fun JsonObject.nullableString(name: String): String? =
        this[name]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    /**
     * `prop-set` once carried user metadata too. Libraries written before the field registry hold
     * `prop-set` events for the well-known five; they decode as the [JournalOp.FieldSet] they would
     * be written as today, so those libraries keep their metadata. The stored bytes are untouched.
     */
    private fun decodePropSet(p: JsonObject): JournalOp? {
        val wire = p.string("key")
        val value = p.nullableString("value")
        if (wire in FieldKeys.WELL_KNOWN) return JournalOp.FieldSet(wire, value)
        val key = SystemProperty.fromWire(wire) ?: return null
        return JournalOp.PropSet(key, value)
    }

    /** Null when the payload is malformed; the caller then keeps the op as [JournalOp.Unknown]. */
    private fun decodeFieldDefSet(p: JsonObject): JournalOp.FieldDefSet? {
        val key = p.stringOrNull("key")?.takeIf { it.isNotBlank() } ?: return null
        val defElem = p["def"]
        if (defElem == null || defElem is JsonNull) return JournalOp.FieldDefSet(key, null)
        val def = decodeFieldDef(key, defElem) ?: return null
        return JournalOp.FieldDefSet(key, def)
    }

    /**
     * A `doc-trash`'s `stat`: null when absent, or when it is not an object of two JSON integers, a
     * size that is not negative and an mtime. It only adds to what the event says, so one this build
     * cannot read leaves the trash as one that recorded none, rather than failing the line.
     */
    private fun decodeFileStat(elem: JsonElement?): FileStat? {
        val obj = elem as? JsonObject ?: return null

        fun long(name: String): Long? =
            (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
        val size = long("size")?.takeIf { it >= 0 } ?: return null
        val mtime = long("mtime") ?: return null
        return FileStat(size, mtime)
    }

    private fun decodeDots(elem: JsonElement?): List<Dot> {
        val arr = elem as? JsonArray ?: return emptyList()
        return arr.map { entry ->
            val pair = entry.jsonArray
            Dot(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.content.toLong())
        }
    }
}
