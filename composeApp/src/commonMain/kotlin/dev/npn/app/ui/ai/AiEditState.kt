package dev.npn.app.ui.ai

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.DocListItem
import dev.npn.core.app.SavedView
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditProgress
import dev.npn.core.app.aiedit.EditScope
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import kotlinx.coroutines.flow.map

/**
 * The documents the next batch would process ([batch]) out of [total] still in scope.
 *
 * [loaded] separates "the scope query has answered and the answer is none" from "it has not
 * answered yet", which a bare count cannot: without it an opening page claims zero documents for
 * as long as the (possibly library-wide) query takes, and the user reads that as a broken scope.
 */
data class ScopePreview(
    val total: Int,
    val batch: List<DocListItem>,
    val loaded: Boolean = true,
) {
    companion object {
        val EMPTY = ScopePreview(0, emptyList(), loaded = false)
    }
}

/**
 * What one Start would buy: [documents] in scope and [requests] model calls actually made now —
 * the whole scope for a background run, one batch for a run reviewed here.
 */
data class RunPlan(
    val documents: Int,
    val requests: Int,
    val background: Boolean,
    val loaded: Boolean,
) {
    /** Whether the run is big enough that Start should ask first. */
    val needsConfirmation: Boolean get() = requests > CONFIRM_ABOVE_REQUESTS

    companion object {
        val EMPTY = RunPlan(documents = 0, requests = 0, background = false, loaded = false)

        /**
         * Above this many model calls, Start asks for confirmation.
         *
         * Deliberately well above [AiEditLimits.BACKGROUND_THRESHOLD_DOCS]: that threshold answers
         * "should a person wait for this", which is true of runs far too small to be worth a
         * dialog. This one answers "is this a bulk commitment of the user's money" — a scope
         * picked by filter can be the whole library, and "All documents" is one tap away from the
         * scope they meant.
         */
        const val CONFIRM_ABOVE_REQUESTS = 50
    }
}

/** Why Start is inert, in the order the user should fix them. Null means the run is ready. */
enum class StartBlocker {
    NO_TASK,
    TOO_MANY_FIELDS,
    NOTHING_REQUESTED,
    NO_SAVED_VIEW,
    LOADING_SCOPE,
    NO_DOCUMENTS,
}

