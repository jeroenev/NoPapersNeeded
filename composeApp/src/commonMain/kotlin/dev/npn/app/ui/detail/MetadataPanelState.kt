package dev.npn.app.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.npn.core.ai.AiField
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField

/**
 * Everything [MetadataPanel] renders, in four groups assembled by
 * [DocDetailViewModel.metadata]. The panel takes this one value plus [MetadataActions]; only the
 * genuinely per-call-site pieces (the optional preview tab and the modifier) stay parameters.
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
 * until the document row arrives (and stays null for a document that is gone), which is the
 * panel's cue to render nothing. [reviewed] is already resolved against [reviewMode], so no call
 * site repeats that derivation.
 */
@Immutable
data class DocumentSection(
    val item: DocListItem? = null,
    val reviewed: Boolean = false,
    val reviewMode: ReviewMode = ReviewMode.DEFAULT,
    /** Which built-in fields to show (library-wide synced setting); title is always shown. */
    val visibleFields: Set<StandardField> = StandardField.DEFAULT,
    val ocrText: String? = null,
    val ocrVersions: List<OcrVersion> = emptyList(),
    /** Terms from the search that opened this document, highlighted in the text tab. */
    val highlightTerms: List<String> = emptyList(),
)

/** Library-wide values offered as completions while editing: tags, correspondents, field keys. */
@Immutable
data class VocabularyState(
    val tagColors: Map<String, String?> = emptyMap(),
    val correspondents: List<String> = emptyList(),
    val docTypes: List<String> = emptyList(),
    val fieldKeys: List<String> = emptyList(),
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
 * Everything the panel can invoke, grouped by concern. Built once per viewmodel by
 * [rememberMetadataActions]; both call sites share that wiring.
 */
@Immutable
data class MetadataActions(
    val edit: MetadataEditActions,
    val tags: TagActions,
    val ai: AiActions,
    val history: HistoryActions,
)

/** Edits to the document values; DocumentEditor redirects text edits into a retained draft. */
@Immutable
data class MetadataEditActions(
    val onCommitTitle: (String) -> Unit,
    val onNoteChange: (String) -> Unit,
    val onCommitCorrespondent: (String) -> Unit,
    val onCommitDocType: (String) -> Unit,
    val onCommitDocDate: (String) -> Unit,
    val onCommitField: (key: String, value: String) -> Unit,
    val onRemoveField: (key: String) -> Unit,
    val onSetReviewed: (Boolean) -> Unit,
    /** Pin the searchable OCR version, or null for automatic best-engine selection. */
    val onSetOcrEngine: (String?) -> Unit,
)

/** Tags on this document. [onAddWithStyle] creates a brand-new tag with a colour chosen inline. */
@Immutable
data class TagActions(
    val onAdd: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onAddWithStyle: (name: String, color: String?) -> Unit,
)

/** Asking for AI suggestions and accepting or dismissing them one at a time. */
@Immutable
data class AiActions(
    val onSuggestTags: () -> Unit,
    val onAcceptTag: (String) -> Unit,
    val onDismissTag: (String) -> Unit,
    val onClearTagSuggestions: () -> Unit,
    val onSuggestMetadata: () -> Unit,
    val onAcceptMetadata: (AiField) -> Unit,
    val onDismissMetadata: (AiField) -> Unit,
)

/** The history tab: a first load when it is opened, and an explicit refresh/retry. */
@Immutable
data class HistoryActions(
    val onLoad: () -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * Wire the panel's actions to [vm]. Remembered on the viewmodel so the holders — and therefore
 * the panel's parameters — stay stable across recomposition.
 */
@Composable
fun rememberMetadataActions(vm: DocDetailViewModel): MetadataActions =
    remember(vm) {
        MetadataActions(
            edit =
                MetadataEditActions(
                    onCommitTitle = vm::setTitle,
                    onNoteChange = vm::setNotes,
                    onCommitCorrespondent = vm::setCorrespondent,
                    onCommitDocType = vm::setDocType,
                    onCommitDocDate = vm::setDocDate,
                    onCommitField = vm::setField,
                    onRemoveField = vm::removeField,
                    onSetReviewed = vm::setReviewed,
                    onSetOcrEngine = vm::setOcrEngine,
                ),
            tags =
                TagActions(
                    onAdd = vm::addTag,
                    onRemove = vm::removeTag,
                    onAddWithStyle = vm::addTagWithStyle,
                ),
            ai =
                AiActions(
                    onSuggestTags = vm::suggestAiTags,
                    onAcceptTag = vm::acceptAiTag,
                    onDismissTag = vm::dismissAiTag,
                    onClearTagSuggestions = vm::clearAiTagSuggestions,
                    onSuggestMetadata = vm::suggestAiMetadata,
                    onAcceptMetadata = vm::acceptAiMetadata,
                    onDismissMetadata = vm::dismissAiMetadata,
                ),
            history =
                HistoryActions(
                    onLoad = { vm.loadHistory() },
                    onRefresh = { vm.loadHistory(refresh = true) },
                ),
        )
    }
