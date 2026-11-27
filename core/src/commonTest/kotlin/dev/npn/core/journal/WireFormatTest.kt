package dev.npn.core.journal

import dev.npn.core.model.SystemProperty
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

    private fun event(
        doc: String?,
        op: JournalOp,
        seq: Long = 1,
    ) = JournalEvent(1, seq, hlc, "dev1", doc, op)

    private fun fields(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun envelopeFieldNamesExact() {
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
    fun nullDocForLibraryOps() {
        val e = event(null, JournalOp.TagRename("a", "b"))
        val obj = fields(JournalCodec.encode(e))
        assertTrue(obj["doc"] is JsonNull)
        val p = obj["p"]!!.jsonObject
        assertEquals("a", p["from"]!!.jsonPrimitive.content)
        assertEquals("b", p["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun roundtripAllOps() {
        val ops =
            listOf(
                event(sha, JournalOp.TagAdd("t")),
                event(sha, JournalOp.TagRemove("t", listOf(Dot("d2", 7), Dot("d3", 9)))),
                event(sha, JournalOp.PropSet(SystemProperty.REVIEWED, "true")),
                event(sha, JournalOp.PropSet(SystemProperty.OCR_ENGINE, null)),
                event(sha, JournalOp.FieldSet("amount", "42.50")),
                event(sha, JournalOp.FieldSet("amount", null)),
                event(sha, JournalOp.DocTrash("/lib/x.pdf")),
                event(sha, JournalOp.DocTrash("/lib/x.pdf", FileStat(size = 1234, mtimeMillis = 1719999999000))),
                event(sha, JournalOp.DocRestore),
                event(sha, JournalOp.DocDelete),
                event(sha, JournalOp.DocUndelete),
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

    /**
     * A trash that moved the document's file records its stat as `stat`; one that moved none writes
     * the payload it always did, so its bytes, and its object's name, are as before.
     */
    @Test
    fun docTrashWritesItsStatOnlyWhenItMovedAFile() {
        val moved = fields(JournalCodec.encode(event(sha, JournalOp.DocTrash("a.pdf", FileStat(12, 3_000)))))
        val p = moved["p"]!!.jsonObject
        assertEquals(setOf("origPath", "stat"), p.keys)
        val stat = p["stat"]!!.jsonObject
        assertEquals(setOf("size", "mtime"), stat.keys)
        assertEquals(12L, stat["size"]!!.jsonPrimitive.content.toLong())
        assertEquals(3_000L, stat["mtime"]!!.jsonPrimitive.content.toLong())
        assertEquals(false, stat["size"]!!.jsonPrimitive.isString)

        assertEquals(
            """{"v":1,"seq":1,"hlc":{"t":1720000000000,"c":0,"d":"dev1"},"dev":"dev1","doc":"$sha",""" +
                """"op":"doc-trash","p":{"origPath":"a.pdf"}}""",
            JournalCodec.encode(event(sha, JournalOp.DocTrash("a.pdf"))),
        )
    }

    /**
     * A `stat` this build cannot read (not an object of two integers, a negative size) leaves the
     * trash as one that recorded none rather than failing the line: it only adds to the event.
     */
    @Test
    fun aDocTrashStatThisBuildCannotReadIsDropped() {
        fun trashWith(stat: String) =
            """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"dev1"},"dev":"dev1","doc":"$sha",""" +
                """"op":"doc-trash","p":{"origPath":"a.pdf","stat":$stat}}"""
        val unreadable =
            listOf(
                "null",
                "7",
                "\"x\"",
                "{}",
                """{"size":1}""",
                """{"size":"1","mtime":2}""",
                """{"size":-1,"mtime":2}""",
            )
        for (bad in unreadable) {
            assertEquals(JournalOp.DocTrash("a.pdf"), JournalCodec.decodeLineOrNull(trashWith(bad))!!.op, bad)
        }
        assertEquals(
            JournalOp.DocTrash("a.pdf", FileStat(1, -2)),
            JournalCodec.decodeLineOrNull(trashWith("""{"size":1,"mtime":-2,"extra":true}"""))!!.op,
        )
    }

    @Test
    fun opWireNamesArePinned() {
        // A new op makes older builds refuse every object that holds it (see JournalOp).
        fun pinned(op: JournalOp): String? =
            when (op) {
                is JournalOp.TagAdd -> "tag-add"
                is JournalOp.TagRemove -> "tag-remove"
                is JournalOp.PropSet -> "prop-set"
                is JournalOp.FieldSet -> "field-set"
                is JournalOp.FieldDefSet -> "field-def"
                is JournalOp.DocTrash -> "doc-trash"
                JournalOp.DocRestore -> "doc-restore"
                JournalOp.DocDelete -> "doc-delete"
                JournalOp.DocUndelete -> "doc-undelete"
                is JournalOp.Relink -> "relink"
                is JournalOp.TagRename -> "tag-rename"
                is JournalOp.TagMerge -> "tag-merge"
                is JournalOp.TagStyle -> "tag-style"
                is JournalOp.SettingSet -> "setting-set"
                is JournalOp.Unknown -> null
            }
        val ops =
            listOf(
                JournalOp.TagAdd("t"),
                JournalOp.TagRemove("t", emptyList()),
                JournalOp.PropSet(SystemProperty.REVIEWED, null),
                JournalOp.FieldSet("amount", null),
                JournalOp.FieldDefSet("amount", null),
                JournalOp.DocTrash("x.pdf"),
                JournalOp.DocRestore,
                JournalOp.DocDelete,
                JournalOp.DocUndelete,
                JournalOp.Relink(sha2),
                JournalOp.TagRename("a", "b"),
                JournalOp.TagMerge("a", "b"),
                JournalOp.TagStyle("a", null, null),
                JournalOp.SettingSet("reviewMode", null),
            )
        for (op in ops) assertEquals(pinned(op), op.wire)
        assertEquals(
            setOf(
                "tag-add",
                "tag-remove",
                "prop-set",
                "field-set",
                "field-def",
                "doc-trash",
                "doc-restore",
                "doc-delete",
                "doc-undelete",
                "relink",
                "tag-rename",
                "tag-merge",
                "tag-style",
                "setting-set",
            ),
            ops.map { it.wire }.toSet(),
        )
    }

    @Test
    fun tagRemoveDotsWireShape() {
        val e = event(sha, JournalOp.TagRemove("t", listOf(Dot("d2", 7))))
        val p = fields(JournalCodec.encode(e))["p"]!!.jsonObject
        val dots = p["dots"]!!.jsonArray
        val first = dots[0].jsonArray
        assertEquals("d2", first[0].jsonPrimitive.content)
        assertEquals(7L, first[1].jsonPrimitive.content.toLong())
    }

    @Test
    fun propSetNullValueIsJsonNull() {
        val e = event(sha, JournalOp.PropSet(SystemProperty.OCR_ENGINE, null))
        val p = fields(JournalCodec.encode(e))["p"]!!.jsonObject
        assertTrue(p["value"] is JsonNull)
        val parsed = JournalCodec.decodeLineOrNull(JournalCodec.encode(e))!!
        assertEquals(null, (parsed.op as JournalOp.PropSet).value)
    }

    @Test
    fun fieldSetWireShapeAndRoundtrip() {
        val e = event(sha, JournalOp.FieldSet("amount", "42.50"))
        val p = fields(JournalCodec.encode(e))["p"]!!.jsonObject
        assertEquals("amount", p["key"]!!.jsonPrimitive.content)
        assertEquals("42.50", p["value"]!!.jsonPrimitive.content)
        val parsed = JournalCodec.decodeLineOrNull(JournalCodec.encode(e))!!
        assertEquals(e, parsed)
        // Null value clears.
        val cleared = event(sha, JournalOp.FieldSet("amount", null))
        val pc = fields(JournalCodec.encode(cleared))["p"]!!.jsonObject
        assertTrue(pc["value"] is JsonNull)
    }

    @Test
    fun unknownOpParsesAsUnknownNotCrash() {
        val line =
            """{"v":1,"seq":5,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":null,""" +
                """"op":"future-op","p":{"whatever":true}}"""
        val parsed = JournalCodec.decodeLineOrNull(line)!!
        assertTrue(parsed.op is JournalOp.Unknown)
        assertEquals("future-op", parsed.op.wire)
    }

    @Test
    fun unknownOpParsesAsUnknownAndGarbageAsNothing() {
        val good = JournalCodec.encode(event(sha, JournalOp.TagAdd("t")))
        val unknown = """{"v":1,"seq":6,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":null,"op":"future-op","p":{}}"""
        assertTrue(JournalCodec.decodeLineOrNull(good)!!.op is JournalOp.TagAdd)
        assertTrue(JournalCodec.decodeLineOrNull(unknown)!!.op is JournalOp.Unknown)
        assertNull(JournalCodec.decodeLineOrNull("not json at all"))
    }

    @Test
    fun unknownPropKeyBecomesUnknownOp() {
        val line =
            """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":"$sha",""" +
                """"op":"prop-set","p":{"key":"bogus","value":"z"}}"""
        val parsed = JournalCodec.decodeLineOrNull(line)!!
        assertTrue(parsed.op is JournalOp.Unknown)
    }

    private fun propSetLine(
        key: String,
        value: String,
    ) = """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":"$sha",""" +
        """"op":"prop-set","p":{"key":"$key","value":"$value"}}"""

    @Test
    fun legacyPropSetForWellKnownFieldDecodesAsFieldSet() {
        // Libraries written before the field registry hold prop-set events for the well-known five.
        // Those keys decode as the FieldSet they would be written as today, so metadata survives,
        // while system-state keys stay PropSet and unknown keys degrade to Unknown.
        val title = JournalCodec.decodeLineOrNull(propSetLine("title", "Invoice"))!!.op
        assertTrue(title is JournalOp.FieldSet, "title prop-set should decode as FieldSet")
        assertEquals("title", title.key)
        assertEquals("Invoice", title.value)

        val docDate = JournalCodec.decodeLineOrNull(propSetLine("docDate", "2024-01-02"))!!.op
        assertTrue(docDate is JournalOp.FieldSet, "docDate prop-set should decode as FieldSet")
        assertEquals("docDate", docDate.key)
        assertEquals("2024-01-02", docDate.value)

        val reviewed = JournalCodec.decodeLineOrNull(propSetLine("reviewed", "true"))!!.op
        assertTrue(reviewed is JournalOp.PropSet, "reviewed prop-set should stay a PropSet")
        assertEquals(SystemProperty.REVIEWED, reviewed.key)

        val unknown = JournalCodec.decodeLineOrNull(propSetLine("bogus", "z"))!!.op
        assertTrue(unknown is JournalOp.Unknown, "unknown prop-set key should decode as Unknown")
    }

    @Test
    fun blankLinesDecodeAsNothing() {
        assertNull(JournalCodec.decodeLineOrNull(""))
        assertNull(JournalCodec.decodeLineOrNull("   "))
    }
}
