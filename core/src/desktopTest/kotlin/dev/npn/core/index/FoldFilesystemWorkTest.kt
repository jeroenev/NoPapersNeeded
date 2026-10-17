package dev.npn.core.index

import dev.npn.core.db.StatementLog
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.recordingDatabase
import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a fold costs on the filesystem. Locating a document and recording where it was seen are
 * filesystem work: they run after the fold's transaction, read their baselines in bulk, and take a
 * rescan's hashes instead of hashing every PDF again.
 */
class FoldFilesystemWorkTest {
    private val log = StatementLog()
    private val rig = IndexRig.create(statements = log, files = ::WatchedFileSystem)
    private val libRoot = rig.libRoot
    private val fs = (rig.fs as WatchedFileSystem).also { it.db = rig.db }
    private val db = rig.db
    private val indexer = rig.indexer
    private val accumulator = rig.accumulator
    private val coordinator = rig.coordinator()

    @AfterTest
    fun teardown() = rig.close()

    private fun writePdfs(count: Int) {
        repeat(count) { i -> rig.writePdf("doc-$i.pdf", "document $i") }
    }

    private fun baselineReadsOneByOne(): Int = log.statements.count { it.startsWith(BASELINE_BY_KEY) }

    @Test
    fun aFirstScanHashesEveryPdfOnceAndTouchesNoFileInsideATransaction() =
        runTest {
            writePdfs(DOCS)

            coordinator.rescan()

            fs.calls("sha256").size shouldBe DOCS
            fs.callsInsideTransactions().shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun anUnchangedRescanHashesNothingAndReadsItsBaselinesInBulk() =
        runTest {
            writePdfs(DOCS)
            coordinator.rescan()
            fs.clear()
            log.clear()

            coordinator.rescan()

            fs.calls("sha256").shouldBeEmpty()
            fs.calls("exists").shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun reprojectingTheWholeLibraryTouchesNoPdf() =
        runTest {
            writePdfs(DOCS)
            coordinator.rescan()
            fs.clear()
            log.clear()

            indexer.refreshState(accumulator.loadState())

            fs.calls("sha256").shouldBeEmpty()
            fs.calls("exists").shouldBeEmpty()
            fs.calls("stat").shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun aRenameMovesTheFileOnceItsCommitIsWritten() =
        runTest {
            writePdfs(1)
            coordinator.rescan()
            val docId = checkNotNull(db.documentDao().rowAtPath("doc-0.pdf")).docId
            fs.clear()

            coordinator.commitAndApply(
                listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, "archive/renamed-statement.pdf")),
            )

            fs.callsInsideTransactions().shouldBeEmpty()
            Files.exists(libRoot.resolve("archive/renamed-statement.pdf")) shouldBe true
            Files.exists(libRoot.resolve("doc-0.pdf")) shouldBe false
            val row = checkNotNull(db.documentDao().getByDocId(docId))
            row.relPath shouldBe "archive/renamed-statement.pdf"
            row.fileName shouldBe "renamed-statement.pdf"
            db.ftsDao().searchDocIds("renamed") shouldContainExactly listOf(docId)
        }

    /**
     * A path written for a relink predecessor moves the live document's file: the relink target
     * shares the file with the id that was renamed away, so the intent written there is its own.
     */
    @Test
    fun aRenameWrittenForARelinkPredecessorMovesTheLiveDocumentsFile() =
        runTest {
            writePdfs(1)
            coordinator.rescan()
            val docId = checkNotNull(db.documentDao().rowAtPath("doc-0.pdf")).docId
            val older = "a".repeat(64)
            fs.clear()

            coordinator.commitAndApply(
                listOf(
                    PendingOp.propSet(older, SystemProperty.STORAGE_PATH, "archive/renamed.pdf"),
                    PendingOp.relink(older, docId),
                ),
            )

            Files.exists(libRoot.resolve("archive/renamed.pdf")) shouldBe true
            Files.exists(libRoot.resolve("doc-0.pdf")) shouldBe false
            db.documentDao().getByDocId(docId)?.relPath shouldBe "archive/renamed.pdf"
        }

    private companion object {
        const val DOCS = 12
        const val BASELINE_BY_KEY = "SELECT value FROM kv WHERE key = ?"
    }
}
