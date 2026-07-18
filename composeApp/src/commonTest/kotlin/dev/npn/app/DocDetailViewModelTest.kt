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

    // Zoom/pan are no longer VM state; they live in the viewer's ViewerTransform (see
    // ViewerTransformTest for the zoom/pan math).

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
    fun setReviewedAndUnreviewed() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setReviewed(true)
        model.setReviewed(false)
        advanceUntilIdle()
        session.meta.reviewedSets shouldContainExactly listOf(
            listOf("doc-1") to true,
            listOf("doc-1") to false,
        )
    }

    // ---- error handling: failed writes surface a user-facing message ----

    @Test
    fun setTitleFailureEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failSetTitle = { error("disk full") } }
        val model = vm(session = session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        model.setTitle("x")
        advanceUntilIdle()
        session.meta.titleSets shouldBe emptyList()
        messages shouldContainExactly listOf("Could not save title: disk full")
        job.cancel()
    }

    @Test
    fun addTagFailureEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failBatchOps = { error("journal write failed") } }
        val model = vm(session = session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        model.addTag("finance")
        advanceUntilIdle()
        messages shouldContainExactly listOf("Could not add tag: journal write failed")
        job.cancel()
    }

    @Test
    fun setFieldFailureEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failSetField = { error("io") } }
        val model = vm(session = session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        model.setField("amount", "42")
        model.removeField("amount")
        advanceUntilIdle()
        messages shouldContainExactly listOf("Could not save field: io", "Could not remove field: io")
        job.cancel()
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

    @Test
    fun trashFailureSkipsCallbackAndEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failTrashDocIds = setOf("doc-1") }
        val model = vm(session = session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        var done = false
        model.trash { done = true }
        advanceUntilIdle()
        done shouldBe false
        messages shouldContainExactly listOf("Could not move to trash: trash failed for doc-1")
        job.cancel()
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

    @Test
    fun reOcrFailureClearsFlagAndEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { failReOcr = { error("ocr engine missing") } }
        val model = vm(session = session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        model.reOcr()
        advanceUntilIdle()
        model.ui.value.reindexing shouldBe false
        messages shouldContainExactly listOf("Could not re-run OCR: ocr engine missing")
        job.cancel()
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
