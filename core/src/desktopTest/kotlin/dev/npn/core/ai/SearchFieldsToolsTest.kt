package dev.npn.core.ai

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
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
        db.documentFieldDao().upsertAll(
            listOf(
                DocumentFieldEntity("a".repeat(64), "amount", "EUR 42.50"),
                DocumentFieldEntity("a".repeat(64), "ref", "INV-2026-001"),
                DocumentFieldEntity("b".repeat(64), "amount", "17.00"),
            ),
        )
    }

    @Test
    fun listFieldsShowsDefaultFieldsAndCustomKeysWithCounts() =
        runTest {
            seed()
            val out = LibraryAiTools.ListFieldsTool(db.documentFieldDao()).execute(LibraryAiTools.ListFieldsTool.Args())
            out shouldContain "Default fields: title, correspondent, docType, docDate, notes, fileName"
            out shouldContain "amount (2 docs)"
            out shouldContain "ref (1 docs)"
        }

    @Test
    fun listFieldsReportsNoCustomFieldsWhenEmpty() =
        runTest {
            val out = LibraryAiTools.ListFieldsTool(db.documentFieldDao()).execute(LibraryAiTools.ListFieldsTool.Args())
            out shouldContain "No custom (flexible) fields are in use yet."
        }

    @Test
    fun searchDefaultFieldMatchesCaseInsensitiveSubstring() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "correspondent", value = "acme"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"correspondent=ACME Corp\""
            out shouldNotContain "b".repeat(64)
        }

    @Test
    fun searchDefaultFieldMatchesPartialDate() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "docDate", value = "2026-07"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"docDate=2026-07-15\""
        }

    @Test
    fun searchCustomFieldByKeyAndValue() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "amount", value = "42"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"amount=EUR 42.50\""
            out shouldNotContain "b".repeat(64)
        }

    @Test
    fun searchCustomFieldIsCaseInsensitiveOnKeyAndValue() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "AMOUNT", value = "eur"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"amount=EUR 42.50\""
        }

    @Test
    fun searchAnyFieldFindsFlexibleFieldValues() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "any", value = "INV-2026"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "field=\"ref=INV-2026-001\""
        }

    @Test
    fun searchAnyFieldDefaultsWhenFieldOmitted() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(value = "17.00"))
            out shouldContain "[doc:${"b".repeat(64)}]"
            out shouldContain "field=\"amount=17.00\""
        }

    @Test
    fun searchFieldsExcludesTrashedAndDeletedDocs() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            // Doc "c" is trashed; nothing matches across default fields.
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "any", value = "c".repeat(64)))
            out shouldContain "No documents"
            out shouldNotContain "[doc:${"c".repeat(64)}]"
        }

    @Test
    fun searchUnknownFieldListsDefaultFields() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "nonexistent", value = "x"))
            out shouldContain "Default fields: title, correspondent, docType, docDate, notes, fileName"
        }

    @Test
    fun searchFieldPresenceFindsDocumentsWithTheField() =
        runTest {
            seed()
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "test2"))
            out shouldContain "No documents have a custom field \"test2\""

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
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.SearchFieldsTool.Args(field = "correspondent"))
            out shouldContain "[doc:${"a".repeat(64)}]"
            out shouldContain "[doc:${"b".repeat(64)}]"
        }

    @Test
    fun searchAnyFieldWithoutValueIsAnError() =
        runTest {
            val tool = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())
            tool
                .execute(LibraryAiTools.SearchFieldsTool.Args(field = "any"))
                .shouldContain("specify a field")
        }

    @Test
    fun getDocumentIncludesCustomFields() =
        runTest {
            seed()
            val tool = LibraryAiTools.GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao())
            val out = tool.execute(LibraryAiTools.GetDocumentTool.Args(docId = "a".repeat(64)))
            out shouldContain "correspondent: ACME Corp"
            out shouldContain "customFields:"
            out shouldContain "  amount: EUR 42.50"
            out shouldContain "  ref: INV-2026-001"
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
