package dev.npn.core.app

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SavedViewRepositoryTest {
    @Test
    fun roundtripAndExportImport() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val repo = rig.session.savedViews
                val view =
                    SavedView(
                        id = "v1",
                        name = "Invoices",
                        filter = LibraryFilter(tagsAll = listOf("invoice"), reviewed = null),
                        sort = LibrarySort(SortField.Name, ascending = true),
                        viewType = ViewType.GRID,
                        pinned = true,
                    )
                repo.save(view)

                val stored = repo.observeSavedViews().first()
                stored shouldHaveSize 1
                stored.first() shouldBe view

                val json = repo.exportJson()

                // Fresh repo (new library) imports the JSON.
                val rig2 = AppSessionRig.create(backgroundScope)
                try {
                    val n = rig2.session.savedViews.importJson(json)
                    n shouldBe 1
                    rig2.session.savedViews
                        .observeSavedViews()
                        .first()
                        .first() shouldBe view
                } finally {
                    rig2.close()
                }
            } finally {
                rig.close()
            }
        }

    @Test
    fun importSkipsBrokenEntriesAndDedupsById() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val json =
                    """
                    [
                      {"id":"a","name":"A","viewType":"LIST"},
                      {"broken":true},
                      {"id":"a","name":"A dup","viewType":"LIST"},
                      {"id":"b","name":"B","viewType":"GRID"}
                    ]
                    """.trimIndent()
                val n = rig.session.savedViews.importJson(json)
                n shouldBe 2 // a (once), b; broken skipped, dup deduped
                rig.session.savedViews
                    .observeSavedViews()
                    .first() shouldHaveSize 2
            } finally {
                rig.close()
            }
        }
}
