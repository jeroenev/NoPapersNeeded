package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.SystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shortcuts around the validating decoder: a locally built event is canonicalized without a
 * string round trip, and an event read back from the index is parsed without being validated
 * again. Both must give exactly what the full decoder gives.
 */
class CanonicalEventTest {
    private val device = "12345678-1234-4234-9234-123456789abc"
    private val doc = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun event(
        seq: Long,
        op: JournalOp,
        doc: String? = this.doc,
    ) = JournalEvent(JournalEventVersions.V1, seq, Hlc(1_000L + seq, seq.toInt(), device), device, doc, op)

    /** One event of every operation the app writes, with the awkward values each can carry. */
    private val events =
        listOf(
            event(1, JournalOp.TagAdd("Invoice \"2026\" ü\n\t\\")),
            event(2, JournalOp.TagRemove("invoice", listOf(Dot(device, 1), Dot("other-device", 7)))),
            event(3, JournalOp.PropSet(SystemProperty.REVIEWED, "true")),
            event(4, JournalOp.PropSet(SystemProperty.STORAGE_PATH, null)),
            event(5, JournalOp.FieldSet(FieldKeys.TITLE, "A title")),
            event(6, JournalOp.FieldSet("amount", null)),
            event(7, JournalOp.DocTrash("folder/a.pdf")),
            event(8, JournalOp.DocRestore),
            event(9, JournalOp.DocDelete),
            event(10, JournalOp.Relink(other)),
            event(11, JournalOp.TagRename("old", "new"), doc = null),
            event(12, JournalOp.TagMerge("a", "b"), doc = null),
            event(13, JournalOp.TagStyle("tax", "#ff0000", null), doc = null),
            event(14, JournalOp.SettingSet("reviewMode", null), doc = null),
            event(
                15,
                JournalOp.FieldDefSet(
                    "amount",
                    FieldDef("amount", "Amount", FieldType.DECIMAL, true, false, 3, hint = "In euro"),
                ),
                doc = null,
            ),
            event(16, JournalOp.FieldDefSet("gone", null), doc = null),
        )

    @Test
    fun aLocalEventCanonicalizesExactlyAsItsDecodedLine() {
        for (event in events) {
            assertEquals(EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false), EventDecoderRegistry.canonical(event))
        }
    }

    @Test
    fun aStoredEventDecodesToTheEventItWasStoredFrom() {
        for (event in events) {
            val stored = EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false)
            assertEquals(stored.event, EventDecoderRegistry.decodeStored(stored.canonicalJson))
        }
    }

    @Test
    fun aLegacyPropSetIsStoredAndReadBackAsTheFieldSetItBecame() {
        val line =
            """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"$device"},"dev":"$device","doc":"$doc",""" +
                """"op":"prop-set","p":{"key":"title","value":"Old"}}"""
        val stored = EventDecoderRegistry.decode(line, allowMissingV1 = false)

        assertEquals(JournalOp.FieldSet(FieldKeys.TITLE, "Old"), EventDecoderRegistry.decodeStored(stored.canonicalJson).op)
    }

    @Test
    fun aMalformedLocalEventIsRefusedLikeADecodedOne() {
        assertFailsWith<IllegalArgumentException> { EventDecoderRegistry.canonical(event(1, JournalOp.TagAdd("x"), doc = "nope")) }
        assertFailsWith<IllegalArgumentException> { EventDecoderRegistry.canonical(event(0, JournalOp.TagAdd("x"))) }
        assertFailsWith<IllegalArgumentException> {
            EventDecoderRegistry.canonical(event(1, JournalOp.TagRename("a", "b"), doc = doc))
        }
    }
}
