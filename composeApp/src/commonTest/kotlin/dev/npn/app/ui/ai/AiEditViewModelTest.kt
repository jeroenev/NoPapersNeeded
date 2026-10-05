@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.doc
import dev.npn.app.failure
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiTagging
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.DocListItem
import dev.npn.core.app.EditProgress
import dev.npn.core.app.EditScope
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.ReviewField
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_fields_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_tags_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_queue_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_setup_load_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_failed
import kotlin.test.Test

/**
 * The registry keys the seed marks aiEnabled, in registry order — what the viewmodel seeds its
 * fieldOptions with (title, correspondent, docType, docDate; notes is aiEnabled=false).
 */
private val seededAiFieldKeys: List<String> =
    FieldKeys.seedDefinitions().filter { it.aiEnabled }.map { it.key }

private fun item(
    id: String,
    tags: List<String> = emptyList(),
    title: String? = null,
    correspondent: String? = null,
    docDate: String? = null,
): DocListItem =
    DocListItem(
        doc = doc(id, title = title, correspondent = correspondent, docDate = docDate),
        tags = tags,
    )

private fun edit(
    addTags: List<TagSuggestion> = emptyList(),
    removeTags: List<String> = emptyList(),
    metadata: FieldSuggestion = FieldSuggestion.EMPTY,
) = AiEditSuggestion(addTags = addTags, removeTags = removeTags, metadata = metadata)

/**
 * A viewmodel with the run pinned to the foreground unless a test says otherwise. Left to itself
 * the background default depends on the scope size, which is not what most of these tests are
 * about; the ones that *are* pass `background = null` and let it decide.
 *
 * The task defaults to [EditTask.CUSTOM] — the everything-on preset — because that is what these
 * tests were written against, and because a page with no task chosen deliberately configures
 * nothing. Preset behaviour has its own suite in [AiEditTaskTest].
 */
private fun vm(
    session: FakeLibrarySession = FakeLibrarySession(),
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    background: Boolean? = false,
    task: EditTask? = EditTask.CUSTOM,
) = AiEditViewModel(session, settings).also { model ->
    task?.let(model::setTask)
    background?.let(model::setBackground)
}

class AiEditSuggestionTest : MainDispatcherTest() {
    @Test
    fun concurrentSuggestionReportsProgressAndPreservesBatchOrder() =
        runTest(dispatcher) {
            // Skew completion order (c finishes first, then b, then a) to prove results are re-ordered
            // back to batch order and progress climbs as docs COMPLETE.
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.suggestDelayByDoc = mapOf("a" to 300, "b" to 200, "c" to 100)
                    aiSuggest.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(TagSuggestion("x", isNew = false))),
                            "b" to edit(addTags = listOf(TagSuggestion("y", isNew = false))),
                            "c" to edit(addTags = listOf(TagSuggestion("z", isNew = false))),
                        )
                }
            val model = vm(session)
            val progress = mutableListOf<Int>()
            val jobs =
                listOf(
                    launch {
                        model.ui.collect { s ->
                            (s.phase as? EditPhase.Suggesting)?.let { progress += it.progress.done }
                        }
                    },
                    launch { model.preview.collect {} },
                )
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldContainExactlyInAnyOrder listOf("a", "b", "c")
            progress.last() shouldBe 3
            progress shouldContainExactly listOf(0, 1, 2, 3)
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a", "b", "c")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun oneSuggestEditsCallPerDocumentCoversEverything() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit =
                        edit(
                            addTags = listOf(TagSuggestion("t", isNew = false)),
                            removeTags = listOf("utility"),
                            metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "New title")),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldContainExactly listOf("a")
            job.cancel()
        }

    @Test
    fun toggleChipFlipsAddAndRemovalSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit =
                        edit(
                            addTags = listOf(TagSuggestion("invoice", isNew = false)),
                            removeTags = listOf("utility"),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.toggleChip("a", "invoice")
            model.toggleChip("a", "utility")
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            val doc = review.docs.single()
            doc.chips.single().selected shouldBe false
            doc.removals.single().selected shouldBe true
            job.cancel()
        }

    @Test
    fun toggleFieldFlipsSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = edit(metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "New title")))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.toggleField("a", FieldKeys.TITLE)
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
                .docs
                .single()
                .fields
                .single()
                .selected shouldBe false
            job.cancel()
        }
}

