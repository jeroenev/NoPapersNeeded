package dev.npn.core.ai

import kotlinx.serialization.Serializable

/**
 * One AI tag proposal for a document. [tag] is already canonicalized to the library's exact
 * spelling when it matches an existing vocabulary entry case-insensitively; [isNew] is true when
 * no existing tag matches (the suggestion would introduce a brand-new tag).
 */
@Serializable
data class TagSuggestion(
    val tag: String,
    val isNew: Boolean,
)

/**
 * AI proposals for a document's fields, by registry key. A key is present only when the model had
 * a validated suggestion that differs from the document's current value; values are already in
 * the field's canonical form (dates as `yyyy-MM-dd`, text canonicalized to the library's existing
 * spelling when it matches one case-insensitively).
 */
@Serializable
data class FieldSuggestion(
    val values: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = values.isEmpty()

    /** The proposed value for [key], or null when this run has nothing to propose for it. */
    operator fun get(key: String): String? = values[key]

    /** This suggestion with [key] cleared (the user accepted or dismissed just that field). */
    fun without(key: String): FieldSuggestion = FieldSuggestion(values - key)

    companion object {
        val EMPTY = FieldSuggestion()
    }
}

/**
 * Whether a field suggestion should be produced for every document or only documents that are
 * currently missing that field's value.
 */
@Serializable
enum class AiFieldMode {
    /** Suggest a value even when the document already has one (the user may accept a replacement). */
    ALL,

    /** Only suggest when the field is currently empty; existing values are never touched. */
    UNFILLED,
}

/** One registry field (by [key]) to ask about in an edit run, with the user's [mode]. */
@Serializable
data class AiFieldConfig(
    val key: String,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/**
 * One AI-edit run's per-document configuration: which fields to autofill ([fields]), whether to
 * also propose tag additions ([tags]) and/or tag removals ([tagRemovals]), the optional per-call
 * tag caps ([maxTags]/[maxNewTags]; null means the configured defaults), and an optional free-text
 * [customPrompt] the user typed (e.g. "merge tags that differ only in case").
 *
 * Serializable because a run handed to the background outlives the process that configured it:
 * the queued job stores its request so every one of its documents is asked the same question.
 */
@Serializable
data class AiEditRequest(
    val fields: List<AiFieldConfig> = emptyList(),
    val tags: Boolean = false,
    val tagRemovals: Boolean = false,
    val maxTags: Int? = null,
    val maxNewTags: Int? = null,
    val customPrompt: String? = null,
) {
    companion object {
        /**
         * The most fields one run may ask about. Past this the reply contract outweighs the
         * document text, so the cap holds everywhere a request is built: the interactive screen
         * refuses to start, and the unattended enricher sends only the first [MAX_FIELDS_PER_RUN]
         * in registry order.
         */
        const val MAX_FIELDS_PER_RUN = 10
    }
}

/**
 * AI-proposed edits for one document. [addTags] carries canonicalized additions (see
 * [TagSuggestion]), [removeTags] are tags already on the document that the model proposes
 * removing (verbatim current spelling), and [metadata] carries the validated field suggestions by
 * key (subject to the run's [AiFieldMode]).
 *
 * Serializable because a background run persists it: the model call is what costs money and time,
 * so its answer is stored verbatim and only projected onto a Review when the user opens the run.
 */
@Serializable
data class AiEditSuggestion(
    val addTags: List<TagSuggestion> = emptyList(),
    val removeTags: List<String> = emptyList(),
    val metadata: FieldSuggestion = FieldSuggestion.EMPTY,
) {
    val isEmpty: Boolean
        get() = addTags.isEmpty() && removeTags.isEmpty() && metadata.isEmpty

    companion object {
        val EMPTY = AiEditSuggestion()
    }
}

/**
 * AI proposals for one document's metadata. Suggestions only: accepting one goes through the
 * normal MetadataWriter/journal path, so nothing here writes. Callers surface failures as UI
 * messages ([suggestEdits] throws on network/endpoint errors).
 */
interface AiSuggest {
    /**
     * The single suggestion primitive: propose edits for [docId] from its indexed text and
     * current metadata, honouring [request]'s field selection/modes, tag-add/removal flags and
     * optional [AiEditRequest.customPrompt]. All requested pieces come back from ONE model
     * request — never a separate call per section — so tag-only, metadata-only and combined runs
     * all flow through here.
     *
     * Tag additions ([AiEditSuggestion.addTags]) are canonicalized to the library vocabulary with
     * [TagSuggestion.isNew] marking genuinely-new tags (capped per doc via [AiEditRequest.maxTags]
     * / [AiEditRequest.maxNewTags], which override the configured defaults when non-null and are
     * clamped to `AiTagging.RANGE` / `AiTagging.NEW_TAGS_RANGE`). Tag removals are filtered to tags
     * actually on the document. Field values ([AiEditSuggestion.metadata]) are canonicalized for
     * the field's registry type (text to the library's existing spelling, dates to ISO
     * `yyyy-MM-dd`), dropped when off-type or equal to the document's current value, and honour
     * each field's [AiFieldMode]. A requested key the registry does not know is treated as text.
     *
     * When the doc has no indexed text yet the result is empty for runs that requested any field
     * ([AiEditRequest.fields] non-empty); tag-only runs can still work off the tag list even
     * without OCR text.
     */
    suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
        /** The bulk run this request belongs to, for the spend ledger; null for a one-off. */
        runId: String? = null,
        /** What kind of work this request is, for the ledger's per-kind rows. */
        kind: AiUsageKind = AiUsageKind.SUGGEST,
    ): AiEditSuggestion
}
