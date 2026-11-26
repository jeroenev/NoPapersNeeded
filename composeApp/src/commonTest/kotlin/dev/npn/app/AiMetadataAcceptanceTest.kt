package dev.npn.app

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.DraftedActions
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.MetadataWriter
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_field_failed
import kotlin.test.Test

/** Accepting a suggestion must retain its retry and the user's draft until the write lands. */
class AiMetadataAcceptanceTest : MainDispatcherTest() {
    @Test
    fun aFailedAcceptanceKeepsTheSuggestionAndDraftForRetry() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiSuggest.edit = suggestion("Suggested")
            session.meta.failSetField = { error("disk full") }
            val model = DocDetailViewModel("one", session, aiConfiguredSettings())
            collecting(model.metadata)
            val messages = collecting(model.messages.map { it.text })
            val drafts = DocumentDrafts(session.meta, backgroundScope)
            drafts.field("one", FieldKeys.TITLE, "Typed", persisted = "Stored")
            val actions = actions(model, drafts)
            model.suggestAiMetadata()
            advanceUntilIdle()

            actions.acceptAiMetadata(FieldKeys.TITLE)
            advanceUntilIdle()

            model.metadata.value.ai.metadata.suggestion[FieldKeys.TITLE] shouldBe "Suggested"
            drafts.state.getValue("one").fields[FieldKeys.TITLE] shouldBe "Typed"
            messages shouldContainExactly listOf(failure(Res.string.common_save_field_failed, "disk full"))

