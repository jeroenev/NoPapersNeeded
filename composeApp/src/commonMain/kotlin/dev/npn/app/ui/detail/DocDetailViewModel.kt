package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.queryTerms
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.SplitOutcome
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transient UI state for the detail viewer (page index, page count, busy flags). Zoom/pan are
 * owned by the viewer itself (see [dev.npn.app.ui.detail.ViewerTransform]) since they need not
 * survive process death.
 */
data class DetailUiState(
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val baking: Boolean = false,
    val reindexing: Boolean = false,
    val splitting: Boolean = false,
)

sealed interface NoteSaveState {
    data object Idle : NoteSaveState

    data object Saving : NoteSaveState

    data object Saved : NoteSaveState

    data class Error(
        val message: String,
    ) : NoteSaveState
}

data class NoteEditorState(
    val draft: String = "",
    val persisted: String = "",
    val saveState: NoteSaveState = NoteSaveState.Idle,
) {
    val dirty: Boolean get() = draft != persisted
}

sealed interface HistoryUiState {
    data object NotLoaded : HistoryUiState

    data object Loading : HistoryUiState

    data object Empty : HistoryUiState

    data class Loaded(
        val items: List<HistoryItem>,
    ) : HistoryUiState

    data class Error(
        val message: String,
    ) : HistoryUiState
}

/**
 * AI tag-suggestion state for the detail screen. [available] is null until the endpoint config has
 * been probed on init (the button stays hidden until it resolves to true), [suggesting] is the
 * in-flight busy flag, and [suggestions] are the not-yet-accepted new-tag proposals.
 */
