package dev.npn.app.ui.detail

import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The name a shared or saved copy gets is one file name made from the title, never a path. */
class ExportNameTest {
    private fun exportNameOf(title: String?) = exportName(listItem(doc("doc-1", fileName = "scan.pdf", title = title)))

    @Test
    fun aSlashInTheTitleIsNoFolder() {
        exportNameOf("Invoices/October") shouldBe "Invoices_October"
    }

    @Test
    fun aTitleCannotClimbOutOfTheFolderItIsWrittenTo() {
        exportNameOf("../cached.pdf") shouldBe "_cached"
        exportNameOf("..") shouldBe "document"
    }

    @Test
    fun aTitleCannotNameAnAbsolutePath() {
        exportNameOf("/abs.pdf") shouldBe "_abs"
    }

    @Test
    fun aDotInsideTheTitleStays() {
        exportNameOf("Invoice no. 12") shouldBe "Invoice no. 12"
        exportNameOf("Report.PDF") shouldBe "Report"
    }

    @Test
    fun aBlankTitleFallsBackToTheFileName() {
        exportNameOf("   ") shouldBe "scan"
        exportNameOf(null) shouldBe "scan"
    }
}
