package dev.npn.app.ui.ai

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.EditProgress
import dev.npn.core.app.EditScope
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Why Start is inert. Tested against the pure projection rather than the flow, because the
 * question is which reason wins for a given configuration, not when a StateFlow emits.
 */
class AiEditStartBlockerTest {
    private val ready =
        AiEditUiState(
            task = EditTask.CUSTOM,
            registryLoaded = true,
            fieldOptions = mapOf(FieldKeys.TITLE to AiFieldOption()),
        )
    private val loaded = ScopePreview(total = 3, batch = emptyList(), loaded = true)

    @Test
    fun leavingAsksFirstExactlyWhilePaidSuggestionsWouldBeLost() {
        val progress = EditProgress(done = 1, total = 2, currentTitle = null, currentFileName = "a.pdf")
        EditPhase.Suggesting(progress).leavingDiscardsSuggestions shouldBe true
        EditPhase.Review(ReviewBatchState(emptyList(), emptyList())).leavingDiscardsSuggestions shouldBe true
        EditPhase.Idle.leavingDiscardsSuggestions shouldBe false
        EditPhase.Applying.leavingDiscardsSuggestions shouldBe false
        EditPhase.Queued(documents = 3).leavingDiscardsSuggestions shouldBe false
        EditPhase.Done(ApplyResult(1, 1, moreRemain = false)).leavingDiscardsSuggestions shouldBe false
    }

    @Test
    fun aReadyRunHasNoBlocker() {
        ready.startBlocker(loaded) shouldBe null
    }

    @Test
    fun noTaskComesFirst() {
        // Even with nothing else configured, the first thing to fix is the first question asked.
        ready
            .copy(task = null, includeTags = false, fieldOptions = emptyMap())
            .startBlocker(loaded) shouldBe StartBlocker.NO_TASK
    }

    @Test
    fun aPromptOnItsOwnIsNotAnEdit() {
        // A prompt-only run asks the model for an object with no allowed keys: it can only come
        // back empty, one paid request per document.
        val promptOnly =
            ready.copy(
                includeTags = false,
                includeTagRemovals = false,
                fieldOptions = emptyMap(),
                customPrompt = "merge tags that differ only in case",
            )
        promptOnly.hasEdits() shouldBe false
        promptOnly.startBlocker(loaded) shouldBe StartBlocker.NOTHING_REQUESTED
    }

    @Test
    fun tooManyFieldsBeatsEverythingElseRequested() {
        val fields = (1..AiEditRequest.MAX_FIELDS_PER_RUN + 1).associate { "f$it" to AiFieldOption() }
        ready.copy(fieldOptions = fields).startBlocker(loaded) shouldBe StartBlocker.TOO_MANY_FIELDS
    }

    @Test
    fun aSavedViewScopeNeedsAView() {
        ready.copy(scope = EditScope.SAVED_VIEW).startBlocker(loaded) shouldBe StartBlocker.NO_SAVED_VIEW
    }

    @Test
    fun anUnansweredScopeQueryIsNotAnEmptyScope() {
        // The distinction the loaded flag exists for: this must not read as "no documents".
        ready.startBlocker(ScopePreview.EMPTY) shouldBe StartBlocker.LOADING_SCOPE
        ready.startBlocker(loaded.copy(total = 0)) shouldBe StartBlocker.NO_DOCUMENTS
    }
}
