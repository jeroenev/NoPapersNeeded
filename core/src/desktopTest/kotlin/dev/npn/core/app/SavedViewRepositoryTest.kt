package dev.npn.core.app

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SavedViewRepositoryTest {
    @Test
    fun savedViewRoundTrips() =
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
            } finally {
                rig.close()
            }
        }

    /** Saved views are library data: what one device saves or deletes, every device sees. */
    @Test
    fun savedViewsSyncBetweenDevices() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            val (second, secondDb) = rig.secondSession(backgroundScope)
            try {
                val invoices = SavedView("a", "Invoices", LibraryFilter(tagsAll = listOf("invoice")))
                val receipts = SavedView("b", "Receipts", viewType = ViewType.GRID)
                rig.session.savedViews.save(invoices)
                second.savedViews.save(receipts)
                rig.session.rescan()
                second.rescan()

                rig.session.savedViews
                    .observeSavedViews()
                    .first() shouldBe listOf(invoices, receipts)
                second.savedViews.observeSavedViews().first() shouldBe listOf(invoices, receipts)

                second.savedViews.delete("a")
                rig.session.rescan()
                rig.session.savedViews
                    .observeSavedViews()
                    .first() shouldBe listOf(receipts)
            } finally {
                second.close()
                secondDb.close()
                rig.close()
            }
        }
}
