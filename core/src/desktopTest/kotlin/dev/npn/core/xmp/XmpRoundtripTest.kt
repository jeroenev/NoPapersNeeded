package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpRoundtripTest {

    private fun roundtrip(data: NpnXmpData) {
        val xml = XmpSerializer.serialize(data)
        XmpParser.parse(xml) shouldBe data
    }

    @Test
    fun fullData() {
        roundtrip(
            NpnXmpData(
                documentId = "b".repeat(64),
                tags = listOf("alpha", "beta", "gamma"),
                title = "A title",
                correspondent = "Someone",
                docType = "letter",
                docDate = "2025-01-02",
                status = "reviewed",
            ),
        )
    }

    @Test
    fun emptyData() {
        roundtrip(NpnXmpData.EMPTY)
    }

    @Test
    fun emptyTagsButOtherProps() {
        roundtrip(NpnXmpData(documentId = "c".repeat(64), tags = emptyList(), status = "inbox"))
    }

    @Test
    fun unicodeContent() {
        roundtrip(
            NpnXmpData(
                tags = listOf("Räkning", "ökonomi", "発票"),
                title = "Räkning ökonomi 発票",
                correspondent = "Ünïcödé Ltd 発",
            ),
        )
    }

    @Test
    fun xmlSpecialCharactersInTags() {
        roundtrip(
            NpnXmpData(
                tags = listOf("<>&\"'", "a & b", "less < than", "greater > than"),
                title = "T<i>t&le\"'",
            ),
        )
    }

    @Test
    fun onlyTitle() {
        roundtrip(NpnXmpData(title = "solo"))
    }

    @Test
    fun singleTag() {
        roundtrip(NpnXmpData(tags = listOf("only")))
    }

    @Test
    fun whitespaceAndAmpersandHeavy() {
        roundtrip(NpnXmpData(tags = listOf("a&amp;b", "x&#65;y"), title = "&&&<<<"))
    }
}
