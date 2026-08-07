@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.doc
import dev.npn.app.ui.common.resolveEnglish
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocListItem
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
import kotlin.test.Test

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
    metadata: MetadataSuggestion = MetadataSuggestion.EMPTY,
) = AiEditSuggestion(addTags = addTags, removeTags = removeTags, metadata = metadata)

private fun vm(
    session: FakeLibrarySession = FakeLibrarySession(),
    settings: FakeSettingsRepository = FakeSettingsRepository(),
) = AiEditViewModel(session, settings)

class AiEditSuggestionTest : MainDispatcherTest() {
    @Test
    fun concurrentSuggestionReportsProgressAndPreservesBatchOrder() =
        runTest(dispatcher) {
            // Skew completion order (c finishes first, then b, then a) to prove results are re-ordered
            // back to batch order and progress climbs as docs COMPLETE.
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    ai.suggestDelayByDoc = mapOf("a" to 300, "b" to 200, "c" to 100)
                    ai.editByDoc =
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
                    launch { model.scopeCount.collect {} },
                )
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldContainExactlyInAnyOrder listOf("a", "b", "c")
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
                    ai.edit =
                        edit(
                            addTags = listOf(TagSuggestion("t", isNew = false)),
                            removeTags = listOf("utility"),
                            metadata = MetadataSuggestion(title = "New title"),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldContainExactly listOf("a")
            // The dedicated per-section calls are never used by the batch flow.
            session.ai.suggestTagsCalls shouldBe emptyList()
            session.ai.suggestMetadataCalls shouldBe emptyList()
            job.cancel()
        }

    @Test
    fun suggestionConcurrencyIsBounded() =
        runTest(dispatcher) {
            // 6 docs, concurrency 4: only 4 calls should be dispatched before any completes.
            val docs = (1..6).map { item("d$it") }
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = docs
                    ai.suggestDelayMillis = 100
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceTimeBy(50) // permits granted, none completed yet
            runCurrent()

            session.ai.suggestEditsCalls shouldHaveSize 4
            advanceUntilIdle()
            session.ai.suggestEditsCalls shouldHaveSize 6
            job.cancel()
        }

    @Test
    fun newTagsPreDeselectedExistingPreSelected() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit =
                        edit(
                            addTags =
                                listOf(
                                    TagSuggestion("invoice", isNew = false),
                                    TagSuggestion("brandnew", isNew = true),
                                ),
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
            val chips = review.docs.single().chips
            chips.single { it.tag == "invoice" }.selected shouldBe true
            chips.single { it.tag == "brandnew" }.selected shouldBe false
            job.cancel()
        }

