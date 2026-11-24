package dev.npn.app.ui.detail

import dev.npn.core.testing.doc
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DocPrintableTest {
    @Test
    fun aLivePdfIsPrintable() {
        doc("doc-1").isPrintable shouldBe true
    }

    @Test
    fun aMissingFileIsNotPrintable() {
        doc("doc-1", missing = true).isPrintable shouldBe false
    }

    @Test
    fun aDocumentThatIsNotAPdfIsNotPrintable() {
        doc("doc-1", fileName = "scan.txt", relPath = "scan.txt").isPrintable shouldBe false
    }
}
