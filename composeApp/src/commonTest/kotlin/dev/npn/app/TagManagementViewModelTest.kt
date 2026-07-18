package dev.npn.app

import dev.npn.app.ui.tags.TagManagementViewModel
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

    private fun tag(name: String, count: Int = 0) = TagInfo(name, null, null, count)

    // ---- buildTree ----

    @Test
    fun buildTreeFlatTags() = runTest(dispatcher) {
        val model = vm()
        val tree = model.buildTree(listOf(tag("finance", 3), tag("travel", 1)))
        tree.map { it.path } shouldContainExactly listOf("finance", "travel")
        tree.all { it.children.isEmpty() } shouldBe true
        tree.first().count shouldBe 3
        tree.first().descendantCount shouldBe 3
    }

    @Test
    fun buildTreeCreatesIntermediateNamespaceNodes() = runTest(dispatcher) {
        val model = vm()
        // "finance.tax" exists but "finance" has no tag of its own.
        val tree = model.buildTree(listOf(tag("finance.tax", 2)))
        tree.map { it.path } shouldContainExactly listOf("finance")
        val finance = tree.first()
        finance.info shouldBe null           // intermediate, not a real tag
        finance.count shouldBe 0             // no direct docs
        finance.descendantCount shouldBe 2   // rolled up from child
        finance.children.map { it.path } shouldContainExactly listOf("finance.tax")
        finance.children.first().segment shouldBe "tax"
        finance.children.first().depth shouldBe 1
    }

    @Test
    fun buildTreeRollsUpDescendantCounts() = runTest(dispatcher) {
        val model = vm()
        val tree = model.buildTree(
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
    fun buildTreeSortsSiblingsAlphabetically() = runTest(dispatcher) {
        val model = vm()
        val tree = model.buildTree(listOf(tag("z"), tag("a"), tag("m")))
        tree.map { it.segment } shouldContainExactly listOf("a", "m", "z")
    }

    // ---- write ops trim and delegate ----

    @Test
    fun createDelegatesTrimmedName() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session)
        model.create("  finance  ", "#fff", "icon")
        advanceUntilIdle()
        session.meta.tagStyles shouldContainExactly listOf(Triple("finance", "#fff", "icon"))
    }

    @Test
    fun renameMergeDeleteDelegate() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session)
        model.rename("old", " new ")
        model.merge("src", " dest ")
        model.delete("gone")
        advanceUntilIdle()
        session.meta.renamedTags shouldContainExactly listOf("old" to "new")
        session.meta.mergedTags shouldContainExactly listOf("src" to "dest")
        session.meta.deletedTags shouldContainExactly listOf("gone")
    }

    @Test
    fun setStyleDelegatesWithoutTrimmingExistingName() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session)
        model.setStyle("finance", "#abc", "star")
        advanceUntilIdle()
        session.meta.tagStyles shouldContainExactly listOf(Triple("finance", "#abc", "star"))
    }

    // ---- failed writes surface a user-facing message ----

    @Test
    fun failedTagWritesEmitMessages() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failTagAdmin = { error("segment write failed") } }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val job = launch { model.messages.collect { messages += it } }
        advanceUntilIdle()
        model.delete("gone")
        model.rename("old", "new")
        advanceUntilIdle()
        session.meta.deletedTags shouldBe emptyList()
        session.meta.renamedTags shouldBe emptyList()
        messages shouldContainExactly listOf(
            "Could not delete tag: segment write failed",
            "Could not rename tag: segment write failed",
        )
        job.cancel()
    }
}
