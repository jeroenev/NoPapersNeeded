package dev.npn.app.ui.detail

import androidx.compose.runtime.Immutable
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldParse
import dev.npn.core.model.ReviewMode

/**
 * Everything [MetadataPanel] renders, in four groups assembled by [DocDetailViewModel.metadata].
 * [DocumentEditor] hands the panel this value, the document with its draft laid over
 * [DocumentSection.item], and the [MetadataActions].
 *
 * The note is not a group of its own: it is one of the document's own values, so it travels with
 * the rest of them in [DocumentSection.item] and picks up the same draft overlay.
 */
@Immutable
data class MetadataUiState(
    val document: DocumentSection = DocumentSection(),
    val vocabulary: VocabularyState = VocabularyState(),
    val ai: AiPanelState = AiPanelState(),
    val history: HistoryUiState = HistoryUiState.NotLoaded,
)

/**
 * The document itself plus the library-wide settings that decide how it is shown. [item] is null
 * until the document row arrives (and stays null for a document that is gone), which the editor
 * shows as unavailable. [loadState] disambiguates that null: still-loading vs confirmed missing, so
 * a screen can wait rather than flash "unavailable". [reviewed] is already resolved against
 * [reviewMode], so no call site repeats that derivation.
 */
@Immutable
data class DocumentSection(
    val item: DocListItem? = null,
    val loadState: DocLoadState = DocLoadState.Loading,
    val reviewed: Boolean = false,
    val reviewMode: ReviewMode = ReviewMode.DEFAULT,
    /** The field registry in display order; decides which fields render and how each is edited. */
    val fieldDefs: List<FieldDef> = emptyList(),
    val ocrText: String? = null,
    val ocrVersions: List<OcrVersion> = emptyList(),
    /** Terms from the search that opened this document, highlighted in the text tab. */
    val highlightTerms: List<String> = emptyList(),
)

/**
 * Library-wide values offered as completions while editing: tags, each text field's existing
 * values, and the unregistered keys other documents carry (offered when defining a new field).
 */
@Immutable
data class VocabularyState(
    val tagColors: Map<String, String?> = emptyMap(),
    val fieldValues: Map<String, List<String>> = emptyMap(),
    val orphanKeys: List<String> = emptyList(),
) {
    /** Tag autocomplete list; [tagColors] already carries every tag name, ordered by name. */
    val allTagNames: List<String> = tagColors.keys.toList()
}

/** The panel's two AI sections: tag suggestions and single-value field suggestions. */
@Immutable
data class AiPanelState(
    val tags: AiTagState = AiTagState(),
    val metadata: AiMetadataState = AiMetadataState(),
)

/**
 * The writes the metadata panel makes at once: tags, AI suggestions, new field definitions, the
 * searchable OCR version, and the history it shows. [DocDetailViewModel] implements it.
 */
interface DocumentWrites {
    fun addTag(tag: String)

    fun removeTag(tag: String)

    /** Create a brand-new tag on the document, with a colour chosen inline. */
    fun addTagWithStyle(
        tag: String,
        color: String?,
    )

    /** Register [def] and, when [value] is non-blank, set it on the document in one go. */
    fun defineField(
        def: FieldDef,
        value: String?,
    )

    /** Pin the searchable OCR version, or null for automatic best-engine selection. */
    fun setOcrEngine(engine: String?)

    fun suggestAiTags()

    fun acceptAiTag(tag: String)

    fun dismissAiTag(tag: String)

    fun clearAiTagSuggestions()

    fun suggestAiMetadata()

    fun acceptAiMetadata(key: String)

    fun dismissAiMetadata(key: String)

    /** Show the history (its tab opened), or read it again when [refresh] is set. */
    fun loadHistory(refresh: Boolean = false)
}

/**
 * Everything the metadata panel can do: the [DocumentWrites], plus the edits that wait in the
 * document's draft until Save. [DocumentEditor] provides it, so the panel never decides which of
 * its edits are immediate.
 */
interface MetadataActions : DocumentWrites {
    /**
     * A keystroke or pick for one field, as its editor reads it: the value in canonical form, or an
     * entry the editor refused, as typed, which blocks the save until a valid edit replaces it.
     */
    fun commitField(
        key: String,
        entry: FieldParse,
    )

    fun removeField(key: String)

    fun setReviewed(reviewed: Boolean)
}
