@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private class Harness(
    selection: List<String> = emptyList(),
) {
    val documents = FakeDocumentRepository()
    val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    val content = FakeDocContentFacade()
    val ai = FakeAiSuggest()
    val fieldRegistry = FakeFieldRegistry()
    val run = AiEditRunImpl(documents, meta, content, ai, fieldRegistry, selection)
}

private val REQUEST = AiEditRequest()

private fun key(
    scope: EditScope = EditScope.ALL,
    savedView: SavedView? = null,
    missing: List<String> = emptyList(),
    batchSize: Int = 50,
): ScopeKey = ScopeKey(scope, savedView, missing, batchSize)

private suspend fun AiEditRun.suggest(
    key: ScopeKey,
    onProgress: (EditProgress) -> Unit = {},
): BatchResult = suggestNextBatch(key, REQUEST, onProgress)

class AiEditRunSuggestTest {
    @Test
    fun emptyScopeReturnsEmptyWithoutProgress() =
        runTest {
            val h = Harness()
            val progress = mutableListOf<EditProgress>()

            h.run.suggest(key()) { progress += it } shouldBe BatchResult.Empty
            progress shouldBe emptyList()
            h.ai.suggestEditsCalls shouldBe emptyList()
        }

    @Test
    fun progressClimbsAndResultsKeepBatchOrder() =
        runTest {
            // Skew completion order (c finishes first, then b, then a) to prove results are
            // re-ordered back to batch order and progress climbs as docs COMPLETE.
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"), listItem("c"))
            h.ai.suggestDelayByDoc = mapOf("a" to 300, "b" to 200, "c" to 100)
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(addTags = listOf(TagSuggestion("x", isNew = false))),
                    "b" to edit(addTags = listOf(TagSuggestion("y", isNew = false))),
                    "c" to edit(addTags = listOf(TagSuggestion("z", isNew = false))),
                )
            val progress = mutableListOf<Int>()

            val result = h.run.suggest(key()) { progress += it.done }

