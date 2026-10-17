@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.appSessionTest
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.testing.doc
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
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

class AiEditRunSuggestTest {
    @Test
    fun emptyScopeReturnsEmptyWithoutProgress() =
        runTest {
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a", tags = listOf("utility")))
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
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
