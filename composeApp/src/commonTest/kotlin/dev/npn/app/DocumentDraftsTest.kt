package dev.npn.app

import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store hands its completion callback back on the Main dispatcher (callers navigate from it),
 * so these run against an installed test Main dispatcher.
 */
class DocumentDraftsTest : MainDispatcherTest() {
    @Test
    fun restoredDraftsKeepEachDocumentsFieldsAndNotes() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, backgroundScope)
            store.field("one", FieldKeys.TITLE, "Invoice", persisted = null)
            store.field("one", FieldKeys.NOTES, "Call the supplier", persisted = null)
            store.field("two", "amount", "25", persisted = null)
            val restored = DocumentDrafts(writer, backgroundScope, store.serialize())
            assertEquals(store.state, restored.state)
            assertTrue(writer.batchedEdits.isEmpty())
        }

    /** A draft saved by another build keeps the user's typing rather than being thrown away. */
    @Test
    fun aDraftWithAPropertyThisBuildDoesNotKnowIsStillRestored() =
        runTest(dispatcher) {
            val saved = """{"one":{"fields":{"title":"Invoice"},"types":{"title":"TEXT"},"pinnedBy":"newer build"}}"""
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope, saved)
            store.state["one"]?.fields shouldBe mapOf(FieldKeys.TITLE to "Invoice")
        }

    @Test
    fun unreadableRestoredStateStartsEmptyInsteadOfThrowing() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope, "{\"one\":{\"properties\":{\"GONE\"")
            assertTrue(store.state.isEmpty())
        }

    @Test
    fun editBackToTheStoredValueLeavesNoDraft() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope)
            store.field("one", FieldKeys.TITLE, "Invoic", persisted = "Invoice")
            assertTrue(
                store.state
                    .getValue("one")
                    .dirty,
            )
            // Trailing whitespace is not a change either: the writers normalize it away.
            store.field("one", FieldKeys.TITLE, "Invoice ", persisted = "Invoice")
            assertTrue(store.state.isEmpty())

            store.field("one", "amount", "25", persisted = null)
            store.field("one", "amount", null, persisted = null)
            assertTrue(store.state.isEmpty())
        }

    @Test
    fun failureKeepsDraftAndDoesNotAdvanceReview() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter().apply { failBatchOps = { error("offline") } }
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "Invoice", persisted = null)
            var advanced = false
            store.save("one", reviewed = true) { advanced = true }
            advanceUntilIdle()
            assertFalse(advanced)
            assertTrue(
                store.state
                    .getValue("one")
                    .dirty,
            )
            assertNotNull(
                store.state
                    .getValue("one")
                    .error,
            )
            writer.failBatchOps = null
            store.save("one", reviewed = true) { advanced = true }
            advanceUntilIdle()
            assertTrue(advanced)
            // advanceUntilIdle runs past the post-save notice delay, so the settled entry is gone.
            assertFalse(store.state.containsKey("one"))
            // The fields land in one applyEdits transaction; the reviewed marker is a separate call.
            assertEquals(
                mapOf(FieldKeys.TITLE to "Invoice"),
                writer.batchedEdits.single().fields,
            )
            assertEquals(listOf("one") to true, writer.reviewedSets.single())
        }

    /** The save bar's technical details name a failure that has no message, rather than going blank. */
    @Test
    fun aFailureWithoutAMessageStillSaysWhatFailed() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter().apply { failBatchOps = { throw IllegalStateException() } }
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "Invoice", persisted = null)
            store.save("one")
            advanceUntilIdle()
            store.state.getValue("one").error shouldBe "IllegalStateException"
        }

    @Test
    fun savedDraftIsDroppedOnceTheNoticeHasBeenRead() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), this)
            store.field("one", FieldKeys.TITLE, "Invoice", persisted = null)
            store.save("one")
            // The save coroutine now owns the notice timer: run the write and its Main hops but
            // stop short of SavedNoticeDuration, so the "saved" notice is still up.
            runCurrent()
            assertTrue(
                store.state
                    .getValue("one")
                    .saved,
            )
            // Once the notice window elapses the settled entry drops itself, no UI involved.
            advanceUntilIdle()
            assertTrue(store.state.isEmpty())
        }

    @Test
    fun customFieldFailureDoesNotMarkDocumentReviewed() =
        runTest(dispatcher) {
            // The field write is the applyEdits transaction, so its failure is a batched-op failure.
            val writer = FakeMetadataWriter().apply { failBatchOps = { error("disk full") } }
            val store = DocumentDrafts(writer, this)
            store.field("one", "amount", "25", persisted = null)
            store.save("one", reviewed = true)
            advanceUntilIdle()
            assertTrue(writer.reviewedSets.isEmpty())
            assertEquals(
                "25",
                store.state
                    .getValue("one")
                    .fields["amount"],
            )
        }

    /** A writer whose field edits wait for [finish] once they have [started], then land in [fake]. */
    private class GatedWriter(
        val fake: FakeMetadataWriter = FakeMetadataWriter(),
    ) : MetadataWriter by fake {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()

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

    @Test
    fun editsMadeDuringSaveRemainUnsavedAndRepeatedSaveIsIgnored() =
        runTest(dispatcher) {
            val writer = GatedWriter()
            val fake = writer.fake
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "First", persisted = null)
            store.save("one")
            writer.started.await()
            store.field("one", FieldKeys.TITLE, "Second", persisted = null)
            store.save("one")
            writer.finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, fake.batchedEdits.size)
            assertEquals("First", fake.batchedEdits.single().fields[FieldKeys.TITLE])
            assertEquals(
                "Second",
                store.state
                    .getValue("one")
                    .fields[FieldKeys.TITLE],
            )
        }

    /**
     * An edit made while a save runs is judged against what that save writes: going back to the
     * stored value, while the save writes another, is an edit of its own once the save lands.
     */
    @Test
    fun goingBackToTheStoredValueWhileASaveRunsStaysUnsaved() =
        runTest(dispatcher) {
            val writer = GatedWriter()
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "B", persisted = "A")
            store.save("one")
            writer.started.await()

            store.field("one", FieldKeys.TITLE, "A", persisted = "A")
            // Until the save lands, the editor shows what the user typed last.
            store.state.getValue("one").fields shouldBe mapOf(FieldKeys.TITLE to "A")
            writer.finish.complete(Unit)
            advanceUntilIdle()

            writer.fake.batchedEdits
                .single()
                .fields shouldBe mapOf(FieldKeys.TITLE to "B")
            val draft = store.state.getValue("one")
            draft.fields shouldBe mapOf(FieldKeys.TITLE to "A")
            assertTrue(draft.dirty)
        }

    /** Typing on and back to what a running save writes shows that value until the save settles it. */
    @Test
    fun goingBackToTheValueASaveWritesIsSettledByThatSave() =
        runTest(dispatcher) {
            val writer = GatedWriter()
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "B", persisted = "A")
            store.save("one")
            writer.started.await()

            store.field("one", FieldKeys.TITLE, "Bx", persisted = "A")
            store.field("one", FieldKeys.TITLE, "B", persisted = "A")
            store.state.getValue("one").fields shouldBe mapOf(FieldKeys.TITLE to "B")
            writer.finish.complete(Unit)
            runCurrent()

            val draft = store.state.getValue("one")
            assertFalse(draft.dirty)
            assertTrue(draft.saved)
        }

    /** A save that fails leaves no draft of a field put back to its stored value while it ran. */
    @Test
    fun aFailedSaveKeepsNoEditBackToTheStoredValue() =
        runTest(dispatcher) {
            val writer = GatedWriter(FakeMetadataWriter().apply { failBatchOps = { error("offline") } })
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "B", persisted = "A")
            store.field("one", FieldKeys.NOTES, "Call back", persisted = null)
            store.save("one")
            writer.started.await()

            store.field("one", FieldKeys.TITLE, "A", persisted = "A")
            writer.finish.complete(Unit)
            advanceUntilIdle()

            val draft = store.state.getValue("one")
            draft.fields shouldBe mapOf(FieldKeys.NOTES to "Call back")
            assertNotNull(draft.error)
        }

    /** A failure of the reviewed marker cannot undo fields that were already written. */
    @Test
    fun aReviewFailureKeepsAnEditBackToThePreviouslyStoredValue() =
        runTest(dispatcher) {
            val fake = FakeMetadataWriter()
            val reviewStarted = CompletableDeferred<Unit>()
            val finishReview = CompletableDeferred<Unit>()
            val writer =
                object : MetadataWriter by fake {
                    override suspend fun setReviewed(
                        docIds: List<String>,
                        reviewed: Boolean,
                    ) {
                        reviewStarted.complete(Unit)
                        finishReview.await()
                        error("review failed")
                    }
                }
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.TITLE, "B", persisted = "A")
            var advanced = false
            store.save("one", reviewed = true) { advanced = true }
            reviewStarted.await()
            store.field("one", FieldKeys.TITLE, "A", persisted = "A")
            finishReview.complete(Unit)
            advanceUntilIdle()

            fake.batchedEdits.single().fields shouldBe mapOf(FieldKeys.TITLE to "B")
            store.state.getValue("one").fields shouldBe mapOf(FieldKeys.TITLE to "A")
            store.state.getValue("one").error shouldBe "review failed"
            assertFalse(advanced)
        }

    @Test
    fun cancellingTheReviewedWriteKeepsEditsMadeAfterTheFieldsLanded() =
        runTest(dispatcher) {
            val fake = FakeMetadataWriter()
            val reviewStarted = CompletableDeferred<Unit>()
            val writer =
                object : MetadataWriter by fake {
                    override suspend fun setReviewed(
                        docIds: List<String>,
                        reviewed: Boolean,
                    ) {
                        reviewStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            val saveScope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = DocumentDrafts(writer, saveScope)
                store.field("one", FieldKeys.TITLE, "B", persisted = "A")
                store.save("one", reviewed = true)
                reviewStarted.await()
                store.field("one", FieldKeys.TITLE, "A", persisted = "A")
                saveScope.cancel()
                runCurrent()

                fake.batchedEdits.single().fields shouldBe mapOf(FieldKeys.TITLE to "B")
                val draft = store.state.getValue("one")
                draft.fields shouldBe mapOf(FieldKeys.TITLE to "A")
                assertFalse(draft.saving)
                draft.error shouldBe null
            } finally {
                saveScope.cancel()
            }
        }

    /**
     * A write that fails as its save scope is cancelled still ends the draft's save. The failure
     * fixup must run even then, or the draft is stuck at `saving` — unsavable and undiscardable for
     * the rest of the session.
     */
    @Test
    fun aFailedSaveWhoseScopeIsCancelledStillUnsticksTheDraft() =
        runTest(dispatcher) {
            val fake = FakeMetadataWriter()
            val saveScope = CoroutineScope(SupervisorJob() + dispatcher)
            val writer =
                object : MetadataWriter by fake {
                    override suspend fun applyEdits(
                        docIds: List<String>,
                        addTags: List<String>,
                        fields: Map<String, String?>,
                        fillOnly: Boolean,
                    ) {
                        saveScope.cancel()
                        error("disk full")
                    }
                }
            try {
                val store = DocumentDrafts(writer, saveScope)
                store.field("one", FieldKeys.TITLE, "B", persisted = "A")
                store.save("one")
                runCurrent()

                val draft = store.state.getValue("one")
                assertFalse(draft.saving)
                assertNotNull(draft.error)
                draft.fields shouldBe mapOf(FieldKeys.TITLE to "B")
                // Unstuck: the draft can still be discarded.
                store.discard("one")
                assertTrue(store.state.isEmpty())
            } finally {
                saveScope.cancel()
            }
        }

    /**
     * During a navigation transition the incoming and outgoing screens are both composed, and both
     * can be editing the same document — expanding the wide layout's preview panel into the
     * full-screen detail route does exactly that. The incoming editor registers before the outgoing
     * one disposes, so the surviving editor has to stay listed.
     */
    @Test
    fun documentStaysOnScreenWhileASecondEditorIsStillComposed() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope)
            store.registerEditing("one")
            store.registerEditing("one")
            store.unregisterEditing("one")
            assertEquals(setOf("one"), store.onScreen)
            store.unregisterEditing("one")
            assertTrue(store.onScreen.isEmpty())
        }

    @Test
    fun invalidDateDoesNotWriteOrAdvance() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            store.field("one", FieldKeys.DOC_DATE, "2026-02-30", persisted = null, type = FieldType.DATE)
            var advanced = false
            store.save("one", reviewed = true) { advanced = true }
            runCurrent()
            assertFalse(advanced)
            assertTrue(writer.batchedEdits.isEmpty())
            assertFalse(store.state.getValue("one").writable)
            store.state.getValue("one").invalidKeys shouldBe setOf(FieldKeys.DOC_DATE)
            // The same text under a TEXT field is fine: validity follows the type the key was edited as.
            store.field("two", "note", "2026-02-30", persisted = null)
            assertTrue(store.state.getValue("two").writable)
        }

    /**
     * The editor's verdict decides, not the canonical reading: `12.34` refused where a comma is the
     * decimal point blocks the save, though canonically it is a number. A valid edit lifts it.
     */
    @Test
    fun aRefusedEntryBlocksTheSaveUntilAValidEditReplacesIt() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            store.field("one", "amount", "12.34", persisted = null, type = FieldType.DECIMAL, refused = true)
            store.state.getValue("one").invalidKeys shouldBe setOf("amount")
            store.save("one")
            runCurrent()
            assertTrue(writer.batchedEdits.isEmpty())

            store.field("one", "amount", "12.34", persisted = null, type = FieldType.DECIMAL)
            assertTrue(store.state.getValue("one").writable)
            store.save("one")
            advanceUntilIdle()
            writer.batchedEdits.single().fields shouldBe mapOf("amount" to "12.34")
        }

    /** A refused entry stays refused across a restore; a draft saved before refusals were kept still loads. */
    @Test
    fun aRefusalSurvivesARestoreAndOlderDraftsStillLoad() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope)
            store.field("one", "count", "1,234", persisted = null, type = FieldType.NUMBER, refused = true)
            val restored = DocumentDrafts(FakeMetadataWriter(), backgroundScope, store.serialize())
            restored.state shouldBe store.state
            assertFalse(restored.state.getValue("one").writable)

            val older = """{"one":{"fields":{"count":"1234"},"types":{"count":"NUMBER"}}}"""
            val loaded = DocumentDrafts(FakeMetadataWriter(), backgroundScope, older)
            loaded.state.getValue("one").refused shouldBe emptySet()
            assertTrue(loaded.state.getValue("one").writable)
        }

    /**
     * A refused entry is no value, so it is an edit even when it reads like the stored number. Only
     * an off-type stored value, typed back exactly, is no change.
     */
    @Test
    fun aRefusedEntryIsAnEditUnlessTheStoredValueIsOffTypeToo() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), backgroundScope)
            store.field("one", "amount", "12.34", persisted = "12.34", type = FieldType.DECIMAL, refused = true)
            assertFalse(store.state.getValue("one").writable)

            store.field("two", "count", "abc", persisted = "abc", type = FieldType.NUMBER, refused = true)
            assertFalse(store.state.containsKey("two"))
        }
}
