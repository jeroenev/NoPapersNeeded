package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.SavedChanges
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_saved_changes
import kotlin.test.Test

/**
 * The editor's one save model: [DocumentDrafts.commit] when an edit is done (a field loses focus,
 * Ctrl+S, the app goes to the background) and [DocumentDrafts.left] when the document is left. A
 * save writes only a change, once, and offers Undo: inline while the document is on screen, as a
 * notice that outlives it once it is not.
 */
class DocumentDraftsCommitTest : MainDispatcherTest() {
    private val stored = mapOf(FieldKeys.TITLE to "Old")

    @Test
    fun aCommitWritesTheChangeOnceAndOffersUndoInline() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            val notices = collecting(store.notices.messages)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "New", persisted = "Old")

            store.commit("one", stored, "New")
            runCurrent()
            // Nothing left to write: a second commit (focus leaving another field) writes nothing.
            store.commit("one", stored, "New")
            runCurrent()

            writer.batchedEdits.map { it.fields } shouldContainExactly listOf(mapOf(FieldKeys.TITLE to "New"))
            store.state.getValue("one").undo shouldBe SavedChanges("New", mapOf(FieldKeys.TITLE to "Old"), notice = 1)
            notices.shouldBeEmpty()

            store.undo("one")
            runCurrent()
            writer.batchedEdits.last().fields shouldBe mapOf(FieldKeys.TITLE to "Old")
            store.state["one"]?.undo shouldBe null
        }

    @Test
    fun aCommitWithNothingChangedWritesNothing() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "Old ", persisted = "Old")

            store.commit("one", stored, "Old")
            advanceUntilIdle()

            writer.batchedEdits.shouldBeEmpty()
            store.state shouldBe emptyMap()
        }

    /** The inline Undo lasts as long as an Undo snackbar, then the entry goes. */
    @Test
    fun theInlineUndoExpires() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), this)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "New", persisted = "Old")
            store.commit("one", stored, "New")
            advanceUntilIdle()
            store.state shouldBe emptyMap()
        }

    /** Leaving the document saves its draft, and says so where the user goes, with an Undo. */
    @Test
    fun leavingSavesTheDraftAndItsNoticeUndoesIt() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            val notices = collecting(store.notices.messages)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "New", persisted = "Old")

            store.unregisterEditing("one")
            store.left("one", stored, "New")
            advanceUntilIdle()

            writer.batchedEdits.single().fields shouldBe mapOf(FieldKeys.TITLE to "New")
            store.state shouldBe emptyMap()
            notices.map { it.text } shouldContainExactly listOf(uiText(Res.string.detail_saved_changes, "New"))
            notices
                .single()
                .action
                .shouldNotBeNull()
                .onPerform()
            advanceUntilIdle()
            writer.batchedEdits.last().fields shouldBe mapOf(FieldKeys.TITLE to "Old")
        }

    /** Saves made on screen and the one made on leaving are one notice, whose Undo goes back to before all of them. */
    @Test
    fun leavingAfterASaveOnScreenMovesItsUndoToTheNotice() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            val notices = collecting(store.notices.messages)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "New", persisted = "Old")
            store.commit("one", stored, "New")
            runCurrent()
            val saved = stored + (FieldKeys.TITLE to "New")
            store.field("one", FieldKeys.NOTES, "Call back", persisted = null)

            store.unregisterEditing("one")
            store.left("one", saved, "New")
            runCurrent()

            writer.batchedEdits.map { it.fields } shouldContainExactly
                listOf(mapOf(FieldKeys.TITLE to "New"), mapOf(FieldKeys.NOTES to "Call back"))
            notices.single().text shouldBe uiText(Res.string.detail_saved_changes, "New")
            notices
                .single()
                .action
                .shouldNotBeNull()
                .onPerform()
            runCurrent()
            writer.batchedEdits.last().fields shouldBe mapOf(FieldKeys.NOTES to null, FieldKeys.TITLE to "Old")
        }

    /** A draft its field refuses is not written on leaving; it stays, for the banner to report. */
    @Test
    fun leavingADraftThatCannotBeWrittenKeepsIt() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            val notices = collecting(store.notices.messages)
            store.registerEditing("one")
            store.field("one", FieldKeys.DOC_DATE, "2026-02-30", persisted = null, type = FieldType.DATE)

            store.unregisterEditing("one")
            store.left("one", stored, "Old")
            advanceUntilIdle()

            writer.batchedEdits.shouldBeEmpty()
            store.state.getValue("one").dirty shouldBe true
            notices.shouldBeEmpty()
        }

    /** A commit asked for while a save runs is not dropped: what was typed meanwhile is saved after it. */
    @Test
    fun aCommitAskedForDuringASaveFollowsIt() =
        runTest(dispatcher) {
            val fake = FakeMetadataWriter()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val writer =
                object : MetadataWriter by fake {
                    override suspend fun applyEdits(
                        docIds: List<String>,
                        addTags: List<String>,
                        fields: Map<String, String?>,
                        fillOnly: Boolean,
                    ) {
                        started.complete(Unit)
                        finish.await()
                        fake.applyEdits(docIds, addTags, fields, fillOnly)
                    }
                }
            val store = DocumentDrafts(writer, this)
            store.registerEditing("one")
            store.field("one", FieldKeys.TITLE, "New", persisted = "Old")
            store.commit("one", stored, "New")
            started.await()

            store.field("one", FieldKeys.NOTES, "Call back", persisted = null)
            store.commit("one", stored, "New")
            finish.complete(Unit)
            runCurrent()

            fake.batchedEdits.map { it.fields } shouldContainExactly
                listOf(mapOf(FieldKeys.TITLE to "New"), mapOf(FieldKeys.NOTES to "Call back"))
            // One inline Undo for both, back to before either.
            store.state
                .getValue("one")
                .undo
                ?.prior shouldBe mapOf(FieldKeys.NOTES to null, FieldKeys.TITLE to "Old")
        }
}
