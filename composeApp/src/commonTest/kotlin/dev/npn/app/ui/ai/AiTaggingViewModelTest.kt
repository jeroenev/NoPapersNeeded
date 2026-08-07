@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.doc
import dev.npn.app.ui.common.AiMetadataField
import dev.npn.app.ui.common.resolveEnglish
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

private fun vm(
    session: FakeLibrarySession = FakeLibrarySession(),
    settings: FakeSettingsRepository = FakeSettingsRepository(),
) = AiTaggingViewModel(session, settings)

class AiTaggingSuggestionTest : MainDispatcherTest() {
    @Test
    fun concurrentSuggestionReportsProgressAndPreservesBatchOrder() =
        runTest(dispatcher) {
            // Skew completion order (c finishes first, then b, then a) to prove results are re-ordered
            // back to batch order and progress climbs as docs COMPLETE.
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    ai.suggestDelayByDoc = mapOf("a" to 300, "b" to 200, "c" to 100)
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(TagSuggestion("x", isNew = false)),
                            "b" to listOf(TagSuggestion("y", isNew = false)),
                            "c" to listOf(TagSuggestion("z", isNew = false)),
                        )
                }
            val model = vm(session)
            val progress = mutableListOf<Int>()
            val jobs =
                listOf(
                    launch {
                        model.ui.collect { s ->
                            (s.phase as? TaggingPhase.Suggesting)?.let { progress += it.progress.done }
                        }
                    },
                    launch { model.scopeCount.collect {} },
                )
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            // All three dispatched (concurrency 4 >= 3); completion count reaches total.
            session.ai.suggestTagsCalls shouldContainExactlyInAnyOrder listOf("a", "b", "c")
            progress.last() shouldBe 3
            progress shouldContainExactly listOf(0, 1, 2, 3)
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a", "b", "c")
            jobs.forEach { it.cancel() }
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
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceTimeBy(50) // permits granted, none completed yet
            runCurrent()

            session.ai.suggestTagsCalls shouldHaveSize 4
            advanceUntilIdle()
            session.ai.suggestTagsCalls shouldHaveSize 6
            job.cancel()
        }

    @Test
    fun newTagsPreDeselectedExistingPreSelected() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.suggestions =
                        listOf(
                            TagSuggestion("invoice", isNew = false),
                            TagSuggestion("brandnew", isNew = true),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            val chips = review.docs.single().chips
            chips.single { it.tag == "invoice" }.selected shouldBe true
            chips.single { it.tag == "brandnew" }.selected shouldBe false
            job.cancel()
        }

    @Test
    fun toggleChipFlipsSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.suggestions = listOf(TagSuggestion("invoice", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            model.toggleChip("a", "invoice")
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs
                .single()
                .chips
                .single()
                .selected shouldBe false
            job.cancel()
        }
}

class AiTaggingApplicationTest : MainDispatcherTest() {
    @Test
    fun applyWritesExactlySelectedPairs() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to
                                listOf(
                                    TagSuggestion("t1", isNew = false),
                                    TagSuggestion("new1", isNew = true),
                                ),
                            "b" to listOf(TagSuggestion("t2", isNew = false)),
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
                    .shouldBeInstanceOf<TaggingPhase.Done>()
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
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(sharedTag, onlyATag),
                            "b" to listOf(sharedTag),
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
                    .shouldBeInstanceOf<TaggingPhase.Done>()
            done.result.applied shouldBe 3
            done.result.docsChanged shouldBe 2
            job.cancel()
        }

    @Test
    fun setAllAndSetDocSelectionToggleChips() =
        runTest(dispatcher) {
            val existingTag = TagSuggestion("t1", isNew = false)
            val newTag = TagSuggestion("new1", isNew = true)
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(existingTag, newTag),
                            "b" to listOf(TagSuggestion("t2", isNew = false)),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            // Clear everything.
            model.setAllSelection(false)
            var review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.flatMap { it.chips }.all { !it.selected } shouldBe true

            // Select everything.
            model.setAllSelection(true)
            review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.flatMap { it.chips }.all { it.selected } shouldBe true

            // Deselect just doc a.
            model.setDocSelection("a", false)
            review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs
                .single { it.docId == "a" }
                .chips
                .all { !it.selected } shouldBe true
            review.docs
                .single { it.docId == "b" }
                .chips
                .all { it.selected } shouldBe true
            job.cancel()
        }
}

