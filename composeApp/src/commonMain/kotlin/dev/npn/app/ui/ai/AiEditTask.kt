package dev.npn.app.ui.ai

import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.aiedit.EditScope

/**
 * The job the user opened the AI-edit page to do.
 *
 * A run is parametrized by a dozen independent knobs (scope, tag flags, per-field modes, caps),
 * which is the right *model* and the wrong first question: people arrive wanting their untagged
 * scans tagged, not wanting `includeTagRemovals = false`. A task names a combination of those
 * knobs so the first choice is a job rather than a form.
 *
 * A task is a starting point, not a mode. Every control stays editable afterwards; changing one
 * that *defines* the task moves the run to [CUSTOM], so the label on screen never describes a
 * configuration the user has since edited away.
 */
enum class EditTask {
    /** Tag the documents that carry no tags at all. */
    ORGANIZE_UNTAGGED,

    /** Fill the empty registry fields (title, correspondent, date, ...) and touch no tags. */
    FILL_FIELDS,

    /** Consolidate the tag vocabulary: existing tags only, plus removal proposals. */
    CLEAN_UP_TAGS,

    /** Everything on and nothing implied — the fully manual run. */
    CUSTOM,
}

/**
 * The knob values one [EditTask] stands for. [fieldsEnabled] is a single flag rather than a set of
 * keys because the field list is registry data: a task says "this run is about fields" or "this run
 * is about tags", and which fields exist is the library's business.
 */
internal data class TaskPreset(
    val scope: EditScope,
    val includeTags: Boolean,
    val allowNewTags: Boolean,
    val includeTagRemovals: Boolean,
    val fieldsEnabled: Boolean,
)

internal val EditTask.preset: TaskPreset
    get() =
        when (this) {
            EditTask.ORGANIZE_UNTAGGED -> {
                TaskPreset(
                    scope = EditScope.UNTAGGED,
                    includeTags = true,
                    allowNewTags = true,
                    includeTagRemovals = false,
                    fieldsEnabled = false,
                )
            }

            EditTask.FILL_FIELDS -> {
                TaskPreset(
                    scope = EditScope.MISSING_FIELDS,
                    includeTags = false,
                    allowNewTags = true,
                    includeTagRemovals = false,
                    fieldsEnabled = true,
                )
            }

            // Existing vocabulary only: the point of a clean-up is fewer distinct tags, so a run
            // that may invent more would work against the task the user picked.
            EditTask.CLEAN_UP_TAGS -> {
                TaskPreset(
                    scope = EditScope.ALL,
                    includeTags = true,
                    allowNewTags = false,
                    includeTagRemovals = true,
                    fieldsEnabled = false,
                )
            }

            EditTask.CUSTOM -> {
                TaskPreset(
                    scope = EditScope.UNTAGGED,
                    includeTags = true,
                    allowNewTags = true,
                    includeTagRemovals = false,
                    fieldsEnabled = true,
                )
            }
        }

/**
 * Apply [task]'s preset over the current state.
 *
 * Fields always come back as [AiFieldMode.UNFILLED]: no preset proposes overwriting a value the
 * user already has: that is a deliberate per-field choice, never something a task decides for a
 * whole library.
 */
internal fun AiEditUiState.withTask(task: EditTask): AiEditUiState {
    val preset = task.preset
    val applied =
        copy(
            task = task,
            includeTags = preset.includeTags,
            allowNewTags = preset.allowNewTags,
            includeTagRemovals = preset.includeTagRemovals,
            fieldOptions =
                fieldOptions.mapValues { (_, option) ->
                    option.copy(enabled = preset.fieldsEnabled, mode = AiFieldMode.UNFILLED)
                },
        )
    return applied.copy(scope = applied.presetScope(preset))
}

/**
 * The scope a preset lands on. A selection handed over from the library wins: the user already
 * said which documents they mean, and a task chosen afterwards says what to do *to those*. An
 * unavailable preset scope (the field-based one before the registry has loaded) falls back to the
 * always-available default and is re-applied once the registry arrives.
 */
private fun AiEditUiState.presetScope(preset: TaskPreset): EditScope =
    when {
        selectionSize > 0 -> EditScope.SELECTION
        scopeAvailable(preset.scope) -> preset.scope
        else -> EditScope.UNTAGGED
    }
