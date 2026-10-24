package dev.npn.core.rules

import dev.npn.core.db.FtsRows
import dev.npn.core.index.IndexRig
import dev.npn.core.index.rowAtPath
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjects
import dev.npn.core.model.FieldKeys
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

    /**
     * Rules only fill in. A value the journal holds, set by anyone (here a person's correspondent), a
     * field someone cleared, and a tag someone removed stay as they are; only what the document never
     * had is written. A second application finds nothing to fill and writes nothing.
     */
    @Test
    fun rulesFillOnlyWhatTheDocumentNeverHad() =
        runTest {
            rig.writePdf("invoice.pdf", "the bill")
            rig.rescan()
            val docId = checkNotNull(rig.db.documentDao().rowAtPath("invoice.pdf")).docId
            val coordinator = rig.coordinator(rig.writer("dev-main"))
            coordinator.refreshState()
            coordinator.commitAndApply(
                listOf(
                    PendingOp.fieldSet(docId, FieldKeys.CORRESPONDENT, "Globex"),
                    PendingOp.fieldSet(docId, FieldKeys.DOC_TYPE, "Letter"),
                    PendingOp.tagAdd(docId, "Paid"),
                ),
            )
            coordinator.commitAndApply(listOf(PendingOp.fieldSet(docId, FieldKeys.DOC_TYPE, null)))
            coordinator.commitBuilt { state -> state.tagRemovalOps(listOf(docId), "Paid") }
            val applier =
                RuleApplier(
                    rules = {
                        listOf(
                            AutoMatchRule(
                                id = "invoices",
                                name = "Invoices",
                                field = RuleField.Content,
                                pattern = "invoice",
                                tags = listOf("invoice", "paid"),
                                sets =
                                    mapOf(
                                        FieldKeys.CORRESPONDENT to "ACME",
                                        FieldKeys.DOC_TYPE to "Invoice",
                                        "project" to "Billing",
                                    ),
                            ),
                        )
                    },
                    ftsDao = rig.db.ftsDao(),
                    documentDao = rig.db.documentDao(),
                    coordinator = coordinator,
                )

            applier.applyToDoc(docId, content = "Invoice 42")

            rig.db.documentFieldDao().valuesOf(docId) shouldBe
                mapOf(FieldKeys.CORRESPONDENT to "Globex", "project" to "Billing")
            rig.db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")

            val objects = rig.fs.journalObjects().size
            applier.applyToDoc(docId, content = "Invoice 42")
            applier.applyToExistingDocs()
            rig.fs.journalObjects().size shouldBe objects
        }

    /**
     * A relink copies the old document's values and live tags onto the new one, but no clear and no
     * removal. What someone cleared or removed on the old document stays taken away on the document it
     * became: a rule on it reads the whole relink chain for what was ever set or carried.
     */
    @Test
    fun rulesDoNotRefillWhatWasTakenAwayBeforeARelink() =
        runTest {
            rig.writePdf("original.pdf", "the bill")
            rig.writePdf("baked.pdf", "the bill, baked")
            rig.rescan()
            val original = checkNotNull(rig.db.documentDao().rowAtPath("original.pdf")).docId
            val baked = checkNotNull(rig.db.documentDao().rowAtPath("baked.pdf")).docId
            val coordinator = rig.coordinator(rig.writer("dev-main"))
            coordinator.refreshState()
            coordinator.commitAndApply(
                listOf(
                    PendingOp.fieldSet(original, FieldKeys.CORRESPONDENT, "Globex"),
                    PendingOp.fieldSet(original, FieldKeys.DOC_TYPE, "Letter"),
                    PendingOp.tagAdd(original, "Paid"),
                ),
            )
            coordinator.commitAndApply(listOf(PendingOp.fieldSet(original, FieldKeys.DOC_TYPE, null)))
            coordinator.commitBuilt { state -> state.tagRemovalOps(listOf(original), "Paid") }
            coordinator.relinkAndRescan(original, baked)
            val applier =
                RuleApplier(
                    rules = {
                        listOf(
                            AutoMatchRule(
                                id = "invoices",
                                name = "Invoices",
                                field = RuleField.Content,
                                pattern = "invoice",
                                tags = listOf("paid", "invoice"),
                                sets = mapOf(FieldKeys.DOC_TYPE to "Invoice", "project" to "Billing"),
                            ),
                        )
                    },
                    ftsDao = rig.db.ftsDao(),
                    documentDao = rig.db.documentDao(),
                    coordinator = coordinator,
                )

            applier.applyToDoc(baked, content = "Invoice 42")

            rig.db.documentFieldDao().valuesOf(baked) shouldBe
                mapOf(FieldKeys.CORRESPONDENT to "Globex", "project" to "Billing")
            rig.db.tagDao().tagsForDocument(baked) shouldContainExactly listOf("invoice")
        }

    private companion object {
        const val DOCS = 25
    }
}