            progress shouldContainExactly listOf(0, 1, 2, 3)
            val review = result.shouldBeInstanceOf<BatchResult.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("a", "b", "c")
        }

    @Test
    fun concurrencyIsBounded() =
        runTest {
            // 14 docs, concurrency 12: only 12 calls should be dispatched before any completes.
            val h = Harness()
            h.documents.documents.value = (1..14).map { listItem("d$it") }
            h.ai.suggestDelayMillis = 100
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))

            val job = launch { h.run.suggest(key()) }
            advanceTimeBy(50) // permits granted, none completed yet
            runCurrent()

            h.ai.suggestEditsCalls shouldHaveSize 12
            advanceUntilIdle()
            h.ai.suggestEditsCalls shouldHaveSize 14
            job.cancel()
        }

    @Test
    fun preSelectPolicyForTags() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit =
                edit(
                    addTags =
                        listOf(
                            TagSuggestion("invoice", isNew = false),
                            TagSuggestion("brandnew", isNew = true),
                        ),
                    removeTags = listOf("utility"),
                )

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()

            val doc = review.docs.single()
            doc.chips.single { it.tag == "invoice" }.selected shouldBe true
            doc.chips.single { it.tag == "brandnew" }.selected shouldBe false
            doc.removals.single().selected shouldBe false
        }

    @Test
    fun preSelectPolicyForFields() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem(doc("a", title = "Old title")))
            h.ai.edit =
                edit(
                    metadata =
                        FieldSuggestion(
                            mapOf(
                                FieldKeys.TITLE to "New title",
                                FieldKeys.CORRESPONDENT to "Acme",
                                FieldKeys.DOC_DATE to "2024-01-15",
                            ),
                        ),
                )

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()

            val fields = review.docs.single().fields
            fields.map { it.key } shouldContainExactly
                listOf(FieldKeys.TITLE, FieldKeys.CORRESPONDENT, FieldKeys.DOC_DATE)
            // Title overwrites an existing value -> pre-deselected, current value retained.
            val title = fields.single { it.key == FieldKeys.TITLE }
            title.selected shouldBe false
            title.current shouldBe "Old title"
            fields.single { it.key == FieldKeys.CORRESPONDENT }.selected shouldBe true
            fields.single { it.key == FieldKeys.CORRESPONDENT }.current shouldBe null
            fields.single { it.key == FieldKeys.DOC_DATE }.selected shouldBe true
        }

    @Test
    fun noSuggestionDocsPartitionedIntoEmptyDocs() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(addTags = listOf(TagSuggestion("t", isNew = false))),
                    "b" to AiEditSuggestion.EMPTY,
                )

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()

            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
        }

    @Test
    fun fieldOnlySuggestionsCountAsSuggestions() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(metadata = FieldSuggestion(mapOf(FieldKeys.DOC_DATE to "2024-01-15"))),
                    "b" to AiEditSuggestion.EMPTY,
                )

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()

            review.docs.map { it.docId } shouldContainExactly listOf("a")
            review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
        }

    @Test
    fun failedDocsAreCountedNotProcessedAndRetriedNextBatch() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.failWith = { if (it == "a") error("suggest edits failed for a") }
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()
            review.docs.map { it.docId } shouldContainExactly listOf("b")
            review.failures shouldBe 1
            review.total shouldBe 2

            // b is processed; the failed a is not, so the next batch retries exactly a.
            h.ai.failWith = null
            h.ai.suggestEditsCalls.clear()
            h.run.suggest(key())
            h.ai.suggestEditsCalls shouldContainExactly listOf("a")
        }

    @Test
    fun processedDocsExcludedFromNextBatchIncludingEmptyOnes() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"), listItem("c"))
            h.ai.editByDoc =
                mapOf(
                    "a" to edit(addTags = listOf(TagSuggestion("t", isNew = false))),
                    "b" to AiEditSuggestion.EMPTY,
                )

            h.run.suggest(key(batchSize = 2))
            h.run.processed.value shouldBe setOf("a", "b")

            h.ai.suggestEditsCalls.clear()
            h.run.suggest(key(batchSize = 2))
            h.ai.suggestEditsCalls shouldContainExactly listOf("c")
        }

    @Test
    fun snippetIsCollapsedWhitespaceAndBlankBecomesNull() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
            h.content.extractedTextByDoc =
                mapOf(
                    "a" to "  Invoice   #123\n\tAcme  Corp  ",
                    "b" to "   ",
                )

            val review = h.run.suggest(key()).shouldBeInstanceOf<BatchResult.Review>()

            review.docs.single { it.docId == "a" }.snippet shouldBe "Invoice #123 Acme Corp"
            review.docs.single { it.docId == "b" }.snippet shouldBe null
        }

    @Test
    fun cancellationTearsDownTheWholeBatch() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
            h.ai.suggestDelayMillis = 1_000
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))

            var completed = false
            var cancelled = false
            val job =
                launch {
                    h.run.suggest(key())
                    completed = true
                }
            job.invokeOnCompletion { cancelled = it is CancellationException }
            advanceTimeBy(500)
            runCurrent()
            job.cancel()
            advanceUntilIdle()

            completed shouldBe false
            cancelled shouldBe true
            h.run.processed.value shouldBe emptySet()
        }
}

class AiEditRunApplyTest {
    private suspend fun reviewOf(
        h: Harness,
        key: ScopeKey = key(),
    ): BatchResult.Review = h.run.suggest(key).shouldBeInstanceOf<BatchResult.Review>()

    @Test
    fun applyWritesOnlySelectedItems() =
        runTest {
            val h = Harness()
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
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"))
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
            val h = Harness()
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
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"))
            h.ai.edit = edit(addTags = listOf(TagSuggestion("brandnew", isNew = true)))

            val review = reviewOf(h)
            h.run.apply(key(), review.docs) shouldBe ApplyOutcome.NothingSelected
            h.meta.addedTags shouldBe emptyList()
        }

