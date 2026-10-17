package dev.npn.core.app

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Exercise combinations of lifecycle operations against a model independent of the journal fold.
 * The model has an import bring a deleted document back for good, on every device, which takes a
 * `doc-undelete`: both sessions write it, as a build with [JournalOp.DocUndelete.WRITTEN] on will.
 */
class DocumentLifecycleSequenceTest {
    @Test
    fun lifecycleSequencesPreserveIdentityAndMetadataAcrossDevicesAndIndexRebuilds() =
        runTest {
            for (seed in SEEDS) {
                AppSessionRig.create(backgroundScope, writeUndelete = true).use { rig ->
                    val (peer, _) = rig.secondSession(backgroundScope, writeUndelete = true)
                    val expected =
                        List(DOCUMENTS) { number ->
                            ExpectedDocument(PdfFixtures.embeddedTextPdf("document $number seed $seed"))
                        }
                    for (doc in expected) import(rig.session, doc)
                    val random = Random(seed)
                    val trace = mutableListOf<String>()
                    for (operation in REQUIRED_SEQUENCE) {
                        val doc = expected.first()
                        trace += "$operation(${doc.id.take(8)})"
                        withClue("seed=$seed; operations=${trace.joinToString()}") {
                            apply(operation, rig.session, rig, doc, trace.size)
                            for (replica in listOf(rig.session, peer)) {
                                replica.ingest.rescan()
                                assertLibrary(replica, rig, expected)
                            }
                        }
                    }
                    // Every operation is offered each cycle; the order and eligible document vary.
                    repeat(CYCLES) { _ ->
                        for (operation in Operation.entries.shuffled(random)) {
                            val session = if (random.nextBoolean()) rig.session else peer
                            session.ingest.rescan()
                            val candidates = expected.filter { operation.accepts(it.state) }
                            if (candidates.isEmpty()) continue
                            val doc = candidates.random(random)
                            trace += "$operation(${doc.id.take(8)})"
                            withClue("seed=$seed; operations=${trace.joinToString()}") {
                                apply(operation, session, rig, doc, trace.size)
                                for (replica in listOf(rig.session, peer)) {
                                    replica.ingest.rescan()
                                    assertLibrary(replica, rig, expected)
                                }
                            }
                        }
                    }
                    // Missing/hidden index rows are local observations. Bring every document back,
                    // checking its metadata, before comparing what a fresh device can reconstruct.
                    withClue("seed=$seed; restore before rebuild; operations=${trace.joinToString()}") {
                        for (doc in expected.filter { it.state != State.LIVE }) import(rig.session, doc)
                        rig.session.rescan()
                        assertLibrary(rig.session, rig, expected)
                    }
                    // A new installation and a dropped index reconstruct every live document.
                    val data = File(rig.dbDir, "rebuilt")
                    repeat(2) { attempt ->
                        if (attempt > 0) {
                            data.listFiles { file -> file.name.startsWith("index.db") }.orEmpty().forEach {
                                check(it.delete())
                            }
                        }
                        openSession(backgroundScope, rig.libRoot, data).use { session, _ ->
                            session.ingest.rescan()
                            withClue("seed=$seed; rebuild=$attempt; operations=${trace.joinToString()}") {
                                assertLibrary(session, rig, expected)
                            }
                        }
                    }
                }
            }
        }

