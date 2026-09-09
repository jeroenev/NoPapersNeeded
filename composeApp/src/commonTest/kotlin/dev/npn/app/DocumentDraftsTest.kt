package dev.npn.app

import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.DocProperty
import kotlinx.coroutines.CompletableDeferred
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DocumentDraftsTest : MainDispatcherTest() {
    @Test
    fun restoredDraftsKeepEachDocumentsFieldsAndNotes() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, backgroundScope)
            store.property("one", DocProperty.TITLE, "Invoice", persisted = null)
            store.property("one", DocProperty.NOTES, "Call the supplier", persisted = null)
            store.field("two", "amount", "25", persisted = null)
            val restored = DocumentDrafts(writer, backgroundScope, store.serialize())
            assertEquals(store.state, restored.state)
            assertTrue(writer.batchedEdits.isEmpty())
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
            store.property("one", DocProperty.TITLE, "Invoic", persisted = "Invoice")
            assertTrue(
                store.state
                    .getValue("one")
                    .dirty,
            )
            // Trailing whitespace is not a change either: the writers normalize it away.
            store.property("one", DocProperty.TITLE, "Invoice ", persisted = "Invoice")
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
            store.property("one", DocProperty.TITLE, "Invoice", persisted = null)
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
            assertEquals(
                mapOf(DocProperty.TITLE to "Invoice", DocProperty.REVIEWED to "true"),
                writer.batchedEdits.single().third,
            )
        }

    @Test
    fun savedDraftIsDroppedOnceTheNoticeHasBeenRead() =
        runTest(dispatcher) {
            val store = DocumentDrafts(FakeMetadataWriter(), this)
            store.property("one", DocProperty.TITLE, "Invoice", persisted = null)
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
            val writer = FakeMetadataWriter().apply { failSetField = { error("disk full") } }
            val store = DocumentDrafts(writer, this)
            store.field("one", "amount", "25", persisted = null)
            store.save("one", reviewed = true)
            advanceUntilIdle()
            assertTrue(writer.batchedEdits.isEmpty())
            assertEquals(
                "25",
                store.state
                    .getValue("one")
                    .fields["amount"],
            )
        }

    @Test
    fun editsMadeDuringSaveRemainUnsavedAndRepeatedSaveIsIgnored() =
        runTest(dispatcher) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val fake = FakeMetadataWriter()
            val writer =
                object : MetadataWriter by fake {
                    override suspend fun applyEdits(
                        docIds: List<String>,
                        addTags: List<String>,
                        properties: Map<DocProperty, String?>,
                    ) {
                        started.complete(Unit)
                        finish.await()
                        fake.applyEdits(docIds, addTags, properties)
                    }
                }
            val store = DocumentDrafts(writer, this)
            store.property("one", DocProperty.TITLE, "First", persisted = null)
            store.save("one")
            started.await()
            store.property("one", DocProperty.TITLE, "Second", persisted = null)
            store.save("one")
            finish.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, fake.batchedEdits.size)
            assertEquals("First", fake.batchedEdits.single().third[DocProperty.TITLE])
            assertEquals(
                "Second",
                store.state
                    .getValue("one")
                    .properties[DocProperty.TITLE],
            )
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
            store.property("one", DocProperty.DOC_DATE, "2026-02-30", persisted = null)
            var advanced = false
            store.save("one", reviewed = true) { advanced = true }
            runCurrent()
            assertFalse(advanced)
            assertTrue(writer.batchedEdits.isEmpty())
            assertFalse(
                store.state
                    .getValue("one")
                    .validDate,
            )
        }
}
