package dev.npn.core.ai

/**
 * One AI tag proposal for a document. [tag] is already canonicalized to the library's exact
 * spelling when it matches an existing vocabulary entry case-insensitively; [isNew] is true when
 * no existing tag matches (the suggestion would introduce a brand-new tag).
 */
data class TagSuggestion(
    val tag: String,
    val isNew: Boolean,
)

/**
 * AI proposals for a document's single-value properties. Each field is null when the model had no
 * (validated) suggestion or the suggestion matched the document's current value. [correspondent]
 * is canonicalized to the library's exact spelling when it matches an existing correspondent
 * case-insensitively; [docDate] is always a valid `yyyy-MM-dd` ISO date.
 */
data class MetadataSuggestion(
    val title: String? = null,
    val correspondent: String? = null,
    val docDate: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && correspondent == null && docDate == null

    /** The proposed value for [field], or null when this run has nothing to propose for it. */
    operator fun get(field: AiField): String? =
        when (field) {
            AiField.TITLE -> title
            AiField.CORRESPONDENT -> correspondent
            AiField.DOC_DATE -> docDate
        }

    /** This suggestion with [field] cleared (the user accepted or dismissed just that field). */
    fun without(field: AiField): MetadataSuggestion =
        when (field) {
            AiField.TITLE -> copy(title = null)
            AiField.CORRESPONDENT -> copy(correspondent = null)
            AiField.DOC_DATE -> copy(docDate = null)
        }

    companion object {
        val EMPTY = MetadataSuggestion()
    }
}

/** A single-value document property the AI can suggest a value for. */
enum class AiField { TITLE, CORRESPONDENT, DOC_DATE }

/**
 * Whether a field suggestion should be produced for every document or only documents that are
 * currently missing that field's value.
 */
enum class AiFieldMode {
    /** Suggest a value even when the document already has one (the user may accept a replacement). */
    ALL,

    /** Only suggest when the field is currently empty; existing values are never touched. */
    UNFILLED,
}

/** One field to ask about in an edit run, with the user's [mode]. */
data class AiFieldConfig(
    val field: AiField,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/**
 * One AI-edit run's per-document configuration: which fields to autofill ([fields]), whether to
 * also propose tag additions ([tags]) and/or tag removals ([tagRemovals]), the optional per-call
 * tag caps ([maxTags]/[maxNewTags]; null means the configured defaults), and an optional free-text
 * [customPrompt] the user typed (e.g. "merge tags that differ only in case").
 */
data class AiEditRequest(
    val fields: List<AiFieldConfig> = emptyList(),
    val tags: Boolean = false,
    val tagRemovals: Boolean = false,
    val maxTags: Int? = null,
    val maxNewTags: Int? = null,
    val customPrompt: String? = null,
)

/**
 * AI-proposed edits for one document. [addTags] carries canonicalized additions (see
 * [TagSuggestion]), [removeTags] are tags already on the document that the model proposes
 * removing (verbatim current spelling), and [metadata] carries the validated single-value field
 * suggestions (subject to the run's [AiFieldMode]).
 */
data class AiEditSuggestion(
    val addTags: List<TagSuggestion> = emptyList(),
    val removeTags: List<String> = emptyList(),
    val metadata: MetadataSuggestion = MetadataSuggestion.EMPTY,
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
interface AiSuggest : AiAvailability {
    /**
     * Run the automatic post-OCR enrichment queue until there is nothing more it can do right now,
     * then return. Lets a caller that must keep the process alive while background work runs (the
     * Android maintenance job) do so without guessing at a duration.
     *
     * Returns rather than waiting out a retry backoff, and returns immediately when the feature is
     * off or the endpoint unconfigured — in every one of those cases no further progress is
     * possible until a later drain, so there is nothing to hold the process open for. No-op by
     * default so test fakes need not implement it.
     */
    suspend fun drainAutoEnrich() {}

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
     * actually on the document. Field values ([AiEditSuggestion.metadata]) canonicalize
     * correspondents to the library's existing spelling, validate dates as ISO `yyyy-MM-dd`, drop
     * values matching the document's current ones, and honour each field's [AiFieldMode].
     *
     * When the doc has no indexed text yet the result is empty for runs that requested any field
     * ([AiEditRequest.fields] non-empty); tag-only runs can still work off the tag list even
     * without OCR text.
     */
    suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion
}