data class AiTagState(
    val available: Boolean? = null,
    val suggesting: Boolean = false,
    val suggestions: List<TagSuggestion> = emptyList(),
)

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 */
@Suppress("TooManyFunctions")
class DocDetailViewModel(
    val docId: String,
    val session: LibrarySession,
    searchQuery: String? = null,
) : MessagesViewModel() {
    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()
    private val metadataMutex = Mutex()
    private val _noteEditor = MutableStateFlow(NoteEditorState())
    val noteEditor: StateFlow<NoteEditorState> = _noteEditor.asStateFlow()

    /**
     * Search terms from the library query that opened this document, used to highlight matches
     * in the OCR text tab.
     */
    val highlightTerms: List<String> = searchQuery?.let { queryTerms(it) }.orEmpty()

    val doc: StateFlow<DocListItem?> =
        session.documents
            .observeDoc(docId)
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val reviewMode: StateFlow<ReviewMode> =
        session.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    val correspondents: StateFlow<List<String>> =
        session.documents
            .observeCorrespondents()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val docTypes: StateFlow<List<String>> =
        session.documents
            .observeDocTypes()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val tagColors: StateFlow<Map<String, String?>> =
        session.tags
            .observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Distinct custom-field keys in the library, for add-field key autocomplete. */
    val fieldKeys: StateFlow<List<String>> =
        session.documents
            .observeFieldKeys()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /**
     * Extracted OCR/embedded text for this doc, reloaded when the doc's OCR state changes (so it
     * appears once background OCR completes). Null until loaded or when there is no text.
     */
    val ocrText: StateFlow<String?> =
        doc
            .map { it?.doc?.ocrState }
            .distinctUntilChanged()
            .map { runCatching { session.content.extractedText(docId) }.getOrNull() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Stored OCR versions of this doc (best-ranked first), reloaded whenever the indexed OCR
     * state or engine changes so the picker reflects freshly-adopted sidecars and re-selections.
     */
    val ocrVersions: StateFlow<List<OcrVersion>> =
        doc
            .map { it?.doc?.ocrState to it?.doc?.ocrEngine }
            .distinctUntilChanged()
            .map { runCatching { session.ocrVersions(docId) }.getOrDefault(emptyList()) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val _history = MutableStateFlow<HistoryUiState>(HistoryUiState.NotLoaded)
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val _aiTags = MutableStateFlow(AiTagState())
    val aiTags: StateFlow<AiTagState> = _aiTags.asStateFlow()

    init {
        viewModelScope.launch {
            val count = runCatching { session.content.pageCount(docId) }.getOrNull() ?: 0
            _ui.value = _ui.value.copy(pageCount = count)
        }
        viewModelScope.launch {
            val configured = session.ai.isConfiguredOrFalse()
            _aiTags.value = _aiTags.value.copy(available = configured)
        }
        viewModelScope.launch {
            session.documents.observeDoc(docId).collect { item ->
                val persisted = item?.doc?.notes.orEmpty()
                val current = _noteEditor.value
                _noteEditor.value =
                    if (current.dirty) {
                        current.copy(persisted = persisted)
                    } else {
                        current.copy(draft = persisted, persisted = persisted)
                    }
            }
        }
    }

    // ---- viewer controls ----

    fun goToPage(index: Int) {
        val cur = _ui.value
        if (index in 0 until cur.pageCount.coerceAtLeast(1)) _ui.value = cur.copy(pageIndex = index)
    }

    // ---- history ----

    /** Load once when opened. Pass [refresh] only for an explicit retry or refresh request. */
    fun loadHistory(refresh: Boolean = false) {
        if (!refresh && _history.value != HistoryUiState.NotLoaded) return
        if (_history.value == HistoryUiState.Loading) return
        _history.value = HistoryUiState.Loading
        viewModelScope.launch {
            _history.value =
                runCatching {
                    HistoryMapper.toHistoryItems(session.documents.history(docId))
                }.fold(
                    onSuccess = { items ->
                        if (items.isEmpty()) HistoryUiState.Empty else HistoryUiState.Loaded(items)
                    },
                    onFailure = { error ->
                        HistoryUiState.Error(error.message.orEmpty())
                    },
                )
        }
    }

    // ---- metadata edits ----

    fun setTitle(title: String?) =
        launchMetadata(UiAction.SAVE_TITLE) { session.meta.setTitle(docId, title?.takeIf { it.isNotBlank() }) }

    fun updateNoteDraft(value: String) {
        _noteEditor.value = _noteEditor.value.copy(draft = value, saveState = NoteSaveState.Idle)
    }

    fun saveNote() {
        val current = _noteEditor.value
        if (!current.dirty || current.saveState == NoteSaveState.Saving) return
        _noteEditor.value = current.copy(saveState = NoteSaveState.Saving)
        viewModelScope.launch {
            runCatching {
                metadataMutex.withLock { session.meta.setNotes(docId, current.draft) }
            }.fold(
                onSuccess = {
                    val latest = _noteEditor.value
                    _noteEditor.value =
                        latest.copy(
                            persisted = current.draft.takeUnless { it.isBlank() }.orEmpty(),
                            saveState = NoteSaveState.Saved,
                        )
                },
                onFailure = { error ->
                    _noteEditor.value =
                        _noteEditor.value.copy(
                            saveState = NoteSaveState.Error(error.message.orEmpty()),
                        )
                },
            )
        }
    }

    fun setCorrespondent(value: String?) =
        launchMetadata(UiAction.SAVE_CORRESPONDENT) {
            session.meta.setCorrespondent(listOf(docId), value?.takeIf { it.isNotBlank() })
        }

    fun setDocType(value: String?) =
        launchMetadata(UiAction.SAVE_DOCUMENT_TYPE) {
            session.meta.setDocType(listOf(docId), value?.takeIf { it.isNotBlank() })
        }

    fun setDocDate(isoDate: String?) =
        launchMetadata(UiAction.SAVE_DATE) { session.meta.setDocDate(docId, isoDate?.takeIf { it.isNotBlank() }) }

    fun addTag(tag: String) = launchMetadata(UiAction.ADD_TAG) { session.meta.addTag(listOf(docId), tag) }

    fun removeTag(tag: String) = launchMetadata(UiAction.REMOVE_TAG) { session.meta.removeTag(listOf(docId), tag) }

    // ---- AI tag suggestions ----

    /**
     * Ask the AI endpoint for new-tag suggestions from this doc's indexed text. No-op while a
     * request is already in flight. Empty results surface an informational message (the document
     * likely has no OCR text yet); failures surface the error via [messages].
     */
    fun suggestAiTags() {
        if (_aiTags.value.suggesting) return
        _aiTags.value = _aiTags.value.copy(suggesting = true)
        viewModelScope.launch {
            runCatching { session.ai.suggestTags(docId) }.fold(
                onSuccess = { suggestions ->
                    if (suggestions.isEmpty()) {
                        message(uiText(UiTextKey.NO_AI_TAG_SUGGESTIONS))
                    }
                    _aiTags.value = _aiTags.value.copy(suggesting = false, suggestions = suggestions)
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_TAGS, reasonOf(error)))
                    _aiTags.value = _aiTags.value.copy(suggesting = false, suggestions = emptyList())
                },
            )
        }
    }

    /** Accept a suggestion: add it as a real tag (normal journal path) and drop it from the list. */
    fun acceptAiTag(tag: String) =
        launchMetadata(UiAction.ADD_TAG) {
            session.meta.addTag(listOf(docId), tag)
            dismissAiTag(tag)
        }

    fun dismissAiTag(tag: String) {
        _aiTags.value =
            _aiTags.value.copy(
                suggestions = _aiTags.value.suggestions.filterNot { it.tag == tag },
            )
    }

    fun clearAiTagSuggestions() {
        _aiTags.value = _aiTags.value.copy(suggestions = emptyList())
    }

    fun setField(
        key: String,
        value: String?,
    ) = launchMetadata(UiAction.SAVE_FIELD) { session.meta.setField(docId, key, value) }

    fun removeField(key: String) = launchMetadata(UiAction.REMOVE_FIELD) { session.meta.setField(docId, key, null) }

    fun setReviewed(reviewed: Boolean) =
        launchMetadata(UiAction.UPDATE_REVIEW_STATE) { session.meta.setReviewed(listOf(docId), reviewed) }

    private fun launchMetadata(
        action: UiAction,
        block: suspend () -> Unit,
    ) = launchReporting(action) { metadataMutex.withLock { block() } }

    /** On failure [onDone] is not invoked (the screen stays put) and the error is surfaced. */
    fun trash(onDone: () -> Unit) =
        launchReporting(UiAction.MOVE_TO_TRASH) {
            session.meta.trashDoc(docId)
            onDone()
        }

    fun rename(newFileName: String) =
        launchReporting(UiAction.RENAME_FILE) { session.meta.renameFile(docId, newFileName) }

    fun reOcr() =
        launchReporting(UiAction.RERUN_OCR) {
            _ui.value = _ui.value.copy(reindexing = true)
            try {
                session.reOcr(docId)
            } finally {
                _ui.value = _ui.value.copy(reindexing = false)
            }
        }

    /** Pin [engine] as the searchable OCR version (null = automatic best-engine selection). */
    fun setOcrEngine(engine: String?) =
        launchReporting(UiAction.CHANGE_OCR_ENGINE) { session.setOcrEngine(docId, engine) }

    /**
     * Split into one document per [segments] entry. On success the original is trashed by the
     * session, so the screen should navigate back; failures surface via [onResult]'s message.
     */
    fun split(
        segments: List<IntRange>,
        onResult: (SplitOutcome) -> Unit,
    ) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(splitting = true)
            val outcome =
                runCatching { session.splitPdf(docId, segments) }
                    .getOrElse { SplitOutcome.Failed(it.message.orEmpty()) }
            _ui.value = _ui.value.copy(splitting = false)
            onResult(outcome)
        }
    }

    // Failure is reported through onResult(false); the screen owns the message.
    fun bakeInPlace(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(baking = true)
            val ok = runCatching { session.content.bakeInPlace(docId) }.getOrDefault(false)
            _ui.value = _ui.value.copy(baking = false)
            onResult(ok)
        }
    }

    suspend fun bakedCopyBytes(): ByteArray? = runCatching { session.content.bakedCopyBytes(docId) }.getOrNull()

    suspend fun renderPage(
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = runCatching { session.content.renderPage(docId, pageIndex, maxDim) }.getOrNull()
}
