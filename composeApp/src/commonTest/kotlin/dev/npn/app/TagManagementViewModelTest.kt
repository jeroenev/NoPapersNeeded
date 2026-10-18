package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.tags.TagManagementViewModel
import dev.npn.app.ui.tags.tagRows
import dev.npn.core.app.TagInfo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_tags_color_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_failed
import no_papers_needed.composeapp.generated.resources.common_tags_delete_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_failed
import no_papers_needed.composeapp.generated.resources.common_tags_merge_some_failed
import no_papers_needed.composeapp.generated.resources.common_tags_rename_failed
import no_papers_needed.composeapp.generated.resources.common_tags_style_failed
import kotlin.test.Test

class TagManagementViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = TagManagementViewModel(session)

    private fun tag(
        name: String,
        count: Int = 0,
    ) = TagInfo(name, null, null, count)

    @Test
    fun flatTagsAreRowsOfTheirOwn() {
        val rows = tagRows(listOf(tag("finance", 3), tag("travel", 1)))
        rows.map { it.path } shouldContainExactly listOf("finance", "travel")
        rows.first().descendantCount shouldBe 3
    }

    @Test
    fun aNamespaceWithoutATagOfItsOwnStillGetsARow() {
        // "finance.tax" exists but "finance" has no tag of its own.
        val rows = tagRows(listOf(tag("finance.tax", 2)))
        rows.map { it.path } shouldContainExactly listOf("finance", "finance.tax")
        val finance = rows.first()
        finance.info shouldBe null // intermediate, not a real tag
        finance.descendantCount shouldBe 2 // rolled up from its child
        rows.last().segment shouldBe "tax"
    }

    @Test
    fun descendantCountsRollUp() {
        val rows = tagRows(listOf(tag("a", 1), tag("a.b", 2), tag("a.b.c", 4)))
        rows.map { it.descendantCount } shouldContainExactly listOf(7, 6, 4) // a, a.b, a.b.c
        rows.last().info.shouldNotBeNull()
    }

    @Test
    fun siblingsAreAlphabeticalAndChildrenFollowTheirParent() {
        // Depth-first, not plain string order: "a-c" sorts before "a.b" as a string, but "a.b" is
        // a's child and comes right after it.
        val rows = tagRows(listOf(tag("z"), tag("a.b"), tag("a-c"), tag("m"), tag("a")))
        rows.map { it.path } shouldContainExactly listOf("a", "a.b", "a-c", "m", "z")
    }

    @Test
    fun mergeAllMergesEverythingIntoPrimaryExceptItself() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.merge(listOf("finance", "invoices", "bills"), into = "finance")
            advanceUntilIdle()
            session.tagAdmin.mergedTags shouldContainExactly
                listOf("invoices" to "finance", "bills" to "finance")
        }

    @Test
    fun setStyleAllAppliesOneColorToEverySelectedTag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.setStyle(listOf("finance", "travel"), "#abc")
            advanceUntilIdle()
            session.tagAdmin.tagStyles shouldContainExactly
                listOf(
                    Triple("finance", "#abc", null),
                    Triple("travel", "#abc", null),
                )
        }

    @Test
    fun deleteAllDeletesEverySelectedTag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.delete(listOf("finance", "travel"))
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldContainExactly listOf("finance", "travel")
        }

    @Test
    fun theSelectionTogglesSelectsAllAndClears() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.tags.tags.value = listOf(tag("finance.tax"), tag("travel"))
            val model = vm(session)
            collecting(model.rows) // the screen shows the rows, which the selection follows
            advanceUntilIdle()

            model.toggleSelected("travel")
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("travel")
            model.selectOnly("finance.tax")
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("finance.tax")
            // Every real tag, in card order; the "finance" namespace has no tag of its own.
            model.selectAll()
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("finance.tax", "travel")
            model.toggleSelected("travel")
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("finance.tax")
            model.clearSelection()
            advanceUntilIdle()
            model.selection.value shouldBe emptyList()
        }

    @Test
    fun theSelectionForgetsATagThatVanishes() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.tags.tags.value = listOf(tag("invoice"), tag("tax"))
            val model = vm(session)
            collecting(model.rows) // the screen shows the rows, which the selection follows
            advanceUntilIdle()
            model.selectAll()
            advanceUntilIdle()

            session.tags.tags.value = listOf(tag("invoice"))
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("invoice")

            // A tag of the old name coming back is not selected again.
            session.tags.tags.value = listOf(tag("invoice"), tag("tax"))
            advanceUntilIdle()
            model.selection.value shouldContainExactly listOf("invoice")
        }

    @Test
    fun renameMergeDeleteDelegate() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.rename("old", "new")
            model.merge(listOf("src"), "dest")
            model.delete(listOf("gone"))
            advanceUntilIdle()
            session.tagAdmin.renamedTags shouldContainExactly listOf("old" to "new")
            session.tagAdmin.mergedTags shouldContainExactly listOf("src" to "dest")
            session.tagAdmin.deletedTags shouldContainExactly listOf("gone")
        }

    @Test
    fun setStyleDelegatesWithoutTrimmingExistingName() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.setStyle(listOf("finance"), "#abc")
            advanceUntilIdle()
            session.tagAdmin.tagStyles shouldContainExactly listOf(Triple("finance", "#abc", null))
        }

    /** One failed tag among several is explained; the others still go through. */
    @Test
    fun bulkTagActionsContinuePastAFailedTagAndSayWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { tagAdmin.failTags = setOf("invoices") }
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.delete(listOf("finance", "invoices", "travel"))
            model.merge(listOf("finance", "invoices", "bills"), into = "finance")
            model.setStyle(listOf("invoices", "travel"), "#abc")
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldContainExactly listOf("finance", "travel")
            session.tagAdmin.mergedTags shouldContainExactly listOf("bills" to "finance")
            session.tagAdmin.tagStyles shouldContainExactly listOf(Triple("travel", "#abc", null))
            messages shouldContainExactly
                listOf(
                    failure(Res.string.common_tags_delete_failed, "cannot write tag invoices"),
                    failure(Res.string.common_tags_merge_failed, "cannot write tag invoices"),
                    failure(Res.string.common_tags_style_failed, "cannot write tag invoices"),
                )
        }

    /** Several failed tags are counted, "X of Y". */
    @Test
    fun bulkTagActionsCountSeveralFailures() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { tagAdmin.failTags = setOf("invoices", "travel") }
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.delete(listOf("finance", "invoices", "travel"))
            model.merge(listOf("finance", "invoices", "travel"), into = "finance")
            model.setStyle(listOf("invoices", "travel"), "#abc")
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldContainExactly listOf("finance")
            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.common_tags_delete_some_failed, 3, 2, 3),
                    uiText(Res.plurals.common_tags_merge_some_failed, 2, 2, 2),
                    uiText(Res.plurals.common_tags_color_some_failed, 2, 2, 2),
                )
        }

    @Test
    fun failedTagWritesEmitMessages() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { tagAdmin.failTagAdmin = { error("segment write failed") } }
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.delete(listOf("gone"))
            model.rename("old", "new")
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldBe emptyList()
            session.tagAdmin.renamedTags shouldBe emptyList()
            messages shouldContainExactly
                listOf(
                    failure(Res.string.common_tags_delete_failed, "segment write failed"),
                    failure(Res.string.common_tags_rename_failed, "segment write failed"),
                )
        }
}