class AiEditApplicationTest : MainDispatcherTest() {
    @Test
    fun applyWritesExactlySelectedPairs() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    aiSuggest.editByDoc =
                        mapOf(
                            "a" to
                                edit(
                                    addTags =
                                        listOf(
                                            TagSuggestion("t1", isNew = false),
                                            TagSuggestion("new1", isNew = true),
                                        ),
                                ),
                            "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // new1 stays deselected; select it and apply -> writes t1, new1, t2.
            model.toggleChip("a", "new1")
            model.apply()
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactlyInAnyOrder
                listOf(
                    listOf("a") to "t1",
                    listOf("a") to "new1",
                    listOf("b") to "t2",
                )
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 3
            done.result.docsChanged shouldBe 2
            job.cancel()
        }

    @Test
    fun setAllAndSetDocSelectionToggleAllKinds() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"), item("b"))
                    aiSuggest.edit =
                        edit(
                            addTags = listOf(TagSuggestion("t1", isNew = false)),
                            removeTags = listOf("utility"),
                            metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "New title")),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Overwrites and removals start deselected; select-all must pick them up too.
            model.setAllSelection(true)
            var review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs
                .single { it.docId == "a" }
                .let {
                    it.chips.all { s -> s.selected } && it.removals.all { s -> s.selected } &&
                        it.fields.all { s -> s.selected }
                }.shouldBe(true)

            // Deselect just doc a.
            model.setDocSelection("a", false)
            review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs
                .single { it.docId == "a" }
                .let {
                    it.chips.none { s -> s.selected } && it.removals.none { s -> s.selected } &&
                        it.fields.none { s -> s.selected }
                }.shouldBe(true)
            review.docs
                .single { it.docId == "b" }
                .chips
                .single()
                .selected shouldBe true
            job.cancel()
        }
}

class AiEditFailureHandlingTest : MainDispatcherTest() {
    @Test
    fun partialFailureSurfacesMessageAndKeepsUnappliedSelections() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    aiSuggest.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                            "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                        )
                }
            val model = vm(session)
            val messages = mutableListOf<UiText>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.text } },
                )
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Only doc a fails.
            session.meta.failAddTagForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()

            messages shouldHaveSize 1
            messages.single() shouldBe uiText(Res.plurals.ai_edit_apply_tags_failed, 2, 1, 2)
            // b was written; a kept for retry in a Review phase.
            session.meta.addedTags shouldContainExactly listOf(listOf("b") to "t2")
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.docs
                .single()
                .chips
                .single()
                .selected shouldBe true
            jobs.forEach { it.cancel() }
        }

    @Test
    fun aFailedFieldWriteIsReportedAsTheDetailsItWrote() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    aiSuggest.edit = edit(metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "New title")))
                    meta.failApplyEditsForDocs = setOf("a")
                }
            val model = vm(session)
            val messages = mutableListOf<UiText>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.text } },
                )
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.plurals.ai_edit_apply_fields_failed, 2, 1, 2))
            jobs.forEach { it.cancel() }
        }

    @Test
    fun cancellationMidRunStopsRunAndReturnsToIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.suggestDelayMillis = 1_000
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceTimeBy(500) // all in flight, none completed yet
            runCurrent()
            model.cancel()
            advanceUntilIdle()

            // Cancelling tears down the whole scope: no Review produced, back to Idle.
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Idle>()
            job.cancel()
        }

    @Test
    fun suggestFailurePerDocContinuesAndSurfacesMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    aiSuggest.failSuggestEditsForDocs = setOf("a")
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val messages = mutableListOf<UiText>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.text } },
                )
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.plurals.ai_edit_suggest_failed, 2, 1, 2))
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("b")
            jobs.forEach { it.cancel() }
        }
}

