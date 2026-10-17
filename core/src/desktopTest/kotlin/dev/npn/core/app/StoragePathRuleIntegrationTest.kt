package dev.npn.core.app

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

class StoragePathRuleIntegrationTest {
    @Test
    fun importUsesRuleAndUniquifiesCollisions() =
        runTest {
            val rule = StoragePathRule("organized/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                val first =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("one"), "same.pdf")
                        as ImportOutcome.Imported
                val second =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("two"), "same.pdf")
                        as ImportOutcome.Imported

                first.relPath shouldBe "organized/same.pdf"
                second.relPath shouldBe "organized/same-1.pdf"
                rig.exists(first.relPath) shouldBe true
                rig.exists(second.relPath) shouldBe true
            } finally {
                rig.close()
            }
        }

    /** A file the index has not seen yet at the rule's target takes that path too: the import goes beside it. */
    @Test
    fun importGoesBesideAnUnindexedFileAtTheRulesTarget() =
        runTest {
            val rule = StoragePathRule("organized/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                val there = PdfFixtures.embeddedTextPdf("there")
                rig.writePdf("organized/same.pdf", there)

                val imported =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("one"), "same.pdf")
                        as ImportOutcome.Imported

                imported.relPath shouldBe "organized/same-1.pdf"
                rig.fs.readBytes("organized/same.pdf") shouldBe there
            } finally {
                rig.close()
            }
        }

    /**
     * A date pattern on a field the user defined is valid wherever the registry says the field is
     * a date. At import the document has no values yet, so the folder is empty and collapses;
     * judged against the seed types alone, the template failed and the file fell back to the inbox.
     */
    @Test
    fun importExpandsADatePatternOnAUserDefinedDateField() =
        runTest {
            val rule = StoragePathRule("organized/{field:invoiceDate:yyyy}/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                rig.session.fieldRegistry.upsert(
                    FieldDef(
                        "invoiceDate",
                        "Invoice date",
                        FieldType.DATE,
                        showByDefault = false,
                        aiEnabled = false,
                        position = 9,
                    ),
                )
                val imported =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("one"), "scan.pdf")
                        as ImportOutcome.Imported
                imported.relPath shouldBe "organized/scan.pdf"

                rig.session.meta.setField(imported.docId, "invoiceDate", "2026-03-14")
                rig.db
                    .documentDao()
                    .requireByDocId(imported.docId)
                    .relPath shouldBe "organized/2026/scan.pdf"
            } finally {
                rig.close()
            }
        }

    @Test
    fun metadataChangeMovesOnceAndKeepsProjectionCoherent() =
        runTest {
            val rule = StoragePathRule("by-title/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                val imported =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("content"), "scan.pdf")
                        as ImportOutcome.Imported
                rig.session.meta.setField(imported.docId, FieldKeys.TITLE, "Annual report")

                val moved = rig.db.documentDao().requireByDocId(imported.docId)
                moved.relPath shouldBe "by-title/Annual report.pdf"
                moved.fileName shouldBe "Annual report.pdf"
                rig.exists(moved.relPath) shouldBe true
                rig.exists(imported.relPath) shouldBe false

                rig.session.meta.setField(imported.docId, FieldKeys.TITLE, "Annual report")
                rig.db
                    .documentDao()
                    .requireByDocId(imported.docId)
                    .relPath shouldBe moved.relPath
                rig.exists("by-title/Annual report-1.pdf") shouldBe false
            } finally {
                rig.close()
            }
        }

    /**
     * Two title changes in a row, the first one's storage-path follow-up held while it looks for a
     * free target: the second one's follow-up waits for it, then reads the latest title, so the
     * document ends at the path that title gives rather than the one the older title gave.
     */
    @Test
    fun aFollowUpHeldBehindAnotherLeavesThePathOfTheLatestTitle() =
        runTest {
            val held = HeldExists("by-title/Old.pdf")
            val rule = StoragePathRule("by-title/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule, files = held::over)
            try {
                val imported =
                    rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("content"), "scan.pdf")
                        as ImportOutcome.Imported
                val first = async { rig.session.meta.setField(imported.docId, FieldKeys.TITLE, "Old") }
                held.reached.await()
                val second = async { rig.session.meta.setField(imported.docId, FieldKeys.TITLE, "New") }
                val secondFinishedFirst = held.finishesWithin(second, QUIET_MILLIS)
                held.release()
                first.await()
                second.await()

                val doc = rig.db.documentDao().requireByDocId(imported.docId)
                doc.relPath shouldBe "by-title/New.pdf"
                rig.exists("by-title/New.pdf") shouldBe true
                rig.exists("by-title/Old.pdf") shouldBe false
                secondFinishedFirst shouldBe false
            } finally {
                held.release()
                rig.close()
            }
        }

    /**
     * On storage that cannot move a document (a library opened through Android's folder picker), the
     * rule's location is journaled once, for a replica that can carry it out: the document stays where
     * it is, and a later write to it does not journal the same location again.
     */
    @Test
    fun aLocationTheStorageCannotCarryOutIsJournaledOnce() =
        runTest {
            val rule = StoragePathRule("organized/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule, files = ::notRelocating)
            try {
                rig.writePdf("scan.pdf", PdfFixtures.embeddedTextPdf("content"))
                rig.session.rescan()
                val id = rig.docId("scan.pdf")

                rig.session.meta.addTag(listOf(id), "first")
                rig.session.meta.addTag(listOf(id), "second")

                rig.storagePathEvents() shouldContainExactly listOf(id to "organized/scan.pdf")
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
                    .relPath shouldBe "scan.pdf"
            } finally {
                rig.close()
            }
        }

    /** There, a rename the user asks for is refused rather than journaled as if it had happened. */
    @Test
    fun aRenameTheStorageCannotCarryOutIsRefused() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope, files = ::notRelocating)
            try {
                rig.writePdf("scan.pdf", PdfFixtures.embeddedTextPdf("content"))
                rig.session.rescan()
                val id = rig.docId("scan.pdf")
                val objects = rig.journalObjectCount()

                rig.session.docLifecycle.renameFile(id, "invoice.pdf") shouldBe RelocationOutcome.UNSUPPORTED
                rig.session.docLifecycle.moveFile(id, "archive") shouldBe RelocationOutcome.UNSUPPORTED

                rig.journalObjectCount() shouldBe objects
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
                    .relPath shouldBe "scan.pdf"
                rig.exists("scan.pdf") shouldBe true
            } finally {
                rig.close()
            }
        }

    /** [fs] as storage that cannot move a document, as a library opened through Android's folder picker. */
    private fun notRelocating(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override val supportsDocumentRelocation: Boolean get() = false
        }

    /** Every `storagePath` event in the journal, as its document and the location it names. */
    private suspend fun AppSessionRig.storagePathEvents(): List<Pair<String?, String?>> =
        fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            .flatMap { fs.readBytes(it.relPath).decodeToString().lines() }
            .mapNotNull(JournalCodec::decodeLineOrNull)
            .mapNotNull { event ->
                val op = event.op as? JournalOp.PropSet
                if (op != null && op.key == SystemProperty.STORAGE_PATH) event.doc to op.value else null
            }

    /**
     * A library filesystem whose first [LibraryFileSystem.exists] check of [path] says so on [reached],
     * then waits for [release]. [realTime] runs [finishesWithin]'s wait on the wall clock.
     */
    private class HeldExists(
        private val path: String,
        private val realTime: CoroutineDispatcher = Dispatchers.Default,
    ) {
        val reached = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()
        private val armed = AtomicBoolean(true)

        fun release() = gate.complete(Unit)

        /** Whether [job] finishes within [millis] of real time, not the test's virtual time. */
        suspend fun finishesWithin(
            job: Job,
            millis: Long,
        ): Boolean = withContext(realTime) { withTimeoutOrNull(millis) { job.join() } != null }

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun exists(relPath: String): Boolean {
                    if (relPath == path && armed.getAndSet(false)) {
                        reached.complete(Unit)
                        gate.await()
                    }
                    return fs.exists(relPath)
                }
            }
    }

    private companion object {
        /** How long a follow-up that does not wait gets to finish. */
        const val QUIET_MILLIS = 1_000L
    }
}
