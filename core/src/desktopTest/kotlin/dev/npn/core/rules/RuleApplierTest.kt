package dev.npn.core.rules

import dev.npn.core.db.FtsRows
import dev.npn.core.index.IndexRig
import dev.npn.core.index.rowAtPath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

class RuleApplierTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() {
        rig.close()
    }

    /**
     * Saving a rule applies every rule to every live document. The rules are read and compiled
     * once for that pass, not once per document.
     */
    @Test
    fun aPassOverTheLibraryReadsTheRulesOnce() =
        runTest {
            repeat(DOCS) { rig.writePdf("doc-$it.pdf", "document $it") }
            rig.writePdf("electricity-bill.pdf", "the bill")
            rig.rescan()
            val bill = checkNotNull(rig.db.documentDao().rowAtPath("electricity-bill.pdf")).docId
            val scanned = checkNotNull(rig.db.documentDao().rowAtPath("doc-3.pdf")).docId
            rig.db.ftsDao().upsertContent(FtsRows.row(scanned, "doc-3.pdf", emptyList(), emptyMap(), "Water Utility"))
            val coordinator = rig.coordinator(rig.writer("dev-main"))
            coordinator.refreshState()

            var reads = 0
            val applier =
                RuleApplier(
                    rules = {
                        reads++
                        listOf(
                            AutoMatchRule(
                                id = "bills",
                                name = "Bills",
                                field = RuleField.FileName,
                                algorithm = MatchAlgorithm.ANY_WORD,
                                pattern = "electricity gas",
                                tags = listOf("utilities"),
                            ),
                            AutoMatchRule(
                                id = "water",
                                name = "Water",
                                field = RuleField.Content,
                                algorithm = MatchAlgorithm.FUZZY,
                                pattern = "water utilty",
                                tags = listOf("water"),
                            ),
                        )
                    },
                    ftsDao = rig.db.ftsDao(),
                    documentDao = rig.db.documentDao(),
                    coordinator = coordinator,
                )

            applier.applyToExistingDocs()

            reads shouldBe 1
            rig.db.tagDao().tagsForDocument(bill) shouldContainExactly listOf("utilities")
            rig.db.tagDao().tagsForDocument(scanned) shouldContainExactly listOf("water")
            rig.db.tagDao().docIdsWithTag("utilities") shouldContainExactly listOf(bill)
        }

    /**
     * A field write re-runs the rules on its document without OCR text at hand. A rule on the
     * content still matches against the indexed text, so it keeps the value it set over an earlier
     * rule's, as a pass over the library would.
     */
    @Test
    fun aDocumentsOwnPassMatchesContentRulesOnItsIndexedText() =
        runTest {
            rig.writePdf("water-bill.pdf", "the bill")
            rig.rescan()
            val docId = checkNotNull(rig.db.documentDao().rowAtPath("water-bill.pdf")).docId
            rig.db.ftsDao().upsertContent(
                FtsRows.row(docId, "water-bill.pdf", emptyList(), emptyMap(), "Water Utility"),
            )
            val coordinator = rig.coordinator(rig.writer("dev-main"))
            coordinator.refreshState()
            val applier =
                RuleApplier(
                    rules = {
                        listOf(
                            AutoMatchRule(
                                id = "bills",
                                name = "Bills",
                                field = RuleField.FileName,
                                pattern = "bill",
                                sets = mapOf("correspondent" to "Some supplier"),
                                position = 0,
                            ),
                            AutoMatchRule(
                                id = "water",
                                name = "Water",
                                field = RuleField.Content,
                                pattern = "water",
                                sets = mapOf("correspondent" to "Water Utility"),
                                position = 1,
                            ),
                        )
                    },
                    ftsDao = rig.db.ftsDao(),
                    documentDao = rig.db.documentDao(),
                    coordinator = coordinator,
                )

            applier.applyToDoc(docId, organize = false)

            rig.db.documentFieldDao().valuesOf(docId)["correspondent"] shouldBe "Water Utility"
        }

    private companion object {
        const val DOCS = 25
    }
}