class AiEditConfigurationTest : MainDispatcherTest() {
    @Test
    fun previewReportsScopeTotalAndTheDocumentsOfTheNextBatch() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                }
            val model = vm(session)
            val job = launch { model.preview.collect {} }
            advanceUntilIdle()

            model.setBatchSize(2)
            advanceUntilIdle()
            model.preview.value.total shouldBe 3
            model.preview.value.batch
                .map { it.doc.docId } shouldContainExactly listOf("a", "b")
            job.cancel()
        }

    @Test
    fun previewDropsDocumentsAlreadyReviewedInThisSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.preview.collect {} })
            advanceUntilIdle()

            model.setScope(EditScope.ALL)
            model.setBatchSize(2)
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // a and b were reviewed, so only c is left for the next batch.
            model.preview.value.total shouldBe 1
            model.preview.value.batch
                .map { it.doc.docId } shouldContainExactly listOf("c")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun selectionHandOffScopesTheRunToThoseDocuments() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = AiEditViewModel(session, FakeSettingsRepository(), listOf("a", "c"))
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.preview.collect {} })
            advanceUntilIdle()
            model.setTask(EditTask.CUSTOM)

            model.ui.value.scope shouldBe EditScope.SELECTION
            model.ui.value.selectionSize shouldBe 2
            model.preview.value.batch
                .map { it.doc.docId } shouldContainExactly listOf("a", "c")

            model.start()
            advanceUntilIdle()
            session.aiSuggest.suggestEditsCalls shouldContainExactly listOf("a", "c")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun missingFieldsScopeTargetsTheEnabledAutofillFields() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.documents.value = listOf(item("a")) }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.preview.collect {} })
            advanceUntilIdle()

            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setScope(EditScope.MISSING_FIELDS)
            advanceUntilIdle()

            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            session.documents.observedFilters
                .last()
                .first.missingFields shouldContainExactly
                listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)

            // Disabling every field leaves nothing to look for, so the scope selects nothing.
            seededAiFieldKeys.forEach { model.setFieldEnabled(it, false) }
            advanceUntilIdle()
            model.preview.value.total shouldBe 0
            jobs.forEach { it.cancel() }
        }

    @Test
    fun savedViewScopeUsesTheViewFilterAndSort() =
        runTest(dispatcher) {
            val view =
                SavedView(
                    id = "v1",
                    name = "Invoices",
                    filter = LibraryFilter(tagsAll = listOf("invoice")),
                    sort = LibrarySort(SortField.Name, ascending = true),
                )
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    savedViews.views.value = listOf(view)
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.preview.collect {} })
            advanceUntilIdle()

            model.ui.value.savedViewId shouldBe "v1"
            model.setScope(EditScope.SAVED_VIEW)
            advanceUntilIdle()

            session.documents.observedFilters.last() shouldBe (view.filter to view.sort)
            jobs.forEach { it.cancel() }
        }

    @Test
    fun unavailableScopesAreRejected() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            // No hand-off, no saved views, no inbox workflow.
            model.setScope(EditScope.SELECTION)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.setScope(EditScope.SAVED_VIEW)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.setScope(EditScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
            job.cancel()
        }

    @Test
    fun batchCapsAtDefaultBatchDocs() =
        runTest(dispatcher) {
            val many = (1..(AiTagging.DEFAULT_BATCH_DOCS + 5)).map { item("d$it") }
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = many
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldHaveSize AiTagging.DEFAULT_BATCH_DOCS
            job.cancel()
        }

    @Test
    fun settingsDefaultsAreSeededIntoUiState() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                }
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(aiMaxTagsPerDoc = 12, aiMaxNewTagsPerDoc = 3) }
                }
            val model = vm(session, settings)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.ui.value.maxTags shouldBe 12
            model.ui.value.maxNewTags shouldBe 3
            model.ui.value.batchSize shouldBe AiTagging.DEFAULT_BATCH_DOCS
            job.cancel()
        }

    @Test
    fun setBatchSizeLimitsBatch() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setBatchSize(2)
            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldHaveSize 2
            // Suggested tags are existing-vocabulary -> pre-selected; apply then confirm more remain.
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
            model.apply()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Done>()
                .result.moreRemain shouldBe true
            job.cancel()
        }

    @Test
    fun overridesArePassedToFacadeCalls() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxTags(11)
            model.setMaxNewTags(2)
            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsRequests
                .single()
                .let {
                    it.maxTags shouldBe 11
                    it.maxNewTags shouldBe 2
                }
            job.cancel()
        }

    @Test
    fun defaultRequestCoversAllFieldsUnfilledAndTagAdds() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val request = session.aiSuggest.suggestEditsRequests.single()
            request.fields.map { it.key } shouldContainExactly seededAiFieldKeys
            request.fields.all { it.mode == AiFieldMode.UNFILLED } shouldBe true
            request.tags shouldBe true
            request.tagRemovals shouldBe false
            request.customPrompt shouldBe null
            job.cancel()
        }

    @Test
    fun fieldOptionsAreSeededFromTheRegistrysAiEnabledFields() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.documents.value = listOf(item("a")) }
            val model = vm(session)
            advanceUntilIdle()

            // The seed marks title/correspondent/docType/docDate aiEnabled (notes is not), so those
            // are exactly the fields the run starts with.
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys
        }

    @Test
    fun addFieldPullsARegisteredFieldIntoTheRunAndItsRequest() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.documents.value = listOf(item("a")) }
            // A registered but not-aiEnabled field the seed left out of the run.
            session.fieldRegistry.defs.value =
                session.fieldRegistry.defs.value +
                FieldDef(
                    "amount",
                    "Amount",
                    FieldType.DECIMAL,
                    showByDefault = false,
                    aiEnabled = false,
                    position = 5,
                )
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            // Not in the run until explicitly added from the picker.
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys

            model.addField("amount")
            model.ui.value.fieldOptions
                .getValue("amount")
                .enabled shouldBe true

            model.start()
            advanceUntilIdle()

            val request = session.aiSuggest.suggestEditsRequests.single()
            request.fields.map { it.key } shouldContainExactly (seededAiFieldKeys + "amount")
            job.cancel()
        }

    @Test
    fun tooManyFieldsBlocksTheRun() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.documents.value = listOf(item("a")) }
            // Register enough extra fields that thirteen can be enabled at once (the cap is 12).
            session.fieldRegistry.defs.value =
                session.fieldRegistry.defs.value +
                (1..13).map {
                    FieldDef(
                        "extra$it",
                        "Extra $it",
                        FieldType.TEXT,
                        showByDefault = false,
                        aiEnabled = false,
                        position =
                            10 + it,
                    )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            // Enable exactly thirteen fields, one over the MAX_FIELDS_PER_RUN cap of 12.
            model.ui.value.fieldOptions.keys
                .forEach { model.setFieldEnabled(it, false) }
            (1..13).forEach { model.addField("extra$it") }

            model.ui.value.tooManyFields shouldBe true
            model.ui.value.hasEdits() shouldBe false
            job.cancel()
        }

    @Test
    fun fieldOptionsAndTagsAndPromptFlowIntoRequest() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setFieldMode(FieldKeys.CORRESPONDENT, AiFieldMode.ALL)
            model.setIncludeTagRemovals(true)
            model.setCustomPrompt("merge similar tags")
            model.start()
            advanceUntilIdle()

            val request = session.aiSuggest.suggestEditsRequests.single()
            request.fields.map { it.key } shouldContainExactly
                listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)
            request.fields.single { it.key == FieldKeys.CORRESPONDENT }.mode shouldBe AiFieldMode.ALL
            request.tags shouldBe true
            request.tagRemovals shouldBe true
            request.customPrompt shouldBe "merge similar tags"
            job.cancel()
        }

    @Test
    fun disallowingNewTagsCapsNewTagsAtZeroButKeepsTagging() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxNewTags(4)
            model.setAllowNewTags(false)
            model.start()
            advanceUntilIdle()

            val request = session.aiSuggest.suggestEditsRequests.single()
            request.tags shouldBe true
            request.maxNewTags shouldBe 0
            // The cap the user typed is remembered for when new tags are re-enabled.
            model.ui.value.maxNewTags shouldBe 4
            job.cancel()
        }

    @Test
    fun taggingCanBeDisabledLeavingFieldsOnly() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldContainExactly listOf("a")
            val request = session.aiSuggest.suggestEditsRequests.single()
            request.tags shouldBe false
            request.fields.map { it.key } shouldContainExactly seededAiFieldKeys
            job.cancel()
        }

    @Test
    fun startBlockedWhenNothingRequested() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.setIncludeTagRemovals(false)
            seededAiFieldKeys.forEach { model.setFieldEnabled(it, false) }
            model.ui.value.hasEdits() shouldBe false

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldBe emptyList()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Idle>()
            job.cancel()
        }

    @Test
    fun startRefusesASavedViewThatIsGone() =
        runTest(dispatcher) {
            val view =
                SavedView(
                    id = "v1",
                    name = "Invoices",
                    filter = LibraryFilter(),
                    sort = LibrarySort(SortField.Name, ascending = true),
                )
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    savedViews.views.value = listOf(view)
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.SAVED_VIEW)
            // Deleted elsewhere: the scope keeps pointing at a view that no longer exists.
            session.savedViews.views.value = emptyList()
            advanceUntilIdle()
            model.ui.value.startBlocker(ScopePreview(1, emptyList())) shouldBe StartBlocker.NO_SAVED_VIEW

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldBe emptyList()
            model.ui.value.phase shouldBe EditPhase.Idle
            job.cancel()
        }

    @Test
    fun startRefusesAnEmptyScope() =
        runTest(dispatcher) {
            val model = vm()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            // The button reads "no documents"; Start must not turn that into a finished run.
            model.ui.value.phase shouldBe EditPhase.Idle
            job.cancel()
        }

    @Test
    fun settersAreNoOpsOutsideIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setMaxTags(11)
            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()

            // In Review, setters must not change the snapshot.
            model.setMaxTags(4)
            model.setMaxNewTags(9)
            model.setBatchSize(2)
            model.setIncludeTags(false)
            model.setAllowNewTags(false)
            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setCustomPrompt("ignored")
            model.ui.value.maxTags shouldBe 11
            model.ui.value.batchSize shouldBe AiTagging.DEFAULT_BATCH_DOCS
            model.ui.value.includeTags shouldBe true
            model.ui.value.allowNewTags shouldBe true
            model.ui.value.fieldOptions
                .getValue(FieldKeys.TITLE)
                .enabled shouldBe true
            model.ui.value.customPrompt shouldBe ""
            job.cancel()
        }

    @Test
    fun settersClampToRange() =
        runTest(dispatcher) {
            val model = vm()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxTags(999)
            model.setMaxNewTags(-5)
            model.setBatchSize(9999)
            model.ui.value.maxTags shouldBe AiTagging.MAX_TAGS_PER_DOC
            model.ui.value.maxNewTags shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
            model.ui.value.batchSize shouldBe AiTagging.MAX_BATCH_DOCS
            job.cancel()
        }
}

