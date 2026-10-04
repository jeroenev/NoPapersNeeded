package dev.npn.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.npn.core.index.FtsRows
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            createVersion(21) { connection ->
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
                db.documentDao().getByDocId(a)!!.relPath shouldBe "a.pdf"
                val fts = db.ftsDao()
                fts.contentForDoc(a) shouldBe "aardvark"
                fts.contentForDoc(b) shouldBe "buffalo"
                fts.searchDocIds("buffalo") shouldContainExactly listOf(b)

                // New keys continue past the migrated rowids instead of colliding with them.
                val c = "c".repeat(64)
                fts.replaceDocument(FtsRows.row(c, "c.pdf", emptyList(), emptyMap(), "condor"))
                fts.replaceDocument(FtsRows.row(a, "a.pdf", emptyList(), emptyMap(), "anteater"))
                fts.searchDocIds("condor") shouldContainExactly listOf(c)
                fts.searchDocIds("aardvark") shouldBe emptyList()
                fts.searchDocIds("anteater") shouldContainExactly listOf(a)
            } finally {
                db.close()
            }
        }

    /** Create [file] as Room's exported schema [version] describes it, then let [seed] fill it. */
    private fun createVersion(
        version: Int,
        seed: (SQLiteConnection) -> Unit,
    ) {
        val schema =
            Json
                .parseToJsonElement(File("schemas/dev.npn.core.db.NpnDatabase/$version.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                fun create(sql: String) = connection.execSQL(sql.replace("\${TABLE_NAME}", table))
                create(entity.getValue("createSql").jsonPrimitive.content)
                entity["indices"]?.jsonArray?.forEach { index ->
                    create(index.jsonObject.getValue("createSql").jsonPrimitive.content)
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $version")
            seed(connection)
        } finally {
            connection.close()
        }
    }
}