class AiTaggingFailureHandlingTest : MainDispatcherTest() {
    @Test
    fun snippetPopulatedFromExtractedText() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
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
                    .shouldBeInstanceOf<TaggingPhase.Review>()
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
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(TagSuggestion("t1", isNew = false)),
                            "b" to listOf(TagSuggestion("t2", isNew = false)),
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
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.docs
                .single()
                .chips
                .single()
                .selected shouldBe true
            jobs.forEach { it.cancel() }
        }

    @Test
    fun cancellationMidRunStopsRunAndReturnsToIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"), item("c"))
                    ai.suggestDelayMillis = 1_000
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
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
                .shouldBeInstanceOf<TaggingPhase.Idle>()
            job.cancel()
        }

    @Test
    fun suggestFailurePerDocContinuesAndSurfacesMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.failSuggestTagsForDocs = setOf("a")
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
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
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("b")
            jobs.forEach { it.cancel() }
        }
}

class AiTaggingConfigurationTest : MainDispatcherTest() {
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
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            session.ai.suggestTagsCalls shouldHaveSize AiTagging.DEFAULT_BATCH_DOCS
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
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setBatchSize(2)
            model.start()
            advanceUntilIdle()

            session.ai.suggestTagsCalls shouldHaveSize 2
            // Suggested tags are existing-vocabulary -> pre-selected; apply then confirm more remain.
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Review>()
            model.apply()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Done>()
                .result.moreRemain shouldBe true
            job.cancel()
        }

    @Test
    fun overridesArePassedToFacadeCalls() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.setMaxTags(11)
            model.setMaxNewTags(2)
            model.start()
            advanceUntilIdle()

            session.ai.suggestTagsTriples shouldContainExactly listOf(Triple("a", 11, 2))
            job.cancel()
        }

    @Test
    fun settersAreNoOpsOutsideIdle() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setMaxTags(11)
            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Review>()

            // In Review, setters must not change the snapshot.
            model.setMaxTags(4)
            model.setMaxNewTags(9)
            model.setBatchSize(2)
            model.ui.value.maxTags shouldBe 11
            model.ui.value.batchSize shouldBe AiTagging.DEFAULT_BATCH_DOCS
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

class AiTaggingBatchWorkflowTest : MainDispatcherTest() {
    @Test
    fun noSuggestionDocsGroupedSeparately() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(TagSuggestion("t", isNew = false)),
                            "b" to emptyList(),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
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
                    ai.suggestionsByDoc =
                        mapOf(
                            // a: existing tag (pre-selected), b: no suggestions, c: existing tag we deselect.
                            "a" to listOf(TagSuggestion("t1", isNew = false)),
                            "b" to emptyList(),
                            "c" to listOf(TagSuggestion("t3", isNew = false)),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            // Deselect c's only chip, apply only a.
            model.toggleChip("c", "t3")
            model.apply()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Done>()

            // Next batch must not re-suggest a, b (no suggestion) or c (deselected) — all reviewed.
            session.ai.suggestTagsCalls.clear()
            model.nextBatch()
            advanceUntilIdle()

            session.ai.suggestTagsCalls shouldBe emptyList()
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Done>()
            done.result.moreRemain shouldBe false
            job.cancel()
        }

    @Test
    fun skipFromReviewGoesToDoneWithoutWriting() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.suggestions = listOf(TagSuggestion("brandnew", isNew = true))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Review>()

            model.skipReview()
            advanceUntilIdle()

            session.meta.addedTags shouldBe emptyList()
            val done =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Done>()
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
                    ai.suggestionsByDoc =
                        mapOf(
                            "a" to listOf(TagSuggestion("t1", isNew = false)),
                            "b" to listOf(TagSuggestion("t2", isNew = false)),
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
                    .shouldBeInstanceOf<TaggingPhase.Review>()
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
                    .shouldBeInstanceOf<TaggingPhase.Done>()
            done.result.applied shouldBe 2
            jobs.forEach { it.cancel() }
        }

