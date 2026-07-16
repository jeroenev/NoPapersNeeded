package dev.npn.app

import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.app.TagInfo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailViewModelTest : MainDispatcherTest() {

    private fun vm(session: FakeLibrarySession = FakeLibrarySession(), docId: String = "doc-1") =
        DocDetailViewModel(docId, session)

    // ---- page count resolution ----

    @Test
    fun pageCountResolvedOnInit() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.pageCountValue = 7 }
        val model = vm(session = session)
        advanceUntilIdle()
        model.ui.value.pageCount shouldBe 7
    }

    @Test
    fun pageCountFailureLeavesZero() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.failPageCount = { error("boom") } }
        val model = vm(session = session)
        advanceUntilIdle()
        model.ui.value.pageCount shouldBe 0
    }

    // ---- paging bounds ----

    @Test
    fun nextPageClampsAtLastPage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.pageCountValue = 2 }
        val model = vm(session = session)
        advanceUntilIdle()
        model.nextPage()
        model.ui.value.pageIndex shouldBe 1
        model.nextPage() // already at last (count 2 -> max index 1)
        model.ui.value.pageIndex shouldBe 1
    }

    @Test
    fun prevPageClampsAtZero() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.pageCountValue = 3 }
        val model = vm(session = session)
        advanceUntilIdle()
        model.prevPage()
        model.ui.value.pageIndex shouldBe 0
    }

    @Test
    fun goToPageIgnoresOutOfRange() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.pageCountValue = 3 }
        val model = vm(session = session)
        advanceUntilIdle()
        model.goToPage(2)
        model.ui.value.pageIndex shouldBe 2
        model.goToPage(5)
        model.ui.value.pageIndex shouldBe 2
        model.goToPage(-1)
        model.ui.value.pageIndex shouldBe 2
    }

    // ---- zoom clamping ----

    @Test
    fun zoomClampsToRange() = runTest(dispatcher) {
        val model = vm()
        model.setZoom(10f)
        model.ui.value.zoom shouldBe 5f
        model.setZoom(0.1f)
        model.ui.value.zoom shouldBe 1f
    }

    @Test
    fun zoomInOutAndReset() = runTest(dispatcher) {
        val model = vm()
        model.zoomIn()
        model.ui.value.zoom shouldBe 1.5f
        model.zoomOut()
        model.ui.value.zoom shouldBe 1f
        model.zoomIn()
        model.resetZoom()
        model.ui.value.zoom shouldBe 1f
    }

    // ---- metadata edits (blank -> null normalization) ----

    @Test
    fun setTitleBlankBecomesNull() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setTitle("   ")
        advanceUntilIdle()
        session.meta.titleSets shouldContainExactly listOf("doc-1" to null)
    }

    @Test
    fun setTitleNonBlankPassesThrough() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setTitle("Invoice 2026")
        advanceUntilIdle()
        session.meta.titleSets shouldContainExactly listOf("doc-1" to "Invoice 2026")
    }

    @Test
    fun setCorrespondentBlankBecomesNull() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setCorrespondent("")
        advanceUntilIdle()
        session.meta.correspondentSets shouldContainExactly listOf(listOf("doc-1") to null)
    }

    @Test
    fun addAndRemoveTagTargetThisDoc() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.addTag("finance")
        model.removeTag("draft")
        advanceUntilIdle()
        session.meta.addedTags shouldContainExactly listOf(listOf("doc-1") to "finance")
        session.meta.removedTags shouldContainExactly listOf(listOf("doc-1") to "draft")
    }

    @Test
    fun markReviewedAndUnreviewed() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.markReviewed()
        model.markUnreviewed()
        advanceUntilIdle()
        session.meta.reviewedSets shouldContainExactly listOf(
            listOf("doc-1") to true,
            listOf("doc-1") to false,
        )
    }

    // ---- error handling: launch swallows write failures ----

    @Test
    fun setTitleFailureDoesNotCrash() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failSetTitle = { error("write failed") } }
        val model = vm(session = session)
        model.setTitle("x")
        advanceUntilIdle()
        // No exception propagated; the write was attempted and swallowed.
        session.meta.titleSets shouldBe emptyList()
    }

    // ---- trash invokes callback ----

    @Test
    fun trashCallsSessionAndCallback() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        var done = false
        model.trash { done = true }
        advanceUntilIdle()
        session.meta.trashedDocs shouldContainExactly listOf("doc-1")
        done shouldBe true
    }

    // ---- reOcr toggles reindexing flag ----

    @Test
    fun reOcrTogglesReindexingFlag() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.reOcr()
        advanceUntilIdle()
        session.reOcrIds shouldContainExactly listOf("doc-1")
        model.ui.value.reindexing shouldBe false
    }

    // ---- bake ----

    @Test
    fun bakeInPlaceReturnsResultAndClearsBusy() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.bakeResult = true }
        val model = vm(session = session)
        var result: Boolean? = null
        model.bakeInPlace { result = it }
        advanceUntilIdle()
        result shouldBe true
        model.ui.value.baking shouldBe false
    }

    @Test
    fun bakeInPlaceFailureReturnsFalse() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { content.failBake = { error("no perms") } }
        val model = vm(session = session)
        var result: Boolean? = null
        model.bakeInPlace { result = it }
        advanceUntilIdle()
        result shouldBe false
        model.ui.value.baking shouldBe false
    }

    // ---- tag colors transformation ----

    @Test
    fun tagColorsMapsNameToColor() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            tags.tags.value = listOf(
                TagInfo("finance", "#ff0000", null, 3),
                TagInfo("draft", null, null, 1),
            )
        }
        val model = vm(session = session)
        val job = launch { model.tagColors.collect {} }
        advanceUntilIdle()
        model.tagColors.value shouldContainExactly mapOf("finance" to "#ff0000", "draft" to null)
        job.cancel()
    }
}
