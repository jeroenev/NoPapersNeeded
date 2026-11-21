package dev.npn.core.app

import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlin.test.Test

class SavedViewRepositoryTest {
    @Test
    fun savedViewRoundTrips() =
        appSessionTest { rig ->
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
        }

    /** Saved views are library data: what one device saves or deletes, every device sees. */
    @Test
    fun savedViewsSyncBetweenDevices() =
        appSessionTest { rig ->
            val (second, _) = rig.secondSession(backgroundScope)
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
        }

    /**
     * A view a newer build saved, of a type this build does not have, is not listed: read as a list
     * view, a rename or a pin here would write it back as one.
     */
    @Test
    fun aViewOfATypeThisBuildDoesNotHaveIsNotListed() =
        appSessionTest { rig ->
            val mine = SavedView("a", "Invoices")
            rig.session.savedViews.save(mine)
            val newer = """{"id":"b","name":"Board","viewType":"BOARD"}"""
            rig
                .writer("newer-installation")
                .commit(listOf(PendingOp.settingSet(LibrarySettingKeys.SAVED_VIEW_PREFIX + "b", newer)))
            rig.session.rescan()

            rig.session.savedViews
                .observeSavedViews()
                .first() shouldBe listOf(mine)
        }

    /**
     * A view a newer build saved with a filter key this build does not have is not listed either:
     * applied without that key it would show more than it means, and a rename here would drop it.
     */
    @Test
    fun aViewWithAFilterKeyThisBuildDoesNotHaveIsNotListed() =
        appSessionTest { rig ->
            val mine = SavedView("a", "Invoices")
            rig.session.savedViews.save(mine)
            val newer = """{"id":"b","name":"Long ones","filter":{"pagesAtLeast":10}}"""
            rig
                .writer("newer-installation")
                .commit(listOf(PendingOp.settingSet(LibrarySettingKeys.SAVED_VIEW_PREFIX + "b", newer)))
            rig.session.rescan()

            rig.session.savedViews
                .observeSavedViews()
                .first() shouldBe listOf(mine)
        }

    /**
     * What older builds wrote still lists: their retired filter keys (written out with every default)
     * and a newer build's option nobody set do not hide a view.
     */
    @Test
    fun aViewWithRetiredKeysOrUnusedNewOnesIsListed() =
        appSessionTest { rig ->
            val older =
                """{"id":"a","name":"Old","filter":{"addedFrom":null,"addedTo":null,"correspondent":"ACME",""" +
                    """"docType":null,"docDateFrom":null,"docDateTo":null,"missingAny":[]}}"""
            val newer = """{"id":"b","name":"New","filter":{"pagesAtLeast":null}}"""
            rig
                .writer("other-installation")
                .commit(
                    listOf(
                        PendingOp.settingSet(LibrarySettingKeys.SAVED_VIEW_PREFIX + "a", older),
                        PendingOp.settingSet(LibrarySettingKeys.SAVED_VIEW_PREFIX + "b", newer),
                    ),
                )
            rig.session.rescan()

            rig.session.savedViews
                .observeSavedViews()
                .first()
                .map { it.id } shouldBe listOf("b", "a")
        }
}