/** UI-level per-field autofill configuration for a run. */
data class AiFieldOption(
    val enabled: Boolean = true,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/** The phases of the bulk AI-edit flow. */
sealed interface EditPhase {
    /** Scope/fields/tags/prompt selection + Start. */
    data object Idle : EditPhase

    /** Concurrent single-request `suggestEdits` calls in progress. */
    data class Suggesting(
        val progress: EditProgress,
    ) : EditPhase

    /**
     * The run was handed to the background queue: [documents] documents were submitted and no
     * model call has been made yet. Terminal for this screen — the result is picked up from the
     * processing queue, not here.
     */
    data class Queued(
        val documents: Int,
    ) : EditPhase

    /** The [batch] awaiting the user's confirmation; nothing written yet. */
    data class Review(
        val batch: ReviewBatchState,
    ) : EditPhase

    /** Writing selected tags and data fields through the journal. */
    data object Applying : EditPhase

    /** Terminal state carrying counts + whether a next batch is available. */
    data class Done(
        val result: ApplyResult,
    ) : EditPhase
}

/**
 * Whether leaving the page now throws away suggestions already paid for: a run still suggesting
 * (the viewmodel goes with the page and cancels it) or a Review nobody has applied.
 */
val EditPhase.leavingDiscardsSuggestions: Boolean
    get() = this is EditPhase.Suggesting || this is EditPhase.Review

/**
 * Full UI state. [scope] and [reviewMode] drive the Idle controls; [phase] is the current
 * step. Selection toggles live inside [EditPhase.Review.docs].
 *
 * [maxTags], [maxNewTags] and [batchSize] are per-run overrides, seeded from the Settings defaults
 * and editable in the Idle phase. [includeTags] turns tag suggestions on or off entirely and
 * [allowNewTags] decides whether brand-new tags may be invented (when off the request caps new tags
 * at zero, so the [maxNewTags] control is irrelevant); [includeTagRemovals] adds removal
 * suggestions, which stay available even with [includeTags] off. [fieldOptions] picks which
 * single-value fields to autofill and in which [AiFieldMode]; the free text [customPrompt] is
 * passed through to the model as extra instructions. All of these parametrize the single
 * per-document [AiEditRequest].
 */
data class AiEditUiState(
    val scope: EditScope = EditScope.UNTAGGED,
    /**
     * The library's review mode, read once when the page opens: whether the review queue is a scope
     * at all ([reviewQueueAvailable]), and what it lists.
     */
    val reviewMode: ReviewMode = ReviewMode.DEFAULT,
    /** Documents handed over from the library selection; 0 when the page was opened standalone. */
    val selectionSize: Int = 0,
    val savedViews: List<SavedView> = emptyList(),
    val savedViewId: String? = null,
    val phase: EditPhase = EditPhase.Idle,
    /**
     * The job the user picked, or null while they have not picked one — in which case the page
     * shows only the task chooser. See [EditTask].
     */
    val task: EditTask? = null,
    /**
     * Whether the field registry has answered yet. The field list is the only part of the form
     * that arrives asynchronously, and an empty list is otherwise indistinguishable from a library
     * with no fields.
     */
    val registryLoaded: Boolean = false,
    val maxTags: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val maxNewTags: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val batchSize: Int = AiEditLimits.DEFAULT_BATCH_DOCS,
    /** The registry, for labels and the "add a field to this run" picker. */
    val fields: List<FieldDef> = emptyList(),
    /**
     * Per-field autofill configuration by key. Seeded from the registry's aiEnabled fields once it
     * arrives; any other registered field can be added to the run from the picker.
     */
    val fieldOptions: Map<String, AiFieldOption> = emptyMap(),
    val includeTags: Boolean = true,
    val allowNewTags: Boolean = true,
    val includeTagRemovals: Boolean = false,
    val customPrompt: String = "",
    /**
     * The user's explicit choice about running in the background, or null while they have not
     * made one — in which case the scope size decides (see [runsInBackground]). Kept as an
     * override rather than a plain flag so the default stays live: changing the scope from three
     * documents to three hundred should flip it, and only until the user says otherwise.
     */
    val backgroundOverride: Boolean? = null,
)

/**
 * Whether the configured run asks the model for anything at all (drives the Start affordance).
 *
 * A custom prompt on its own deliberately does NOT count. The reply contract is built from the
 * requested sections (see `AiPrompts.editSystem`), so a prompt-only run asks the model to answer
 * with an object that has no allowed keys — it can only come back empty, having cost a request per
 * document. The prompt refines a run; it cannot be one.
 */
fun AiEditUiState.hasEdits(): Boolean =
    !tooManyFields && (includeTags || includeTagRemovals || fieldOptions.values.any { it.enabled })

/**
 * Why the run cannot start yet, most fixable first, or null when it can. Lives here rather than in
 * the composables because every entry is a policy statement about the run, not about the layout —
 * and because a disabled button with no reason is the single most common complaint about a form
 * this dense.
 */
internal fun AiEditUiState.startBlocker(preview: ScopePreview): StartBlocker? =
    when {
        task == null -> StartBlocker.NO_TASK
        tooManyFields -> StartBlocker.TOO_MANY_FIELDS
        !hasEdits() -> StartBlocker.NOTHING_REQUESTED
        scope == EditScope.SAVED_VIEW && savedViews.none { it.id == savedViewId } -> StartBlocker.NO_SAVED_VIEW
        !preview.loaded -> StartBlocker.LOADING_SCOPE
        preview.total == 0 -> StartBlocker.NO_DOCUMENTS
        else -> null
    }

/** Whether the review queue is a scope: only where the review marker is real. */
val AiEditUiState.reviewQueueAvailable: Boolean get() = reviewMode.reviewMarkerIsAuthoritative

/** Whether [scope] can be picked: the hand-off, review mode and saved views gate three of them. */
fun AiEditUiState.scopeAvailable(scope: EditScope): Boolean =
    when (scope) {
        EditScope.SELECTION -> selectionSize > 0
        EditScope.REVIEW_QUEUE -> reviewQueueAvailable
        EditScope.MISSING_FIELDS -> fieldOptions.values.any { it.enabled }
        EditScope.SAVED_VIEW -> savedViews.isNotEmpty()
        EditScope.UNTAGGED, EditScope.ALL -> true
    }

/**
 * The autofilled fields' keys in registry order: what the run asks for, and the fields whose
 * absence defines [EditScope.MISSING_FIELDS].
 */
fun AiEditUiState.enabledFieldKeys(): List<String> =
    fieldOptions.filterValues { it.enabled }.keys.inRegistryOrder(fields)

/** These keys in [fields]' registry order; a key the registry no longer lists comes last. */
internal fun Iterable<String>.inRegistryOrder(fields: List<FieldDef>): List<String> {
    val order = fields.map { it.key }
    return sortedBy { key -> order.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/**
 * True when the run asks about more fields than one reply can sensibly carry
 * ([AiEditRequest.MAX_FIELDS_PER_RUN]).
 */
val AiEditUiState.tooManyFields: Boolean
    get() = fieldOptions.count { it.value.enabled } > AiEditRequest.MAX_FIELDS_PER_RUN

/**
 * What the setup form changes, and its Start. [AiEditViewModel] implements it; every change is
 * ignored outside [EditPhase.Idle], where the run works from a snapshot of the form.
 */
interface AiEditSetupActions {
    /**
     * Pick the job this run does, applying its preset over the current configuration. Re-picking
     * the task the run already carries resets it to the preset, a one-click way out of a form the
     * user has edited into a corner.
     */
    fun setTask(task: EditTask)

    /** Drop back to the task chooser, keeping the scope and the numeric overrides. */
    fun clearTask()

    /** Change the scope; unavailable scopes are ignored. */
    fun setScope(scope: EditScope)

    /** Pick which saved view backs the [EditScope.SAVED_VIEW] scope. */
    fun setSavedView(id: String)

    /** Set the per-run max suggested tags per document; clamped to [AiTagging.RANGE]. */
    fun setMaxTags(value: Int)

    /** Set the per-run max new tags per document; clamped to [AiTagging.NEW_TAGS_RANGE]. */
    fun setMaxNewTags(value: Int)

    /** Set the per-run batch size; clamped to [AiEditLimits.BATCH_RANGE]. */
    fun setBatchSize(value: Int)

    /** Toggle whether field [key] participates in the run. */
    fun setFieldEnabled(
        key: String,
        enabled: Boolean,
    )

    /** Set the autofill [mode] for field [key]. */
    fun setFieldMode(
        key: String,
        mode: AiFieldMode,
    )

    /** Add a registered field the seed left out to this run, enabled. */
    fun addField(key: String)

    /** Toggle whether tag additions are suggested at all. */
    fun setIncludeTags(value: Boolean)

    /** Toggle whether the model may invent tags outside the current vocabulary. */
    fun setAllowNewTags(value: Boolean)

    /** Toggle whether tag removals are suggested. */
    fun setIncludeTagRemovals(value: Boolean)

    /** Set the free-text extra instructions. */
    fun setCustomPrompt(value: String)

    /** Choose whether this run goes to the background queue, overriding the size-based default. */
    fun setBackground(value: Boolean)

    /**
     * Start the run: suggest the next batch here, or hand the whole scope to the background
     * queue. Refuses whatever [AiEditUiState.startBlocker] refuses.
     */
    fun start()
}
