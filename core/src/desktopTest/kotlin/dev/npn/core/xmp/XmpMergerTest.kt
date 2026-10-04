package dev.npn.core.xmp

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XmpMergerTest {
    @Test
    fun noExistingProducesFreshPacket() {
        val data = xmp(tags = listOf("t"), reviewed = "false")
        XmpMerger.merge(null, data) shouldBe XmpSerializer.serialize(data)
    }

    @Test
    fun carriesExistingTitleWhenDataTitleNull() {
        val existing = XmpSerializer.serialize(xmp(title = "Old Title", tags = listOf("x")))
        val data = xmp(tags = listOf("new"), reviewed = "true")
        XmpMerger.merge(existing, data) shouldBe XmpSerializer.serialize(xmp(title = "Old Title", tags = listOf("new"), reviewed = "true"))
    }

    @Test
    fun dataTitleWinsOverExisting() {
        val existing = XmpSerializer.serialize(xmp(title = "Old Title"))
        val data = xmp(title = "New Title")
        XmpParser.parse(XmpMerger.merge(existing, data)).title shouldBe "New Title"
    }

    @Test
    fun foreignPropsDropped() {
        val foreign =
            """
            <rdf:Description rdf:about=""
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
              <pdf:Producer>Other Tool</pdf:Producer>
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Carried</rdf:li></rdf:Alt></dc:title>
            </rdf:Description>
            """.trimIndent()
        val merged = XmpMerger.merge(foreign, xmp(tags = listOf("k")))
        // Foreign producer dropped, title carried.
        (merged.contains("pdf:Producer")) shouldBe false
        (merged.contains("Carried")) shouldBe true
    }
}
