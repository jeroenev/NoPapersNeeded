package dev.npn.core.ai

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Integration tests for the AI field-search tools ([LibraryAiTools.ListFieldsTool],
 * [LibraryAiTools.SearchFieldsTool], [LibraryAiTools.GetDocumentTool]) against the real
 * Room + bundled-SQLite index.
 */
class SearchFieldsToolsTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    private fun doc(
        id: String,
        relPath: String = "$id.pdf",
        title: String? = null,
        correspondent: String? = null,
        docType: String? = null,
        docDate: String? = null,
        notes: String? = null,
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
        title = title,
        correspondent = correspondent,
        docType = docType,
        docDate = docDate,
        notes = notes,
        trashed = trashed,
        deleted = deleted,
        missing = missing,
    )

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-ai-fields-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    /** The seed field registry (title, correspondent, docType, docDate, notes) every tool reads. */
    private val registry = FieldKeys.seedDefinitions()

    private val searchTool: LibraryAiTools.SearchFieldsTool
        get() = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao(), registry)

    private val getTool: LibraryAiTools.GetDocumentTool
        get() = LibraryAiTools.GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao(), registry)

    private val listTool: LibraryAiTools.ListFieldsTool
        get() = LibraryAiTools.ListFieldsTool(db.documentFieldDao(), registry)

    private suspend fun seed() {
        db.documentDao().upsertAll(
            listOf(
                doc(
                    "a".repeat(64),
                    title = "Q3 Report",
                    correspondent = "ACME Corp",
                    docType = "report",
                    docDate = "2026-07-15",
                ),
                doc("b".repeat(64), relPath = "b.pdf", correspondent = "Example BV"),
                doc("c".repeat(64), relPath = "c.pdf", trashed = true),
                doc("d".repeat(64), relPath = "d.pdf", deleted = true),
            ),
        )
        // document_fields is the authoritative projection of every value, well-known ones
        // included, so a field search reads it rather than the typed document columns.
        db.documentFieldDao().upsertAll(
            listOf(
                DocumentFieldEntity("a".repeat(64), "title", "Q3 Report"),
                DocumentFieldEntity("a".repeat(64), "correspondent", "ACME Corp"),
                DocumentFieldEntity("a".repeat(64), "docType", "report"),
                DocumentFieldEntity("a".repeat(64), "docDate", "2026-07-15"),
                DocumentFieldEntity("a".repeat(64), "amount", "EUR 42.50"),
                DocumentFieldEntity("a".repeat(64), "ref", "INV-2026-001"),
                DocumentFieldEntity("b".repeat(64), "correspondent", "Example BV"),
                DocumentFieldEntity("b".repeat(64), "amount", "17.00"),
            ),
        )
    }

    @Test
    fun listFieldsShowsDefaultFieldsAndCustomKeysWithCounts() =
        runTest {
            seed()
            val out = listTool.execute(LibraryAiTools.ListFieldsTool.Args())
            // Every registered (seed) field is advertised with its key, label and type.
            out shouldContain "Registered fields (key · label · type · documents with a value):"
            out shouldContain "title · title · text"
            out shouldContain "docDate · docDate · date"
            // Keys carried by documents but not yet defined are surfaced with their counts.
            out shouldContain "Keys in use without a definition:"
            out shouldContain "amount (2 docs)"
            out shouldContain "ref (1 docs)"
            // The two document columns searchable like fields stay advertised.
            out shouldContain "Also searchable: fileName"
            out shouldContain "ocrEngine"
        }

    @Test
    fun listFieldsReportsNoOrphanKeysWhenEmpty() =
        runTest {
            val out = listTool.execute(LibraryAiTools.ListFieldsTool.Args())
            // No documents carry any field values, so there are no undefined keys in use.
            out shouldNotContain "Keys in use without a definition:"
            // The registry itself is still advertised.
            out shouldContain "Registered fields (key · label · type · documents with a value):"
        }

    @Test
    fun searchDefaultFieldMatchesCaseInsensitiveSubstring() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "correspondent", value = "acme"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"correspondent=ACME Corp\""
            out shouldNotContain "b".repeat(64)
        }

    @Test
    fun searchDefaultFieldMatchesPartialDate() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "docDate", value = "2026-07"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"docDate=2026-07-15\""
        }

    @Test
    fun searchCustomFieldByKeyAndValue() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "amount", value = "42"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"amount=EUR 42.50\""
            out shouldNotContain "b".repeat(64)
        }

    @Test
    fun searchCustomFieldIsCaseInsensitiveOnKeyAndValue() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "AMOUNT", value = "eur"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"amount=EUR 42.50\""
        }

    @Test
    fun searchAnyFieldFindsFlexibleFieldValues() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "any", value = "INV-2026"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"ref=INV-2026-001\""
        }

    @Test
    fun searchAnyFieldDefaultsWhenFieldOmitted() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(value = "17.00"))
            out shouldContain "[doc:${"b".repeat(64)}]"
            out shouldContain "field=\"amount=17.00\""
        }

    @Test
    fun searchFieldsExcludesTrashedAndDeletedDocs() =
        runTest {
            seed()
            val tool = searchTool
            // Doc "c" is trashed; nothing matches across default fields.
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "any", value = "c".repeat(64)))
            out shouldContain "No documents"
            out shouldNotContain "[doc:${"c".repeat(64)}]"
        }

    @Test
    fun searchUnknownFieldPointsAtListFields() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "nonexistent", value = "x"))
            // An unknown key finds nothing and steers the model to list_fields to learn the keys.
            out shouldContain "No documents have a \"nonexistent\" value containing \"x\"."
            out shouldContain "Call list_fields to see the field keys in use."
        }

    @Test
    fun searchFieldPresenceFindsDocumentsWithTheField() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "test2"))
            out shouldContain "No documents have a \"test2\" field."

            db.documentFieldDao().upsertAll(
                listOf(DocumentFieldEntity("a".repeat(64), "test2", "whatever")),
            )
            val found = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "test2"))
            found shouldContain "[doc:${"a".repeat(64)}]"
            found shouldContain "field=\"test2=whatever\""
        }

    @Test
    fun searchFieldPresenceMatchesDefaultField() =
        runTest {
            seed()
            val tool = searchTool
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "correspondent"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "[doc:${"b".repeat(64)}]"
        }

    @Test
    fun searchAnyFieldWithoutValueIsAnError() =
        runTest {
            val tool = searchTool
            tool
                .execute(LibraryAiTools.SearchFieldsTool.Args(field = "any"))
                .shouldContain("specify a field")
        }

    @Test
    fun getDocumentIncludesCustomFields() =
        runTest {
            seed()
            val tool = getTool
            val out = tool.execute(LibraryAiTools.GetDocumentTool.Args(docId = "a".repeat(64)))
            // Every field prints as "<key>: <value>" in registry order, then undefined keys.
            out shouldContain "correspondent: ACME Corp"
            out shouldContain "amount: EUR 42.50"
            out shouldContain "ref: INV-2026-001"
        }

    @Test
    fun searchNumberFieldRangeComparesNumerically() =
        runTest {
            // A NUMBER field sorts by its numeric shadow, not lexically: "10" must count as above
            // "9" even though the strings compare the other way. from="10" keeps only the 10 doc.
            val nineDoc = "a".repeat(64)
            val tenDoc = "b".repeat(64)
            db.documentDao().upsertAll(listOf(doc(nineDoc), doc(tenDoc, relPath = "b.pdf")))
            db.documentFieldDao().upsertAll(
                listOf(
                    DocumentFieldEntity(nineDoc, "amount", "9", numValue = 9.0),
                    DocumentFieldEntity(tenDoc, "amount", "10", numValue = 10.0),
                ),
            )
            val amountRegistry =
                FieldKeys.seedDefinitions() +
                    FieldDef(
                        key = "amount",
                        label = "Amount",
                        type = FieldType.NUMBER,
                        showByDefault = false,
                        aiEnabled = false,
                        position = 5,
                    )
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao(), amountRegistry)
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "amount", from = "10"))
            out shouldContain "[doc:$tenDoc]"
            out shouldNotContain nineDoc
        }

    @Test
    fun docLineRendersMatchedField() {
        val id = "a".repeat(64)
        val line =
            LibraryAiTools.docLine(
                doc(id, correspondent = "ACME Corp"),
                field = "amount" to "EUR 42.50",
            )
        line shouldContain "[doc:$id]"
        line shouldContain "field=\"amount=EUR 42.50\""
    }
}
