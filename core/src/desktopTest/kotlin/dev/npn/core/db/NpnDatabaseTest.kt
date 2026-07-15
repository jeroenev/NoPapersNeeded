package dev.npn.core.db

import androidx.room3.RoomRawQuery
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Integration tests for the local index database, run against the real bundled SQLite
 * driver on the JVM (desktop) target. Verifies Room 3 wiring and, critically, that the
 * @Fts5 entity works with BundledSQLiteDriver on JVM (PLAN.md spike 0.2).
 */
class NpnDatabaseTest {

    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    private fun doc(
        id: String,
        relPath: String = "$id.pdf",
        status: String = "inbox",
        correspondent: String? = null,
        docType: String? = null,
        docDate: String? = null,
        trashed: Boolean = false,
        deleted: Boolean = false,
        missing: Boolean = false,
    ) = DocumentEntity(
        docId = id,
        relPath = relPath,
        fileName = relPath.substringAfterLast('/'),
        size = 100,
        mtime = 1000,
        addedAt = 1000,
        status = status,
        correspondent = correspondent,
        docType = docType,
        docDate = docDate,
        trashed = trashed,
        deleted = deleted,
        missing = missing,
    )

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-db-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    @Test
    fun documentUpsertAndLookup() = runTest {
        val dao = db.documentDao()
        dao.upsert(doc("a".repeat(64)))
        dao.getByDocId("a".repeat(64))!!.fileName shouldBe "${"a".repeat(64)}.pdf"
        dao.getByPath("${"a".repeat(64)}.pdf")!!.docId shouldBe "a".repeat(64)

        // upsert updates in place
        dao.upsert(doc("a".repeat(64), status = "filed"))
        dao.getByDocId("a".repeat(64))!!.status shouldBe "filed"
    }

    @Test
    fun observeAllExcludesTrashedDeletedMissing() = runTest {
        val dao = db.documentDao()
        dao.upsertAll(
            listOf(
                doc("a".repeat(64)),
                doc("b".repeat(64), relPath = "b.pdf", trashed = true),
                doc("c".repeat(64), relPath = "c.pdf", deleted = true),
                doc("d".repeat(64), relPath = "d.pdf", missing = true),
            ),
        )
        dao.observeAll().first().map { it.docId } shouldContainExactly listOf("a".repeat(64))
        dao.observeTrashed().first().map { it.docId } shouldContainExactly listOf("b".repeat(64))
        dao.observeMissing().first().map { it.docId } shouldContainExactly listOf("d".repeat(64))
    }

    @Test
    fun statusFilterAndCounts() = runTest {
        val dao = db.documentDao()
        dao.upsertAll(
            listOf(
                doc("a".repeat(64), status = "inbox"),
                doc("b".repeat(64), relPath = "b.pdf", status = "inbox"),
                doc("c".repeat(64), relPath = "c.pdf", status = "filed"),
            ),
        )
        dao.observeByStatus("inbox").first().map { it.docId }
            .shouldContainExactlyInAnyOrder("a".repeat(64), "b".repeat(64))
        dao.countInbox().first() shouldBe 2
        dao.countTrash().first() shouldBe 0
    }

    @Test
    fun untaggedQueryAndTagCounts() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val tagged = "a".repeat(64)
        val untagged = "b".repeat(64)
        docDao.upsertAll(listOf(doc(tagged), doc(untagged, relPath = "b.pdf")))
        tagDao.upsert(TagEntity("invoice", color = "#ff0000"))
        tagDao.upsert(TagEntity("empty"))
        tagDao.setForDocument(tagged, listOf("invoice"))

        docDao.observeUntagged().first().map { it.docId } shouldContainExactly listOf(untagged)
        docDao.countUntagged().first() shouldBe 1