            session.meta.failSetField = null
            actions.acceptAiMetadata(FieldKeys.TITLE)
            advanceUntilIdle()
            model.metadata.value.ai.metadata.suggestion shouldBe FieldSuggestion.EMPTY
            drafts.state shouldBe emptyMap()
        }

    @Test
    fun anAcceptanceThatLandsKeepsANewerDraftAndSuggestion() =
        runTest(dispatcher) {
            val fake = FakeLibrarySession()
            val finish = CompletableDeferred<Unit>()
            val writer =
                object : MetadataWriter by fake.meta {
                    override suspend fun setField(
                        docId: String,
                        key: String,
                        value: String?,
                    ) {
                        finish.await()
                        fake.meta.setField(docId, key, value)
                    }
                }
            val session =
                object : LibrarySession by fake {
                    override val meta: MetadataWriter = writer
                }
            fake.aiSuggest.edit = suggestion("First suggestion")
            val model = DocDetailViewModel("one", session, aiConfiguredSettings())
            collecting(model.metadata)
            val drafts = DocumentDrafts(writer, backgroundScope)
            drafts.field("one", FieldKeys.TITLE, "First draft", persisted = "Stored")
            val actions = actions(model, drafts)
            model.suggestAiMetadata()
            advanceUntilIdle()

            actions.acceptAiMetadata(FieldKeys.TITLE)
            runCurrent()
            drafts.field("one", FieldKeys.TITLE, "Later draft", persisted = "Stored")
            fake.aiSuggest.edit = suggestion("Later suggestion")
            model.suggestAiMetadata()
            runCurrent()
            finish.complete(Unit)
            advanceUntilIdle()

            fake.meta.fieldSets shouldContainExactly listOf(Triple(listOf("one"), FieldKeys.TITLE, "First suggestion"))
            drafts.state.getValue("one").fields[FieldKeys.TITLE] shouldBe "Later draft"
            model.metadata.value.ai.metadata.suggestion[FieldKeys.TITLE] shouldBe "Later suggestion"
        }

    @Test
    fun acceptingBlocksSaveAndDiscardBeforeItsWriteStarts() =
        runTest(dispatcher) {
            val fixture = AcceptanceFixture(this)
            collecting(fixture.model.metadata)
            fixture.model.suggestAiMetadata()
            advanceUntilIdle()

            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            fixture.drafts.state
                .getValue("one")
                .saving shouldBe true
            fixture.drafts.save("one")
            fixture.drafts.discard("one")
            fixture.drafts.state
                .getValue("one")
                .fields[FieldKeys.TITLE] shouldBe "Typed"
            runCurrent()
            fixture.finish.complete(Unit)
            advanceUntilIdle()

            fixture.fake.meta.batchedEdits shouldBe emptyList()
            fixture.fake.meta.fieldSets shouldContainExactly listOf(Triple(listOf("one"), FieldKeys.TITLE, "Suggested"))
            fixture.drafts.state shouldBe emptyMap()
        }

    @Test
    fun aSaveInFlightBlocksAcceptanceUntilItFinishes() =
        runTest(dispatcher) {
            val fixture = AcceptanceFixture(this)
            collecting(fixture.model.metadata)
            fixture.model.suggestAiMetadata()
            advanceUntilIdle()

            fixture.drafts.save("one")
            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            runCurrent()
            fixture.fake.meta.fieldSets shouldBe emptyList()
            fixture.model.metadata.value.ai.metadata.suggestion[FieldKeys.TITLE] shouldBe "Suggested"
            fixture.finish.complete(Unit)
            advanceUntilIdle()

            fixture.fake.meta.batchedEdits
                .single()
                .fields[FieldKeys.TITLE] shouldBe "Typed"
            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            advanceUntilIdle()
            fixture.fake.meta.fieldSets shouldContainExactly listOf(Triple(listOf("one"), FieldKeys.TITLE, "Suggested"))
            fixture.drafts.state shouldBe emptyMap()
        }

    @Test
    fun revertingToTheStoredValueDuringAcceptanceRemainsAnEditOnceItLands() =
        runTest(dispatcher) {
            val fixture = AcceptanceFixture(this)
            collecting(fixture.model.metadata)
            fixture.model.suggestAiMetadata()
            advanceUntilIdle()

            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            runCurrent()
            fixture.drafts.field("one", FieldKeys.TITLE, "Stored", persisted = "Stored")
            fixture.finish.complete(Unit)
            advanceUntilIdle()

            fixture.drafts.state
                .getValue("one")
                .fields[FieldKeys.TITLE] shouldBe "Stored"
            fixture.drafts.state
                .getValue("one")
                .saving shouldBe false
            fixture.drafts.save("one")
            advanceUntilIdle()
            fixture.fake.meta.batchedEdits
                .single()
                .fields[FieldKeys.TITLE] shouldBe "Stored"
        }

    @Test
    fun clearingTheViewmodelReleasesAnAcceptanceThatHasNotStarted() =
        runTest(dispatcher) {
            val fixture = AcceptanceFixture(this)
            collecting(fixture.model.metadata)
            fixture.model.suggestAiMetadata()
            advanceUntilIdle()
            val models = ViewModelStore().apply { put("detail", fixture.model) }

            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            models.clear()
            runCurrent()

            fixture.drafts.state
                .getValue("one")
                .saving shouldBe false
            fixture.drafts.state
                .getValue("one")
                .fields[FieldKeys.TITLE] shouldBe "Typed"
            fixture.fake.meta.fieldSets shouldBe emptyList()
        }

    @Test
    fun cancellationOfAcceptanceDropsARevertThatNeverLanded() =
        runTest(dispatcher) {
            val fixture = AcceptanceFixture(this)
            collecting(fixture.model.metadata)
            fixture.model.suggestAiMetadata()
            advanceUntilIdle()
            val models = ViewModelStore().apply { put("detail", fixture.model) }

            fixture.actions.acceptAiMetadata(FieldKeys.TITLE)
            runCurrent()
            fixture.drafts.field("one", FieldKeys.TITLE, "Stored", persisted = "Stored")
            models.clear()
            runCurrent()

            fixture.drafts.state shouldBe emptyMap()
            fixture.fake.meta.fieldSets shouldBe emptyList()
        }

    private inner class AcceptanceFixture(
        scope: CoroutineScope,
    ) {
        val fake = FakeLibrarySession()
        val finish = CompletableDeferred<Unit>()
        private val writer =
            object : MetadataWriter by fake.meta {
                override suspend fun setField(
                    docId: String,
                    key: String,
                    value: String?,
                ) {
                    finish.await()
                    fake.meta.setField(docId, key, value)
                }

                override suspend fun applyEdits(
                    docIds: List<String>,
                    addTags: List<String>,
                    fields: Map<String, String?>,
                    fillOnly: Boolean,
                ) {
                    finish.await()
                    fake.meta.applyEdits(docIds, addTags, fields, fillOnly)
                }
            }
        private val session =
            object : LibrarySession by fake {
                override val meta: MetadataWriter = writer
            }
        val model = DocDetailViewModel("one", session, aiConfiguredSettings())
        val drafts = DocumentDrafts(writer, scope)
        val actions = actions(model, drafts)

        init {
            fake.aiSuggest.edit = suggestion("Suggested")
            drafts.field("one", FieldKeys.TITLE, "Typed", persisted = "Stored")
        }
    }

    private fun actions(
        model: DocDetailViewModel,
        drafts: DocumentDrafts,
    ) = DraftedActions(
        model,
        drafts,
        "one",
        mutableStateOf(mapOf(FieldKeys.TITLE to "Stored")),
        mutableStateOf(mapOf(FieldKeys.TITLE to FieldType.TEXT)),
        mutableStateOf("Stored"),
        mutableStateOf(false),
    )

    private fun suggestion(value: String) =
        AiEditSuggestion(metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to value)))
}