class AiEditBatchWorkflowTest : MainDispatcherTest() {
    @Test
    fun noSuggestionDocsGroupedSeparately() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    aiSuggest.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(TagSuggestion("t", isNew = false))),
                            "b" to AiEditSuggestion.EMPTY,
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
            job.cancel()
        }

    @Test
    fun nextBatchExcludesPreviouslyReviewedDocsIncludingDeselectedAndNoSuggestion() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    aiSuggest.editByDoc =
                        mapOf(
                            // a: existing tag (pre-selected), b: no suggestions, c: existing tag we deselect.
                            "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                            "b" to AiEditSuggestion.EMPTY,
                            "c" to edit(addTags = listOf(TagSuggestion("t3", isNew = false))),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            model.start()
            advanceUntilIdle()

            // Deselect c's only chip, apply only a.
            model.toggleChip("c", "t3")
            model.apply()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Done>()

            // Next batch must not re-suggest a, b (no suggestion) or c (deselected) — all reviewed.
            session.aiSuggest.suggestEditsCalls.clear()
            model.nextBatch()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldBe emptyList()
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.moreRemain shouldBe false
            job.cancel()
        }

    @Test
    fun skipFromReviewGoesToDoneWithoutWriting() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("brandnew", isNew = true)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()

            model.skipReview()
            advanceUntilIdle()

            session.meta.addedTags shouldBe emptyList()
            session.meta.removedTags shouldBe emptyList()
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 0
            done.result.docsChanged shouldBe 0
            done.result.moreRemain shouldBe false
            job.cancel()
        }

    @Test
    fun reviewQueueScopeGatedOnInboxWorkflow() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.ui.value.reviewQueueAvailable shouldBe false
            model.setScope(EditScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe EditScope.UNTAGGED // ignored
            job.cancel()
        }

    @Test
    fun reviewQueueScopeAllowedWithInboxWorkflow() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.ui.value.reviewQueueAvailable shouldBe true
            model.setScope(EditScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe EditScope.REVIEW_QUEUE
            job.cancel()
        }
}

