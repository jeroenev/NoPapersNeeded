package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldDefFoldTest {
    private fun def(
        key: String,
        position: Int = 0,
        type: FieldType = FieldType.TEXT,
        label: String = key,
        ai: Boolean = false,
        hint: String? = null,
    ) = FieldDef(key, label, type, showByDefault = true, aiEnabled = ai, position = position, hint = hint)

    private fun set(
        seq: Long,
        def: FieldDef,
        t: Long,
        dev: String = "d1",
    ) = JournalEvent(1, seq, Hlc(t, 0, dev), dev, null, JournalOp.FieldDefSet(def.key, def))

    private fun remove(
        seq: Long,
        key: String,
        t: Long,
        dev: String = "d1",
    ) = JournalEvent(1, seq, Hlc(t, 0, dev), dev, null, JournalOp.FieldDefSet(key, null))

    @Test
    fun wire_shape_and_roundtrip() {
        val e =
            set(1, def("amount", position = 3, type = FieldType.DECIMAL, label = "Amount", ai = true, hint = "€"), 10)
        val encoded = JournalCodec.encode(e)
        val p = Json.parseToJsonElement(encoded).jsonObject["p"]!!.jsonObject
        assertEquals("amount", p["key"]!!.jsonPrimitive.content)
        val d = p["def"]!!.jsonObject
        assertEquals("Amount", d["label"]!!.jsonPrimitive.content)
        assertEquals("decimal", d["type"]!!.jsonPrimitive.content)
        assertEquals("true", d["showByDefault"]!!.jsonPrimitive.content)
        assertEquals("true", d["aiEnabled"]!!.jsonPrimitive.content)
        assertEquals("3", d["position"]!!.jsonPrimitive.content)
        assertEquals("€", d["hint"]!!.jsonPrimitive.content)
        assertEquals(e, JournalCodec.decodeLineOrNull(encoded))

        val removal = remove(2, "amount", 20)
        val rp = Json.parseToJsonElement(JournalCodec.encode(removal)).jsonObject["p"]!!.jsonObject
        assertTrue(rp["def"] is JsonNull)
        assertEquals(removal, JournalCodec.decodeLineOrNull(JournalCodec.encode(removal)))
    }

    @Test
    fun unknown_type_decodes_as_unknown_op_not_crash() {
        val line =
            """{"v":1,"seq":5,"hlc":{"t":1,"c":0,"d":"x"},"dev":"x","doc":null,""" +
                """"op":"field-def","p":{"key":"flag","def":{"label":"Flag","type":"boolean","position":0}}}"""
        val parsed = JournalCodec.decodeLineOrNull(line)!!
        assertIs<JournalOp.Unknown>(parsed.op)
        // and folds to nothing
        assertTrue(JournalFolder.fold(listOf(parsed)).effectiveFieldDefs().isEmpty())
    }

    @Test
    fun lww_per_key_and_order_independent() {
        val a1 = set(1, def("amount", label = "Amount"), 10)
        val a2 = set(2, def("amount", label = "Total amount"), 20)
        val b = set(3, def("vendor", position = 1), 15, dev = "d2")
        val forward = JournalFolder.fold(listOf(a1, a2, b)).effectiveFieldDefs()
        val reversed = JournalFolder.fold(listOf(b, a2, a1)).effectiveFieldDefs()
        assertEquals(forward, reversed)
        assertEquals(listOf("amount", "vendor"), forward.map { it.key })
        assertEquals("Total amount", forward[0].label)
    }

    @Test
    fun two_devices_adding_two_fields_both_survive() {
        val a = set(1, def("amount"), 10, dev = "d1")
        val b = set(1, def("vendor"), 10, dev = "d2")
        assertEquals(
            setOf("amount", "vendor"),
            JournalFolder
                .fold(listOf(a, b))
                .effectiveFieldDefs()
                .map { it.key }
                .toSet(),
        )
    }

    @Test
    fun later_removal_wins_and_earlier_readd_does_not_resurrect() {
        val add = set(1, def("amount"), 10)
        val rm = remove(2, "amount", 20)
        val state = JournalFolder.fold(listOf(rm, add))
        assertNull(state.effectiveFieldDef("amount"))
        // A genuinely later re-add does bring it back.
        val readd = set(3, def("amount", label = "Amount again"), 30)
        assertEquals("Amount again", JournalFolder.fold(listOf(add, rm, readd)).effectiveFieldDef("amount")?.label)
    }

    @Test
    fun registry_order_is_position_then_key() {
        val state =
            JournalFolder.fold(
                listOf(
                    set(1, def("zeta", position = 0), 10),
                    set(2, def("alpha", position = 0), 11),
                    set(3, def("mid", position = 1), 12),
                ),
            )
        assertEquals(listOf("alpha", "zeta", "mid"), state.effectiveFieldDefs().map { it.key })
    }
}
