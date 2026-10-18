package dev.npn.core.db

import androidx.sqlite.execSQL
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** v26 -> v27 drops `metadata_superseded`; the rest of the accumulator comes through the upgrade. */
class SupersededTableMigrationTest {
    private val dir: File = Files.createTempDirectory("npn-superseded-migration").toFile()
    private val file = File(dir, "index.db")

    @AfterTest
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun theAccumulatorKeepsItsRowsWithoutTheSupersessionTable() =
        runTest {
            createFromSchema(file, version = 26) { connection ->
                connection.execSQL(
                    "INSERT INTO metadata_transaction_actors (actorId, objectHash) VALUES ('actor', 'hash')",
                )
                connection.execSQL(
                    "INSERT INTO metadata_superseded (snapshotPath, supersededHash) VALUES ('snapshot', 'hash')",
                )
            }

            val db = npnDatabase(file)
            try {
                // A destructive fallback would have emptied the table.
                db.metadataProjectionDao().actor("actor") shouldBe MetadataTransactionActorEntity("actor", "hash")
            } finally {
                db.close()
            }
        }
}
