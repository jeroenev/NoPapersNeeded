package dev.npn.core.db

import androidx.room3.RoomRawQuery
import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.index.FtsRows
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Integration tests for the local index database, run against the real bundled SQLite
 * driver on the JVM (desktop) target. Verifies Room 3 wiring and, critically, that the
 * @Fts5 entity works with BundledSQLiteDriver on JVM.
 */
class NpnDatabaseTest {
    private val temp = TempIndexDb("npn-db-test")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun documentUpsertAndLookup() =
        runTest {
            val dao = db.documentDao()
            dao.upsert(documentEntity("a".repeat(64)))
            dao.getByDocId("a".repeat(64))!!.fileName shouldBe "${"a".repeat(64)}.pdf"
            dao.allPathsAndMeta().single { it.relPath == "${"a".repeat(64)}.pdf" }.docId shouldBe "a".repeat(64)

            // upsert updates in place
            dao.upsert(documentEntity("a".repeat(64), reviewed = true))
            dao.getByDocId("a".repeat(64))!!.reviewed shouldBe true
        }

    @Test
    fun observeAllExcludesTrashedDeletedMissing() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64)),
                    documentEntity("b".repeat(64), relPath = "b.pdf", trashed = true),
                    documentEntity("c".repeat(64), relPath = "c.pdf", deleted = true),
                    documentEntity("d".repeat(64), relPath = "d.pdf", missing = true),
                ),
            )
            dao.getAllLive().map { it.docId } shouldContainExactly listOf("a".repeat(64))
            dao.observeTrashed().first().map { it.docId } shouldContainExactly listOf("b".repeat(64))
            dao.observeMissing().first().map { it.docId } shouldContainExactly listOf("d".repeat(64))
        }

    @Test
    fun reviewedFilterAndCounts() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64), reviewed = false),
                    documentEntity("b".repeat(64), relPath = "b.pdf", reviewed = false),
                    documentEntity("c".repeat(64), relPath = "c.pdf", reviewed = true),
                ),
            )
            // All three are untagged, so the untagged-only review count is 3.
            dao.countUntagged().first() shouldBe 3
            // Unreviewed-or-untagged also 3 (all untagged).
            dao.countUnreviewedOrUntagged().first() shouldBe 3
        }

    @Test
    fun untaggedQueryAndTagCounts() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val tagged = "a".repeat(64)
            val untagged = "b".repeat(64)
            docDao.upsertAll(listOf(documentEntity(tagged), documentEntity(untagged, relPath = "b.pdf")))
            tagDao.upsertAll(listOf(TagEntity("invoice", color = "#ff0000"), TagEntity("empty")))
            tagDao.setForDocuments(mapOf(tagged to listOf("invoice")))

            docDao.countUntagged().first() shouldBe 1

            val counts = tagDao.observeAllWithCounts().first().associateBy { it.name }
            counts["invoice"]!!.count shouldBe 1
            counts["invoice"]!!.color shouldBe "#ff0000"
            counts["empty"]!!.count shouldBe 0
        }

    @Test
    fun tagCountsIncludeOnlyLiveDocuments() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val live = "a".repeat(64)
            val trashed = "b".repeat(64)
            val missing = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(live),
                    documentEntity(trashed, relPath = "trashed.pdf", trashed = true),
                    documentEntity(missing, relPath = "missing.pdf", missing = true),
                ),
            )
            tagDao.upsertAll(listOf(TagEntity("invoice")))
            tagDao.setForDocuments(listOf(live, trashed, missing).associateWith { listOf("invoice") })

            tagDao
                .allWithLiveCounts()
                .single { it.name == "invoice" }
                .count shouldBe 1
        }

    @Test
    fun setForDocumentReplacesTags() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val id = "a".repeat(64)
            docDao.upsert(documentEntity(id))
            tagDao.setForDocuments(mapOf(id to listOf("x", "y")))
            tagDao.tagsForDocument(id) shouldContainExactly listOf("x", "y")
            tagDao.setForDocuments(mapOf(id to listOf("y", "z")))
            tagDao.tagsForDocument(id) shouldContainExactly listOf("y", "z")
        }

    @Test
    fun getLiveWithTagFiltersAndLimitsInDatabase() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val liveA = "a".repeat(64)
            val liveB = "b".repeat(64)
            val trashed = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(liveA),
                    documentEntity(liveB, relPath = "b.pdf"),
                    documentEntity(trashed, relPath = "c.pdf", trashed = true),
                ),
            )
            tagDao.setForDocuments(listOf(liveA, liveB, trashed).associateWith { listOf("invoice") })

            docDao.getLiveWithTag("invoice", limit = 1).map { it.docId }.size shouldBe 1
            docDao
                .getLiveWithTag("invoice", limit = 10)
                .map { it.docId }
                .shouldContainExactlyInAnyOrder(liveA, liveB)
        }

    @Test
    fun setForDocumentAddsAndClearsTags() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            docDao.upsertAll(listOf(documentEntity(a), documentEntity(b, relPath = "b.pdf")))
            tagDao.setForDocuments(mapOf(a to listOf("shared"), b to listOf("shared")))
            tagDao.tagsForDocument(a) shouldContainExactly listOf("shared")
            tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
            tagDao.setForDocuments(mapOf(a to emptyList()))
            tagDao.tagsForDocument(a) shouldBe emptyList()
            tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
        }

    /** relPath is unique: a hidden row gives its path up to a live one rather than swallow it. */
    @Test
    fun aLiveRowTakesThePathOfAHiddenOne() =
        runTest {
            val dao = db.documentDao()
            val trashed = documentEntity("a".repeat(64), relPath = "t.pdf", trashed = true)
            val deleted = documentEntity("b".repeat(64), relPath = "d.pdf", deleted = true)
            val missing = documentEntity("c".repeat(64), relPath = "m.pdf", missing = true)
            dao.upsertAll(listOf(trashed, deleted, missing))

            dao.upsertAll(
                listOf(
                    documentEntity("d".repeat(64), relPath = "t.pdf"),
                    documentEntity("e".repeat(64), relPath = "d.pdf"),
                ),
            )
            dao.upsert(documentEntity("f".repeat(64), relPath = "m.pdf"))

            dao.getAllLive().map { it.relPath } shouldContainExactlyInAnyOrder listOf("t.pdf", "d.pdf", "m.pdf")
            for (hidden in listOf(trashed, deleted, missing)) {
                val row = dao.getByDocId(hidden.docId)
                row shouldBe hidden.copy(relPath = missingSentinel(hidden.docId))
            }
        }

    /** Two live rows on one path is a bug, and the write says so instead of dropping one. */
    @Test
    fun aRowOnTheWayOfALiveOneFailsTheWrite() =
        runTest {
            val dao = db.documentDao()
            dao.upsert(documentEntity("a".repeat(64), relPath = "a.pdf"))

            assertFailsWith<IllegalStateException> {
                dao.upsertAll(listOf(documentEntity("b".repeat(64), relPath = "a.pdf")))
            }

            dao.allPathsAndMeta().map { it.docId } shouldContainExactly listOf("a".repeat(64))
        }

    @Test
    fun allPathsAndMetaProjection() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64), relPath = "docs/a.pdf"),
                    documentEntity("b".repeat(64), relPath = "docs/b.pdf", deleted = true),
                ),
            )
            val metas = dao.allPathsAndMeta()
            metas.map { it.relPath } shouldContainExactly listOf("docs/a.pdf")
            metas.first().size shouldBe 100L
        }

    @Test
    fun ftsMatchReturnsRightDocIds() =
        runTest {
            val fts = db.ftsDao()
            fts.upsertContent(
                FtsRows.row(
                    "a".repeat(64),
                    "invoice.pdf",
                    tags = listOf("utilities"),
                    fields =
                        mapOf(
                            FieldKeys.TITLE to "Electricity invoice",
                            FieldKeys.CORRESPONDENT to "acme",
                            FieldKeys.DOC_TYPE to "invoice",
                        ),
                    content = "total amount due for electricity usage",
                ),
            )
            fts.upsertContent(
                FtsRows.row(
                    "b".repeat(64),
                    "recipe.pdf",
                    tags = listOf("cooking"),
                    fields =
                        mapOf(
                            FieldKeys.TITLE to "Pasta recipe",
                            FieldKeys.DOC_TYPE to "note",
                        ),
                    content = "boil water add pasta and salt",
                ),
            )
            fts.searchDocIds("electricity") shouldContainExactly listOf("a".repeat(64))
            fts.searchDocIds("pasta") shouldContainExactly listOf("b".repeat(64))
        }

    @Test
    fun ftsIndexesNotesSeparatelyFromOcrContent() =
        runTest {
            val fts = db.ftsDao()
            val id = "n".repeat(64)
            fts.upsertContent(
                FtsRows.row(
                    id,
                    "doc.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.NOTES to "note buffalo"),
                    content = "ocr aardvark",
                ),
            )

            fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
            fts.searchDocIds("buffalo") shouldContainExactly listOf(id)
            fts.contentForDoc(id) shouldBe "ocr aardvark"

            // The fold's write replaces the metadata columns and leaves the OCR text alone.
            fts.upsertMetadata(
                listOf(
                    FtsRows.row(
                        id,
                        "renamed.pdf",
                        tags = emptyList(),
                        fields = mapOf(FieldKeys.NOTES to "note condor"),
                        content = null,
                    ),
                ),
            )
            fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
            fts.contentForDoc(id) shouldBe "ocr aardvark"
            fts.searchDocIds("buffalo") shouldBe emptyList()
            fts.searchDocIds("condor") shouldContainExactly listOf(id)
        }

    @Test
    fun ftsSnippetHighlightsFragment() =
        runTest {
            val fts = db.ftsDao()
            fts.upsertContent(
                FtsRows.row(
                    "a".repeat(64),
                    "invoice.pdf",
                    tags = listOf("utilities"),
                    fields =
                        mapOf(
                            FieldKeys.TITLE to "Electricity invoice",
                            FieldKeys.CORRESPONDENT to "acme",
                            FieldKeys.DOC_TYPE to "invoice",
                        ),
                    content = "the total amount due for your electricity usage this month",
                ),
            )
            val hits = fts.search("electricity")
            hits.size shouldBe 1
            hits.first().docId shouldBe "a".repeat(64)
            hits.first().snippet.lowercase() shouldContain "\u0001electricity\u0002"
        }

    @Test
    fun ftsPrefixSearch() =
        runTest {
            val fts = db.ftsDao()
            fts.upsertContent(
                FtsRows.row(
                    "a".repeat(64),
                    "doc.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.TITLE to "title"),
                    content = "electrification of the grid",
                ),
            )
            fts.searchDocIds("electr*") shouldContainExactly listOf("a".repeat(64))
            fts.searchDocIds("electr").shouldBe(emptyList()) // no prefix operator, no exact token match
        }

    @Test
    fun ftsPrefixRowsProvideAutocompleteSource() =
        runTest {
            val fts = db.ftsDao()
            val id = "a".repeat(64)
            fts.upsertContent(
                FtsRows.row(
                    id,
                    "invoice.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.TITLE to "Annual invoice"),
                    content = "electricity total",
                ),
            )
            fts.rowsForPrefix("\"invo\"*", 5).map { it.docId } shouldContainExactly listOf(id)
            fts.rowsForPrefix("\"absent\"*", 5) shouldBe emptyList()
        }

    @Test
    fun repositorySuggestionsCombineAndEscapeSources() =
        runTest {
            val id = "a".repeat(64)
            db.documentDao().upsertAll(
                listOf(documentEntity(id, correspondent = "Invoice Corp", docType = "Invoice type")),
            )
            // Field suggestions come from every registered TEXT field's rows, not the cache columns.
            db.fieldDefDao().replaceAll(FieldKeys.seedDefinitions())
            db.documentFieldDao().replaceForDocs(
                mapOf(
                    id to
                        listOf(
                            DocumentFieldEntity(id, FieldKeys.CORRESPONDENT, "Invoice Corp"),
                            DocumentFieldEntity(id, FieldKeys.DOC_TYPE, "Invoice type"),
                        ),
                ),
            )
            db.tagDao().upsertAll(listOf(TagEntity("Invoice tag")))
            db.ftsDao().upsertContent(
                FtsRows.row(
                    id,
                    "document.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.TITLE to "Invoice title"),
                    content = "invoiced amount",
                ),
            )

            val suggestions = DocumentRepositoryImpl(db).searchSuggestions("invo", 8)
            suggestions shouldContainExactly
                listOf(
                    "Invoice",
                    "Invoice tag",
                    // Field sources interleave in registry order: correspondent before docType.
                    "Invoice Corp",
                    "Invoice type",
                    "invoiced",
                )
            DocumentRepositoryImpl(db).searchSuggestions("%_", 8) shouldBe emptyList()
        }

    @Test
    fun ftsContentWriteReplacesOnlyTheText() =
        runTest {
            val fts = db.ftsDao()
            val id = "a".repeat(64)
            fts.upsertContent(
                FtsRows.row(
                    id,
                    "doc.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.TITLE to "tapir"),
                    content = "aardvark content",
                ),
            )
            fts.searchDocIds("aardvark") shouldContainExactly listOf(id)
            // Re-index the same docId with new content: the old term must no longer match, there
            // must be no duplicate row for the new term, and the metadata columns stay the fold's.
            fts.upsertContent(
                FtsRows.row(
                    id,
                    "doc.pdf",
                    tags = emptyList(),
                    fields = mapOf(FieldKeys.TITLE to "ignored"),
                    content = "buffalo content",
                ),
            )
            fts.searchDocIds("aardvark") shouldBe emptyList()
            fts.searchDocIds("buffalo") shouldContainExactly listOf(id)
            fts.searchDocIds("tapir") shouldContainExactly listOf(id)
            fts.searchDocIds("ignored") shouldBe emptyList()
        }

    @Test
    fun ftsSearchAcrossTagsAndCorrespondent() =
        runTest {
            val fts = db.ftsDao()
            fts.upsertContent(
                FtsRows.row(
                    "a".repeat(64),
                    "doc.pdf",
                    tags = listOf("taxes", "finance"),
                    fields =
                        mapOf(
                            FieldKeys.TITLE to "title",
                            FieldKeys.CORRESPONDENT to "cityhall",
                            FieldKeys.DOC_TYPE to "letter",
                        ),
                    content = "body text",
                ),
            )
            fts.searchDocIds("taxes") shouldContainExactly listOf("a".repeat(64))
            fts.searchDocIds("cityhall") shouldContainExactly listOf("a".repeat(64))
            fts.searchDocIds("letter") shouldContainExactly listOf("a".repeat(64))
        }

    @Test
    fun rawQueryFilterByReviewedAndTagJoin() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val c = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(a, reviewed = false),
                    documentEntity(b, relPath = "b.pdf", reviewed = false),
                    documentEntity(c, relPath = "c.pdf", reviewed = true),
                ),
            )
            tagDao.setForDocuments(mapOf(a to listOf("invoice"), c to listOf("invoice")))

            // Upper-layer-built SQL: unreviewed docs that carry the 'invoice' tag -> only `a`.
            val query =
                RoomRawQuery(
                    sql =
                        "SELECT d.* FROM documents d " +
                            "JOIN document_tags t ON d.docId = t.docId " +
                            "WHERE d.reviewed = ? AND t.tag = ? ORDER BY d.addedAt DESC",
                    onBindStatement = {
                        it.bindLong(1, 0L)
                        it.bindText(2, "invoice")
                    },
                )
            db
                .documentDao()
                .observeLibraryRaw(query)
                .first()
                .map { it.docId } shouldContainExactly listOf(a)
        }

    @Test
    fun keyValueRoundtrip() =
        runTest {
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
    fun duplicatesReplaceAll() =
        runTest {
            val dao = db.duplicateDao()
            val id = "a".repeat(64)
            val other = "b".repeat(64)
            dao.replaceAll(listOf(DuplicateEntity(id, "copy1.pdf"), DuplicateEntity(other, "copy2.pdf")))
            dao
                .observeAll()
                .first()
                .map { it.relPath }
                .shouldContainExactlyInAnyOrder("copy1.pdf", "copy2.pdf")
            // A rescan's duplicates replace the whole table, a document with none left included.
            dao.replaceAll(listOf(DuplicateEntity(id, "copy1.pdf")))
            dao.observeAll().first().map { it.relPath } shouldContainExactly listOf("copy1.pdf")
            dao.delete(id, "copy1.pdf")
            dao.observeAll().first().shouldBeEmpty()
        }

    @Test
    fun metadataAccumulatorRejectsIdentityAndHlcCollisions() =
        runTest {
            val dao = db.metadataProjectionDao()

            fun source(path: String) =
                MetadataSourceEntity(
                    path,
                    SourceKind.LEGACY_SEGMENT,
                    SourceFingerprint(path),
                    null,
                    SourceStatus.APPLIED,
                )

            fun event(
                dev: String,
                seq: Long,
                canonical: String,
                hlcDevice: String = dev,
            ) = MetadataEventEntity(
                deviceId = dev,
                sequence = seq,
                canonicalJson = canonical,
                hlcPhysical = 10,
                hlcCounter = 0,
                hlcDevice = hlcDevice,
                documentId = null,
                operation = "future",
            )

            dao.ingest(source("one"), listOf(event("dev-a", 1, "a")))
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("two"), listOf(event("dev-a", 1, "different")))
            }
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("three"), listOf(event("dev-b", 1, "b", hlcDevice = "dev-a")))
            }
        }

    /**
     * A copy of an event that lacks only keys of the other (a build that does not know them encoded
     * it again) is the same event, and the richer copy is the one kept, whichever came first. Any
     * other difference is still a collision.
     */
    @Test
    fun metadataAccumulatorKeepsTheRicherCopyOfOneEvent() =
        runTest {
            val dao = db.metadataProjectionDao()

            fun source(path: String) =
                MetadataSourceEntity(path, SourceKind.SNAPSHOT, SourceFingerprint(path), null, SourceStatus.APPLIED)

            fun event(
                dev: String,
                canonical: String,
            ) = MetadataEventEntity(
                deviceId = dev,
                sequence = 1,
                canonicalJson = canonical,
                hlcPhysical = 10,
                hlcCounter = 0,
                hlcDevice = dev,
                documentId = null,
                operation = "field-def",
            )
            val poorer = """{"op":"field-def","p":{"def":{"label":"Title","type":"text"},"key":"title"}}"""
            val richer =
                """{"op":"field-def","p":{"def":{"label":"Title","perDocument":true,"type":"text"},"key":"title"}}"""

            dao.ingest(source("poorer"), listOf(event("dev-a", poorer)))
            dao.ingest(source("richer"), listOf(event("dev-a", richer)))
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer
            dao.ingest(source("poorer-again"), listOf(event("dev-a", poorer)))
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer

            val otherValue =
                """{"op":"field-def","p":{"def":{"label":"Name","perDocument":true,"type":"text"},"key":"title"}}"""
            val otherKeys =
                """{"op":"field-def","p":{"def":{"hint":"x","label":"Title","type":"text"},"key":"title"}}"""
            for ((name, other) in listOf("other-value" to otherValue, "other-keys" to otherKeys)) {
                assertFailsWith<MetadataIdentityCollision> { dao.ingest(source(name), listOf(event("dev-a", other))) }
            }
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer

            // Arrays hold nothing of each other: they are equal or they differ.
            dao.ingest(source("dots"), listOf(event("dev-b", """{"p":{"dots":[["dev-a",1]]}}""")))
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("no-dots"), listOf(event("dev-b", """{"p":{"dots":[]}}""")))
            }
        }
}
