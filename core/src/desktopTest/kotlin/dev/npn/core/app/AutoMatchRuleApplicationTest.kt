package dev.npn.core.app

import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjects
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrSidecar
import dev.npn.core.ocr.OcrSidecarCodec
import dev.npn.core.ocr.OcrSidecarFiles
import dev.npn.core.ocr.OcrSidecarName
import dev.npn.core.ocr.OcrState
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * When the auto-match rules run, and what they may write: they fill in what a document lacks, on
 * text new to the library (this device's OCR read it) and when a rule is saved or enabled, and
 * never on text an index merely re-indexes (a rebuild, a sidecar another device wrote).
 */
class AutoMatchRuleApplicationTest {
    /**
     * A rebuilt index re-indexes every document's text from the sidecars in the folder. That text is
     * not new, so no rule runs: what a person corrected stays corrected, and a rule that arrived after
     * the text was read does not reach the document. Rebuilding writes nothing into the journal.
     */
    @Test
    fun aRebuiltIndexRunsNoRules() =
        runTest {
            LibraryDirs().use { (library, data) ->
                lateinit var id: String
                var objects = 0
                openSession(backgroundScope, library, data).use { session, deps ->
                    session.start()
                    session.rules.save(INVOICES)
                    Files.write(library.resolve("inv.pdf"), PdfFixtures.embeddedTextPdf(TEXT))
                    session.rescan()
                    id = deps.fs.sha256("inv.pdf")
                    session.documents
                        .observeDoc(id)
                        .first { it?.tags?.contains("invoice") == true }
                    // A person corrects what the rule set.
                    session.meta.setField(id, FieldKeys.CORRESPONDENT, "Globex")
                    session.meta.removeTag(listOf(id), "invoice")
                    // Another device adds a rule after the text was read; the list arriving runs nothing.
                    ContentAddressedJournalWriter(deps.fs, "other-installation", HlcWatermarkAllocator())
                        .commit(
                            listOf(
                                PendingOp.settingSet(
                                    LibrarySettingKeys.AUTO_MATCH_RULES,
                                    AutoMatchRuleJson.encodeList(listOf(INVOICES, PAID)),
                                ),
                            ),
                        )
                    session.rescan()
                    session.rules.observeRules().first().map { it.id } shouldBe listOf(INVOICES.id, PAID.id)
                    objects = deps.fs.journalObjects().size
                }
                for (name in listOf("index.db", "index.db-wal", "index.db-shm")) File(data, name).delete()

                openSession(backgroundScope, library, data).use { session, deps ->
                    session.start()
                    session.awaitInitialRescan()
                    deps.db
                        .documentDao()
                        .observeByDocId(id)
                        .first { it?.ocrState == OcrState.DONE.column }
                    session.ocr.ocrStatus.first { it.pending == 0 && !it.running }

                    val item = session.documents.observeDoc(id).first().shouldNotBeNull()
                    item.doc.correspondent shouldBe "Globex"
                    // As the first rule set it: the later one's "Receipt" never reached the document.
                    item.doc.docType shouldBe "Invoice"
                    item.tags.shouldBeEmpty()
                    deps.fs.journalObjects().size shouldBe objects
                }
            }
        }

    /**
     * A sidecar another device wrote, which arrives with the file, is text that device read and
     * handed to its own rules. Indexing it here runs none; saving or enabling a rule is what reaches a
     * document whose text was there before.
     */
    @Test
    fun aSidecarAnotherDeviceWroteRunsNoRulesUntilARuleIsSavedOrEnabled() =
        appSessionTest { rig ->
            rig.session.start()
            rig.session.rules.save(INVOICES)
            rig.writePdf("inv.pdf", PdfFixtures.embeddedTextPdf(TEXT))
            val id = rig.docId("inv.pdf")
            val sidecar =
                OcrSidecar(
                    doc = id,
                    dev = "device-two",
                    engine = OcrEngineId.EMBEDDED.column,
                    lang = "eng",
                    createdAt = 1,
                    pages = listOf(TEXT, ""),
                )
            OcrSidecarFiles(rig.fs).writeOnce(
                id,
                OcrSidecarName.writerKey("device-two", OcrEngineId.EMBEDDED.column),
                OcrSidecarCodec.encode(sidecar),
            )
            val objects = rig.journalObjectCount()

            rig.session.rescan()
            rig.session.ocr.ocrStatus
                .first { it.pending == 0 && !it.running }

            rig.db.documentDao().getByDocId(id)?.ocrState shouldBe OcrState.ADOPTED.column
            rig.db.tagDao().tagsForDocument(id).shouldBeEmpty()
            rig.journalObjectCount() shouldBe objects

            rig.session.rules.setEnabled(INVOICES.id, true)
            val item = rig.session.documents.observeDoc(id).first().shouldNotBeNull()
            item.tags shouldContainExactly listOf("invoice")
            item.doc.correspondent shouldBe "ACME"
        }

    /**
     * Text this device's OCR just read runs the rules, once: they fill the fields the document has
     * no value for and leave the one a person set while the OCR ran. Re-indexing the same text later
     * (a rescan's reconcile) writes nothing more.
     */
    @Test
    fun freshOcrTextFillsOnlyWhatTheDocumentLacksAndOnlyOnce() {
        val engine = HeldOcrEngine(TEXT)
        appSessionTest(ocr = engine) { rig ->
            rig.session.start()
            rig.session.rules.save(INVOICES)
            rig.writePdf("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.session.rescan()
            val id = rig.docId("scan.pdf")
            engine.started.await()
            rig.session.meta.setField(id, FieldKeys.CORRESPONDENT, "Globex")
            engine.release.complete(Unit)

            val item =
                rig.session.documents
                    .observeDoc(id)
                    .first { it?.tags?.contains("invoice") == true }
                    .shouldNotBeNull()
            item.doc.correspondent shouldBe "Globex"
            item.doc.docType shouldBe "Invoice"
            rig.session.ocr.ocrStatus
                .first { it.pending == 0 && !it.running }

            val objects = rig.journalObjectCount()
            rig.session.rescan()
            rig.session.ocr.ocrStatus
                .first { it.pending == 0 && !it.running }
            rig.journalObjectCount() shouldBe objects
        }
    }

    /**
     * Saving or enabling a rule runs every enabled rule over every live document, filling in only:
     * a value a person set, a field they cleared and a tag they removed stay. Editing a rule's value
     * does not rewrite what an earlier version of it filled in.
     */
    @Test
    fun savingOrEnablingARuleFillsInExistingDocumentsAndKeepsWhatAPersonSet() =
        appSessionTest { rig ->
            rig.session.start()
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf(TEXT))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf(TEXT, "second copy"))
            rig.session.rescan()
            rig.session.ocr.ocrStatus
                .first { it.pending == 0 && !it.running }
            val edited = rig.docId("a.pdf")
            val untouched = rig.docId("b.pdf")
            rig.session.meta.setField(edited, FieldKeys.CORRESPONDENT, "Globex")
            rig.session.meta.setField(edited, FieldKeys.DOC_TYPE, "Letter")
            rig.session.meta.setField(edited, FieldKeys.DOC_TYPE, null)
            rig.session.meta.addTag(listOf(edited), "invoice")
            rig.session.meta.removeTag(listOf(edited), "invoice")

            rig.session.rules.save(INVOICES)

            val filled = rig.session.documents.observeDoc(untouched).first().shouldNotBeNull()
            filled.doc.correspondent shouldBe "ACME"
            filled.doc.docType shouldBe "Invoice"
            filled.tags shouldContainExactly listOf("invoice")
            val kept = rig.session.documents.observeDoc(edited).first().shouldNotBeNull()
            kept.doc.correspondent shouldBe "Globex"
            kept.doc.docType shouldBe null
            kept.tags.shouldBeEmpty()

            val objects = rig.journalObjectCount()
            rig.session.rules.setEnabled(INVOICES.id, false)
            rig.session.rules.setEnabled(INVOICES.id, true)
            rig.session.rules.save(INVOICES.copy(sets = INVOICES.sets + (FieldKeys.CORRESPONDENT to "ACME Corp")))
            // The rule list's own three writes (disable, enable, edit); nothing on a document.
            rig.journalObjectCount() shouldBe objects + 3
            rig.session.documents
                .observeDoc(untouched)
                .first()
                .shouldNotBeNull()
                .doc.correspondent shouldBe "ACME"
        }

    /** An OCR engine that reads [text] from a page once [release] completes, saying when it [started]. */
    private class HeldOcrEngine(
        private val text: String,
    ) : OcrEngine {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override val name: String = OcrEngineId.TESSERACT.column

        override suspend fun available(): Boolean = true

        override suspend fun recognizePng(
            png: ByteArray,
            lang: String,
        ): String {
            started.complete(Unit)
            release.await()
            return text
        }
    }

    private companion object {
        const val TEXT = "Invoice from ACME Corporation"

        val INVOICES =
            AutoMatchRule(
                id = "invoices",
                name = "Invoices",
                field = RuleField.Content,
                algorithm = MatchAlgorithm.ANY_WORD,
                pattern = "invoice",
                tags = listOf("invoice"),
                sets = mapOf(FieldKeys.CORRESPONDENT to "ACME", FieldKeys.DOC_TYPE to "Invoice"),
            )

        val PAID =
            AutoMatchRule(
                id = "paid",
                name = "Paid",
                field = RuleField.Content,
                algorithm = MatchAlgorithm.ANY_WORD,
                pattern = "acme",
                tags = listOf("paid"),
                sets = mapOf(FieldKeys.DOC_TYPE to "Receipt"),
                position = 1,
            )
    }
}
