package dev.npn.core.ai

import dev.npn.core.db.DocumentEntity
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
 * The OCR engine as a *searchable* document field: "which documents did the vision model read?" is
 * a question about how the archive was built rather than what it says, so it is answered from the
 * index columns instead of the full-text content.
 *
 * Kept apart from [SearchFieldsToolsTest] because this field is the app's own, not the user's, and
 * so behaves differently on purpose — most visibly by staying out of "any" searches.
 */
class OcrEngineFieldToolsTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    /** Pinned by hand to the AI engine. */
    private val aiDoc = "a".repeat(64)

    /** Read locally under automatic engine selection. */
    private val tesseractDoc = "b".repeat(64)

    /** Imported but never OCRed, so search has no text for it at all. */
    private val unreadDoc = "c".repeat(64)

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-ai-ocr-field-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    private fun doc(
        id: String,
        ocrEngine: String? = null,
        ocrEngineOverride: String? = null,
    ) = DocumentEntity(
        docId = id,
        relPath = "$id.pdf",
        fileName = "$id.pdf",
        size = 100,
        mtime = 1000,
        addedAt = 1000,
        title = "Report",
        ocrEngine = ocrEngine,
        ocrEngineOverride = ocrEngineOverride,
    )

    private suspend fun seed() {
        db.documentDao().upsertAll(
            listOf(
                doc(aiDoc, ocrEngine = "ai", ocrEngineOverride = "ai"),
                doc(tesseractDoc, ocrEngine = "tesseract"),
                doc(unreadDoc),
            ),
        )
    }

    private fun searchTool() = LibraryAiTools.SearchFieldsTool(db.documentDao(), db.documentFieldDao())

    private fun getTool() = LibraryAiTools.GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao())

    @Test
    fun searchFindsDocumentsByTheEngineThatReadThem() =
        runTest {
            seed()
            val out = searchTool().execute(LibraryAiTools.SearchFieldsTool.Args(field = "ocrEngine", value = "ai"))
            out shouldContain "[doc:$aiDoc]"
            out shouldContain "field=\"ocrEngine=ai\""
            out shouldNotContain tesseractDoc
        }

    @Test
    fun searchAcceptsTheEngineAlias() =
        runTest {
            seed()
            val out = searchTool().execute(LibraryAiTools.SearchFieldsTool.Args(field = "engine", value = "tesseract"))
            out shouldContain "[doc:$tesseractDoc]"
            out shouldContain "field=\"ocrEngine=tesseract\""
        }

    @Test
    fun searchWithoutValueFindsOnlyDocumentsThatHaveIndexedText() =
        runTest {
            seed()
            val out = searchTool().execute(LibraryAiTools.SearchFieldsTool.Args(field = "ocrEngine"))
            out shouldContain "[doc:$aiDoc]"
            out shouldContain "[doc:$tesseractDoc]"
            // A document awaiting OCR has no engine, so it must not read as one that has been read.
            out shouldNotContain unreadDoc
        }

    @Test
    fun anyFieldSearchIgnoresTheEngine() =
        runTest {
            seed()
            // A bare "tesseract" is a content-shaped query; answering it with every locally OCRed
            // document would be noise, so the app's own fields stay out of "any".
            val out = searchTool().execute(LibraryAiTools.SearchFieldsTool.Args(field = "any", value = "tesseract"))
            out shouldContain "No documents"
            out shouldNotContain "ocrEngine"
        }

    @Test
    fun listFieldsAdvertisesTheEngineAsADefaultField() =
        runTest {
            val out = LibraryAiTools.ListFieldsTool(db.documentFieldDao()).execute(LibraryAiTools.ListFieldsTool.Args())
            out shouldContain "ocrEngine"
            out shouldContain "set by the app, not the user"
        }

    @Test
    fun getDocumentSeparatesTheIndexedEngineFromAUserPin() =
        runTest {
            seed()
            val pinned = getTool().execute(LibraryAiTools.GetDocumentTool.Args(docId = aiDoc))
            pinned shouldContain "ocrEngine: ai"
            pinned shouldContain "ocrEnginePinnedByUser: ai"

            // Automatic selection is the common case, and must not read as a user decision.
            val automatic = getTool().execute(LibraryAiTools.GetDocumentTool.Args(docId = tesseractDoc))
            automatic shouldContain "ocrEngine: tesseract"
            automatic shouldNotContain "ocrEnginePinnedByUser"
        }

    @Test
    fun getDocumentSaysSoWhenNoTextIsIndexedYet() =
        runTest {
            seed()
            val out = getTool().execute(LibraryAiTools.GetDocumentTool.Args(docId = unreadDoc))
            out shouldContain "ocrEngine: (no text indexed yet)"
        }
}
