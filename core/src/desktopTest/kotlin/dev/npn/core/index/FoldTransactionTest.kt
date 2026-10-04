package dev.npn.core.index

import dev.npn.core.db.StatementLog
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A fold writes a document's row, tags, fields and search row together: on every entry point the
 * reconcile writes and the fold run as one transaction, so no reader sees a row before its tags,
 * and the number of transactions does not grow with the library.
 */
class FoldTransactionTest {
    /** The top-level transactions in [statements] that wrote, each as the statements it ran. */
    private fun writeTransactions(statements: List<String>): List<List<String>> {
        val blocks = ArrayList<List<String>>()
        var open: ArrayList<String>? = null
        for (sql in statements) {
            when {
                sql.startsWith("BEGIN") -> {
                    open = ArrayList()
                }

                sql.startsWith("END TRANSACTION") || sql.startsWith("ROLLBACK") -> {
                    open?.takeIf { block -> block.any(::isWrite) }?.let(blocks::add)
                    open = null
                }

                else -> {
                    open?.add(sql)
                }
            }
        }
        return blocks
    }

    /** A write to the index's own tables; Room's invalidation bookkeeping does not count. */
    private fun isWrite(sql: String): Boolean =
        WRITES.any { sql.startsWith(it) } && "room_table_modification_log" !in sql

    /** Writes to the projection tables that ran outside any transaction. */
    private fun projectionWritesOutsideTransactions(statements: List<String>): List<String> {
        var depth = 0
        val outside = ArrayList<String>()
        for (sql in statements) {
            when {
                sql.startsWith("BEGIN") -> depth++

                // The log may have been cleared inside a transaction Room's invalidation tracker
                // had open, leaving its END without a BEGIN.
                sql.startsWith("END TRANSACTION") || sql.startsWith("ROLLBACK") -> depth = maxOf(0, depth - 1)

                depth == 0 && isWrite(sql) && PROJECTION_TABLES.any { it in sql } -> outside += sql
            }
        }
        return outside
    }

    /**
     * The savepoints Room opened for DAO calls inside a transaction: one per call, so one per row
     * when rows are written one call at a time. The search index is left out: it is written by
     * rowid, one statement per document by design.
     */
    private fun savepointsOutsideTheSearchIndex(statements: List<String>): Int =
        statements.indices.count { i ->
            statements[i].startsWith("SAVEPOINT") &&
                statements
                    .asSequence()
                    .drop(i + 1)
                    .firstOrNull { !it.startsWith("SAVEPOINT") }
                    ?.contains("fts_") !=
                true
        }

    /** A library of [docs] PDFs, each tagged and titled by another device, and the rig that indexes it. */
    private fun library(
        docs: Int,
        log: StatementLog,
    ): Pair<IndexRig, List<PendingOp>> {
        val rig = IndexRig.create(log)
        val ops = ArrayList<PendingOp>()
        repeat(docs) { i ->
            val content = "document $i"
            rig.writePdf("doc-$i.pdf", content)
            val docId = sha256Bytes(content.encodeToByteArray())
            ops += PendingOp.tagAdd(docId, "tag-$i")
            ops += PendingOp.fieldSet(docId, FieldKeys.TITLE, "Title $i")
        }
        return rig to ops
    }

    /** What a fresh index's first rescan of [docs] annotated PDFs ran. */
    private suspend fun firstScan(docs: Int): List<String> {
        val log = StatementLog()
        val (rig, ops) = library(docs, log)
        try {
            rig.writer("dev-remote").commit(ops)
            val state = rig.accumulator.reconcile().state ?: rig.accumulator.loadState()
            log.clear()
            rig.indexer.rescan(metadataState = state)
            return log.statements
        } finally {
            rig.close()
        }
    }

    /** What reprojecting a library of [docs] PDFs ran, after another device annotated every one. */
    private suspend fun reprojection(docs: Int): List<String> {
        val log = StatementLog()
        val (rig, ops) = library(docs, log)
        try {
            rig.rescan()
            rig.clock.advance()
            rig.writer("dev-remote").commit(ops)
            val state = rig.accumulator.reconcile().state ?: rig.accumulator.loadState()
            log.clear()
            rig.indexer.refreshState(state)
            return log.statements
        } finally {
            rig.close()
        }
    }

    @Test
    fun aFirstScanWritesInTheSameFewTransactionsWhateverTheLibrarySize() =
        runTest {
            val small = firstScan(SMALL)
            val large = firstScan(LARGE)

            projectionWritesOutsideTransactions(large).shouldBeEmpty()
            // One for the reconcile writes and the fold, one for the move baselines after it.
            writeTransactions(large).size shouldBe 2
            writeTransactions(large).size shouldBe writeTransactions(small).size
            savepointsOutsideTheSearchIndex(large) shouldBe savepointsOutsideTheSearchIndex(small)
        }

    @Test
    fun aReprojectionWritesRowsTagsAndFieldsInOneTransaction() =
        runTest {
            val small = reprojection(SMALL)
            val large = reprojection(LARGE)

            projectionWritesOutsideTransactions(large).shouldBeEmpty()
            writeTransactions(large).size shouldBe 1
            savepointsOutsideTheSearchIndex(large) shouldBe savepointsOutsideTheSearchIndex(small)
        }

    private companion object {
        const val SMALL = 3
        const val LARGE = 30
        val WRITES = listOf("INSERT", "UPDATE", "DELETE")
        val PROJECTION_TABLES = listOf("documents", "document_tags", "document_fields", "fts_", "`tags`", " tags ")
    }
}
