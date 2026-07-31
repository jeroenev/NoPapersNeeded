package dev.npn.core.db

import androidx.room3.RoomRawQuery
import dev.npn.core.app.DocumentRepositoryImpl
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
import kotlin.test.assertFailsWith

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
        reviewed: Boolean = false,
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
        reviewed = reviewed,
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
        dao.allPathsAndMeta().single { it.relPath == "${"a".repeat(64)}.pdf" }.docId shouldBe "a".repeat(64)

        // upsert updates in place
        dao.upsert(doc("a".repeat(64), reviewed = true))
        dao.getByDocId("a".repeat(64))!!.reviewed shouldBe true
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
    fun reviewedFilterAndCounts() = runTest {
        val dao = db.documentDao()
        dao.upsertAll(
            listOf(
                doc("a".repeat(64), reviewed = false),
                doc("b".repeat(64), relPath = "b.pdf", reviewed = false),
                doc("c".repeat(64), relPath = "c.pdf", reviewed = true),
            ),
        )
        // All three are untagged, so the untagged-only review count is 3.
        dao.countUntagged().first() shouldBe 3
        // Unreviewed-or-untagged also 3 (all untagged).
        dao.countUnreviewedOrUntagged().first() shouldBe 3
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
    fun getLiveWithTagFiltersAndLimitsInDatabase() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val liveA = "a".repeat(64)
        val liveB = "b".repeat(64)
        val trashed = "c".repeat(64)
        docDao.upsertAll(
            listOf(
                doc(liveA),
                doc(liveB, relPath = "b.pdf"),
                doc(trashed, relPath = "c.pdf", trashed = true),
            ),
        )
        listOf(liveA, liveB, trashed).forEach { tagDao.setForDocument(it, listOf("invoice")) }

        docDao.getLiveWithTag("invoice", limit = 1).map { it.docId }.size shouldBe 1
        docDao.getLiveWithTag("invoice", limit = 10).map { it.docId }
            .shouldContainExactlyInAnyOrder(liveA, liveB)
    }

    @Test
    fun setForDocumentAddsAndClearsTags() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        docDao.upsertAll(listOf(doc(a), doc(b, relPath = "b.pdf")))
        tagDao.setForDocument(a, listOf("shared"))
        tagDao.setForDocument(b, listOf("shared"))
        tagDao.tagsForDocument(a) shouldContainExactly listOf("shared")
        tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
        tagDao.setForDocument(a, emptyList())
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
    fun ftsIndexesNotesSeparatelyFromOcrContent() = runTest {
        val fts = db.ftsDao()
        val id = "n".repeat(64)
        fts.replaceDocument(id, "doc.pdf", null, "ocr aardvark", null, null, null, "note buffalo")

        fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
        fts.searchDocIds("buffalo") shouldContainExactly listOf(id)
        fts.contentForDoc(id) shouldBe "ocr aardvark"

        fts.replaceDocument(id, "renamed.pdf", null, "ocr aardvark", null, null, null, "note condor")
        fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
        fts.searchDocIds("buffalo") shouldBe emptyList()
        fts.searchDocIds("condor") shouldContainExactly listOf(id)
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
        hits.first().snippet.lowercase() shouldContain "\u0001electricity\u0002"
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
    fun ftsPrefixRowsProvideAutocompleteSource() = runTest {
        val fts = db.ftsDao()
        val id = "a".repeat(64)
        fts.replaceDocument(id, "invoice.pdf", "Annual invoice", "electricity total", null, null, null)
        fts.rowsForPrefix("\"invo\"*", 5).map { it.docId } shouldContainExactly listOf(id)
        fts.rowsForPrefix("\"absent\"*", 5) shouldBe emptyList()
    }

    @Test
    fun repositorySuggestionsCombineAndEscapeSources() = runTest {
        val id = "a".repeat(64)
        db.documentDao().upsertAll(
            listOf(doc(id, correspondent = "Invoice Corp", docType = "Invoice type")),
        )
        db.tagDao().upsert(TagEntity("Invoice tag"))
        db.ftsDao().replaceDocument(id, "document.pdf", "Invoice title", "invoiced amount", null, null, null)

        val suggestions = DocumentRepositoryImpl(db).searchSuggestions("invo", 8)
        suggestions shouldContainExactly listOf(
            "Invoice",
            "Invoice tag",
            "Invoice type",
            "Invoice Corp",
            "invoiced",
        )
        DocumentRepositoryImpl(db).searchSuggestions("%_", 8) shouldBe emptyList()
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
    fun rawQueryFilterByReviewedAndTagJoin() = runTest {
        val docDao = db.documentDao()
        val tagDao = db.tagDao()
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        docDao.upsertAll(
            listOf(
                doc(a, reviewed = false),
                doc(b, relPath = "b.pdf", reviewed = false),
                doc(c, relPath = "c.pdf", reviewed = true),
            ),
        )
        tagDao.setForDocument(a, listOf("invoice"))
        tagDao.setForDocument(c, listOf("invoice"))

        // Upper-layer-built SQL: unreviewed docs that carry the 'invoice' tag -> only `a`.
        val query = RoomRawQuery(
            sql = "SELECT d.* FROM documents d " +
                "JOIN document_tags t ON d.docId = t.docId " +
                "WHERE d.reviewed = ? AND t.tag = ? ORDER BY d.addedAt DESC",
            onBindStatement = {
                it.bindLong(1, 0L)
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
    fun duplicatesReplaceAll() = runTest {
        val dao = db.duplicateDao()
        val id = "a".repeat(64)
        dao.replaceAll(id, listOf(DuplicateEntity(id, "copy1.pdf"), DuplicateEntity(id, "copy2.pdf")))
        dao.observeAll().first().map { it.relPath }
            .shouldContainExactlyInAnyOrder("copy1.pdf", "copy2.pdf")
        // Re-projecting a doc's duplicates replaces the whole set for that doc.
        dao.replaceAll(id, listOf(DuplicateEntity(id, "copy2.pdf")))
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

    @Test
    fun metadataAccumulatorRejectsIdentityAndHlcCollisions() = runTest {
        val dao = db.metadataProjectionDao()
        fun source(path: String) = MetadataSourceEntity(path, 1, path, null, "applied")
        fun event(dev: String, seq: Long, canonical: String, hlcDevice: String = dev) = MetadataEventEntity(
            deviceId = dev,
            sequence = seq,
            canonicalJson = canonical,
            hlcPhysical = 10,
            hlcCounter = 0,
            hlcDevice = hlcDevice,
            documentId = null,
            operation = "future",
        )

        dao.ingestSource(source("one"), listOf(event("dev-a", 1, "a")))
        assertFailsWith<MetadataIdentityCollision> {
            dao.ingestSource(source("two"), listOf(event("dev-a", 1, "different")))
        }
        assertFailsWith<MetadataIdentityCollision> {
            dao.ingestSource(source("three"), listOf(event("dev-b", 1, "b", hlcDevice = "dev-a")))
        }
    }
}
