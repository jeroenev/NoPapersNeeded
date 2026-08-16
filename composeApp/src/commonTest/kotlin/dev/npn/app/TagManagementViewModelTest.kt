package dev.npn.app

import dev.npn.app.ui.tags.TagManagementViewModel
import dev.npn.app.ui.tags.buildTree
import dev.npn.core.app.TagInfo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagManagementViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = TagManagementViewModel(session)

    private fun tag(
        name: String,
        count: Int = 0,
    ) = TagInfo(name, null, null, count)

    // ---- buildTree ----

    @Test
    fun buildTreeFlatTags() =
        runTest(dispatcher) {
            val tree = buildTree(listOf(tag("finance", 3), tag("travel", 1)))
            tree.map { it.path } shouldContainExactly listOf("finance", "travel")
            tree.all { it.children.isEmpty() } shouldBe true
            tree.first().count shouldBe 3
            tree.first().descendantCount shouldBe 3
        }

    @Test
    fun buildTreeCreatesIntermediateNamespaceNodes() =
        runTest(dispatcher) {
            // "finance.tax" exists but "finance" has no tag of its own.
            val tree = buildTree(listOf(tag("finance.tax", 2)))
            tree.map { it.path } shouldContainExactly listOf("finance")
            val finance = tree.first()
            finance.info shouldBe null // intermediate, not a real tag
            finance.count shouldBe 0 // no direct docs
            finance.descendantCount shouldBe 2 // rolled up from child
            finance.children.map { it.path } shouldContainExactly listOf("finance.tax")
            finance.children.first().segment shouldBe "tax"
            finance.children.first().depth shouldBe 1
        }

    @Test
    fun buildTreeRollsUpDescendantCounts() =
        runTest(dispatcher) {
            val tree =
                buildTree(
                    listOf(tag("a", 1), tag("a.b", 2), tag("a.b.c", 4)),
                )
            val a = tree.single()
            a.count shouldBe 1
            a.descendantCount shouldBe 7 // 1 + 2 + 4
            val b = a.children.single()
            b.count shouldBe 2
            b.descendantCount shouldBe 6 // 2 + 4
            val c = b.children.single()
            c.count shouldBe 4
            c.descendantCount shouldBe 4
            c.info.shouldNotBeNull()
        }

    @Test
    fun buildTreeSortsSiblingsAlphabetically() =
        runTest(dispatcher) {
            val tree = buildTree(listOf(tag("z"), tag("a"), tag("m")))
            tree.map { it.segment } shouldContainExactly listOf("a", "m", "z")
        }

    // ---- write ops trim and delegate ----

    @Test
    fun mergeAllMergesEverythingIntoPrimaryExceptItself() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.mergeAll(listOf("finance", "invoices", "bills"), into = "finance")
            advanceUntilIdle()
            session.tagAdmin.mergedTags shouldContainExactly
                listOf("invoices" to "finance", "bills" to "finance")
        }

    @Test
    fun setStyleAllAppliesOneColorToEverySelectedTag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.setStyleAll(listOf("finance", "travel"), "#abc")
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
            model.deleteAll(listOf("finance", "travel"))
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldContainExactly listOf("finance", "travel")
        }

    @Test
    fun renameMergeDeleteDelegate() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.rename("old", " new ")
            model.merge("src", " dest ")
            model.delete("gone")
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
            model.setStyle("finance", "#abc", "star")
            advanceUntilIdle()
            session.tagAdmin.tagStyles shouldContainExactly listOf(Triple("finance", "#abc", "star"))
        }

    // ---- failed writes surface a user-facing message ----

    @Test
    fun failedTagWritesEmitMessages() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { tagAdmin.failTagAdmin = { error("segment write failed") } }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.delete("gone")
            model.rename("old", "new")
            advanceUntilIdle()
            session.tagAdmin.deletedTags shouldBe emptyList()
            session.tagAdmin.renamedTags shouldBe emptyList()
            messages shouldContainExactly
                listOf(
                    "Could not delete tag: segment write failed",
                    "Could not rename tag: segment write failed",
                )
            job.cancel()
        }
}
