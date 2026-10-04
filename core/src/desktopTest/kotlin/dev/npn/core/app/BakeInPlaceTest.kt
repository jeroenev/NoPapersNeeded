package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import dev.npn.core.xmp.BakeRefusedException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BakeInPlaceTest {
    @Test
    fun aBakedDocumentKeepsItsMetadataOnTheNewHash() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val before = rig.docId("a.pdf")
                rig.session.meta.applyEdits(
                    listOf(before),
                    addTags = listOf("filed"),
                    fields = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin"),
                )

                rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

                val after = rig.docId("a.pdf")
                after shouldNotBe before
                rig.db
                    .documentDao()
                    .getByDocId(after)
                    ?.relPath shouldBe "a.pdf"
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(after))
                    .associate { it.fieldKey to it.fieldValue } shouldBe
                    mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")
                rig.db
                    .tagDao()
                    .tagsForDocument(after) shouldContainExactly listOf("filed")
                rig.db
                    .documentDao()
                    .getByDocId(before)
                    ?.deleted shouldBe true
            } finally {
                rig.close()
            }
        }

    @Test
    fun aDocumentThatIsGoneIsNotBaked() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.docLifecycle.trashDoc(id)

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
            } finally {
                rig.close()
            }
        }

    @Test
    fun aFileThatCannotCarryMetadataIsRefusedAndLeftAlone() =
        runTest {
            val log = RecordingLog()
            val rig = AppSessionRig.create(backgroundScope, log = log)
            try {
                val bytes = "not really a pdf".encodeToByteArray()
                rig.writePdf("broken.pdf", bytes)
                rig.session.rescan()
                val id = rig.docId("broken.pdf")

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF)

                rig.docId("broken.pdf") shouldBe id
                log.entries.any { it.error is BakeRefusedException } shouldBe true
            } finally {
                rig.close()
            }
        }
}