/**
 * Where a run executes. The default follows the scope size, an explicit choice overrides it, and a
 * backgrounded run hands over the WHOLE scope rather than one review-sized batch.
 */
class AiEditBackgroundTest : MainDispatcherTest() {
    private fun sessionOf(count: Int) =
        FakeLibrarySession().apply {
            documents.documents.value = (1..count).map { item("d$it") }
            aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
        }

    @Test
    fun aScopeOverTheThresholdIsQueuedByDefault() =
        runTest(dispatcher) {
            val session = sessionOf(AiTagging.BACKGROUND_THRESHOLD_DOCS + 1)
            val model = vm(session, background = null)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldBe emptyList()
            session.aiEditQueue.queued
                .single()
                .first shouldHaveSize AiTagging.BACKGROUND_THRESHOLD_DOCS + 1
            model.ui.value.phase shouldBe EditPhase.Queued(AiTagging.BACKGROUND_THRESHOLD_DOCS + 1)
            job.cancel()
        }

    @Test
    fun aScopeAtOrUnderTheThresholdRunsHere() =
        runTest(dispatcher) {
            val session = sessionOf(AiTagging.BACKGROUND_THRESHOLD_DOCS)
            val model = vm(session, background = null)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            session.aiEditQueue.queued shouldBe emptyList()
            session.aiSuggest.suggestEditsCalls shouldHaveSize AiTagging.BACKGROUND_THRESHOLD_DOCS
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
            job.cancel()
        }

