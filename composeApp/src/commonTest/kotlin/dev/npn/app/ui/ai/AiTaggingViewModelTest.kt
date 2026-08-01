package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.doc
import dev.npn.core.ai.TagSuggestion
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

@OptIn(ExperimentalCoroutinesApi::class)
class AiTaggingViewModelTest : MainDispatcherTest() {

    private fun item(id: String, tags: List<String> = emptyList(), title: String? = null): DocListItem =
        DocListItem(doc = doc(id, title = title), tags = tags)

    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = AiTaggingViewModel(session)

    @Test
    fun concurrentSuggestionReportsProgressAndPreservesBatchOrder() = runTest(dispatcher) {
        // Skew completion order (c finishes first, then b, then a) to prove results are re-ordered
        // back to batch order and progress climbs as docs COMPLETE.
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"), item("c"))
            ai.suggestDelayByDoc = mapOf("a" to 300, "b" to 200, "c" to 100)
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("x", isNew = false)),
                "b" to listOf(TagSuggestion("y", isNew = false)),
                "c" to listOf(TagSuggestion("z", isNew = false)),
            )
        }
        val model = vm(session)
        val progress = mutableListOf<Int>()
        val jobs = listOf(
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
        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.map { it.docId } shouldContainExactly listOf("a", "b", "c")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun suggestionConcurrencyIsBounded() = runTest(dispatcher) {
        // 6 docs, concurrency 4: only 4 calls should be dispatched before any completes.
        val docs = (1..6).map { item("d$it") }
        val session = FakeLibrarySession().apply {
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
    fun newTagsPreDeselectedExistingPreSelected() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"))
            ai.suggestions = listOf(
                TagSuggestion("invoice", isNew = false),
                TagSuggestion("brandnew", isNew = true),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.start()
        advanceUntilIdle()

        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        val chips = review.docs.single().chips
        chips.single { it.tag == "invoice" }.selected shouldBe true
        chips.single { it.tag == "brandnew" }.selected shouldBe false
        job.cancel()
    }

    @Test
    fun toggleChipFlipsSelection() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"))
            ai.suggestions = listOf(TagSuggestion("invoice", isNew = false))
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()

        model.toggleChip("a", "invoice")
        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.single().chips.single().selected shouldBe false
        job.cancel()
    }

    @Test
    fun applyWritesExactlySelectedPairs() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("t1", isNew = false), TagSuggestion("new1", isNew = true)),
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

        session.meta.addedTags shouldContainExactlyInAnyOrder listOf(
            listOf("a") to "t1",
            listOf("a") to "new1",
            listOf("b") to "t2",
        )
        val done = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()
        done.result.tagsAdded shouldBe 3
        done.result.docsTagged shouldBe 2
        job.cancel()
    }

    @Test
    fun applyGroupsPairsByTagIntoOneCallPerTag() = runTest(dispatcher) {
        // a and b both suggest "shared"; a also suggests "onlyA". Expect one addTag(["a","b"],
        // "shared") and one addTag(["a"], "onlyA") — grouped by tag, not per (doc, tag) pair.
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("shared", isNew = false), TagSuggestion("onlyA", isNew = false)),
                "b" to listOf(TagSuggestion("shared", isNew = false)),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()

        model.apply()
        advanceUntilIdle()

        session.meta.addedTags shouldContainExactlyInAnyOrder listOf(
            listOf("a", "b") to "shared",
            listOf("a") to "onlyA",
        )
        val done = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()
        done.result.tagsAdded shouldBe 3
        done.result.docsTagged shouldBe 2
        job.cancel()
    }

    @Test
    fun setAllAndSetDocSelectionToggleChips() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("t1", isNew = false), TagSuggestion("new1", isNew = true)),
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
        var review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.flatMap { it.chips }.all { !it.selected } shouldBe true

        // Select everything.
        model.setAllSelection(true)
        review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.flatMap { it.chips }.all { it.selected } shouldBe true

        // Deselect just doc a.
        model.setDocSelection("a", false)
        review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.single { it.docId == "a" }.chips.all { !it.selected } shouldBe true
        review.docs.single { it.docId == "b" }.chips.all { it.selected } shouldBe true
        job.cancel()
    }

    @Test
    fun snippetPopulatedFromExtractedText() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestions = listOf(TagSuggestion("t", isNew = false))
            content.extractedTextByDoc = mapOf(
                "a" to "  Invoice   #123\n\tAcme  Corp  ",
                "b" to "   ", // blank -> null snippet
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()

        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.single { it.docId == "a" }.snippet shouldBe "Invoice #123 Acme Corp"
        review.docs.single { it.docId == "b" }.snippet shouldBe null
        job.cancel()
    }

    @Test
    fun partialFailureSurfacesMessageAndKeepsUnappliedSelections() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("t1", isNew = false)),
                "b" to listOf(TagSuggestion("t2", isNew = false)),
            )
        }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val jobs = listOf(
            launch { model.ui.collect {} },
            launch { model.messages.collect { messages += it } },
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
        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.map { it.docId } shouldContainExactly listOf("a")
        review.docs.single().chips.single().selected shouldBe true
        jobs.forEach { it.cancel() }
    }

    @Test
    fun cancellationMidRunStopsRunAndReturnsToIdle() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
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
        model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Idle>()
        job.cancel()
    }

    @Test
    fun suggestFailurePerDocContinuesAndSurfacesMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.failSuggestTagsForDocs = setOf("a")
            ai.suggestions = listOf(TagSuggestion("t", isNew = false))
        }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val jobs = listOf(
            launch { model.ui.collect {} },
            launch { model.messages.collect { messages += it } },
        )
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()

        messages shouldContainExactly listOf("Could not suggest tags for 1 of 2 document(s)")
        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.map { it.docId } shouldContainExactly listOf("b")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun scopeCountReflectsSelectedScope() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"), item("c"))
        }
        val model = vm(session)
        val job = launch { model.scopeCount.collect {} }
        advanceUntilIdle()

        model.scopeCount.value shouldBe 3
        job.cancel()
    }

    @Test
    fun batchCapsAtMaxBatchDocs() = runTest(dispatcher) {
        val many = (1..30).map { item("d$it") }
        val session = FakeLibrarySession().apply {
            documents.documents.value = many
            ai.suggestions = listOf(TagSuggestion("t", isNew = false))
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.start()
        advanceUntilIdle()

        session.ai.suggestTagsCalls shouldHaveSize MAX_BATCH_DOCS
        job.cancel()
    }

    @Test
    fun noSuggestionDocsGroupedSeparately() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
                "a" to listOf(TagSuggestion("t", isNew = false)),
                "b" to emptyList(),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()

        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.map { it.docId } shouldContainExactly listOf("a")
        review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
        job.cancel()
    }

    @Test
    fun nextBatchExcludesPreviouslyReviewedDocsIncludingDeselectedAndNoSuggestion() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"), item("c"))
            ai.suggestionsByDoc = mapOf(
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
        model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()

        // Next batch must not re-suggest a, b (no suggestion) or c (deselected) — all reviewed.
        session.ai.suggestTagsCalls.clear()
        model.nextBatch()
        advanceUntilIdle()

        session.ai.suggestTagsCalls shouldBe emptyList()
        val done = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()
        done.result.moreRemain shouldBe false
        job.cancel()
    }

    @Test
    fun skipFromReviewGoesToDoneWithoutWriting() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"))
            ai.suggestions = listOf(TagSuggestion("brandnew", isNew = true))
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()
        model.setScope(TaggingScope.ALL)
        model.start()
        advanceUntilIdle()
        model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()

        model.skipReview()
        advanceUntilIdle()

        session.meta.addedTags shouldBe emptyList()
        val done = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()
        done.result.tagsAdded shouldBe 0
        done.result.docsTagged shouldBe 0
        done.result.moreRemain shouldBe false
        job.cancel()
    }

    @Test
    fun partialFailureRetryKeepsOnlyUnappliedAndCarriesCount() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(item("a"), item("b"))
            ai.suggestionsByDoc = mapOf(
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
        val review = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Review>()
        review.docs.map { it.docId } shouldContainExactly listOf("a")
        review.docs.single().chips.single().selected shouldBe true

        // Retry with a now succeeding: only (a, t1) is written again (b, t2 not re-written), and
        // the Done count includes the earlier successful (b, t2).
        session.meta.failAddTagForDocs = emptySet()
        session.meta.addedTags.clear()
        model.apply()
        advanceUntilIdle()

        session.meta.addedTags shouldContainExactly listOf(listOf("a") to "t1")
        val done = model.ui.value.phase.shouldBeInstanceOf<TaggingPhase.Done>()
        done.result.tagsAdded shouldBe 2
        jobs.forEach { it.cancel() }
    }

    @Test
    fun reviewQueueScopeGatedOnInboxWorkflow() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
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
    fun reviewQueueScopeAllowedWithInboxWorkflow() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
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
