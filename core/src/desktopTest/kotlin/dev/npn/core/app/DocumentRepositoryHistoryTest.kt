package dev.npn.core.app

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Integration tests for the document-history read path: the DAO query, the repository
 * decode/ordering, and the contract surface exposed to the UI layer.
 */
class DocumentRepositoryHistoryTest {
    private val temp = TempIndexDb("npn-history-test")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun historyReturnsOnlyTargetDocumentEventsOrderedByHlc() =
        runTest {
            val docId = "a".repeat(64)
            val other = "b".repeat(64)
            val dao = db.metadataProjectionDao()

            // Insert events out of causal order, including one for a different document.
            val events =
                listOf(
                    event(docId, seq = 1, Hlc(100, 0, "dev-a"), JournalOp.TagAdd("invoice")),
                    event(docId, seq = 2, Hlc(50, 0, "dev-a"), JournalOp.FieldSet("title", "Title")),
                    event(other, seq = 3, Hlc(10, 0, "dev-a"), JournalOp.TagAdd("other")),
                    event(docId, seq = 4, Hlc(100, 1, "dev-a"), JournalOp.DocTrash("a.pdf")),
                )
            val source =
                MetadataSourceEntity(
                    "src",
                    SourceKind.SNAPSHOT,
                    SourceFingerprint("fp"),
                    null,
                    SourceStatus.APPLIED,
                )
            dao.ingest(source, events)

            val repo = DocumentRepositoryImpl(db)
            val history = repo.history(docId)

            history.map { it.entry } shouldContainExactly
                listOf(
                    HistoryEntry.FieldSet("title", "Title"),
                    HistoryEntry.TagAdded("invoice"),
                    HistoryEntry.Trashed("a.pdf"),
                )
            history.map { it.timeMillis } shouldContainExactly listOf(50L, 100L, 100L)
        }

    @Test
    fun historyRetainsUndecodableRowsAndOrdersByFullHlc() =
        runTest {
            val docId = "c".repeat(64)
            val dao = db.metadataProjectionDao()
            val valid = event(docId, 2, Hlc(100, 0, "dev-z"), JournalOp.TagAdd("later"))
            val broken =
                MetadataEventEntity(
                    deviceId = "dev-a",
                    sequence = 1,
                    canonicalJson = "not json",
                    hlcPhysical = 100,
                    hlcCounter = 0,
                    hlcDevice = "dev-a",
                    documentId = docId,
                    operation = "future-op",
                )
            dao.ingest(
                MetadataSourceEntity(
                    "src",
                    SourceKind.SNAPSHOT,
                    SourceFingerprint("fp"),
                    null,
                    SourceStatus.APPLIED,
                ),
                listOf(valid, broken),
            )

            val history = DocumentRepositoryImpl(db).history(docId)

            history.map { it.deviceId } shouldContainExactly listOf("dev-a", "dev-z")
            history.first().entry shouldBe HistoryEntry.Undecodable("future-op")
        }

    /** Every journal op a document's history can hold, as the domain entry the UI is given. */
    @Test
    fun everyDocumentOpBecomesItsDomainEntry() {
        val newDoc = "b".repeat(64)
        listOf(
            JournalOp.TagAdd("invoice") to HistoryEntry.TagAdded("invoice"),
            JournalOp.TagRemove("draft", emptyList()) to HistoryEntry.TagRemoved("draft"),
            JournalOp.FieldSet("amount", "42") to HistoryEntry.FieldSet("amount", "42"),
            JournalOp.FieldSet("amount", null) to HistoryEntry.FieldSet("amount", null),
            JournalOp.PropSet(SystemProperty.REVIEWED, "true") to HistoryEntry.Reviewed(true),
            JournalOp.PropSet(SystemProperty.REVIEWED, "false") to HistoryEntry.Reviewed(false),
            JournalOp.PropSet(SystemProperty.REVIEWED, null) to HistoryEntry.Reviewed(null),
            JournalOp.PropSet(SystemProperty.REVIEWED, "maybe") to HistoryEntry.ReviewUnreadable("maybe"),
            JournalOp.PropSet(SystemProperty.OCR_ENGINE, "tesseract") to
                HistoryEntry.OcrEnginePinned(OcrEngineRef.Known(OcrEngineId.TESSERACT)),
            JournalOp.PropSet(SystemProperty.OCR_ENGINE, "future") to
                HistoryEntry.OcrEnginePinned(OcrEngineRef.Unknown("future")),
            JournalOp.PropSet(SystemProperty.OCR_ENGINE, null) to HistoryEntry.OcrEnginePinned(null),
            JournalOp.PropSet(SystemProperty.STORAGE_PATH, "a/b.pdf") to HistoryEntry.StoragePathSet("a/b.pdf"),
            JournalOp.DocTrash("orig.pdf") to HistoryEntry.Trashed("orig.pdf"),
            JournalOp.DocRestore to HistoryEntry.Restored,
            JournalOp.DocUndelete to HistoryEntry.Restored,
            JournalOp.DocDelete to HistoryEntry.Deleted,
            JournalOp.Relink(newDoc) to HistoryEntry.Relinked(newDoc),
            JournalOp.Unknown("future-op") to HistoryEntry.Unknown("future-op", null),
        ).forEach { (op, entry) -> HistoryEntry.of(op) shouldBe entry }
    }

    private fun event(
        docId: String,
        seq: Long,
        hlc: Hlc,
        op: JournalOp,
    ): MetadataEventEntity {
        val je = JournalEvent(1, seq, hlc, hlc.deviceId, docId, op)
        return MetadataEventEntity(
            deviceId = hlc.deviceId,
            sequence = seq,
            canonicalJson = JournalCodec.encode(je),
            hlcPhysical = hlc.physicalMillis,
            hlcCounter = hlc.counter,
            hlcDevice = hlc.deviceId,
            documentId = docId,
            operation = op.wire,
        )
    }
}
