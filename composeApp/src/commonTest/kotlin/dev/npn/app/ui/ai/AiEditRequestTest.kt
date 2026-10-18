package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.recreatable
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiTagging
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What the setup form asks the run for: the request and the batch size a Start sends. */
class AiEditRequestTest : MainDispatcherTest() {
    private fun extraField(
        key: String,
        position: Int,
        type: FieldType = FieldType.TEXT,
    ) = FieldDef(key, key, type, showByDefault = false, aiEnabled = false, position = position)

    /**
     * A restored process keeps how the run was configured, field by field: the registry's first
     * answer to the new viewmodel does not seed it back to the defaults.
     */
    @Test
    fun aRestoredProcessKeepsTheRequestAsConfigured() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val screen = recreatable { vm(FakeAiEditRun().apply { inScope("a") }, session, task = null, saved = it) }
            collecting(screen.vm.ui)
            advanceUntilIdle()
            screen.vm.apply {
                setTask(EditTask.CUSTOM)
                setFieldMode(FieldKeys.TITLE, AiFieldMode.ALL)
                setMaxTags(2)
                setIncludeTagRemovals(true)
            }

            val restored = screen.recreate(this, { it.ui })
            restored.ui.value.fieldOptions
                .getValue(FieldKeys.TITLE)
                .mode shouldBe AiFieldMode.ALL
        }

    @Test
    fun settingsDefaultsAreSeededIntoUiState() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(aiMaxTagsPerDoc = 12, aiMaxNewTagsPerDoc = 3) }
                }
            val model = vm(settings = settings)
            collecting(model.ui)
            advanceUntilIdle()

            model.ui.value.maxTags shouldBe 12
            model.ui.value.maxNewTags shouldBe 3
            model.ui.value.batchSize shouldBe AiEditLimits.DEFAULT_BATCH_DOCS
        }

    @Test
    fun theDefaultRequestCoversEveryAiFieldUnfilledAndTagAdditions() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            val request = run.requests.single()
            request.fields.map { it.key } shouldContainExactly seededAiFieldKeys
            request.fields.all { it.mode == AiFieldMode.UNFILLED } shouldBe true
            request.tags shouldBe true
            request.tagRemovals shouldBe false
            request.customPrompt shouldBe null
            run.keys.last().batchSize shouldBe AiEditLimits.DEFAULT_BATCH_DOCS
        }

    @Test
    fun overridesReachTheRequestAndTheBatch() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.setMaxTags(11)
            model.setMaxNewTags(2)
            model.setBatchSize(2)
            model.start()
            advanceUntilIdle()

            val request = run.requests.single()
            request.maxTags shouldBe 11
            request.maxNewTags shouldBe 2
            run.keys.last().batchSize shouldBe 2
        }

    @Test
    fun fieldOptionsAreSeededFromTheRegistrysAiEnabledFields() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()

            // The seed marks title/correspondent/docType/docDate aiEnabled (notes is not), so those
            // are exactly the fields the run starts with.
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys
        }

    @Test
    fun addFieldPullsARegisteredFieldIntoTheRunAndItsRequest() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            // A registered but not-aiEnabled field the seed left out of the run.
            val session = FakeLibrarySession()
            session.fieldRegistry.defs.value += extraField("amount", position = 5, type = FieldType.DECIMAL)
            val model = vm(run, session)
            collecting(model.ui)
            advanceUntilIdle()

            // Not in the run until explicitly added from the picker.
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys

            model.addField("amount")
            model.ui.value.fieldOptions
                .getValue("amount")
                .enabled shouldBe true
            model.start()
            advanceUntilIdle()

            run.requests
                .single()
                .fields
                .map { it.key } shouldContainExactly (seededAiFieldKeys + "amount")
        }

    @Test
    fun tooManyFieldsBlocksTheRun() =
        runTest(dispatcher) {
            // Register enough extra fields that thirteen can be enabled at once (the cap is 12).
            val session = FakeLibrarySession()
            session.fieldRegistry.defs.value += (1..13).map { extraField("extra$it", position = 10 + it) }
            val model = vm(session = session)
            collecting(model.ui)
            advanceUntilIdle()

            model.ui.value.fieldOptions.keys
                .forEach { model.setFieldEnabled(it, false) }
            (1..13).forEach { model.addField("extra$it") }

            model.ui.value.tooManyFields shouldBe true
            model.ui.value.hasEdits() shouldBe false
        }

    @Test
    fun fieldOptionsTagsAndPromptFlowIntoTheRequest() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setFieldMode(FieldKeys.CORRESPONDENT, AiFieldMode.ALL)
            model.setIncludeTagRemovals(true)
            model.setCustomPrompt("merge similar tags")
            model.start()
            advanceUntilIdle()

            val request = run.requests.single()
            request.fields.map { it.key } shouldContainExactly
                listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)
            request.fields.single { it.key == FieldKeys.CORRESPONDENT }.mode shouldBe AiFieldMode.ALL
            request.tags shouldBe true
            request.tagRemovals shouldBe true
            request.customPrompt shouldBe "merge similar tags"
        }

    @Test
    fun disallowingNewTagsCapsNewTagsAtZeroButKeepsTagging() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.setMaxNewTags(4)
            model.setAllowNewTags(false)
            model.start()
            advanceUntilIdle()

            val request = run.requests.single()
            request.tags shouldBe true
            request.maxNewTags shouldBe 0
            // The cap the user typed is remembered for when new tags are re-enabled.
            model.ui.value.maxNewTags shouldBe 4
        }

    @Test
    fun taggingCanBeDisabledLeavingFieldsOnly() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.start()
            advanceUntilIdle()

            val request = run.requests.single()
            request.tags shouldBe false
            request.fields.map { it.key } shouldContainExactly seededAiFieldKeys
        }

    @Test
    fun startIsRefusedWhenNothingIsRequested() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.setIncludeTags(false)
            model.setIncludeTagRemovals(false)
            seededAiFieldKeys.forEach { model.setFieldEnabled(it, false) }
            model.ui.value.hasEdits() shouldBe false

            model.start()
            advanceUntilIdle()

            run.requests.shouldBeEmpty()
            model.ui.value.phase shouldBe EditPhase.Idle
        }

    @Test
    fun settersAreNoOpsOutsideIdle() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a")
                    batch = review(reviewDoc("a", tags = listOf("t")))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()
            model.setMaxTags(11)
            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()

            // In Review, setters must not change the snapshot.
            model.setMaxTags(4)
            model.setMaxNewTags(9)
            model.setBatchSize(2)
            model.setIncludeTags(false)
            model.setAllowNewTags(false)
            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setCustomPrompt("ignored")
            model.ui.value.maxTags shouldBe 11
            model.ui.value.batchSize shouldBe AiEditLimits.DEFAULT_BATCH_DOCS
            model.ui.value.includeTags shouldBe true
            model.ui.value.allowNewTags shouldBe true
            model.ui.value.fieldOptions
                .getValue(FieldKeys.TITLE)
                .enabled shouldBe true
            model.ui.value.customPrompt shouldBe ""
        }

    @Test
    fun settersClampToRange() =
        runTest(dispatcher) {
            val model = vm()
            collecting(model.ui)
            advanceUntilIdle()

            model.setMaxTags(999)
            model.setMaxNewTags(-5)
            model.setBatchSize(9999)
            model.ui.value.maxTags shouldBe AiTagging.MAX_TAGS_PER_DOC
            model.ui.value.maxNewTags shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
            model.ui.value.batchSize shouldBe AiEditLimits.MAX_BATCH_DOCS
        }
}
