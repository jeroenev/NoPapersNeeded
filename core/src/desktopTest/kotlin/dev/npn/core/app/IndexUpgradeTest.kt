package dev.npn.core.app

import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.createFromSchema
import dev.npn.core.db.exec
import dev.npn.core.util.NpnJson
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Opening a library over the index an older build left behind: what the index held that it cannot
 * rebuild reaches its new home before the index forgets it.
 */
class IndexUpgradeTest {
    private lateinit var library: Path
    private lateinit var data: File

    @BeforeTest
    fun setup() {
        library = Files.createTempDirectory("npn-upgrade-lib")
        data = Files.createTempDirectory("npn-upgrade-data").toFile()
    }

    @AfterTest
    fun teardown() {
        library.toFile().deleteRecursively()
        data.deleteRecursively()
    }

    @Test
    fun savedViewsInTheIndexMoveIntoTheJournal() =
        runTest {
            val view = SavedView("v1", "Invoices", LibraryFilter(tagsAll = listOf("invoice")), pinned = true)
            createFromSchema(File(data, "index.db"), version = 22) { connection ->
                connection.exec(
                    "INSERT INTO saved_views (id, name, json, pinned, position) VALUES (?, ?, ?, 1, 0)",
                    view.id,
                    view.name,
                    NpnJson.Persisted.encodeToString(view),
                )
            }

            openSession(backgroundScope, library, data).use { session, deps ->
                session.savedViews.observeSavedViews().first() shouldBe listOf(view)
                LegacyIndexTables.savedViews(deps.db) shouldBe null
            }
            // Library data now: a device with an index of its own reads them from the journal.
            openSession(backgroundScope, library, File(data, "other-device")).use { session, _ ->
                session.savedViews.observeSavedViews().first() shouldBe listOf(view)
            }
        }
}