        val counts = tagDao.observeAllWithCounts().first().associateBy { it.name }
        counts["invoice"]!!.count shouldBe 1
        counts["invoice"]!!.color shouldBe "#ff0000"
        counts["empty"]!!.count shouldBe 0
    }

    @Test
    fun setForDocumentReplacesTags() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val id = "a".repeat(64)
        docDao.upsert(doc(id))
        tagDao.setForDocument(id, listOf("x", "y"))
        tagDao.tagsForDocument(id) shouldContainExactly listOf("x", "y")
        tagDao.setForDocument(id, listOf("y", "z"))
        tagDao.tagsForDocument(id) shouldContainExactly listOf("y", "z")
    }

    @Test
    fun tagRenameMovesDocumentTags() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val id = "a".repeat(64)
        docDao.upsert(doc(id))
        tagDao.upsert(TagEntity("old"))
        tagDao.setForDocument(id, listOf("old"))
        tagDao.rename("old", "new")
        tagDao.tagsForDocument(id) shouldContainExactly listOf("new")
        tagDao.observeAllWithCounts().first().map { it.name } shouldContainExactly listOf("new")
    }

    @Test
    fun addAndRemoveTagBulk() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        docDao.upsertAll(listOf(doc(a), doc(b, relPath = "b.pdf")))
        tagDao.addTagToDocs("shared", listOf(a, b))
        tagDao.tagsForDocument(a) shouldContainExactly listOf("shared")
        tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
        tagDao.removeTagFromDocs("shared", listOf(a))
        tagDao.tagsForDocument(a) shouldBe emptyList()
        tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
    }

    @Test
    fun allPathsAndMetaProjection() = runTest {
        val dao = db.documentDao()
        dao.upsertAll(
            listOf(
                doc("a".repeat(64), relPath = "docs/a.pdf"),
                doc("b".repeat(64), relPath = "docs/b.pdf", deleted = true),
            ),
        )
        val metas = dao.allPathsAndMeta()
        metas.map { it.relPath } shouldContainExactly listOf("docs/a.pdf")
        metas.first().size shouldBe 100L
    }

    // --- FTS5: the key spike ---

    @Test
    fun ftsMatchReturnsRightDocIds() = runTest {
        val fts = db.ftsDao()
        fts.replaceDocument(
            "a".repeat(64), "invoice.pdf", "Electricity invoice",
            "total amount due for electricity usage", "utilities", "acme", "invoice",
        )
        fts.replaceDocument(
            "b".repeat(64), "recipe.pdf", "Pasta recipe",
            "boil water add pasta and salt", "cooking", null, "note",
        )
        fts.searchDocIds("electricity") shouldContainExactly listOf("a".repeat(64))
        fts.searchDocIds("pasta") shouldContainExactly listOf("b".repeat(64))
    }

    @Test
    fun ftsSnippetHighlightsFragment() = runTest {
        val fts = db.ftsDao()
        fts.replaceDocument(
            "a".repeat(64), "invoice.pdf", "Electricity invoice",
            "the total amount due for your electricity usage this month", "utilities", "acme", "invoice",
        )
        val hits = fts.search("electricity")
        hits.size shouldBe 1
        hits.first().docId shouldBe "a".repeat(64)
        // snippet() wraps the match in the [ ] markers we passed (original case preserved).
        hits.first().snippet.lowercase() shouldContain "[electricity]"
    }

    @Test
    fun ftsPrefixSearch() = runTest {
        val fts = db.ftsDao()
        fts.replaceDocument(
            "a".repeat(64), "doc.pdf", "title",
            "electrification of the grid", null, null, null,
        )
        fts.searchDocIds("electr*") shouldContainExactly listOf("a".repeat(64))
        fts.searchDocIds("electr").shouldBe(emptyList()) // no prefix operator, no exact token match
    }

    @Test
    fun ftsReplaceDocumentUpdatesResults() = runTest {
        val fts = db.ftsDao()
        val id = "a".repeat(64)
        fts.replaceDocument(id, "doc.pdf", "t", "aardvark content", null, null, null)
        fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
        // re-index same docId with new content; old term must no longer match, and there
        // must be no duplicate row for the new term.
        fts.replaceDocument(id, "doc.pdf", "t", "buffalo content", null, null, null)
        fts.searchDocIds("aardvark") shouldBe emptyList()
        fts.searchDocIds("buffalo") shouldContainExactly listOf(id)
    }

    @Test
    fun ftsSearchAcrossTagsAndCorrespondent() = runTest {
        val fts = db.ftsDao()
        fts.replaceDocument(
            "a".repeat(64), "doc.pdf", "title", "body text",
            tags = "taxes finance", correspondent = "cityhall", docType = "letter",
        )
        fts.searchDocIds("taxes") shouldContainExactly listOf("a".repeat(64))
        fts.searchDocIds("cityhall") shouldContainExactly listOf("a".repeat(64))
        fts.searchDocIds("letter") shouldContainExactly listOf("a".repeat(64))
    }

    // --- RawQuery path ---

    @Test
    fun rawQueryFilterByStatusAndTagJoin() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        docDao.upsertAll(
            listOf(
                doc(a, status = "inbox"),
                doc(b, relPath = "b.pdf", status = "inbox"),
                doc(c, relPath = "c.pdf", status = "filed"),
            ),
        )
        tagDao.addTagToDocs("invoice", listOf(a, c))

        // Upper-layer-built SQL: inbox docs that carry the 'invoice' tag -> only `a`.
        val query = RoomRawQuery(
            sql = "SELECT d.* FROM documents d " +
                "JOIN document_tags t ON d.docId = t.docId " +
                "WHERE d.status = ? AND t.tag = ? ORDER BY d.addedAt DESC",
            onBindStatement = {
                it.bindText(1, "inbox")
                it.bindText(2, "invoice")
            },
        )
        db.documentDao().observeLibraryRaw(query).first().map { it.docId } shouldContainExactly listOf(a)
    }

    // --- kv + folded segments ---

    @Test
    fun keyValueRoundtrip() = runTest {
        val kv = db.keyValueDao()
        kv.get("deviceId") shouldBe null
        kv.put("deviceId", "uuid-123")
        kv.get("deviceId") shouldBe "uuid-123"
        kv.put("deviceId", "uuid-456")
        kv.get("deviceId") shouldBe "uuid-456"
        kv.delete("deviceId")
        kv.get("deviceId") shouldBe null
    }

    @Test
    fun foldedSegmentsReplaceForDevice() = runTest {
        val dao = db.foldedSegmentDao()
        dao.replaceForDevice("dev1", listOf("0.jsonl", "1.jsonl"))
        dao.replaceForDevice("dev2", listOf("0.jsonl"))
        dao.segmentsForDevice("dev1").map { it.fileName }
            .shouldContainExactlyInAnyOrder("0.jsonl", "1.jsonl")
        dao.allSegments().size shouldBe 3

        // refold dev1: replaces its set entirely, leaves dev2 untouched.
        dao.replaceForDevice("dev1", listOf("snapshot.jsonl"))
        dao.segmentsForDevice("dev1").map { it.fileName } shouldContainExactly listOf("snapshot.jsonl")
        dao.segmentsForDevice("dev2").map { it.fileName } shouldContainExactly listOf("0.jsonl")
    }

    @Test
    fun duplicatesReplaceAndDeleteForPath() = runTest {
        val dao = db.duplicateDao()
        val id = "a".repeat(64)
        dao.replaceAll(id, listOf(DuplicateEntity(id, "copy1.pdf"), DuplicateEntity(id, "copy2.pdf")))
        dao.observeAll().first().map { it.relPath }
            .shouldContainExactlyInAnyOrder("copy1.pdf", "copy2.pdf")
        dao.deleteForPath("copy1.pdf")
        dao.observeAll().first().map { it.relPath } shouldContainExactly listOf("copy2.pdf")
    }

    @Test
    fun ocrQueueNextPending() = runTest {
        val dao = db.ocrQueueDao()
        dao.upsert(OcrQueueEntity("a".repeat(64), "done", updatedAt = 1))
        dao.upsert(OcrQueueEntity("b".repeat(64), "pending", updatedAt = 5))
        dao.upsert(OcrQueueEntity("c".repeat(64), "pending", updatedAt = 2))
        dao.nextPending()!!.docId shouldBe "c".repeat(64) // oldest pending first
    }

    @Test
    fun savedViewsObserve() = runTest {
        val dao = db.savedViewDao()
        dao.upsert(SavedViewEntity("id2", "Second", "{}", pinned = false, position = 1))
        dao.upsert(SavedViewEntity("id1", "First", "{}", pinned = true, position = 0))
        dao.observeAll().first().map { it.name } shouldContainExactly listOf("First", "Second")
    }

    @Test
    fun clearAllWipesDocuments() = runTest {
        val dao = db.documentDao()
        dao.upsert(doc("a".repeat(64)))
        dao.clearAll()
        dao.observeAll().first() shouldBe emptyList()
    }
}
