package dev.npn.core.index

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.StatementLog
import dev.npn.core.db.isFullFtsScan
import dev.npn.core.db.queryPlan
import dev.npn.core.db.recordingDatabase
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

/**
 * FTS5 can find a row only by MATCH or by rowid; a `docId = ?` filter reads the whole index, which
 * holds every document's extracted text. None of the document-scoped reads and writes may do that.
 */
class FtsQueryPlanTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun theScanCheckCatchesALookupByDocId() =
        withDatabase { db, _ ->
            db.queryPlan("SELECT content FROM fts_documents WHERE docId = ?").any(::isFullFtsScan) shouldBe true
            db.queryPlan("SELECT docId FROM fts_documents WHERE fts_documents MATCH ?").any(::isFullFtsScan) shouldBe
                false
        }

    @Test
    fun documentScopedFtsCallsAddressRowsByRowid() =
        withDatabase { db, log ->
            val fts = db.ftsDao()
            fts.replaceDocuments(listOf(row(a, "alpha"), row(b, "bravo")))
            fts.replaceDocument(row(a, "alpha again"))
            fts.contentForDoc(a) shouldBe "alpha again"
            fts.contentsForDocs(listOf(a, b)).map { it.content }.sortedBy { it } shouldContainExactly
                listOf("alpha again", "bravo")
            fts.indexedDocIds(listOf(a, b, "c".repeat(64))).sorted() shouldContainExactly listOf(a, b)

            noFtsStatementScans(db, log)
        }

    @Test
    fun aRescanAndACommitNeverScanTheIndex() =
        runTest {
            val log = StatementLog()
            val rig = IndexRig.create(log)
            try {
                rig.writePdf("a.pdf", "alpha")
                rig.writePdf("b.pdf", "bravo")
                rig.rescan()
                val docId = rig.db.documentDao().rowAtPath("a.pdf")!!.docId
                val coordinator = rig.coordinator(rig.writer("dev-main"))
                coordinator.refreshState()
                rig.clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "invoice")))
                rig.db.ftsDao().searchDocIds("invoice") shouldContainExactly listOf(docId)

                noFtsStatementScans(rig.db, log)
            } finally {
                rig.close()
            }
        }

    private suspend fun noFtsStatementScans(
        db: NpnDatabase,
        log: StatementLog,
    ) {
        val ftsStatements = log.statements.filter { "fts_documents" in it }.distinct()
        ftsStatements.shouldNotBeEmpty()
        val scans = ftsStatements.filter { sql -> db.queryPlan(sql).any(::isFullFtsScan) }
        scans.shouldBeEmpty()
    }

    private fun row(
        docId: String,
        content: String,
    ) = FtsRows.row(docId, "$docId.pdf", tags = emptyList(), fields = emptyMap(), content = content)

    private fun withDatabase(block: suspend (NpnDatabase, StatementLog) -> Unit) =
        runTest {
            val dir = Files.createTempDirectory("npn-fts-plan").toFile()
            val log = StatementLog()
            val db = recordingDatabase(dir.resolve("index.db"), log)
            try {
                block(db, log)
            } finally {
                db.close()
                dir.deleteRecursively()
            }
        }
}
