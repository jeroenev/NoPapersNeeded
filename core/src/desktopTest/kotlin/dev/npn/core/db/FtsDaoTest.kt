package dev.npn.core.db

import androidx.room3.RoomRawQuery
import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.index.FtsRows
import dev.npn.core.index.requireByDocId
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

/** FTS5 over the bundled SQLite driver: matching, snippets, prefixes, and what writes each column. */
class FtsDaoTest {
    private val temp = TempIndexDb("npn-db-test")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

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
}
