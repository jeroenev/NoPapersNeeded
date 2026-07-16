package dev.npn.core.journal

import dev.npn.core.model.DocProperty
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
 * Field order on write: v, seq, hlc, dev, doc, op, p. Unknown op strings decode into
 * [JournalOp.Unknown] with the payload discarded, so a reader from an older build never
 * crashes on a newer event type (forward compatibility).
 */
object JournalEventSerializer : KSerializer<JournalEvent> {
    private val hlcSerializer = Hlc.serializer()

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("dev.npn.core.journal.JournalEvent")

    override fun serialize(encoder: Encoder, value: JournalEvent) {
        val json = (encoder as? JsonEncoder)
            ?: error("JournalEventSerializer only supports JSON")
        val obj = buildJsonObject {
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
        val json = (decoder as? JsonDecoder)
            ?: error("JournalEventSerializer only supports JSON")
        val obj = json.decodeJsonElement().jsonObject
        val version = obj["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: JournalEvent.CURRENT_VERSION
        val seq = obj["seq"]!!.jsonPrimitive.content.toLong()
        val hlc = json.json.decodeFromJsonElement(hlcSerializer, obj["hlc"]!!)
        val dev = obj["dev"]!!.jsonPrimitive.content
        val doc = obj["doc"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val opWire = obj["op"]!!.jsonPrimitive.content
        val payload = obj["p"] as? JsonObject ?: JsonObject(emptyMap())
        val op = decodePayload(opWire, payload)
        return JournalEvent(version, seq, hlc, dev, doc, op)
    }

    private fun encodePayload(op: JournalOp): JsonObject = when (op) {
        is JournalOp.TagAdd -> buildJsonObject { put("tag", JsonPrimitive(op.tag)) }
        is JournalOp.TagRemove -> buildJsonObject {
            put("tag", JsonPrimitive(op.tag))
            put("dots", buildJsonArray {
                op.dots.forEach { dot ->
                    add(buildJsonArray {
                        add(JsonPrimitive(dot.deviceId))
                        add(JsonPrimitive(dot.seq))
                    })
                }
            })
        }
        is JournalOp.PropSet -> buildJsonObject {
            put("key", JsonPrimitive(op.key.wire))
            put("value", op.value?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is JournalOp.DocTrash -> buildJsonObject { put("origPath", JsonPrimitive(op.origPath)) }
        JournalOp.DocRestore -> JsonObject(emptyMap())
        JournalOp.DocDelete -> JsonObject(emptyMap())
        is JournalOp.Relink -> buildJsonObject { put("newDoc", JsonPrimitive(op.newDoc)) }
        is JournalOp.TagRename -> buildJsonObject {
            put("from", JsonPrimitive(op.from))
            put("to", JsonPrimitive(op.to))
        }
        is JournalOp.TagMerge -> buildJsonObject {
            put("from", JsonPrimitive(op.from))
            put("to", JsonPrimitive(op.to))
        }
        is JournalOp.TagStyle -> buildJsonObject {
            put("tag", JsonPrimitive(op.tag))
            put("color", op.color?.let { JsonPrimitive(it) } ?: JsonNull)
            put("icon", op.icon?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is JournalOp.SettingSet -> buildJsonObject {
            put("key", JsonPrimitive(op.key))
            put("value", op.value?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is JournalOp.Unknown -> JsonObject(emptyMap())
    }

    private fun decodePayload(opWire: String, p: JsonObject): JournalOp = when (opWire) {
        "tag-add" -> JournalOp.TagAdd(p["tag"]!!.jsonPrimitive.content)
        "tag-remove" -> JournalOp.TagRemove(
            tag = p["tag"]!!.jsonPrimitive.content,
            dots = decodeDots(p["dots"]),
        )
        "prop-set" -> {
            val key = DocProperty.fromWire(p["key"]!!.jsonPrimitive.content)
                ?: return JournalOp.Unknown(opWire)
            val valueElem = p["value"]
            val value = if (valueElem == null || valueElem is JsonNull) null else valueElem.jsonPrimitive.content
            JournalOp.PropSet(key, value)
        }
        "doc-trash" -> JournalOp.DocTrash(p["origPath"]!!.jsonPrimitive.content)
        "doc-restore" -> JournalOp.DocRestore
        "doc-delete" -> JournalOp.DocDelete
        "relink" -> JournalOp.Relink(p["newDoc"]!!.jsonPrimitive.content)
        "tag-rename" -> JournalOp.TagRename(p["from"]!!.jsonPrimitive.content, p["to"]!!.jsonPrimitive.content)
        "tag-merge" -> JournalOp.TagMerge(p["from"]!!.jsonPrimitive.content, p["to"]!!.jsonPrimitive.content)
        "tag-style" -> JournalOp.TagStyle(
            tag = p["tag"]!!.jsonPrimitive.content,
            color = p["color"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
            icon = p["icon"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
        )
        "setting-set" -> JournalOp.SettingSet(
            key = p["key"]!!.jsonPrimitive.content,
            value = p["value"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
        )
        else -> JournalOp.Unknown(opWire)
    }

    private fun decodeDots(elem: JsonElement?): List<Dot> {
        val arr = elem as? JsonArray ?: return emptyList()
        return arr.map { entry ->
            val pair = entry.jsonArray
            Dot(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.content.toLong())
        }
    }
}
