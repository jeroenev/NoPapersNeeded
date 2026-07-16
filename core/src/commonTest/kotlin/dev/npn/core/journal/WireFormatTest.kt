package dev.npn.core.journal

import dev.npn.core.model.DocProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireFormatTest {

    private val sha = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val hlc = Hlc(1720000000000, 0, "dev1")

    private fun event(doc: String?, op: JournalOp, seq: Long = 1) =
        JournalEvent(1, seq, hlc, "dev1", doc, op)

    private fun fields(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun envelope_field_names_exact() {
        val e = event(sha, JournalOp.TagAdd("invoice"))
        val obj = fields(JournalCodec.encode(e))
        assertEquals(setOf("v", "seq", "hlc", "dev", "doc", "op", "p"), obj.keys)
        assertEquals(1, obj["v"]!!.jsonPrimitive.content.toInt())
        assertEquals(1L, obj["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals("dev1", obj["dev"]!!.jsonPrimitive.content)
        assertEquals(sha, obj["doc"]!!.jsonPrimitive.content)
        assertEquals("tag-add", obj["op"]!!.jsonPrimitive.content)

        val hlcObj = obj["hlc"]!!.jsonObject
        assertEquals(setOf("t", "c", "d"), hlcObj.keys)
        assertEquals(1720000000000L, hlcObj["t"]!!.jsonPrimitive.content.toLong())
        assertEquals(0, hlcObj["c"]!!.jsonPrimitive.content.toInt())
        assertEquals("dev1", hlcObj["d"]!!.jsonPrimitive.content)

        assertEquals("invoice", obj["p"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun null_doc_for_library_ops() {
        val e = event(null, JournalOp.TagRename("a", "b"))
        val obj = fields(JournalCodec.encode(e))
        assertTrue(obj["doc"] is JsonNull)
        val p = obj["p"]!!.jsonObject
        assertEquals("a", p["from"]!!.jsonPrimitive.content)
        assertEquals("b", p["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun roundtrip_all_ops() {
        val ops = listOf(
            event(sha, JournalOp.TagAdd("t")),
            event(sha, JournalOp.TagRemove("t", listOf(Dot("d2", 7), Dot("d3", 9)))),
            event(sha, JournalOp.PropSet(DocProperty.REVIEWED, "true")),
            event(sha, JournalOp.PropSet(DocProperty.TITLE, null)),
            event(sha, JournalOp.DocTrash("/lib/x.pdf")),
            event(sha, JournalOp.DocRestore),
            event(sha, JournalOp.DocDelete),
            event(sha, JournalOp.Relink(sha2)),
            event(null, JournalOp.TagRename("a", "b")),
            event(null, JournalOp.TagMerge("a", "b")),
            event(null, JournalOp.TagStyle("a", "#fff", "icon")),
            event(null, JournalOp.TagStyle("a", null, null)),
            event(null, JournalOp.SettingSet("reviewMode", "inbox-workflow")),
            event(null, JournalOp.SettingSet("reviewMode", null)),
        )
        for (e in ops) {
            val parsed = JournalCodec.decodeLineOrNull(JournalCodec.encode(e))
            assertEquals(e, parsed, "roundtrip failed for ${e.op}")
        }
    }

    @Test
    fun tag_remove_dots_wire_shape() {
        val e = event(sha, JournalOp.TagRemove("t", listOf(Dot("d2", 7))))
        val p = fields(JournalCodec.encode(e))["p"]!!.jsonObject
        val dots = p["dots"]!!.jsonArray
        val first = dots[0].jsonArray
        assertEquals("d2", first[0].jsonPrimitive.content)
        assertEquals(7L, first[1].jsonPrimitive.content.toLong())
    }

    @Test
    fun prop_set_null_value_is_json_null() {
        val e = event(sha, JournalOp.PropSet(DocProperty.CORRESPONDENT, null))
        val p = fields(JournalCodec.encode(e))["p"]!!.jsonObject
        assertTrue(p["value"] is JsonNull)
        val parsed = JournalCodec.decodeLineOrNull(JournalCodec.encode(e))!!
        assertEquals(null, (parsed.op as JournalOp.PropSet).value)
    }

    @Test
    fun unknown_op_parses_as_unknown_not_crash() {
        val line = """{"v":1,"seq":5,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":null,"op":"future-op","p":{"whatever":true}}"""
        val parsed = JournalCodec.decodeLineOrNull(line)!!
        assertTrue(parsed.op is JournalOp.Unknown)
        assertEquals("future-op", (parsed.op as JournalOp.Unknown).wire)
    }

    @Test
    fun unknown_op_skipped_during_decode_but_kept_as_line() {
        val good = JournalCodec.encode(event(sha, JournalOp.TagAdd("t")))
        val unknown = """{"v":1,"seq":6,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":null,"op":"future-op","p":{}}"""
        val garbage = "not json at all"
        val content = "$good\n$unknown\n$garbage\n"
        val decoded = JournalCodec.decode(content)
        // good + unknown parse (unknown as Unknown), garbage skipped
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].op is JournalOp.TagAdd)
        assertTrue(decoded[1].op is JournalOp.Unknown)
    }

    @Test
    fun unknown_prop_key_becomes_unknown_op() {
        val line = """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":"$sha","op":"prop-set","p":{"key":"bogus","value":"z"}}"""
        val parsed = JournalCodec.decodeLineOrNull(line)!!
        assertTrue(parsed.op is JournalOp.Unknown)
    }

    @Test
    fun blank_lines_skipped() {
        val good = JournalCodec.encode(event(sha, JournalOp.TagAdd("t")))
        assertEquals(1, JournalCodec.decode("\n\n$good\n\n").size)
        assertNull(JournalCodec.decodeLineOrNull("   "))
    }
}
