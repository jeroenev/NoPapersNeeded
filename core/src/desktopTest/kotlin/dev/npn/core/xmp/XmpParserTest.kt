package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpParserTest {

    @Test
    fun nullAndEmptyYieldEmpty() {
        XmpParser.parse(null) shouldBe NpnXmpData.EMPTY
        XmpParser.parse("") shouldBe NpnXmpData.EMPTY
    }

    @Test
    fun garbageNeverThrows() {
        XmpParser.parse("not xml at all <<< &&& >>>") shouldBe NpnXmpData.EMPTY
        XmpParser.parse("<rdf:Description") shouldBe NpnXmpData.EMPTY
        XmpParser.parse("<dc:subject><rdf:Bag><rdf:li>unclosed") shouldBe NpnXmpData.EMPTY
        // Random bytes turned into a string.
        XmpParser.parse("\u0000\uFFFDbinary\u0001garbage") shouldBe NpnXmpData.EMPTY
    }

    @Test
    fun foreignPacketWithDcSubject() {
        val foreign = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
                  <dc:title>
                    <rdf:Alt>
                      <rdf:li xml:lang="x-default">Foreign Title</rdf:li>
                    </rdf:Alt>
                  </dc:title>
                  <dc:subject>
                    <rdf:Bag>
                      <rdf:li>keyword one</rdf:li>
                      <rdf:li>keyword two</rdf:li>
                    </rdf:Bag>
                  </dc:subject>
                  <pdf:Producer>Some Other Tool</pdf:Producer>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val data = XmpParser.parse(foreign)
        data.tags shouldBe listOf("keyword one", "keyword two")
        data.title shouldBe "Foreign Title"
        // No npn props present.
        data.documentId shouldBe null
        data.reviewed shouldBe null
    }

    @Test
    fun npnAttributeShorthand() {
        val xml = """
            <rdf:Description rdf:about=""
                xmlns:npn="https://npn.dev/xmp/1.0/"
                npn:documentId="deadbeef"
                npn:reviewed="true"/>
        """.trimIndent()
        val data = XmpParser.parse(xml)
        data.documentId shouldBe "deadbeef"
        data.reviewed shouldBe "true"
    }

    @Test
    fun falseTagPrefixNotMatched() {
        // dc:subjectMatter must not be read as dc:subject.
        val xml = """
            <rdf:Description>
              <dc:subjectMatter>nope</dc:subjectMatter>
            </rdf:Description>
        """.trimIndent()
        XmpParser.parse(xml).tags shouldBe emptyList()
    }

    @Test
    fun selfClosingElementsHandled() {
        val xml = """
            <rdf:Description>
              <dc:subject/>
              <npn:documentId/>
            </rdf:Description>
        """.trimIndent()
        val data = XmpParser.parse(xml)
        data.tags shouldBe emptyList()
        data.documentId shouldBe null
    }
}
