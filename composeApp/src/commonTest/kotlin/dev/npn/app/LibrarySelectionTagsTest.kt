package dev.npn.app

import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The remove-tag picker's candidate list. Kept out of the already-large [LibraryViewModelTest]
 * because it is a self-contained read over the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySelectionTagsTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) =
        LibraryViewModel(session, FakeSettingsRepository())

    @Test
    fun removeTagPickerListsOnlyTagsCarriedByTheSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value =
                        listOf(
                            listItem("d1", tags = listOf("invoices", "urgent")),
                            listItem("d2", tags = listOf("urgent", "archive")),
                            // Not selected: its tags must not be offered.
                            listItem("d3", tags = listOf("unrelated")),
                        )
                }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.tagsInSelection() shouldContainExactly listOf("archive", "invoices", "urgent")
        }

    @Test
    fun removeTagPickerIsEmptyWithoutASelection() =
        runTest(dispatcher) {
            vm().tagsInSelection() shouldBe emptyList()
        }
}