    @Test
    fun anExplicitChoiceBeatsTheSizeDefaultInBothDirections() =
        runTest(dispatcher) {
            val big = sessionOf(AiTagging.BACKGROUND_THRESHOLD_DOCS + 1)
            val foreground = vm(big, background = false)
            val bigJob = launch { foreground.ui.collect {} }
            advanceUntilIdle()
            foreground.setScope(EditScope.ALL)
            foreground.start()
            advanceUntilIdle()
            big.aiEditQueue.queued shouldBe emptyList()
            bigJob.cancel()

            val small = sessionOf(1)
            val background = vm(small, background = true)
            val smallJob = launch { background.ui.collect {} }
            advanceUntilIdle()
            background.setScope(EditScope.ALL)
            background.start()
            advanceUntilIdle()
            small.aiSuggest.suggestEditsCalls shouldBe emptyList()
            small.aiEditQueue.queued shouldHaveSize 1
            smallJob.cancel()
        }

    @Test
    fun aQueuedRunCoversTheWholeScopeNotOneBatch() =
        runTest(dispatcher) {
            val session = sessionOf(AiTagging.DEFAULT_BATCH_DOCS + 7)
            val model = vm(session, background = true)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            session.aiEditQueue.queued
                .single()
                .first shouldHaveSize AiTagging.DEFAULT_BATCH_DOCS + 7
            job.cancel()
        }

    @Test
    fun queuedDocumentsAreSpentSoAFollowUpRunSkipsThem() =
        runTest(dispatcher) {
            val session = sessionOf(3)
            val model = vm(session, background = true)
            val job = launch { model.ui.collect {} }
            val preview = launch { model.preview.collect {} }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            model.start()
            advanceUntilIdle()

            model.preview.value.total shouldBe 0
            job.cancel()
            preview.cancel()
        }

    @Test
    fun aFailedQueueingReportsAndStaysOnTheSetupPage() =
        runTest(dispatcher) {
            val session = sessionOf(3)
            session.aiEditQueue.enqueueFailure = IllegalStateException("disk full")
            val model = vm(session, background = true)
            val job = launch { model.ui.collect {} }
            val messages = mutableListOf<UiText>()
            val messageJob = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Idle
            messages.single() shouldBe failure(Res.string.ai_edit_queue_failed, "disk full")
            job.cancel()
            messageJob.cancel()
        }

    @Test
    fun aRunPlanCountsWhatOneStartActuallySpends() =
        runTest(dispatcher) {
            val session = sessionOf(AiTagging.DEFAULT_BATCH_DOCS + 10)
            val model = vm(session, background = false)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.plan.collect {} })
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            advanceUntilIdle()

            // Reviewed here: one batch is bought now, however big the scope is.
            model.plan.value.documents shouldBe AiTagging.DEFAULT_BATCH_DOCS + 10
            model.plan.value.requests shouldBe AiTagging.DEFAULT_BATCH_DOCS

            // Queued: the whole scope is bought, so that is what the user is told.
            model.setBackground(true)
            advanceUntilIdle()
            model.plan.value.requests shouldBe AiTagging.DEFAULT_BATCH_DOCS + 10
            jobs.forEach { it.cancel() }
        }
}

/**
 * Task presets. A task is a named combination of the same knobs the form exposes by hand, so what
 * matters is that picking one lands on that combination and that editing it away is honest about
 * having done so.
 */
class AiEditTaskTest : MainDispatcherTest() {
    private fun model(session: FakeLibrarySession = FakeLibrarySession()) =
        AiEditViewModel(session, FakeSettingsRepository())