    @Test
    fun tagRemovalsPreDeselected() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit = edit(removeTags = listOf("utility"))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs
                .single()
                .removals
                .single()
                .selected shouldBe false
            job.cancel()
        }

    @Test
    fun toggleChipFlipsAddAndRemovalSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit =
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
    fun fieldSuggestionsPreSelectFillInsAndDeselectOverwrites() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"))
                    ai.editByDoc =
                        mapOf(
                            "a" to
                                edit(
                                    metadata =
                                        MetadataSuggestion(
                                            title = "New title",
                                            correspondent = "Acme",
                                            docDate = "2024-01-15",
                                        ),
                                ),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val fields =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
                    .docs
                    .single()
                    .fields
            fields.map { it.field } shouldContainExactly
                listOf(AiField.TITLE, AiField.CORRESPONDENT, AiField.DOC_DATE)
            // Title overwrites an existing value -> pre-deselected, current value retained for display.
            val title = fields.single { it.field == AiField.TITLE }
            title.selected shouldBe false
            title.current shouldBe "Old title"
            // Correspondent and date fill empty fields -> pre-selected, no replaced value.
            fields.single { it.field == AiField.CORRESPONDENT }.selected shouldBe true
            fields.single { it.field == AiField.CORRESPONDENT }.current shouldBe null
            fields.single { it.field == AiField.DOC_DATE }.selected shouldBe true
            job.cancel()
        }

    @Test
    fun toggleFieldFlipsSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit = edit(metadata = MetadataSuggestion(title = "New title"))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.toggleField("a", AiField.TITLE)
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
                    ai.editByDoc =
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
    fun applyGroupsPairsByTagIntoOneCallPerTag() =
        runTest(dispatcher) {
            // a and b both suggest "shared"; a also suggests "onlyA". Expect one addTag(["a","b"],
            // "shared") and one addTag(["a"], "onlyA") — grouped by tag, not per (doc, tag) pair.
            val sharedTag = TagSuggestion("shared", isNew = false)
            val onlyATag = TagSuggestion("onlyA", isNew = false)
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(sharedTag, onlyATag)),
                            "b" to edit(addTags = listOf(sharedTag)),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactlyInAnyOrder
                listOf(
                    listOf("a", "b") to "shared",
                    listOf("a") to "onlyA",
                )
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 3
            done.result.docsChanged shouldBe 2
            job.cancel()
        }

    @Test
    fun applyWritesSelectedRemovalsGroupedByTag() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.edit = edit(removeTags = listOf("utility"))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Removals start deselected; select them on both docs, then apply.
            model.setAllSelection(true)
            model.apply()
            advanceUntilIdle()

            // "utility" is on both docs -> one grouped removeTag call for both.
            session.meta.removedTags shouldContainExactly listOf(listOf("a", "b") to "utility")
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 2
            done.result.docsChanged shouldBe 2
            job.cancel()
        }

    @Test
    fun applyWritesSelectedFieldsThroughMatchingSetters() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"))
                    ai.edit =
                        edit(
                            addTags = listOf(TagSuggestion("t1", isNew = false)),
                            metadata =
                                MetadataSuggestion(
                                    title = "New title",
                                    correspondent = "Acme",
                                    docDate = "2024-01-15",
                                ),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Title stays deselected (overwrite); correspondent + date + tag are written.
            model.apply()
            advanceUntilIdle()

            session.meta.titleSets shouldBe emptyList()
            session.meta.correspondentSets shouldContainExactly listOf(listOf("a") to "Acme")
            session.meta.docDateSets shouldContainExactly listOf("a" to "2024-01-15")
            session.meta.addedTags shouldContainExactly listOf(listOf("a") to "t1")
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 3
            done.result.docsChanged shouldBe 1
            job.cancel()
        }

    @Test
    fun setAllAndSetDocSelectionToggleAllKinds() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"), item("b"))
                    ai.edit =
                        edit(
                            addTags = listOf(TagSuggestion("t1", isNew = false)),
                            removeTags = listOf("utility"),
                            metadata = MetadataSuggestion(title = "New title"),
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
    fun snippetPopulatedFromExtractedText() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                    content.extractedTextByDoc =
                        mapOf(
                            "a" to "  Invoice   #123\n\tAcme  Corp  ",
                            "b" to "   ", // blank -> null snippet
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
            review.docs.single { it.docId == "a" }.snippet shouldBe "Invoice #123 Acme Corp"
            review.docs.single { it.docId == "b" }.snippet shouldBe null
            job.cancel()
        }

    @Test
    fun partialFailureSurfacesMessageAndKeepsUnappliedSelections() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                            "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                        )
                }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.resolveEnglish() } },
                )
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Only doc a fails.
            session.meta.failAddTagForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()

            messages shouldHaveSize 1
            messages.single() shouldBe "Could not tag 1 of 2 document(s)"
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
    fun removalPartialFailureKeepsUnappliedAndCarriesCount() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            // Distinct removal tags keep the calls separate, so only a's fails.
                            "a" to edit(removeTags = listOf("utility")),
                            "b" to edit(removeTags = listOf("old")),
                        )
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.messages.collect {} })
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Select removals on both; a's removal fails, b's succeeds.
            model.setAllSelection(true)
            session.meta.failRemoveTagForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.docs
                .single()
                .removals
                .single()
                .selected shouldBe true

            // Retry succeeding: only a's removal is written; Done counts include b's earlier write.
            session.meta.failRemoveTagForDocs = emptySet()
            session.meta.removedTags.clear()
            model.apply()
            advanceUntilIdle()

            session.meta.removedTags shouldContainExactly listOf(listOf("a") to "utility")
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 2
            jobs.forEach { it.cancel() }
        }

    @Test
    fun fieldPartialFailureRetainsUnappliedSelectionAndCarriesCount() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            "a" to edit(metadata = MetadataSuggestion(title = "Title A")),
                            "b" to edit(metadata = MetadataSuggestion(correspondent = "Acme")),
                        )
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.messages.collect {} })
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // First apply: a's title write fails, b's correspondent succeeds -> Review retains a.
            session.meta.failSetTitle = { error("boom") }
            model.apply()
            advanceUntilIdle()
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.docs
                .single()
                .fields
                .single()
                .selected shouldBe true

            // Retry succeeding: only a's title is written; Done counts include b's earlier write.
            session.meta.failSetTitle = null
            model.apply()
            advanceUntilIdle()

            session.meta.titleSets shouldContainExactly listOf("a" to "Title A")
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 2
            jobs.forEach { it.cancel() }
        }

    @Test
    fun cancellationMidRunStopsRunAndReturnsToIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    ai.suggestDelayMillis = 1_000
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
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
                    ai.failSuggestEditsForDocs = setOf("a")
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.resolveEnglish() } },
                )
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            messages shouldContainExactly listOf("Could not get suggestions for 1 of 2 document(s)")
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("b")
            jobs.forEach { it.cancel() }
        }
}

