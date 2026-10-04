package dev.npn.core.db

import androidx.sqlite.execSQL
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** v24 -> v25 adds `field_defs.perDocument`; the registry comes through the upgrade unset. */
class PerDocumentColumnMigrationTest {
    private val dir: File = Files.createTempDirectory("npn-field-defs-migration").toFile()
    private val file = File(dir, "index.db")

    @AfterTest
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun existingDefinitionsKeepTheirRowsAndReadAsNotPerDocument() =
        runTest {
            createFromSchema(file, version = 24) { connection ->
                connection.execSQL(
                    "INSERT INTO field_defs (fieldKey, label, type, showByDefault, aiEnabled, position, hint) " +
                        "VALUES ('title', 'Title', 'text', 1, 1, 0, NULL)",
                )
            }

            val db = npnDatabase(file)
            try {
                // A destructive fallback would have emptied the table.
                val title = db.fieldDefDao().all().firstOrNull { it.fieldKey == "title" }?.toFieldDef()
                title?.label shouldBe "Title"
                title?.perDocument shouldBe false
            } finally {
                db.close()
            }
        }
}
