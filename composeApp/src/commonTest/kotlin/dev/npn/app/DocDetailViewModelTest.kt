package dev.npn.app

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.HistoryUiState
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.TagInfo
import dev.npn.core.db.FieldKeyCount
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrState
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_no_ai_metadata_suggestions
import no_papers_needed.composeapp.generated.resources.common_no_ai_tag_suggestions
import no_papers_needed.composeapp.generated.resources.common_save_field_failed
import no_papers_needed.composeapp.generated.resources.detail_add_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr_details_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr_engine_failed
import no_papers_needed.composeapp.generated.resources.detail_suggest_details_failed
import no_papers_needed.composeapp.generated.resources.detail_suggest_tags_failed
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
        settings: FakeSettingsRepository = aiConfiguredSettings(),
    ) = DocDetailViewModel(docId, session, settings).also { model ->
        backgroundScope.launch { model.metadata.collect {} }
    }

    private fun tagSuggestions(vararg tags: String): List<TagSuggestion> = tags.map { TagSuggestion(it, isNew = false) }

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
    fun addTagFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failBatchOps = { error("journal write failed") } }
            val model = vm(session = session)
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.addTag("finance")
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.detail_add_tag_failed, "journal write failed"))
            job.cancel()
        }

    @Test
    fun setFieldFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failSetField = { error("io") } }
            val model = vm(session = session)
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.setField("amount", "42")
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.common_save_field_failed, "io"))
            job.cancel()
        }

    /**
     * An edit made while the panel is open changes the document row; the History tab must show it
     * without a manual refresh, here and on the full-screen route alike.
     */
    @Test
    fun historyFollowsTheDocumentAfterItFirstLoads() =
        runTest(dispatcher) {
            val tagged = historyRecord(1, HistoryEntry.TagAdded("invoice"))
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    documents.historyList = listOf(tagged)
                }
            val model = vm(session = session)
            model.loadHistory()
            advanceUntilIdle()
            (model.metadata.value.history as HistoryUiState.Loaded).items shouldHaveSize 1

            // A field edit lands: the journal gains an event and the row changes with it.
            session.documents.historyList =
                listOf(tagged, historyRecord(2, HistoryEntry.FieldSet(FieldKeys.TITLE, "X")))
            session.documents.singleDoc.value = listItem("doc-1", tags = listOf("invoice"))
            advanceUntilIdle()

            (model.metadata.value.history as HistoryUiState.Loaded).items shouldHaveSize 2
        }

    private fun historyRecord(
        seq: Long,
        entry: HistoryEntry,
    ) = DocumentHistoryRecord(100 + seq, "dev-a", entry)

    @Test
    fun ocrDetailsThatCannotBeReadAreReported() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    ocr.failOcrVersions = { error("index busy") }
                    ocr.failAiOcrOffer = { error("index busy") }
                }
            val model = DocDetailViewModel("doc-1", session, aiConfiguredSettings())
            val messages = mutableListOf<UiText>()
            val collector = launch { model.messages.collect { messages += it.text } }
            val panel = launch { model.metadata.collect {} }
            advanceUntilIdle()
            panel.cancel()
            collector.cancel()

            messages shouldContainExactly List(2) { failure(Res.string.detail_ocr_details_failed, "index busy") }
            model.ui.value.aiOcr shouldBe AiOcrOffer.Unavailable
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
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.setOcrEngine("mlkit")
            advanceUntilIdle()
            session.ocr.ocrEngineSets shouldBe emptyList()
            messages shouldContainExactly listOf(failure(Res.string.detail_ocr_engine_failed, "reindex failed"))
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
            val model = DocDetailViewModel("doc-1", FakeLibrarySession(), aiConfiguredSettings(), initialItem = seed)

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
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf(uiText(Res.string.common_no_ai_tag_suggestions))
            model.metadata.value.ai.tags.suggestions shouldBe emptyList()
            model.metadata.value.ai.tags.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiTagsFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiSuggest.failWith = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.suggestAiTags()
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.detail_suggest_tags_failed, "network down"))
            model.metadata.value.ai.tags.suggestions shouldBe emptyList()
            model.metadata.value.ai.tags.suggesting shouldBe false
            job.cancel()
        }

    /** Turning AI on or off in Settings shows or hides the AI actions of an open document. */
    @Test
    fun aiAvailabilityFollowsSettings() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            advanceUntilIdle()
            model.metadata.value.ai.tags.available shouldBe false

            settings.edit { it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b") }
            advanceUntilIdle()
            model.metadata.value.ai.tags.available shouldBe true

            settings.edit { it.copy(aiEnabled = false) }
            advanceUntilIdle()
            model.metadata.value.ai.tags.available shouldBe false
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
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf(uiText(Res.string.common_no_ai_metadata_suggestions))
            model.metadata.value.ai.metadata.suggesting shouldBe false
            job.cancel()
        }

    @Test
    fun suggestAiMetadataFailureSurfacesErrorAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiSuggest.failWith = { error("network down") } }
            val model = vm(session = session)
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.suggestAiMetadata()
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.detail_suggest_details_failed, "network down"))
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
