package dev.npn.core.app

import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StoragePathRuleIntegrationTest {
    @Test
    fun importUsesRuleAndUniquifiesCollisions() = runTest {
        val rule = StoragePathRule("organized/{title}.pdf", enabled = true)
        val rig = AppSessionRig.create(backgroundScope, storagePathRule = { rule })
        try {
            val first = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("one"), "same.pdf")
                as ImportOutcome.Imported
            val second = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("two"), "same.pdf")
                as ImportOutcome.Imported

            first.relPath shouldBe "organized/same.pdf"
            second.relPath shouldBe "organized/same-1.pdf"
            rig.exists(first.relPath) shouldBe true
            rig.exists(second.relPath) shouldBe true
        } finally {
            rig.close()
        }
    }

    @Test
    fun metadataChangeMovesOnceAndKeepsProjectionCoherent() = runTest {
        val rule = StoragePathRule("by-title/{title}.pdf", enabled = true)
        val rig = AppSessionRig.create(backgroundScope, storagePathRule = { rule })
        try {
            val imported = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("content"), "scan.pdf")
                as ImportOutcome.Imported
            rig.session.meta.setTitle(imported.docId, "Annual report")

            val moved = rig.db.documentDao().getByDocId(imported.docId)!!
            moved.relPath shouldBe "by-title/Annual report.pdf"
            moved.fileName shouldBe "Annual report.pdf"
            rig.exists(moved.relPath) shouldBe true
            rig.exists(imported.relPath) shouldBe false

            rig.session.meta.setTitle(imported.docId, "Annual report")
            rig.db.documentDao().getByDocId(imported.docId)!!.relPath shouldBe moved.relPath
            rig.exists("by-title/Annual report-1.pdf") shouldBe false
        } finally {
            rig.close()
        }
    }
}
