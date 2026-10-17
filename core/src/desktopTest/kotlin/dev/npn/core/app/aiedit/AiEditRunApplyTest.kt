@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app.aiedit

import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiEditRunApplyTest {
    private suspend fun reviewOf(
        h: AiEditRunHarness,
        key: ScopeKey = key(),
    ): BatchResult.Review = h.run.suggest(key).shouldBeInstanceOf<BatchResult.Review>()

    @Test
    fun applyWritesOnlySelectedItems() =
        runTest {
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit =
                edit(
                    addTags =
                        listOf(
                            TagSuggestion("kept", isNew = false),
                            TagSuggestion("brandnew", isNew = true), // pre-deselected
                        ),
                    removeTags = listOf("utility"), // pre-deselected
                )

            val review = reviewOf(h)
            h.run.apply(key(), review.docs)

            h.meta.addedTags shouldContainExactly listOf(listOf("a") to "kept")
            h.meta.removedTags shouldBe emptyList()
        }

    @Test
    fun removalPartialFailureRetainsUnappliedAndCarriesCount() =
        runTest {
            // Distinct removal tags keep the calls separate, so only a's fails.
            val h = AiEditRunHarness()
            h.documents.documents.value =
                listOf(listItem("a", tags = listOf("utility")), listItem("b", tags = listOf("old")))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(removeTags = listOf("utility")),
                    "b" to edit(removeTags = listOf("old")),
                )

            val review = reviewOf(h)
            val selected = review.docs.map { it.withRemovalsSelected() }
            h.meta.failRemoveTagForDocs = setOf("a")
            val retry = h.run.apply(key(), selected).shouldBeInstanceOf<ApplyOutcome.Retry>()

            retry.remaining.map { it.docId } shouldContainExactly listOf("a")
            retry.remaining
                .single()
                .removals
                .single()
                .selected shouldBe true

            // Retry succeeding: only a's removal is written; Done counts include b's earlier write.
            h.meta.failRemoveTagForDocs = emptySet()
            h.meta.removedTags.clear()
            val done = h.run.apply(key(), retry.remaining).shouldBeInstanceOf<ApplyOutcome.Done>()

            h.meta.removedTags shouldContainExactly listOf(listOf("a") to "utility")
            done.result.applied shouldBe 2
        }

    private fun ReviewDoc.withRemovalsSelected(): ReviewDoc = copy(removals = removals.map { it.copy(selected = true) })

    /**
     * Batching moves field-retry granularity from per field to per document, which is the honest
     * grain: applyEdits is all-or-nothing, so a failed write left none of the document's fields
     * written and every one of them is still owed.
     */
    @Test
    fun aFailedFieldWriteRetainsEveryFieldOfThatDocument() =
        runTest {
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit =
                edit(
                    metadata =
                        FieldSuggestion(
                            mapOf(FieldKeys.CORRESPONDENT to "Acme", FieldKeys.DOC_DATE to "2024-01-15"),
                        ),
                )

            val review = reviewOf(h)
            h.meta.failApplyEditsForDocs = setOf("a")
            val retry = h.run.apply(key(), review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

            retry.remaining
                .single()
                .fields
                .map { it.selected } shouldContainExactly listOf(true, true)
        }

    @Test
    fun nothingSelectedWritesNothing() =
        runTest {
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit = edit(addTags = listOf(TagSuggestion("brandnew", isNew = true)))

            val review = reviewOf(h)
            h.run.apply(key(), review.docs) shouldBe ApplyOutcome.NothingSelected
            h.meta.addedTags shouldBe emptyList()
        }

    @Test
    fun doneReportsMoreRemainWhenScopeHasUnprocessedDocs() =
        runTest {
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"), listItem("c"))
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))

            val review = reviewOf(h, key(batchSize = 2))
            val done = h.run.apply(key(batchSize = 2), review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()

            done.result.moreRemain shouldBe true
        }

    @Test
    fun partialFailureRetainsFailedDocsWithUnappliedSelections() =
        runTest {
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                    "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                )

            val review = reviewOf(h)
            h.meta.failAddTagForDocs = setOf("a")
            val retry = h.run.apply(key(), review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

            h.meta.addedTags shouldContainExactly listOf(listOf("b") to "t2")
            retry.failedDocs shouldBe 1
            retry.total shouldBe 2
            retry.remaining.map { it.docId } shouldContainExactly listOf("a")
            retry.remaining
                .single()
                .chips
                .single()
                .selected shouldBe true

            // Retry succeeding: only (a, t1) is written; Done carries the earlier (b, t2) count, and
            // counts b among the documents changed.
            h.meta.failAddTagForDocs = emptySet()
            h.meta.addedTags.clear()
            val done = h.run.apply(key(), retry.remaining).shouldBeInstanceOf<ApplyOutcome.Done>()

            h.meta.addedTags shouldContainExactly listOf(listOf("a") to "t1")
            done.result.applied shouldBe 2
            done.result.docsChanged shouldBe 2
        }

    @Test
    fun retryDropsAlreadySucceededItemsOnFailedDocs() =
        runTest {
            // Doc a: the "bad" tag call fails (a shares it with nothing), the field write succeeds.
            // The retained row must keep the failed chip selected and no longer offer the written field.
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit =
                edit(
                    addTags = listOf(TagSuggestion("bad", isNew = false)),
                    metadata = FieldSuggestion(mapOf(FieldKeys.CORRESPONDENT to "Acme")),
                )

            val review = reviewOf(h)
            h.meta.failAddTagForDocs = setOf("a")
            val retry = h.run.apply(key(), review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

            val remaining = retry.remaining.single()
            remaining.chips.single().selected shouldBe true
            remaining.fields.shouldBeEmpty()
        }

    @Test
    fun suggestingResetsTheCarriedApplyCount() =
        runTest {
            // A partial failure carries its succeeded count; a NEW batch must not inherit it.
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(addTags = listOf(TagSuggestion("t1", isNew = false))),
                    "b" to edit(addTags = listOf(TagSuggestion("t2", isNew = false))),
                )

            val review = reviewOf(h, key(batchSize = 1))
            review.docs.map { it.docId } shouldContainExactly listOf("a")
            h.meta.failAddTagForDocs = setOf("a")
            h.run.apply(key(batchSize = 1), review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

            // Abandon the retry: suggest the next batch (b), apply it, and expect only b's write
            // counted — the carried count from a's failed batch was reset by the new suggestion.
            h.meta.failAddTagForDocs = emptySet()
            val next = h.run.suggest(key(batchSize = 1)).shouldBeInstanceOf<BatchResult.Review>()
            next.docs.map { it.docId } shouldContainExactly listOf("b")
            val done = h.run.apply(key(batchSize = 1), next.docs).shouldBeInstanceOf<ApplyOutcome.Done>()
            done.result.applied shouldBe 1
        }
}
