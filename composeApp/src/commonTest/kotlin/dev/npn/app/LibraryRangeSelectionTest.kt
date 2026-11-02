package dev.npn.app

import dev.npn.app.ui.library.LibraryViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Shift+click: the range from the document last clicked into the selection to the one clicked. */
class LibraryRangeSelectionTest : MainDispatcherTest() {
    private val order = listOf("a", "b", "c", "d", "e")

    // Lazy: built inside the test, once the test dispatcher is Main.
    private val vm by lazy { LibraryViewModel(FakeLibrarySession(), FakeSettingsRepository()) }

    @Test
    fun aShiftClickSelectsTheRangeFromTheLastClickedDocument() =
        runTest(dispatcher) {
            vm.startSelection("b")
            vm.selectRange("d", order)

            vm.ui.value.selected shouldBe setOf("b", "c", "d")
        }

    @Test
    fun theRangeRunsUpwardsTooAndKeepsWhatWasSelected() =
        runTest(dispatcher) {
            vm.startSelection("a")
            vm.toggleSelection("e")
            vm.selectRange("c", order)

            vm.ui.value.selected shouldBe setOf("a", "c", "d", "e")
        }

    /** Another Shift+click extends from the same anchor, not from the end of the last range. */
    @Test
    fun theAnchorStaysForTheNextShiftClick() =
        runTest(dispatcher) {
            vm.startSelection("c")
            vm.selectRange("e", order)
            vm.selectRange("a", order)

            vm.ui.value.selected shouldBe setOf("a", "b", "c", "d", "e")
        }

    @Test
    fun withoutAnAnchorAShiftClickSelectsTheOneDocument() =
        runTest(dispatcher) {
            vm.selectRange("c", order)

            vm.ui.value.selected shouldBe setOf("c")
        }
}