    @Test
    fun organizingUntaggedDocumentsAsksForTagsOnly() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setTask(EditTask.ORGANIZE_UNTAGGED)

            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.ui.value.includeTags shouldBe true
            model.ui.value.allowNewTags shouldBe true
            model.ui.value.includeTagRemovals shouldBe false
            model.ui.value.fieldOptions.values
                .none { it.enabled } shouldBe true
            job.cancel()
        }

    @Test
    fun cleaningUpTagsNeverInventsNewOnes() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setTask(EditTask.CLEAN_UP_TAGS)

            model.ui.value.scope shouldBe EditScope.ALL
            model.ui.value.includeTags shouldBe true
            model.ui.value.includeTagRemovals shouldBe true
            // The point of a clean-up is fewer distinct tags, so the run may not add to the
            // vocabulary while doing it.
            model.ui.value.allowNewTags shouldBe false
            job.cancel()
        }

    @Test
    fun fillingFieldsTargetsTheDocumentsMissingThemAndTouchesNoTags() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setTask(EditTask.FILL_FIELDS)

            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            model.ui.value.includeTags shouldBe false
            model.ui.value.includeTagRemovals shouldBe false
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys
            model.ui.value.fieldOptions.values
                .all { it.enabled && it.mode == AiFieldMode.UNFILLED } shouldBe true
            job.cancel()
        }

    @Test
    fun aTaskPickedBeforeTheRegistryArrivesStillLandsOnItsScope() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }

            // No advanceUntilIdle: the field registry has not answered, so the field-based scope
            // is not available yet and the preset cannot take it.
            model.setTask(EditTask.FILL_FIELDS)
            model.ui.value.scope shouldBe EditScope.UNTAGGED

            advanceUntilIdle()

            // Seeding the fields finishes applying the task rather than leaving the fallback.
            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            model.ui.value.fieldOptions.values
                .all { it.enabled } shouldBe true
            job.cancel()
        }

    @Test
    fun aHandedOverSelectionOutranksThePresetScope() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.documents.value = listOf(item("a")) }
            val model = AiEditViewModel(session, FakeSettingsRepository(), listOf("a"))
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            // The user already said which documents; the task says what to do to those.
            model.setTask(EditTask.CLEAN_UP_TAGS)
            model.ui.value.scope shouldBe EditScope.SELECTION
            job.cancel()
        }

    @Test
    fun editingWhatATaskStandsForMakesTheRunCustom() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setTask(EditTask.ORGANIZE_UNTAGGED)

            model.setIncludeTagRemovals(true)

            model.ui.value.task shouldBe EditTask.CUSTOM
            model.ui.value.includeTagRemovals shouldBe true
            job.cancel()
        }

    @Test
    fun choosingWhereToRunOrWhichDocumentsKeepsTheTask() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    savedViews.views.value =
                        listOf(SavedView("v1", "Invoices", LibraryFilter(), LibrarySort()))
                }
            val model = model(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setTask(EditTask.FILL_FIELDS)

            // Same job, different paperwork: neither of these changes what the run asks for.
            model.setScope(EditScope.SAVED_VIEW)
            model.setBackground(true)
            model.setBatchSize(3)
            model.setCustomPrompt("prefer the sender on the letterhead")

            model.ui.value.task shouldBe EditTask.FILL_FIELDS
            job.cancel()
        }

    @Test
    fun rePickingTheCurrentTaskResetsItsEdits() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setTask(EditTask.FILL_FIELDS)
            model.setIncludeTags(true)
            model.setFieldMode(FieldKeys.TITLE, AiFieldMode.ALL)
            model.ui.value.task shouldBe EditTask.CUSTOM

            model.setTask(EditTask.FILL_FIELDS)

            model.ui.value.task shouldBe EditTask.FILL_FIELDS
            model.ui.value.includeTags shouldBe false
            model.ui.value.fieldOptions
                .getValue(FieldKeys.TITLE)
                .mode shouldBe AiFieldMode.UNFILLED
            job.cancel()
        }

    @Test
    fun startDoesNothingUntilATaskIsChosen() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    aiSuggest.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = model(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.aiSuggest.suggestEditsCalls shouldBe emptyList()
            model.ui.value.phase shouldBe EditPhase.Idle
            job.cancel()
        }

    @Test
    fun clearingTheTaskReturnsToTheChooser() =
        runTest(dispatcher) {
            val model = model()
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setTask(EditTask.CLEAN_UP_TAGS)

            model.clearTask()

            model.ui.value.task shouldBe null
            job.cancel()
        }
}

/**
 * Why Start is inert. Tested against the pure projection rather than the flow, because the
 * question is which reason wins for a given configuration, not when a StateFlow emits.
 */
class AiEditStartBlockerTest {
    private val ready =
        AiEditUiState(
            task = EditTask.CUSTOM,
            registryLoaded = true,
            fieldOptions = mapOf(FieldKeys.TITLE to AiFieldOption()),
        )
    private val loaded = ScopePreview(total = 3, batch = emptyList(), loaded = true)

