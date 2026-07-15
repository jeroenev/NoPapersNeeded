package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpMergerTest {

    @Test
    fun noExistingProducesFreshPacket() {
        val data = NpnXmpData(tags = listOf("t"), status = "inbox")
        val merged = XmpMerger.merge(null, data)
        XmpParser.parse(merged) shouldBe data
    }

    @Test
    fun carriesExistingTitleWhenDataTitleNull() {
        val existing = XmpSerializer.serialize(NpnXmpData(title = "Old Title", tags = listOf("x")))
        val data = NpnXmpData(tags = listOf("new"), status = "filed")
        val merged = XmpMerger.merge(existing, data)
        val parsed = XmpParser.parse(merged)
        parsed.title shouldBe "Old Title"
        parsed.tags shouldBe listOf("new")
        parsed.status shouldBe "filed"
    }

    @Test
    fun dataTitleWinsOverExisting() {
        val existing = XmpSerializer.serialize(NpnXmpData(title = "Old Title"))
        val data = NpnXmpData(title = "New Title")
        XmpParser.parse(XmpMerger.merge(existing, data)).title shouldBe "New Title"
    }

    @Test
    fun foreignPropsDropped() {
        val foreign = """
            <rdf:Description rdf:about=""
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
              <pdf:Producer>Other Tool</pdf:Producer>
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Carried</rdf:li></rdf:Alt></dc:title>
            </rdf:Description>
        """.trimIndent()
        val merged = XmpMerger.merge(foreign, NpnXmpData(tags = listOf("k")))
        // Foreign producer dropped, title carried.
        (merged.contains("pdf:Producer")) shouldBe false
        (merged.contains("Carried")) shouldBe true
    }
}