    @Test
    fun doneReportsMoreRemainWhenScopeHasUnprocessedDocs() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"), listItem("b"), listItem("c"))
            h.ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))

            val review = reviewOf(h, key(batchSize = 2))
            val done = h.run.apply(key(batchSize = 2), review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()

            done.result.moreRemain shouldBe true
        }

    @Test
    fun partialFailureRetainsFailedDocsWithUnappliedSelections() =
        runTest {
            val h = Harness()
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

            // Retry succeeding: only (a, t1) is written; Done carries the earlier (b, t2) count.
            h.meta.failAddTagForDocs = emptySet()
            h.meta.addedTags.clear()
            val done = h.run.apply(key(), retry.remaining).shouldBeInstanceOf<ApplyOutcome.Done>()

            h.meta.addedTags shouldContainExactly listOf(listOf("a") to "t1")
            done.result.applied shouldBe 2
        }

    @Test
    fun retryDeselectsAlreadySucceededItemsOnFailedDocs() =
        runTest {
            // Doc a: the "bad" tag call fails (a shares it with nothing), the title write succeeds.
            // The retained row must keep the failed chip selected but deselect the written field.
            val h = Harness()
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
            remaining.fields.single().selected shouldBe false
        }

    @Test
    fun suggestingResetsTheCarriedApplyCount() =
        runTest {
            // A partial failure carries its succeeded count; a NEW batch must not inherit it.
            val h = Harness()
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

/** The selection scope over the real library query, which a fake repository cannot vouch for. */
class AiEditRunSelectionQueryTest {
    @Test
    fun aSelectionPastSqlitesBindLimitResolvesToItsLiveDocuments() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                listOf("a", "b", "c", "d").forEach { rig.writePdf("$it.pdf", PdfFixtures.embeddedTextPdf(it)) }
                rig.session.rescan()
                val (a, b, c) = listOf("a.pdf", "b.pdf", "c.pdf").map { rig.docId(it) }
                rig.session.docLifecycle.trashDoc(b)
                // d stays live but unselected; the rest of the selection names nothing in the library.
                val unknown = (1..SQLITE_MAX_VARIABLE_NUMBER).map { it.toString(16).padStart(64, '0') }
                val selection = listOf(a, b, c) + unknown
                val run =
                    AiEditRunImpl(
                        DocumentRepositoryImpl(rig.db),
                        FakeMetadataWriter(),
                        FakeDocContentFacade(),
                        FakeAiSuggest(),
                        FakeFieldRegistry(),
                        selection,
                    )
                val key = key(scope = EditScope.SELECTION)

                run.docsFor(key).first().map { it.doc.docId } shouldContainExactlyInAnyOrder listOf(a, c)
                run.remainingDocs(key).map { it.doc.docId } shouldContainExactlyInAnyOrder listOf(a, c)
                run.moreRemain(key) shouldBe true
            } finally {
                rig.close()
            }
        }

    private companion object {
        /** The bundled SQLite's bind-variable limit on desktop and Android, which BIND_CHUNK stays under. */
        const val SQLITE_MAX_VARIABLE_NUMBER = 32_766
    }
}

class AiEditRunScopeTest {
    @Test
    fun selectionScopeQueriesTheSelectedIds() =
        runTest {
            val h = Harness(selection = listOf("a", "c"))
            h.documents.documents.value = listOf(listItem("a"), listItem("b"), listItem("c"))

            val docs = h.run.docsFor(key(scope = EditScope.SELECTION)).first()

            docs.map { it.doc.docId } shouldContainExactly listOf("a", "c")
            h.documents.observedFilters
                .single()
                .first shouldBe LibraryFilter(docIds = listOf("a", "c"))
        }

    @Test
    fun selectionScopeWithoutSelectionSelectsNothing() =
        runTest {
            // An empty id list is no constraint at all to the library query, so it must not get one.
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run.docsFor(key(scope = EditScope.SELECTION)).first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun savedViewScopeUsesTheViewFilterAndSort() =
        runTest {
            val h = Harness()
            val view =
                SavedView(
                    id = "v1",
                    name = "Invoices",
                    filter = LibraryFilter(tagsAll = listOf("invoice")),
                    sort = LibrarySort(SortField.Name, ascending = true),
                )

            h.run.docsFor(key(scope = EditScope.SAVED_VIEW, savedView = view))

            h.documents.observedFilters.single() shouldBe (view.filter to view.sort)
        }

    @Test
    fun savedViewScopeWithoutViewSelectsNothing() =
        runTest {
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run.docsFor(key(scope = EditScope.SAVED_VIEW, savedView = null)).first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun missingFieldsScopeFiltersOnTheChosenFields() =
        runTest {
            val h = Harness()
            val missing = listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_DATE)

            h.run.docsFor(key(scope = EditScope.MISSING_FIELDS, missing = missing))

            h.documents.observedFilters
                .single()
                .first.missingFields shouldContainExactly missing
        }

    @Test
    fun missingFieldsScopeWithNoFieldsSelectsNothing() =
        runTest {
            // No autofill field enabled must select nothing, not degrade into "all documents".
            val h = Harness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run.docsFor(key(scope = EditScope.MISSING_FIELDS, missing = emptyList())).first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun untaggedReviewQueueAndAllMapToTheirFilters() =
        runTest {
            val h = Harness()

            h.run.docsFor(key(scope = EditScope.UNTAGGED))
            h.run.docsFor(key(scope = EditScope.REVIEW_QUEUE))
            h.run.docsFor(key(scope = EditScope.ALL))

            h.documents.observedFilters.map { it.first } shouldContainExactly
                listOf(
                    LibraryFilter(untaggedOnly = true),
                    LibraryFilter(unreviewedOrUntagged = true),
                    LibraryFilter(),
                )
        }
}
