package dev.npn.core.db

import androidx.sqlite.execSQL
import dev.npn.core.index.FtsRows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * v21 -> v22 adds fts_keys. The index must come through the upgrade with its extracted text (OCR
 * output is not refolded from the journal), so the migration keys the rows that are already there.
 */
class FtsKeysMigrationTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setup() {
        dir = Files.createTempDirectory("npn-fts-migration").toFile()
        file = File(dir, "index.db")
    }

    @AfterTest
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun existingRowsKeepTheirTextAndGetKeys() =
        runTest {
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            createFromSchema(file, version = 21) { connection ->
                connection.execSQL(
                    "INSERT INTO documents (docId, relPath, fileName, size, mtime, addedAt, reviewed, trashed, " +
                        "deleted, missing, ocrState) VALUES ('$a', 'a.pdf', 'a.pdf', 1, 1, 1, 0, 0, 0, 0, 'done')",
                )
                connection.execSQL(
                    "INSERT INTO fts_documents (docId, fileName, content) VALUES ('$a', 'a.pdf', 'aardvark')",
                )
                connection.execSQL(
                    "INSERT INTO fts_documents (docId, fileName, content) VALUES ('$b', 'b.pdf', 'buffalo')",
                )
                // A document indexed twice: only its first row survives.
                connection.execSQL(
                    "INSERT INTO fts_documents (docId, fileName, content) VALUES ('$b', 'b.pdf', 'buffalo')",
                )
            }

            val db = npnDatabase(file)
            try {
                // A destructive fallback would have emptied every table.
                db.documentDao().getByDocId(a)?.relPath shouldBe "a.pdf"
                val fts = db.ftsDao()
                fts.contentForDoc(a) shouldBe "aardvark"
                fts.contentForDoc(b) shouldBe "buffalo"
                fts.searchDocIds("buffalo") shouldContainExactly listOf(b)

                // New keys continue past the migrated rowids instead of colliding with them.
                val c = "c".repeat(64)
                fts.upsertContent(FtsRows.row(c, "c.pdf", emptyList(), emptyMap(), "condor"))
                fts.upsertContent(FtsRows.row(a, "a.pdf", emptyList(), emptyMap(), "anteater"))
                fts.searchDocIds("condor") shouldContainExactly listOf(c)
                fts.searchDocIds("aardvark") shouldBe emptyList()
                fts.searchDocIds("anteater") shouldContainExactly listOf(a)
            } finally {
                db.close()
            }
        }
}
