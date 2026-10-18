package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.library.LibraryViewModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.detail_trash_failed
import no_papers_needed.composeapp.generated.resources.library_moved_to_trash
import no_papers_needed.composeapp.generated.resources.library_trash_failed
import no_papers_needed.composeapp.generated.resources.library_undo
import kotlin.test.Test

/** Bulk trash from the library selection, and the Undo message that takes it back. */
class LibraryTrashTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) =
        LibraryViewModel(session, FakeSettingsRepository())

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
            val messages = collecting(model.messages)
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
            val messages = collecting(model.messages)
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

    /**
     * A document trashed or deleted elsewhere meanwhile is not this action's: it is neither counted
     * nor offered for Undo, whose restore would undo the other device's trash.
     */
    @Test
    fun bulkTrashLeavesADocumentAlreadyGoneOutOfTheCountAndTheUndo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.goneDocIds = setOf("d2") }
            val model = vm(session = session)
            val messages = collecting(model.messages)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            val undo = messages.single()
            undo.text shouldBe uiText(Res.plurals.library_moved_to_trash, 2, 2)
            model.ui.value.selected shouldBe emptySet()
            undo.action?.onPerform?.invoke()
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d3")
        }

    /** A second press while the first bulk trash runs does not trash the selection again. */
    @Test
    fun aSecondBulkTrashWhileOneRunsDoesNothing() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val session = FakeLibrarySession().apply { docLifecycle.trashGate = gate }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            runCurrent()

            model.bulkTrash()
            gate.complete(Unit)
            advanceUntilIdle()

            session.docLifecycle.trashedDocs shouldContainExactly listOf("d1")
            messages shouldContainExactly listOf(uiText(Res.plurals.library_moved_to_trash, 1, 1))
        }

    /**
     * The selection stays usable while a bulk trash runs: what the user selects meanwhile is theirs,
     * and the trash, once done, takes only its own documents out of it.
     */
    @Test
    fun aBulkTrashLeavesASelectionMadeWhileItRan() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val session = FakeLibrarySession().apply { docLifecycle.trashGate = gate }
            val model = vm(session = session)
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            runCurrent()

            model.clearSelection()
            model.startSelection("d2")
            gate.complete(Unit)
            advanceUntilIdle()

            session.docLifecycle.trashedDocs shouldContainExactly listOf("d1")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }

    /** A document that failed comes back into the selection only while the user still has it selected. */
    @Test
    fun aFailedBulkTrashKeepsOnlyWhatIsStillSelected() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val session =
                FakeLibrarySession().apply {
                    docLifecycle.trashGate = gate
                    docLifecycle.failTrashDocIds = setOf("d1", "d2")
                }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            runCurrent()
            model.bulkTrash()
            runCurrent()

            model.toggleSelection("d1")
            model.toggleSelection("d3")
            gate.complete(Unit)
            advanceUntilIdle()

            model.ui.value.selected shouldBe setOf("d2", "d3")
        }

    /** A selection dismissed while a bulk trash ran stays dismissed, even when the trash failed. */
    @Test
    fun aSelectionClearedWhileABulkTrashRanStaysCleared() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val session =
                FakeLibrarySession().apply {
                    docLifecycle.trashGate = gate
                    docLifecycle.failTrashDocIds = setOf("d1")
                }
            val model = vm(session = session)
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            runCurrent()

            model.clearSelection()
            gate.complete(Unit)
            advanceUntilIdle()

            model.ui.value.selected shouldBe emptySet()
            model.ui.value.selectionMode shouldBe false
        }

    /** A batched bulk action, too, takes only its own documents out of the selection. */
    @Test
    fun aBulkTagLeavesASelectionMadeWhileItRan() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val session = FakeLibrarySession().apply { meta.addTagGate = gate }
            val model = vm(session = session)
            model.startSelection("d1")
            runCurrent()
            model.bulkAddTag("finance")
            runCurrent()

            model.toggleSelection("d2")
            gate.complete(Unit)
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactly listOf(listOf("d1") to "finance")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }

    /** Several failures are counted rather than each explained. */
    @Test
    fun bulkTrashCountsSeveralFailures() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d1", "d2") }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.library_trash_failed, 3, 2, 3),
                    uiText(Res.plurals.library_moved_to_trash, 1, 1),
                )
            model.ui.value.selected shouldBe setOf("d1", "d2")
        }

    @Test
    fun bulkTrashNoSuccessOffersNoUndo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d1") }
            val model = vm(session = session)
            val messages = collecting(model.messages)
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            messages.filter { it.action != null } shouldBe emptyList()
        }

    @Test
    fun undoTrashRestoresGivenTrashes() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.undoTrash(listOf("d1", "d2").mapNotNull { session.docLifecycle.trashDoc(it) })
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d2")
        }

    /**
     * The Undo takes back the trashes this action made, not the documents: one restored and trashed
     * again since, here or on another device, stays in the trash.
     */
    @Test
    fun theUndoLeavesADocumentTrashedAgainSince() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = collecting(model.messages)
            model.startSelection("d1")
            model.toggleSelection("d2")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            session.docLifecycle.trashAgain("d1")
            messages
                .single()
                .action
                ?.onPerform
                ?.invoke()
            advanceUntilIdle()

            session.docLifecycle.restoredDocs shouldContainExactly listOf("d2")
        }

    @Test
    fun undoTrashPartialFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failRestore = { error("path taken") } }
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })
            model.undoTrash(listOf("d1", "d2").mapNotNull { session.docLifecycle.trashDoc(it) })
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
            // Both messages show, in order: why the one failed, then the Undo for what did move.
            messages shouldContainExactly
                listOf(
                    failure(Res.string.detail_trash_failed, "trash failed for d2"),
                    uiText(Res.plurals.library_moved_to_trash, 2, 2),
                )
            // d1 and d3 succeeded; only d2 kept selected for retry.
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d3")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }
}
