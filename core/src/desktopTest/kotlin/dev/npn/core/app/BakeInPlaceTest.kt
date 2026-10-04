package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeTrue
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

                rig.session.content
                    .bakeInPlace(before)
                    .shouldBeTrue()

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
}