    @Test
    fun fieldSuggestionsPreSelectFillInsAndDeselectOverwrites() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"))
                    ai.metadataByDoc =
                        mapOf(
                            "a" to
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
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            session.ai.suggestMetadataCalls shouldContainExactly listOf("a")
            val fields =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
                    .docs
                    .single()
                    .fields
            fields.map { it.field } shouldContainExactly
                listOf(AiMetadataField.TITLE, AiMetadataField.CORRESPONDENT, AiMetadataField.DOC_DATE)
            // Title overwrites an existing value -> pre-deselected, current value retained for display.
            val title = fields.single { it.field == AiMetadataField.TITLE }
            title.selected shouldBe false
            title.current shouldBe "Old title"
            // Correspondent and date fill empty fields -> pre-selected, no replaced value.
            fields.single { it.field == AiMetadataField.CORRESPONDENT }.selected shouldBe true
            fields.single { it.field == AiMetadataField.CORRESPONDENT }.current shouldBe null
            fields.single { it.field == AiMetadataField.DOC_DATE }.selected shouldBe true
            job.cancel()
        }

    @Test
    fun toggleFieldFlipsSelection() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"))
                    ai.metadataSuggestion = MetadataSuggestion(title = "New title")
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            model.toggleField("a", AiMetadataField.TITLE)
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Review>()
                .docs
                .single()
                .fields
                .single()
                .selected shouldBe false
            job.cancel()
        }

    @Test
    fun applyWritesSelectedFieldsThroughMatchingSetters() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"))
                    ai.suggestions = listOf(TagSuggestion("t1", isNew = false))
                    ai.metadataByDoc =
                        mapOf(
                            "a" to
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
            model.setScope(TaggingScope.ALL)
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
                    .shouldBeInstanceOf<TaggingPhase.Done>()
            done.result.applied shouldBe 3
            done.result.docsChanged shouldBe 1
            job.cancel()
        }

    @Test
    fun docWithOnlyFieldSuggestionsAppearsInReviewNotEmptyGroup() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.metadataByDoc = mapOf("a" to MetadataSuggestion(docDate = "2024-01-15"))
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
            job.cancel()
        }

    @Test
    fun metadataFailurePerDocDropsDocAndSurfacesMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                    ai.failSuggestMetadataForDocs = setOf("a")
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
            model.ui.value.phase
                .shouldBeInstanceOf<TaggingPhase.Review>()
                .docs
                .map { it.docId } shouldContainExactly listOf("b")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun docAndBatchSelectionCoverFields() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a", title = "Old title"))
                    ai.suggestions = listOf(TagSuggestion("t", isNew = false))
                    ai.metadataSuggestion = MetadataSuggestion(title = "New title")
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            // Overwrite title starts deselected; select-all must pick it up too.
            model.setAllSelection(true)
            var doc =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
                    .docs
                    .single()
            doc.fields.single().selected shouldBe true

            model.setDocSelection("a", false)
            doc =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
                    .docs
                    .single()
            doc.chips.single().selected shouldBe false
            doc.fields.single().selected shouldBe false
            job.cancel()
        }

    @Test
    fun fieldPartialFailureRetainsUnappliedSelectionAndCarriesCount() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(item("a"), item("b"))
                    ai.metadataByDoc =
                        mapOf(
                            "a" to MetadataSuggestion(title = "Title A"),
                            "b" to MetadataSuggestion(correspondent = "Acme"),
                        )
                }
            val model = vm(session)
            val jobs = listOf(launch { model.ui.collect {} }, launch { model.messages.collect {} })
            advanceUntilIdle()
            model.setScope(TaggingScope.ALL)
            model.start()
            advanceUntilIdle()

            // First apply: a's title write fails, b's correspondent succeeds -> Review retains a.
            session.meta.failSetTitle = { error("boom") }
            model.apply()
            advanceUntilIdle()
            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<TaggingPhase.Review>()
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
                    .shouldBeInstanceOf<TaggingPhase.Done>()
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
            model.setScope(TaggingScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe TaggingScope.UNTAGGED // ignored
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
            model.setScope(TaggingScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe TaggingScope.REVIEW_QUEUE
            job.cancel()
        }
}
