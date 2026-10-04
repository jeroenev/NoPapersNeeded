package dev.npn.core.ai

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class LibraryAiToolsConsistencyTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-ai-consistency-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    private fun doc(
        id: String,
        relPath: String = "$id.pdf",
        trashed: Boolean = false,
        deleted: Boolean = false,
    ) = DocumentEntity(
        docId = id,
        relPath = relPath,
        fileName = relPath.substringAfterLast('/'),
        size = 100,
        mtime = 1000,
        addedAt = 1000,
        trashed = trashed,
        deleted = deleted,
    )

    @Test
    fun listFieldsCountsOnlySearchableDocuments() =
        runTest {
            val live = "a".repeat(64)
            val trashed = "c".repeat(64)
            val deleted = "d".repeat(64)
            db.documentDao().upsertAll(listOf(doc(live), doc(trashed, trashed = true), doc(deleted, deleted = true)))
            db.documentFieldDao().upsertAll(
                listOf(
                    DocumentFieldEntity(live, "amount", "live"),
                    DocumentFieldEntity(trashed, "amount", "trashed"),
                    DocumentFieldEntity(deleted, "deletedOnly", "hidden"),
                ),
            )

            val tool = LibraryAiTools.ListFieldsTool(db.documentFieldDao(), FieldKeys.seedDefinitions())
            val out = tool.execute(LibraryAiTools.ListFieldsTool.Args())

            out shouldContain "amount (1 docs)"
            out shouldNotContain "deletedOnly"
        }

    @Test
    fun pathSearchMatchesFolderWhileFileNameSearchDoesNot() =
        runTest {
            val id = "a".repeat(64)
            db.documentDao().upsert(doc(id, relPath = "invoices/report.pdf"))
            val tool =
                LibraryAiTools.SearchFieldsTool(
                    db.documentDao(),
                    db.documentFieldDao(),
                    FieldKeys.seedDefinitions(),
                )

            tool
                .execute(LibraryAiTools.SearchFieldsTool.Args(field = "path", value = "invoices"))
                .shouldContain("[doc:$id]")
            tool
                .execute(LibraryAiTools.SearchFieldsTool.Args(field = "fileName", value = "invoices"))
                .shouldContain("No documents")
        }

    @Test
    fun getDocumentReportsDeletedStatus() =
        runTest {
            val id = "d".repeat(64)
            db.documentDao().upsert(doc(id, deleted = true))
            val tool =
                LibraryAiTools.GetDocumentTool(
                    db.documentDao(),
                    db.tagDao(),
                    db.documentFieldDao(),
                    FieldKeys.seedDefinitions(),
                )

            tool.execute(LibraryAiTools.GetDocumentTool.Args(id)) shouldContain "status: permanently deleted"
        }

    @Test
    fun documentSearchAppliesLimitAfterRemovingNonLiveHits() =
        runTest {
            val hidden = (0 until LibraryAiTools.MAX_SEARCH_RESULTS).map { it.toString().padStart(64, '0') }
            val live = "f".repeat(64)
            db.documentDao().upsertAll(
                hidden.mapIndexed { index, id -> doc(id, relPath = "$index.pdf", trashed = true) } + doc(live),
            )
            db.ftsDao().replaceDocuments(
                hidden.map { FtsDocumentEntity(docId = it, content = "needle") } +
                    FtsDocumentEntity(docId = live, content = "needle"),
            )
            val tool = LibraryAiTools.SearchDocumentsTool(db.ftsDao(), db.documentDao())

            tool.execute(LibraryAiTools.SearchDocumentsTool.Args("needle")) shouldContain "[doc:$live]"
        }
}
