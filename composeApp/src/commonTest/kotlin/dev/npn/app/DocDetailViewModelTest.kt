package dev.npn.app

import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.TagInfo
import dev.npn.core.db.FieldKeyCount
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
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

    @Test
    fun setFieldPassesBlankThroughForTheWriterToClear() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setField(FieldKeys.TITLE, "   ")
            advanceUntilIdle()
            // Blank reaches the writer as-is; canonicalization there turns it into a clear.
            session.meta.fieldSets shouldContainExactly listOf(Triple(listOf("doc-1"), FieldKeys.TITLE, "   "))
        }

    @Test
    fun setFieldNonBlankPassesThrough() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setField(FieldKeys.TITLE, "Invoice 2026")
            advanceUntilIdle()
            session.meta.fieldSets shouldContainExactly listOf(Triple(listOf("doc-1"), FieldKeys.TITLE, "Invoice 2026"))
        }

    @Test
    fun defineFieldRegistersThenWritesTheValue() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val def =
                FieldDef("amount", "Amount", FieldType.DECIMAL, showByDefault = false, aiEnabled = false, position = 5)
            model.defineField(def, "12.50")
            model.defineField(def.copy(key = "vendor", label = "Vendor", type = FieldType.TEXT), null)
            advanceUntilIdle()
            session.fieldRegistry.upserts.map { it.key } shouldContainExactly listOf("amount", "vendor")
            // No value, no write: defining a field does not stamp an empty value on the document.
            session.meta.fieldSets shouldContainExactly listOf(Triple(listOf("doc-1"), "amount", "12.50"))
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

    @Test
    fun aiOcrActionIsOfferedOnlyWithAConfiguredEndpoint() =
        runTest(dispatcher) {
            val without = vm(session = FakeLibrarySession())
            advanceUntilIdle()
            without.ui.value.aiOcr shouldBe AiOcrOffer.Unavailable

            val with = vm(session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Ready })
            advanceUntilIdle()
            with.ui.value.aiOcr shouldBe AiOcrOffer.Ready
        }

    @Test
    fun aLongDocumentIsStillOfferedButCarriesItsPageCount() =
        runTest(dispatcher) {
            // Size is a warning the UI delivers, not a refusal: the state has to carry the number
            // so the confirmation can name it.
            val session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Large(pages = 214) }
            val model = vm(session = session)
            advanceUntilIdle()

            model.ui.value.aiOcr shouldBe AiOcrOffer.Large(pages = 214)
        }

    @Test
    fun ocrWithAiQueuesTheDocumentAndClearsTheReindexingFlag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Ready }
            val model = vm(session = session)
            model.ocrWithAi()
            advanceUntilIdle()
            session.ocr.aiOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun ocrWithAiRunsALongDocumentOnceItIsConfirmed() =
        runTest(dispatcher) {
            // The confirmation lives in the UI, so the viewmodel must not add a second refusal
            // behind it: a confirmed long document queues like any other.
            val session = FakeLibrarySession().apply { ocr.aiOcr = AiOcrOffer.Large(pages = 214) }
            val model = vm(session = session)
            model.ocrWithAi()
            advanceUntilIdle()

            session.ocr.aiOcrIds shouldContainExactly listOf("doc-1")
            model.ui.value.reindexing shouldBe false
        }

    @Test
    fun rejectedAiOcrRequestSurfacesAsAMessage() =
        runTest(dispatcher) {
            // A request the session refuses (the endpoint went away between offering the action and
            // tapping it) comes back as a throw; the user has to see why nothing happened rather
            // than watch the action do nothing.
            val session =
                FakeLibrarySession().apply {
                    ocr.aiOcr = AiOcrOffer.Ready
                    ocr.failAiOcr = { error("AI OCR needs a configured AI endpoint (Settings > AI assistant)") }
                }
            val model = vm(session = session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.ocrWithAi()
            advanceUntilIdle()
            session.ocr.aiOcrIds shouldBe emptyList()
            model.ui.value.reindexing shouldBe false
            messages shouldContainExactly
                listOf(
                    "Could not read text with the assistant: " +
                        "AI OCR needs a configured AI endpoint (Settings > AI assistant)",
                )
            job.cancel()
        }

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

    @Test
    fun metadataCarriesTheDocumentVocabularyAndDerivedReviewFlag() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = DocListItem(doc("doc-1", reviewed = true), listOf("finance"))
                    fieldRegistry.values.value =
                        mapOf(FieldKeys.CORRESPONDENT to listOf("ACME"), FieldKeys.DOC_TYPE to listOf("invoice"))
                    fieldRegistry.orphans.value = listOf(FieldKeyCount("amount", 3))
                    tags.tags.value = listOf(TagInfo("finance", "#ff0000", null, 1))
                    reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
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
            state.document.fieldDefs.map { it.key } shouldContainExactly FieldKeys.seedDefinitions().map { it.key }
            // Only TEXT fields get a vocabulary; the date and prose fields have nothing useful to offer.
            state.vocabulary.fieldValues shouldBe
                mapOf(
                    FieldKeys.TITLE to emptyList(),
                    FieldKeys.CORRESPONDENT to listOf("ACME"),
                    FieldKeys.DOC_TYPE to listOf("invoice"),
                )
            state.vocabulary.orphanKeys shouldContainExactly listOf("amount")
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

    @Test
    fun suggestAiMetadataStoresSuggestion() =
        runTest(dispatcher) {
            val suggestion =
                FieldSuggestion(
                    mapOf(
                        FieldKeys.TITLE to "Invoice Jan",
                        FieldKeys.CORRESPONDENT to "Acme",
                        FieldKeys.DOC_DATE to "2024-01-15",
                    ),
                )
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
            val suggestion =
                FieldSuggestion(
                    mapOf(
                        FieldKeys.TITLE to "Invoice Jan",
                        FieldKeys.CORRESPONDENT to "Acme",
                        FieldKeys.DOC_DATE to "2024-01-15",
                    ),
                )
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion(metadata = suggestion) }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.acceptAiMetadata(FieldKeys.TITLE)
            model.acceptAiMetadata(FieldKeys.CORRESPONDENT)
            model.acceptAiMetadata(FieldKeys.DOC_DATE)
            advanceUntilIdle()

            session.meta.fieldSets shouldContainExactly
                listOf(
                    Triple(listOf("doc-1"), FieldKeys.TITLE, "Invoice Jan"),
                    Triple(listOf("doc-1"), FieldKeys.CORRESPONDENT, "Acme"),
                    Triple(listOf("doc-1"), FieldKeys.DOC_DATE, "2024-01-15"),
                )
            model.metadata.value.ai.metadata.suggestion shouldBe FieldSuggestion.EMPTY
        }

    @Test
    fun dismissAiMetadataDropsOnlyThatField() =
        runTest(dispatcher) {
            val suggestion =
                FieldSuggestion(
                    mapOf(
                        FieldKeys.TITLE to "Invoice Jan",
                        FieldKeys.CORRESPONDENT to "Acme",
                        FieldKeys.DOC_DATE to "2024-01-15",
                    ),
                )
            val session = FakeLibrarySession().apply { aiSuggest.edit = AiEditSuggestion(metadata = suggestion) }
            val model = vm(session = session)
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()

            model.dismissAiMetadata(FieldKeys.CORRESPONDENT)
            advanceUntilIdle()

            model.metadata.value.ai.metadata.suggestion shouldBe suggestion.without(FieldKeys.CORRESPONDENT)
            session.meta.fieldSets shouldBe emptyList()
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
            model.metadata.value.ai.metadata.suggestion shouldBe FieldSuggestion.EMPTY
            model.metadata.value.ai.metadata.suggesting shouldBe false
            job.cancel()
        }

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
