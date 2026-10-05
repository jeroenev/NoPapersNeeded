package dev.npn.app

import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.library.LibraryViewModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.library_moved_to_trash
import no_papers_needed.composeapp.generated.resources.library_trash_failed
import no_papers_needed.composeapp.generated.resources.library_undo
import kotlin.test.Test

/** Bulk trash from the library selection, and the Undo message that takes it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTrashTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) =
        LibraryViewModel(session, FakeSettingsRepository())

    /** Collect the text of the messages [model] emits. */
    private fun TestScope.collectMessages(model: LibraryViewModel): MutableList<UiText> {
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { model.messages.collect { messages += it.text } }
        return messages
    }

    @Test
    fun bulkTrashAllSucceedClearsSelection() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            model.bulkTrash()
            advanceUntilIdle()
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d2", "d3")
            model.ui.value.selected shouldBe emptySet()
            model.ui.value.selectionMode shouldBe false
        }

    @Test
    fun bulkTrashOffersAnUndoThatRestoresTheTrashedIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            model.toggleSelection("d2")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            val undo = messages.single()
            undo.text shouldBe uiText(Res.plurals.library_moved_to_trash, 2, 2)
            undo.action?.label shouldBe uiText(Res.string.library_undo)
            undo.action?.onPerform?.invoke()
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d2")
        }

    @Test
    fun bulkTrashPartialFailureUndoesOnlyTrashedIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d2") }
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            // Only the successfully trashed ids are offered for undo; d2 failed and stays selected.
            messages
                .single { it.action != null }
                .action
                ?.onPerform
                ?.invoke()
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d3")
        }

    @Test
    fun bulkTrashNoSuccessOffersNoUndo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d1") }
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            messages.filter { it.action != null } shouldBe emptyList()
        }

    @Test
    fun undoTrashRestoresGivenIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.undoTrash(listOf("d1", "d2"))
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d2")
        }

    @Test
    fun undoTrashPartialFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failRestore = { error("path taken") } }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.undoTrash(listOf("d1", "d2"))
            advanceUntilIdle()
            messages shouldContainExactly listOf(uiText(Res.plurals.common_restore_documents_failed, 2, 2, 2))
        }

    @Test
    fun bulkTrashPartialFailureKeepsOnlyFailedIdsSelected() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d2") }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")

            val messages = collecting(model.messages.map { it.text })
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            // Both messages show, in order: the failure, then the Undo for what did move.
            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.library_trash_failed, 3, 1, 3),
                    uiText(Res.plurals.library_moved_to_trash, 2, 2),
                )
            // d1 and d3 succeeded; only d2 kept selected for retry.
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d3")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }
}
