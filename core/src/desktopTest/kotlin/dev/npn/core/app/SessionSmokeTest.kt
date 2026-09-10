package dev.npn.core.app

import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.xmp.XmpParser
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SessionSmokeTest {
    @Test
    fun openSessionAndRescanFindsFixtures() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
                rig.writePdf("two.pdf", PdfFixtures.embeddedTextPdf("second document"))

                val report = rig.session.rescan()
                report.added shouldBe 2

                val docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                docs shouldHaveSize 2
            } finally {
                rig.close()
            }
        }

    @Test
    fun importPdfBytesDedupsToDuplicate() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val bytes = PdfFixtures.embeddedTextPdf("importable content here")
                val first = rig.session.importPdfBytes(bytes, "imported.pdf")
                first.shouldBeInstanceOf<ImportOutcome.Imported>()

                val second = rig.session.importPdfBytes(bytes, "imported-again.pdf")
                second.shouldBeInstanceOf<ImportOutcome.Duplicate>()
            } finally {
                rig.close()
            }
        }

    @Test
    fun bakedCopyBytesContainsBakedTag() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("bakeable"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")
                rig.session.meta.addTag(listOf(id), "receipt")

                val baked = rig.session.content.bakedCopyBytes(id)
                baked.shouldNotBeNull()

                val io =
                    dev.npn.core.xmp
                        .PdfBoxXmpIO()
                val xmp = io.readXmp(baked)
                xmp.shouldNotBeNull()
                val parsed = XmpParser.parse(xmp)
                parsed.tags shouldBe listOf("receipt")
            } finally {
                rig.close()
            }
        }

    @Test
    fun importImagesAsPdf() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val img = PdfFixtures.jpegBytes()
                val outcome = rig.session.importImagesAsPdf(listOf(img), "scan.pdf")
                outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
            } finally {
                rig.close()
            }
        }
}
