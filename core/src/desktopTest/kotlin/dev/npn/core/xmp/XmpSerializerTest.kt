package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpSerializerTest {

    @Test
    fun goldenFullPacket() {
        val data = NpnXmpData(
            documentId = "a".repeat(64),
            tags = listOf("invoice", "2026", "acme"),
            title = "Q1 report",
            correspondent = "ACME Corp",
            docType = "invoice",
            docDate = "2026-07-15",
            reviewed = "true",
        )

        val expected = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("    <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:npn=\"https://npn.dev/xmp/1.0/\">\n")
            append("      <dc:title>\n")
            append("        <rdf:Alt>\n")
            append("          <rdf:li xml:lang=\"x-default\">Q1 report</rdf:li>\n")
            append("        </rdf:Alt>\n")
            append("      </dc:title>\n")
            append("      <dc:subject>\n")
            append("        <rdf:Bag>\n")
            append("          <rdf:li>invoice</rdf:li>\n")
            append("          <rdf:li>2026</rdf:li>\n")
            append("          <rdf:li>acme</rdf:li>\n")
            append("        </rdf:Bag>\n")
            append("      </dc:subject>\n")
            append("      <npn:documentId>").append("a".repeat(64)).append("</npn:documentId>\n")
            append("      <npn:correspondent>ACME Corp</npn:correspondent>\n")
            append("      <npn:documentType>invoice</npn:documentType>\n")
            append("      <npn:documentDate>2026-07-15</npn:documentDate>\n")
            append("      <npn:reviewed>true</npn:reviewed>\n")
            append("    </rdf:Description>\n")
            append("  </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }

        XmpSerializer.serialize(data) shouldBe expected
    }

    @Test
    fun emptyDataOmitsAllProperties() {
        val expected = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("    <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:npn=\"https://npn.dev/xmp/1.0/\">\n")
            append("    </rdf:Description>\n")
            append("  </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
        XmpSerializer.serialize(NpnXmpData.EMPTY) shouldBe expected
    }

    @Test
    fun escapesXmlSpecials() {
        val data = NpnXmpData(tags = listOf("a<b>c&d\"e'f"))
        val xml = XmpSerializer.serialize(data)
        (xml.contains("a&lt;b&gt;c&amp;d&quot;e&apos;f")) shouldBe true
        // No raw specials leaked into the li text.
        (xml.contains("<rdf:li>a<b>")) shouldBe false
    }

    @Test
    fun deterministicAcrossCalls() {
        val data = NpnXmpData(tags = listOf("x", "y"), title = "T", reviewed = "false")
        XmpSerializer.serialize(data) shouldBe XmpSerializer.serialize(data)
    }
}