class AiEditConfigurationTest : MainDispatcherTest() {
    @Test
    fun scopeCountReflectsSelectedScope() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                }
            val model = vm(session)
            val job = launch { model.scopeCount.collect {} }
            advanceUntilIdle()

            model.scopeCount.value shouldBe 3
            job.cancel()
        }

    @Test
    fun batchCapsAtDefaultBatchDocs() =
        runTest(dispatcher) {
            val many = (1..(AiTagging.DEFAULT_BATCH_DOCS + 5)).map { item("d$it") }
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = many
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldHaveSize AiTagging.DEFAULT_BATCH_DOCS
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
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setBatchSize(2)
            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldHaveSize 2
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
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxTags(11)
            model.setMaxNewTags(2)
            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsRequests
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
                    ai.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val request = session.ai.suggestEditsRequests.single()
            request.fields.map { it.field } shouldContainExactly AiField.entries
            request.fields.all { it.mode == AiFieldMode.UNFILLED } shouldBe true
            request.tags shouldBe true
            request.tagRemovals shouldBe false
            request.customPrompt shouldBe null
            job.cancel()
        }

    @Test
    fun fieldOptionsAndTagsAndPromptFlowIntoRequest() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setFieldEnabled(AiField.TITLE, false)
            model.setFieldMode(AiField.CORRESPONDENT, AiFieldMode.ALL)
            model.setIncludeTagRemovals(true)
            model.setCustomPrompt("merge similar tags")
            model.start()
            advanceUntilIdle()

            val request = session.ai.suggestEditsRequests.single()
            request.fields.map { it.field } shouldContainExactly listOf(AiField.CORRESPONDENT, AiField.DOC_DATE)
            request.fields.single { it.field == AiField.CORRESPONDENT }.mode shouldBe AiFieldMode.ALL
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
                    ai.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxNewTags(4)
            model.setAllowNewTags(false)
            model.start()
            advanceUntilIdle()

            val request = session.ai.suggestEditsRequests.single()
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
                    ai.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldContainExactly listOf("a")
            val request = session.ai.suggestEditsRequests.single()
            request.tags shouldBe false
            request.fields.map { it.field } shouldContainExactly AiField.entries
            job.cancel()
        }

    @Test
    fun startBlockedWhenNothingRequested() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit = AiEditSuggestion.EMPTY
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.setIncludeTagRemovals(false)
            AiField.entries.forEach { model.setFieldEnabled(it, false) }
            model.ui.value.hasEdits() shouldBe false

            model.start()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldBe emptyList()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Idle>()
            job.cancel()
        }

    @Test
    fun settersAreNoOpsOutsideIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
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
            model.setFieldEnabled(AiField.TITLE, false)
            model.setCustomPrompt("ignored")
            model.ui.value.maxTags shouldBe 11
            model.ui.value.batchSize shouldBe AiTagging.DEFAULT_BATCH_DOCS
            model.ui.value.includeTags shouldBe true
            model.ui.value.allowNewTags shouldBe true
            model.ui.value.fieldOptions
                .getValue(AiField.TITLE)
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
                    ai.editByDoc =
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
    fun docWithOnlyFieldSuggestionsAppearsInReviewNotEmptyGroup() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            "a" to edit(metadata = MetadataSuggestion(docDate = "2024-01-15")),
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
                    ai.editByDoc =
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
            session.ai.suggestEditsCalls.clear()
            model.nextBatch()
            advanceUntilIdle()

            session.ai.suggestEditsCalls shouldBe emptyList()
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
                    ai.edit = edit(addTags = listOf(TagSuggestion("brandnew", isNew = true)))
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
    fun partialFailureRetryKeepsOnlyUnappliedAndCarriesCount() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.editByDoc =
                        mapOf(
                            "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                            "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                        )
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.messages.collect {} })
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // First apply: a fails, b succeeds -> back to Review with only a (t1 still selected).
            session.meta.failAddTagForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.docs
                .single()
                .chips
                .single()
                .selected shouldBe true

            // Retry with a now succeeding: only (a, t1) is written again (b, t2 not re-written), and
            // the Done count includes the earlier successful (b, t2).
            session.meta.failAddTagForDocs = emptySet()
            session.meta.addedTags.clear()
            model.apply()
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactly listOf(listOf("a") to "t1")
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Done>()
            done.result.applied shouldBe 2
            jobs.forEach { it.cancel() }
        }

    @Test
    fun reviewQueueScopeGatedOnInboxWorkflow() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
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
                    reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
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
