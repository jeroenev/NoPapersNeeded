package dev.npn.core.app

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.npnDatabase
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.DocProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Integration tests for the document-history read path: the DAO query, the repository
 * decode/ordering, and the contract surface exposed to the UI layer.
 */
class DocumentRepositoryHistoryTest {

    private lateinit var dbDir: File
    private lateinit var db: dev.npn.core.db.NpnDatabase

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-history-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    @Test
    fun historyReturnsOnlyTargetDocumentEventsOrderedByHlc() = runTest {
        val docId = "a".repeat(64)
        val other = "b".repeat(64)
        val dao = db.metadataProjectionDao()

        // Insert events out of causal order, including one for a different document.
        val events = listOf(
            event(docId, seq = 1, Hlc(100, 0, "dev-a"), JournalOp.TagAdd("invoice")),
            event(docId, seq = 2, Hlc(50, 0, "dev-a"), JournalOp.PropSet(DocProperty.TITLE, "Title")),
            event(other, seq = 3, Hlc(10, 0, "dev-a"), JournalOp.TagAdd("other")),
            event(docId, seq = 4, Hlc(100, 1, "dev-a"), JournalOp.DocTrash("a.pdf")),
        )
        val source = MetadataSourceEntity("src", 1, "fp", null, "applied")
        dao.ingestSource(source, events, transactionActor = null)

        val repo = DocumentRepositoryImpl(db)
        val history = repo.history(docId)

        history.map { it.event?.op } shouldContainExactly listOf(
            JournalOp.PropSet(DocProperty.TITLE, "Title"),
            JournalOp.TagAdd("invoice"),
            JournalOp.DocTrash("a.pdf"),
        )
    }

    @Test
    fun historyRetainsUndecodableRowsAndOrdersByFullHlc() = runTest {
        val docId = "c".repeat(64)
        val dao = db.metadataProjectionDao()
        val valid = event(docId, 2, Hlc(100, 0, "dev-z"), JournalOp.TagAdd("later"))
        val broken = MetadataEventEntity(
            deviceId = "dev-a",
            sequence = 1,
            canonicalJson = "not json",
            hlcPhysical = 100,
            hlcCounter = 0,
            hlcDevice = "dev-a",
            documentId = docId,
            operation = "future-op",
        )
        dao.ingestSource(MetadataSourceEntity("src", 1, "fp", null, "applied"), listOf(valid, broken))

        val history = DocumentRepositoryImpl(db).history(docId)

        history.map { it.deviceId } shouldContainExactly listOf("dev-a", "dev-z")
        history.first().event shouldBe null
        history.first().operation shouldBe "future-op"
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
