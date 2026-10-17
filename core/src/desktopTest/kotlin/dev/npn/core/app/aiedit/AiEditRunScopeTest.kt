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

/**
 * Which documents a run's scope asks the library for. The tests check the query a scope makes, not
 * the documents it returns, so they ignore what `docsFor` gives back.
 */
@Suppress("IgnoredReturnValue")
class AiEditRunScopeTest {
    @Test
    fun selectionScopeQueriesTheSelectedIds() =
        runTest {
            val h = AiEditRunHarness(selection = listOf("a", "c"))
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
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run.docsFor(key(scope = EditScope.SELECTION)).first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun savedViewScopeUsesTheViewFilterAndSort() =
        runTest {
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run.docsFor(key(scope = EditScope.SAVED_VIEW, savedView = null)).first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun missingFieldsScopeFiltersOnTheChosenFields() =
        runTest {
            val h = AiEditRunHarness()
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
            val h = AiEditRunHarness()
            h.documents.documents.value = listOf(listItem("a"))

            h.run
                .docsFor(key(scope = EditScope.MISSING_FIELDS, missing = emptyList()))
                .first() shouldBe emptyList()
            h.documents.observedFilters shouldBe emptyList()
        }

    @Test
    fun untaggedReviewQueueAndAllMapToTheirFilters() =
        runTest {
            val h = AiEditRunHarness()

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
