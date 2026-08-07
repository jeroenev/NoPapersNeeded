package dev.npn.app

import dev.npn.app.ui.common.AiMetadataField
import dev.npn.app.ui.common.resolveEnglish
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
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
    private fun vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        docId: String = "doc-1",
    ) = DocDetailViewModel(docId, session)

    private fun tagSuggestions(vararg tags: String): List<TagSuggestion> = tags.map { TagSuggestion(it, isNew = false) }

    // ---- page count resolution ----

    @Test
    fun pageCountResolvedOnInit() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = 7 }
            val model = vm(session = session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe 7
        }

    @Test
    fun pageCountFailureLeavesZero() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failPageCount = { error("boom") } }
            val model = vm(session = session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe 0
        }

    // ---- paging bounds ----

    @Test
    fun goToPageIgnoresOutOfRange() =
        runTest(dispatcher) {
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
    fun setTitleBlankBecomesNull() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setTitle("   ")
            advanceUntilIdle()
            session.meta.titleSets shouldContainExactly listOf("doc-1" to null)
        }

    @Test
    fun setTitleNonBlankPassesThrough() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setTitle("Invoice 2026")
            advanceUntilIdle()
            session.meta.titleSets shouldContainExactly listOf("doc-1" to "Invoice 2026")
        }

    @Test
    fun setCorrespondentBlankBecomesNull() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setCorrespondent("")
            advanceUntilIdle()
            session.meta.correspondentSets shouldContainExactly listOf(listOf("doc-1") to null)
        }

    @Test
    fun addAndRemoveTagTargetThisDoc() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.addTag("finance")
            model.removeTag("draft")
            advanceUntilIdle()
            session.meta.addedTags shouldContainExactly listOf(listOf("doc-1") to "finance")
            session.meta.removedTags shouldContainExactly listOf(listOf("doc-1") to "draft")
        }

    @Test
    fun setReviewedAndUnreviewed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setReviewed(true)
            model.setReviewed(false)
            advanceUntilIdle()
            session.meta.reviewedSets shouldContainExactly
                listOf(
                    listOf("doc-1") to true,
                    listOf("doc-1") to false,
                )
        }

    // ---- error handling: failed writes surface a user-facing message ----

    @Test
    fun setTitleFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failSetTitle = { error("disk full") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.setTitle("x")
            advanceUntilIdle()
            session.meta.titleSets shouldBe emptyList()
            messages shouldContainExactly listOf("Could not save title: disk full")
            job.cancel()
        }

    @Test
    fun addTagFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failBatchOps = { error("journal write failed") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.addTag("finance")
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not add tag: journal write failed")
            job.cancel()
        }

    @Test
    fun setFieldFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failSetField = { error("io") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.setField("amount", "42")
            model.removeField("amount")
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not save field: io", "Could not remove field: io")
            job.cancel()
        }

    // ---- trash invokes callback ----

    @Test
    fun trashCallsSessionAndCallback() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            var done = false
            model.trash { done = true }
            advanceUntilIdle()
            session.meta.trashedDocs shouldContainExactly listOf("doc-1")
            done shouldBe true
        }

    @Test
    fun trashFailureSkipsCallbackAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failTrashDocIds = setOf("doc-1") }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
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
    fun reOcrTogglesReindexingFlag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.reOcr()
            advanceUntilIdle()
            session.reOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun reOcrFailureClearsFlagAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { failReOcr = { error("ocr engine missing") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.reOcr()
            advanceUntilIdle()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly listOf("Could not re-run OCR: ocr engine missing")
            job.cancel()
        }

    // ---- OCR engine pin ----

    @Test
    fun setOcrEnginePinsAndClears() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setOcrEngine("tesseract")
            model.setOcrEngine(null)
            advanceUntilIdle()
            session.ocrEngineSets shouldContainExactly
                listOf(
                    "doc-1" to "tesseract",
                    "doc-1" to null,
                )
        }

    @Test
    fun setOcrEngineFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { failSetOcrEngine = { error("reindex failed") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.setOcrEngine("mlkit")
            advanceUntilIdle()
            session.ocrEngineSets shouldBe emptyList()
            messages shouldContainExactly listOf("Could not change OCR engine: reindex failed")
            job.cancel()
        }

    // ---- OCR versions load + reload on ocrState change ----

    @Test
    fun ocrVersionsLoadFromSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ocrVersionsList = listOf(OcrVersion("tesseract", "dev-a", "eng", 0L, active = true))
                    documents.singleDoc.value = listItem("doc-1")
                }
            val model = vm(session = session)
            val job = launch { model.ocrVersions.collect {} }
            advanceUntilIdle()
            model.ocrVersions.value shouldContainExactly session.ocrVersionsList
            job.cancel()
        }

    @Test
    fun ocrVersionsReloadWhenOcrStateChanges() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = DocListItem(doc("doc-1").copy(ocrState = "none"), emptyList())
                }
            val model = vm(session = session)
            val job = launch { model.ocrVersions.collect {} }
            advanceUntilIdle()
            model.ocrVersions.value shouldBe emptyList()

            // A newly-completed OCR run (state -> done) should trigger a reload with the fresh version.
            val fresh = listOf(OcrVersion("tesseract", "dev-a", "eng", 0L, active = true))
            session.ocrVersionsList = fresh
            session.documents.singleDoc.value = DocListItem(doc("doc-1").copy(ocrState = "done"), emptyList())
            advanceUntilIdle()
            model.ocrVersions.value shouldContainExactly fresh
            job.cancel()
        }

    // ---- bake ----

    @Test
    fun bakeInPlaceReturnsResultAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.bakeResult = true }
            val model = vm(session = session)
            var result: Boolean? = null
            model.bakeInPlace { result = it }
            advanceUntilIdle()
            result shouldBe true
            model.ui.value.baking shouldBe false
        }

    @Test
    fun bakeInPlaceFailureReturnsFalse() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failBake = { error("no perms") } }
            val model = vm(session = session)
            var result: Boolean? = null
            model.bakeInPlace { result = it }
            advanceUntilIdle()
            result shouldBe false
            model.ui.value.baking shouldBe false
        }

    // ---- AI tag suggestions ----

    @Test
    fun suggestAiTagsStoresSuggestions() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.suggestions = tagSuggestions("finance", "invoice") }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            session.ai.suggestTagsCalls shouldContainExactly listOf("doc-1")
            model.aiTags.value.suggestions shouldContainExactly tagSuggestions("finance", "invoice")
            model.aiTags.value.suggesting shouldBe false
        }

    @Test
    fun acceptAiTagAddsTagAndRemovesSuggestion() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.suggestions = tagSuggestions("finance", "invoice") }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            model.acceptAiTag("finance")
            advanceUntilIdle()
            session.meta.addedTags shouldContainExactly listOf(listOf("doc-1") to "finance")
            model.aiTags.value.suggestions shouldContainExactly tagSuggestions("invoice")
        }

    @Test
    fun acceptAiTagFailureKeepsSuggestion() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ai.suggestions = tagSuggestions("finance", "invoice")
                    meta.failBatchOps = { error("disk full") }
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()

            model.acceptAiTag("finance")
            advanceUntilIdle()

            model.aiTags.value.suggestions shouldContainExactly tagSuggestions("finance", "invoice")
        }

    @Test
    fun suggestAiTagsEmptySurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.suggestions = emptyList() }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf("No suggestions - document may not be OCRed yet")
            model.aiTags.value.suggestions shouldBe emptyList()
            model.aiTags.value.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiTagsFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.failSuggestTags = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not suggest tags: network down")
            model.aiTags.value.suggestions shouldBe emptyList()
            model.aiTags.value.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun aiAvailabilityReflectsConfig() =
        runTest(dispatcher) {
            val configured = FakeLibrarySession()
            val configuredVm = vm(session = configured)
            advanceUntilIdle()
            configuredVm.aiTags.value.available shouldBe true

            val disabled =
                FakeLibrarySession().apply {
                    ai.configValue = ai.configValue.copy(enabled = false)
                }
            val disabledVm = vm(session = disabled)
            advanceUntilIdle()
            disabledVm.aiTags.value.available shouldBe false
        }

    // ---- AI metadata suggestions ----

    @Test
    fun suggestAiMetadataStoresSuggestion() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { ai.metadataSuggestion = suggestion }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            session.ai.suggestMetadataCalls shouldContainExactly listOf("doc-1")
            model.aiMetadata.value.suggestion shouldBe suggestion
            model.aiMetadata.value.suggesting shouldBe false
        }

    @Test
    fun acceptAiMetadataWritesFieldAndRemovesSuggestion() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { ai.metadataSuggestion = suggestion }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.acceptAiMetadata(AiMetadataField.TITLE)
            model.acceptAiMetadata(AiMetadataField.CORRESPONDENT)
            model.acceptAiMetadata(AiMetadataField.DOC_DATE)
            advanceUntilIdle()

            session.meta.titleSets shouldContainExactly listOf("doc-1" to "Invoice Jan")
            session.meta.correspondentSets shouldContainExactly listOf(listOf("doc-1") to "Acme")
            session.meta.docDateSets shouldContainExactly listOf("doc-1" to "2024-01-15")
            model.aiMetadata.value.suggestion shouldBe MetadataSuggestion.EMPTY
        }

    @Test
    fun dismissAiMetadataDropsOnlyThatField() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { ai.metadataSuggestion = suggestion }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.dismissAiMetadata(AiMetadataField.CORRESPONDENT)

            model.aiMetadata.value.suggestion shouldBe suggestion.copy(correspondent = null)
            session.meta.correspondentSets shouldBe emptyList()
        }

    @Test
    fun suggestAiMetadataEmptySurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession() // fake defaults to MetadataSuggestion.EMPTY
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf("No suggestions - document may not be OCRed yet")
            model.aiMetadata.value.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiMetadataFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.failSuggestMetadata = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not suggest details: network down")
            model.aiMetadata.value.suggestion shouldBe MetadataSuggestion.EMPTY
            model.aiMetadata.value.suggesting shouldBe false
            job.cancel()
        }

    // ---- tag colors transformation ----

    @Test
    fun tagColorsMapsNameToColor() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    tags.tags.value =
                        listOf(
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
