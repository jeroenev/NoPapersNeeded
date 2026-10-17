@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.doc
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiEditQueueReviewTest {
    private suspend fun AiEditQueueRig.suggestAll(jobId: String): String {
        while (queue.processNext()) {
            // drain to a standstill, as the session's kick does
        }
        return jobId
    }

    @Test
    fun aRunStillOwingModelCallsDoesNotOpen() =
        runTest {
            withQueue { rig ->
                val jobId = rig.enqueue("a")

                rig.queue.openEditRun(jobId) shouldBe null
            }
        }

    @Test
    fun anUnknownRunDoesNotOpen() =
        runTest {
            withQueue { rig ->
                rig.queue.openEditRun("nope") shouldBe null
            }
        }

    @Test
    fun theReviewIsProjectedAgainstTheDocumentAsItIsWhenItOpensNotWhenItWasQueued() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(title = "Electricity bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))

                // Someone titled the document while the run sat in the queue: the suggestion is now
                // an overwrite, which the pre-select policy must leave unticked.
                rig.documents.documents.value = listOf(listItem(doc("a", title = "Already named")))

                val field =
                    rig.queue
                        .openEditRun(jobId)
                        .shouldNotBeNull()
                        .docs
                        .single()
                        .fields
                        .single()
                field.key shouldBe FieldKeys.TITLE
                field.current shouldBe "Already named"
                field.selected shouldBe false
            }
        }

    @Test
    fun documentsTheModelHadNothingForAreSeparatedOutNeverWritten() =
        runTest {
            withQueue { rig ->
                rig.ai.editByDoc = mapOf("a" to suggestion(), "b" to AiEditSuggestion.EMPTY)
                val jobId = rig.suggestAll(rig.enqueue("a", "b"))

                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                review.docs.map { it.docId } shouldContainExactly listOf("a")
                review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
            }
        }

    @Test
    fun applyingWritesTheSelectionAndTakesTheRunOutOfTheQueue() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Electricity bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()

                val outcome = review.apply(review.docs)

                outcome.shouldBeInstanceOf<ApplyOutcome.Done>()
                // A queued run has no scope left to carry on into.
                outcome.result.moreRemain shouldBe false
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly
                    listOf("a" to mapOf(FieldKeys.TITLE to "Electricity bill"))
                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
            }
        }

    /**
     * A run reopened after a partly failed Apply (as after a restart, or a queue deletion that
     * failed) offers only what would still change something: the tag that landed is not offered
     * again, so Applying once more does not add it a second time.
     */
    @Test
    fun reopeningARunAfterAPartlyFailedApplyDoesNotOfferWhatAlreadyLanded() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.meta.failApplyEditsForDocs = setOf("a")
                val first = rig.queue.openEditRun(jobId).shouldNotBeNull()
                first.apply(first.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")

                rig.documents.documents.value = listOf(listItem("a", tags = listOf("invoice")))
                rig.meta.failApplyEditsForDocs = emptySet()
                val reopened = rig.queue.openEditRun(jobId).shouldNotBeNull()

                reopened.docs
                    .single()
                    .chips
                    .filter { it.selected }
                    .map { it.tag } shouldBe emptyList()
                reopened.apply(reopened.docs).shouldBeInstanceOf<ApplyOutcome.Done>()
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly
                    listOf("a" to mapOf(FieldKeys.TITLE to "Bill"))
            }
        }

    /**
     * Discarding a run after an Apply that partly landed leaves those writes standing, so the Done
     * it ends in reports them rather than "no documents were changed".
     */
    @Test
    fun aRunDiscardedAfterAPartlyFailedApplyReportsWhatLanded() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.meta.failApplyEditsForDocs = setOf("a")
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                review.landedSoFar() shouldBe ApplyResult(applied = 0, docsChanged = 0, moreRemain = false)
                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

                review.discard()

                review.landedSoFar() shouldBe ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
                rig.job(jobId) shouldBe null
            }
        }

    /**
     * Whether a tag is new is judged when the run opens: since it was suggested, the library may
     * have dropped a tag it had or gained one the model invented, in its own spelling.
     */
    @Test
    fun aSuggestedTagIsNewOrKnownByTheVocabularyWhenTheRunOpens() =
        runTest {
            withQueue { rig ->
                val known = TagSuggestion("invoice", isNew = false)
                rig.ai.edit = edit(addTags = listOf(known, TagSuggestion("energy", isNew = true)))
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.tags.tagNames = listOf("Energy")

                rig.queue
                    .openEditRun(jobId)
                    .shouldNotBeNull()
                    .docs
                    .single()
                    .chips shouldContainExactly
                    listOf(
                        ReviewChip("invoice", isNew = true, selected = false),
                        ReviewChip("Energy", isNew = false, selected = true),
                    )
            }
        }

    /** A removal of a tag the document no longer carries, or a value it already has, changes nothing. */
    @Test
    fun aReviewLeavesOutRemovalsAndValuesThatWouldChangeNothing() =
        runTest {
            withQueue { rig ->
                rig.ai.edit =
                    edit(
                        removeTags = listOf("draft", "old"),
                        metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "Bill", FieldKeys.CORRESPONDENT to "Acme")),
                    )
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.documents.documents.value = listOf(listItem(doc("a", title = "Bill"), tags = listOf("Draft")))

                val row =
                    rig.queue
                        .openEditRun(jobId)
                        .shouldNotBeNull()
                        .docs
                        .single()

                row.removals.map { it.tag } shouldContainExactly listOf("Draft")
                row.fields.map { it.key } shouldContainExactly listOf(FieldKeys.CORRESPONDENT)
            }
        }

    @Test
    fun aPartlyFailedApplyKeepsTheRunSoTheRetryCanFinishIt() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                rig.meta.failAddTagForDocs = setOf("a")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()

                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

                rig.job(jobId).shouldNotBeNull()
            }
        }

    /**
     * Applying and discarding are caller-driven, like queueing: the session must be able to wait for
     * their writes before it closes the device database. The drain's own writes run on the session's
     * drain and are not counted here.
     */
    @Test
    fun finishingAReviewDeletesTheRunAsOwnedWorkAppliedOrDiscarded() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                val applied = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(applied).shouldNotBeNull()
                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()
                val discarded = rig.suggestAll(rig.enqueue("b"))
                rig.queue
                    .openEditRun(discarded)
                    .shouldNotBeNull()
                    .discard()

                rig.job(applied) shouldBe null
                rig.job(discarded) shouldBe null
                // Two runs queued, two finished.
                rig.ownedWrites shouldBe 4
            }
        }

    /**
     * Dropping the run from the queue comes after the writes; when it fails, the writes still stand,
     * so the review is done, with the failure beside its counts, and the run stays queued.
     */
    @Test
    fun anAppliedRunThatCannotLeaveTheQueueIsStillDone() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                val failure = IllegalStateException("database closed")
                rig.ownWriteFailure = failure

                val done = review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()

                done.result shouldBe ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
                done.followUpFailure shouldBe failure
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.job(jobId).shouldNotBeNull()
            }
        }

    @Test
    fun discardingDropsTheRunAndItsSuggestions() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.suggestAll(rig.enqueue("a"))

                rig.queue.discardEditRun(jobId)

                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
                rig.queue.openEditRun(jobId) shouldBe null
            }
        }
}
