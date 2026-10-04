package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What [XmpParser] reads back, the document id, tags and title, survives [XmpSerializer] whatever else is written. */
class XmpRoundtripTest {
    private fun roundtrip(data: NpnXmpData) {
        val xml = XmpSerializer.serialize(data)
        XmpParser.parse(xml) shouldBe xmp(documentId = data.documentId, tags = data.tags, title = data.title)
    }

    @Test
    fun fullData() {
        roundtrip(
            xmp(
                documentId = "b".repeat(64),
                tags = listOf("alpha", "beta", "gamma"),
                title = "A title",
                correspondent = "Someone",
                docType = "letter",
                docDate = "2025-01-02",
                reviewed = "true",
                fields = mapOf("amount" to "42.50", "invoice #" to "INV-001"),
            ),
        )
    }

    @Test
    fun emptyData() {
        roundtrip(NpnXmpData.EMPTY)
    }

    @Test
    fun emptyTagsButOtherProps() {
        roundtrip(xmp(documentId = "c".repeat(64), tags = emptyList(), reviewed = "false"))
    }

    @Test
    fun unicodeContent() {
        roundtrip(
            xmp(
                tags = listOf("Räkning", "ökonomi", "発票"),
                title = "Räkning ökonomi 発票",
                correspondent = "Ünïcödé Ltd 発",
            ),
        )
    }

    @Test
    fun xmlSpecialCharactersInTags() {
        roundtrip(
            xmp(
                tags = listOf("<>&\"'", "a & b", "less < than", "greater > than"),
                title = "T<i>t&le\"'",
            ),
        )
    }

    @Test
    fun onlyTitle() {
        roundtrip(xmp(title = "solo"))
    }

    @Test
    fun singleTag() {
        roundtrip(xmp(tags = listOf("only")))
    }

    @Test
    fun whitespaceAndAmpersandHeavy() {
        roundtrip(xmp(tags = listOf("a&amp;b", "x&#65;y"), title = "&&&<<<"))
    }
}