    @Test
    fun leavingAsksFirstExactlyWhilePaidSuggestionsWouldBeLost() {
        val progress = EditProgress(done = 1, total = 2, currentTitle = null, currentFileName = "a.pdf")
        EditPhase.Suggesting(progress).leavingDiscardsSuggestions shouldBe true
        EditPhase.Review(emptyList(), emptyList()).leavingDiscardsSuggestions shouldBe true
        EditPhase.Idle.leavingDiscardsSuggestions shouldBe false
        EditPhase.Applying.leavingDiscardsSuggestions shouldBe false
        EditPhase.Queued(documents = 3).leavingDiscardsSuggestions shouldBe false
        EditPhase.Done(ApplyResult(1, 1, moreRemain = false)).leavingDiscardsSuggestions shouldBe false
    }

    @Test
    fun aReadyRunHasNoBlocker() {
        ready.startBlocker(loaded) shouldBe null
    }

    @Test
    fun noTaskComesFirst() {
        // Even with nothing else configured, the first thing to fix is the first question asked.
        ready
            .copy(task = null, includeTags = false, fieldOptions = emptyMap())
            .startBlocker(loaded) shouldBe StartBlocker.NO_TASK
    }

    @Test
    fun aPromptOnItsOwnIsNotAnEdit() {
        // A prompt-only run asks the model for an object with no allowed keys: it can only come
        // back empty, one paid request per document.
        val promptOnly =
            ready.copy(
                includeTags = false,
                includeTagRemovals = false,
                fieldOptions = emptyMap(),
                customPrompt = "merge tags that differ only in case",
            )
        promptOnly.hasEdits() shouldBe false
        promptOnly.startBlocker(loaded) shouldBe StartBlocker.NOTHING_REQUESTED
    }

    @Test
    fun tooManyFieldsBeatsEverythingElseRequested() {
        val fields = (1..AiEditRequest.MAX_FIELDS_PER_RUN + 1).associate { "f$it" to AiFieldOption() }
        ready.copy(fieldOptions = fields).startBlocker(loaded) shouldBe StartBlocker.TOO_MANY_FIELDS
    }

    @Test
    fun aSavedViewScopeNeedsAView() {
        ready.copy(scope = EditScope.SAVED_VIEW).startBlocker(loaded) shouldBe StartBlocker.NO_SAVED_VIEW
    }

    @Test
    fun anUnansweredScopeQueryIsNotAnEmptyScope() {
        // The distinction the loaded flag exists for: this must not read as "no documents".
        ready.startBlocker(ScopePreview.EMPTY) shouldBe StartBlocker.LOADING_SCOPE
        ready.startBlocker(loaded.copy(total = 0)) shouldBe StartBlocker.NO_DOCUMENTS
    }
}

/**
 * Bulk selection over a review batch. "Recommended" has to mean exactly what the domain's
 * pre-select policy means, or the header's shortcut quietly disagrees with the rows.
 */
class ReviewSelectionTest {
    private val docs =
        listOf(
            ReviewDoc(
                docId = "a",
                title = "A",
                fileName = "a.pdf",
                snippet = null,
                chips =
                    listOf(
                        ReviewChip("invoice", isNew = false, selected = false),
                        ReviewChip("brandnew", isNew = true, selected = true),
                    ),
                removals = listOf(ReviewChip("utility", isNew = false, selected = true)),
                fields =
                    listOf(
                        ReviewField("title", "Title", "New", current = null, selected = false),
                        ReviewField("correspondent", "From", "Acme", current = "Old", selected = true),
                    ),
            ),
        )

    @Test
    fun recommendedIsExistingTagsAndEmptyFieldsOnly() {
        val doc = docs.selectRecommended().single()

        doc.chips.single { it.tag == "invoice" }.selected shouldBe true
        doc.chips.single { it.tag == "brandnew" }.selected shouldBe false
        doc.removals.single().selected shouldBe false
        doc.fields.single { it.key == "title" }.selected shouldBe true
        doc.fields.single { it.key == "correspondent" }.selected shouldBe false
    }

    @Test
    fun selectAllStillTakesEverythingIncludingTheRiskyOnes() {
        val doc = docs.setAllSelected(true).single()

        (doc.chips + doc.removals).all { it.selected } shouldBe true
        doc.fields.all { it.selected } shouldBe true
    }
}

class AiEditSetupFailureTest : MainDispatcherTest() {
    @Test
    fun aSetupReadThatFailsIsReportedAndFallsBackToDefaults() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { savedViews.failObserve = IllegalStateException("database locked") }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            val messages = mutableListOf<UiText>()
            val messageJob = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()

            model.ui.value.savedViews shouldBe emptyList()
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_setup_load_failed, "database locked"))
            job.cancel()
            messageJob.cancel()
        }
}
