package dev.npn.app

import dev.npn.app.ui.common.resolveEnglish
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiField
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.TagInfo
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import dev.npn.core.ocr.OcrState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailViewModelTest : MainDispatcherTest() {
    /**
     * A viewmodel with its panel state kept live for the whole test: [DocDetailViewModel.metadata]
     * shares while subscribed, so an assertion on `metadata.value` needs a standing collector.
     */
    private fun TestScope.vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        docId: String = "doc-1",
    ) = DocDetailViewModel(docId, session).also { model ->
        backgroundScope.launch { model.metadata.collect {} }
    }

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
            session.docLifecycle.trashedDocs shouldContainExactly listOf("doc-1")
            done shouldBe true
        }

    @Test
    fun trashFailureSkipsCallbackAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("doc-1") }
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
            session.ocr.reOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun reOcrFailureClearsFlagAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ocr.failReOcr = { error("ocr engine missing") } }
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
            session.ocr.ocrEngineSets shouldContainExactly
                listOf(
                    "doc-1" to "tesseract",
                    "doc-1" to null,
                )
        }

    @Test
    fun setOcrEngineFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ocr.failSetOcrEngine = { error("reindex failed") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.setOcrEngine("mlkit")
            advanceUntilIdle()
            session.ocr.ocrEngineSets shouldBe emptyList()
            messages shouldContainExactly listOf("Could not change OCR engine: reindex failed")
            job.cancel()
        }

    // ---- OCR versions load + reload on ocrState change ----

    @Test
    fun ocrVersionsLoadFromSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ocr.ocrVersionsList = listOf(OcrVersion("tesseract", "dev-a", "eng", 0L, active = true))
                    documents.singleDoc.value = listItem("doc-1")
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.metadata.value.document.ocrVersions shouldContainExactly session.ocr.ocrVersionsList
        }

    @Test
    fun ocrVersionsReloadWhenOcrStateChanges() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = DocListItem(doc("doc-1", ocrState = OcrState.NONE), emptyList())
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.metadata.value.document.ocrVersions shouldBe emptyList()

            // A newly-completed OCR run (state -> done) should trigger a reload with the fresh version.
            val fresh = listOf(OcrVersion("tesseract", "dev-a", "eng", 0L, active = true))
            session.ocr.ocrVersionsList = fresh
            session.documents.singleDoc.value = DocListItem(doc("doc-1", ocrState = OcrState.DONE), emptyList())
            advanceUntilIdle()
            model.metadata.value.document.ocrVersions shouldContainExactly fresh
        }

    // ---- assembled panel state ----

    @Test
    fun metadataCarriesTheDocumentVocabularyAndDerivedReviewFlag() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = DocListItem(doc("doc-1", reviewed = true), listOf("finance"))
                    documents.correspondents.value = listOf("ACME")
                    documents.docTypes.value = listOf("invoice")
                    documents.fieldKeys.value = listOf("amount")
                    tags.tags.value = listOf(TagInfo("finance", "#ff0000", null, 1))
                    reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
                    reviewConfig.standardFieldsFlow.value = setOf(StandardField.NOTES)
                }
            val model = vm(session = session)
            advanceUntilIdle()

            val state = model.metadata.value
            state.document.item
                ?.doc
                ?.docId shouldBe "doc-1"
            // The panel is handed the resolved flag, not the raw one plus a mode to re-derive from.
            state.document.reviewed shouldBe true
            state.document.reviewMode shouldBe ReviewMode.INBOX_WORKFLOW
            state.document.visibleFields shouldBe setOf(StandardField.NOTES)
            state.vocabulary.correspondents shouldContainExactly listOf("ACME")
            state.vocabulary.docTypes shouldContainExactly listOf("invoice")
            state.vocabulary.fieldKeys shouldContainExactly listOf("amount")
            state.vocabulary.allTagNames shouldContainExactly listOf("finance")
        }

    @Test
    fun metadataStartsAtTheSeededItemBeforeTheIndexEmits() =
        runTest(dispatcher) {
            // The library's preview panel already holds the row and rebuilds this viewmodel per
            // document; the seed is what keeps the panel from blanking on every switch, so it must
            // be visible before anything is collected or dispatched.
            val seed = DocListItem(doc("doc-1", title = "Seeded"), listOf("finance"))
            val model = DocDetailViewModel("doc-1", FakeLibrarySession(), initialItem = seed)

            model.metadata.value.document.item shouldBe seed
        }

    @Test
    fun metadataDerivesReviewedFromTagsInUntaggedOnlyMode() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    // Stored flag false, but the doc carries a tag: UNTAGGED_ONLY treats it reviewed.
                    documents.singleDoc.value = DocListItem(doc("doc-1", reviewed = false), listOf("finance"))
                    reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
                }
            val model = vm(session = session)
            advanceUntilIdle()

            model.metadata.value.document.reviewed shouldBe true
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
            val session =
                FakeLibrarySession().apply {
                    aiSuggest.edit = AiEditSuggestion(addTags = tagSuggestions("finance", "invoice"))
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            session.aiSuggest.suggestEditsCalls shouldContainExactly listOf("doc-1")
            model.metadata.value.ai.tags.suggestions shouldContainExactly tagSuggestions("finance", "invoice")
            model.metadata.value.ai.tags.suggesting shouldBe false
        }

    @Test
    fun acceptAiTagAddsTagAndRemovesSuggestion() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiSuggest.edit = AiEditSuggestion(addTags = tagSuggestions("finance", "invoice"))
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            model.acceptAiTag("finance")
            advanceUntilIdle()
            session.meta.addedTags shouldContainExactly listOf(listOf("doc-1") to "finance")
            model.metadata.value.ai.tags.suggestions shouldContainExactly tagSuggestions("invoice")
        }

    @Test
    fun acceptAiTagFailureKeepsSuggestion() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiSuggest.edit = AiEditSuggestion(addTags = tagSuggestions("finance", "invoice"))
                    meta.failBatchOps = { error("disk full") }
                }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()

            model.acceptAiTag("finance")
            advanceUntilIdle()

            model.metadata.value.ai.tags.suggestions shouldContainExactly tagSuggestions("finance", "invoice")
        }

    @Test
    fun suggestAiTagsEmptySurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion.EMPTY }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf("No suggestions - document may not be OCRed yet")
            model.metadata.value.ai.tags.suggestions shouldBe emptyList()
            model.metadata.value.ai.tags.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiTagsFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiSuggest.failSuggestEdits = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not suggest tags: network down")
            model.metadata.value.ai.tags.suggestions shouldBe emptyList()
            model.metadata.value.ai.tags.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun aiAvailabilityReflectsConfig() =
        runTest(dispatcher) {
            val configured = FakeLibrarySession()
            val configuredVm = vm(session = configured)
            advanceUntilIdle()
            configuredVm.metadata.value.ai.tags.available shouldBe true

            val disabled =
                FakeLibrarySession().apply {
                    aiSuggest.configuredValue = false
                }
            val disabledVm = vm(session = disabled)
            advanceUntilIdle()
            disabledVm.metadata.value.ai.tags.available shouldBe false
        }

    // ---- AI metadata suggestions ----

    @Test
    fun suggestAiMetadataStoresSuggestion() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion(metadata = suggestion) }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            session.aiSuggest.suggestEditsCalls shouldContainExactly listOf("doc-1")
            model.metadata.value.ai.metadata.suggestion shouldBe suggestion
            model.metadata.value.ai.metadata.suggesting shouldBe false
        }

    @Test
    fun acceptAiMetadataWritesFieldAndRemovesSuggestion() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion(metadata = suggestion) }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.acceptAiMetadata(AiField.TITLE)
            model.acceptAiMetadata(AiField.CORRESPONDENT)
            model.acceptAiMetadata(AiField.DOC_DATE)
            advanceUntilIdle()

            session.meta.titleSets shouldContainExactly listOf("doc-1" to "Invoice Jan")
            session.meta.correspondentSets shouldContainExactly listOf(listOf("doc-1") to "Acme")
            session.meta.docDateSets shouldContainExactly listOf("doc-1" to "2024-01-15")
            model.metadata.value.ai.metadata.suggestion shouldBe MetadataSuggestion.EMPTY
        }

    @Test
    fun dismissAiMetadataDropsOnlyThatField() =
        runTest(dispatcher) {
            val suggestion = MetadataSuggestion(title = "Invoice Jan", correspondent = "Acme", docDate = "2024-01-15")
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion(metadata = suggestion) }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.dismissAiMetadata(AiField.CORRESPONDENT)
            advanceUntilIdle()

            model.metadata.value.ai.metadata.suggestion shouldBe suggestion.copy(correspondent = null)
            session.meta.correspondentSets shouldBe emptyList()
        }

    @Test
    fun suggestAiMetadataEmptySurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession() // fake defaults to AiEditSuggestion.EMPTY
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf("No suggestions - document may not be OCRed yet")
            model.metadata.value.ai.metadata.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiMetadataFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiSuggest.failSuggestEdits = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not suggest details: network down")
            model.metadata.value.ai.metadata.suggestion shouldBe MetadataSuggestion.EMPTY
            model.metadata.value.ai.metadata.suggesting shouldBe false
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
            advanceUntilIdle()
            model.metadata.value.vocabulary.tagColors shouldContainExactly
                mapOf("finance" to "#ff0000", "draft" to null)
        }
}