    private suspend fun apply(
        operation: Operation,
        session: LibrarySession,
        rig: AppSessionRig,
        doc: ExpectedDocument,
        step: Int,
    ) {
        when (operation) {
            Operation.IMPORT -> {
                import(session, doc)
            }

            Operation.TRASH -> {
                session.docLifecycle.trashDoc(doc.id).shouldNotBeNull()
                doc.state = State.TRASHED
            }

            Operation.RESTORE -> {
                session.docLifecycle.restoreDoc(doc.id)
                doc.state = State.LIVE
            }

            Operation.DELETE -> {
                session.docLifecycle.deleteDocPermanently(doc.id) shouldBe PermanentDeleteOutcome.DELETED
                doc.state = State.DELETED
            }

            Operation.RENAME -> {
                session.docLifecycle.renameFile(doc.id, "scan-$step.pdf") shouldBe
                    RelocationOutcome.MOVED
            }

            Operation.MOVE -> {
                session.docLifecycle.moveFile(doc.id, "folder-$step") shouldBe RelocationOutcome.MOVED
            }

            Operation.EDIT -> {
                val value = "title $step"
                val tag = "tag-${step % DOCUMENTS}"
                session.meta.applyEdits(listOf(doc.id), fields = mapOf(FieldKeys.TITLE to value), addTags = listOf(tag))
                doc.fields[FieldKeys.TITLE] = value
                doc.tags += tag
            }

            Operation.CLEAR -> {
                session.meta.setField(doc.id, FieldKeys.TITLE, null)
                doc.fields.remove(FieldKeys.TITLE)
                for (tag in doc.tags) session.meta.removeTag(listOf(doc.id), tag)
                doc.tags.clear()
            }

            Operation.MISSING -> {
                rig.fs.delete(
                    session.documents
                        .observeDoc(doc.id)
                        .first()
                        .shouldNotBeNull()
                        .doc.relPath,
                )
                doc.state = State.MISSING
            }

            Operation.BAKE -> {
                val path =
                    session.documents
                        .observeDoc(doc.id)
                        .first()
                        .shouldNotBeNull()
                        .doc.relPath
                session.content.bakeInPlace(doc.id) shouldBe BakeOutcome.Baked
                val bytes = rig.fs.readBytes(path)
                sha256Bytes(bytes) shouldNotBe doc.id
                doc.bytes = bytes
                doc.id = sha256Bytes(bytes)
            }
        }
    }

    private suspend fun import(
        session: LibrarySession,
        doc: ExpectedDocument,
    ) {
        val outcome = session.ingest.importPdfBytes(doc.bytes, "scan.pdf")
        if (doc.state == State.LIVE) {
            (outcome as ImportOutcome.Duplicate).existingDocId shouldBe doc.id
        } else {
            (outcome as ImportOutcome.Imported).docId shouldBe doc.id
        }
        doc.state = State.LIVE
    }

    private suspend fun assertLibrary(
        session: LibrarySession,
        rig: AppSessionRig,
        expected: List<ExpectedDocument>,
    ) {
        val live = session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
        live.map { it.doc.docId }.toSet() shouldBe expected.filter { it.state == State.LIVE }.map { it.id }.toSet()
        live.map { it.doc.relPath }.distinct().size shouldBe live.size
        session.documents
            .observeTrashed()
            .first()
            .map { it.doc.docId }
            .toSet() shouldBe
            expected.filter { it.state == State.TRASHED }.map { it.id }.toSet()
        session.documents
            .observeMissing()
            .first()
            .map { it.docId }
            .toSet() shouldBe
            expected.filter { it.state == State.MISSING }.map { it.id }.toSet()
        session.documents
            .observeDuplicates()
            .first()
            .shouldBeEmpty()
        for (item in live) {
            val model = expected.single { it.id == item.doc.docId }
            rig.fs.sha256(item.doc.relPath) shouldBe model.id
            item.doc.fields shouldBe model.fields
            item.tags.toSet() shouldBe model.tags
        }
    }

    private class ExpectedDocument(
        var bytes: ByteArray,
    ) {
        var id = sha256Bytes(bytes)
        var state = State.DELETED
        val fields = mutableMapOf<String, String>()
        val tags = mutableSetOf<String>()
    }

    private enum class State { LIVE, TRASHED, DELETED, MISSING }

    private enum class Operation {
        IMPORT,
        TRASH,
        RESTORE,
        DELETE,
        RENAME,
        MOVE,
        EDIT,
        CLEAR,
        MISSING,
        BAKE,
        ;

        fun accepts(state: State): Boolean =
            when (this) {
                IMPORT -> true
                RESTORE, DELETE -> state == State.TRASHED
                else -> state == State.LIVE
            }
    }

    private companion object {
        const val DOCUMENTS = 3
        const val CYCLES = 4
        val SEEDS = listOf(7, 41, 93, 2026, 8107, 65537)
        val REQUIRED_SEQUENCE =
            listOf(
                Operation.EDIT,
                Operation.TRASH,
                Operation.DELETE,
                Operation.IMPORT,
                Operation.MOVE,
                Operation.RENAME,
                Operation.BAKE,
                Operation.TRASH,
                Operation.RESTORE,
                Operation.MISSING,
                Operation.IMPORT,
                Operation.CLEAR,
            )
    }
}
