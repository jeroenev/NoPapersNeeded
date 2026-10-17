package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.journalObjectEvents
import dev.npn.core.model.SystemProperty
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Switching to the inbox workflow marks documents reviewed: once each, never twice. */
class ReviewMigrationTest {
    @Test
    fun aMigrationWithNothingToMarkWritesNoEvent() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            rig.session.reviewConfig.markReviewedForMigration(onlyTagged = false)
            val objects = rig.journalObjectCount()

            rig.session.reviewConfig.markReviewedForMigration(onlyTagged = false)

            rig.journalObjectCount() shouldBe objects
        }

    @Test
    fun onlyDocumentsNotYetReviewedAreMarked() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.session.rescan()
            val a = rig.docId("a.pdf")
            val b = rig.docId("b.pdf")
            rig.session.meta.setReviewed(listOf(a), true)

            rig.session.reviewConfig.markReviewedForMigration(onlyTagged = false)

            rig.reviewedEvents() shouldContainExactlyInAnyOrder listOf(a, b)
        }

    @Test
    fun theTaggedMigrationMarksOnlyTaggedDocumentsNotYetReviewed() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
            rig.session.rescan()
            val a = rig.docId("a.pdf")
            val b = rig.docId("b.pdf")
            rig.session.meta.addTag(listOf(a, b), "invoice")
            rig.session.meta.setReviewed(listOf(a), true)

            rig.session.reviewConfig.markReviewedForMigration(onlyTagged = true)

            rig.reviewedEvents() shouldContainExactlyInAnyOrder listOf(a, b)
        }

    /** The documents of every `reviewed` event in the journal, one entry per event. */
    private suspend fun AppSessionRig.reviewedEvents(): List<String?> =
        fs
            .journalObjectEvents()
            .filter { (it.op as? JournalOp.PropSet)?.key == SystemProperty.REVIEWED }
            .map { it.doc }
}
