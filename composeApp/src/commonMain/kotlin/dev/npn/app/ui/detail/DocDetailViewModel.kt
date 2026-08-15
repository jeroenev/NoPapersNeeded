package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.queryTerms
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.MaintenanceFacade
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TagAdmin
import dev.npn.core.app.TagRepository
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
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
 * AI metadata-suggestion state (title/correspondent/date) for the detail screen. Availability is
 * shared with [AiTagState.available] (one endpoint probe covers both); [suggesting] is the
 * in-flight busy flag, and [suggestion] holds the not-yet-accepted per-field proposals.
 */
data class AiMetadataState(
    val suggesting: Boolean = false,
    val suggestion: MetadataSuggestion = MetadataSuggestion.EMPTY,
)

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 */
@Suppress("TooManyFunctions")
class DocDetailViewModel(
    val docId: String,
    private val documents: DocumentRepository,
    tagRepository: TagRepository,
    private val meta: MetadataWriter,
    private val tagAdmin: TagAdmin,
    private val docLifecycle: DocLifecycle,
    private val content: DocContentFacade,
    private val ai: AiSuggest,
    private val ocr: OcrFacade,
    private val maintenance: MaintenanceFacade,
    reviewConfig: ReviewConfigFacade,
    searchQuery: String? = null,
) : MessagesViewModel() {
    constructor(docId: String, session: LibrarySession, searchQuery: String? = null) : this(
        docId = docId,
        documents = session.documents,
        tagRepository = session.tags,
        meta = session.meta,
        tagAdmin = session.tagAdmin,
        docLifecycle = session.docLifecycle,
        content = session.content,
        ai = session.aiSuggest,
        ocr = session.ocr,
        maintenance = session.maintenance,
        reviewConfig = session.reviewConfig,
        searchQuery = searchQuery,
    )

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
        documents
            .observeDoc(docId)
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    /** Library-wide visible standard fields (synced setting); title is always shown. */
    val standardFields: StateFlow<Set<StandardField>> =
        reviewConfig.standardFields
            .stateIn(viewModelScope, WhileSubscribedTimeout, StandardField.DEFAULT)

    val correspondents: StateFlow<List<String>> =
        documents
            .observeCorrespondents()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val docTypes: StateFlow<List<String>> =
        documents
            .observeDocTypes()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val tagColors: StateFlow<Map<String, String?>> =
        tagRepository
            .observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Distinct custom-field keys in the library, for add-field key autocomplete. */
    val fieldKeys: StateFlow<List<String>> =
        documents
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
            .map { runCatching { content.extractedText(docId) }.getOrNull() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Stored OCR versions of this doc (best-ranked first), reloaded whenever the indexed OCR
     * state or engine changes so the picker reflects freshly-adopted sidecars and re-selections.
     */
    val ocrVersions: StateFlow<List<OcrVersion>> =
        doc
            .map { it?.doc?.ocrState to it?.doc?.ocrEngine }
            .distinctUntilChanged()
            .map { runCatching { ocr.ocrVersions(docId) }.getOrDefault(emptyList()) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val _history = MutableStateFlow<HistoryUiState>(HistoryUiState.NotLoaded)
    val history: StateFlow<HistoryUiState> = _history.asStateFlow()

    private val _aiTags = MutableStateFlow(AiTagState())
    val aiTags: StateFlow<AiTagState> = _aiTags.asStateFlow()

    private val _aiMetadata = MutableStateFlow(AiMetadataState())
    val aiMetadata: StateFlow<AiMetadataState> = _aiMetadata.asStateFlow()

    init {
        viewModelScope.launch {
            val count = runCatching { content.pageCount(docId) }.getOrNull() ?: 0
            _ui.value = _ui.value.copy(pageCount = count)
        }
        viewModelScope.launch {
            val configured = ai.isConfiguredOrFalse()
            _aiTags.value = _aiTags.value.copy(available = configured)
        }
        // Sync the note editor's persisted value from the already-shared [doc] StateFlow instead of
        // subscribing to observeDoc a second time (that would double the Room invalidation wiring).
        viewModelScope.launch {
            doc
                .map { it?.doc?.notes.orEmpty() }
                .distinctUntilChanged()
                .collect { persisted ->
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
                    HistoryMapper.toHistoryItems(documents.history(docId))
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
        launchMetadata(UiAction.SAVE_TITLE) { meta.setTitle(docId, title?.takeIf { it.isNotBlank() }) }

    fun updateNoteDraft(value: String) {
        _noteEditor.value = _noteEditor.value.copy(draft = value, saveState = NoteSaveState.Idle)
    }

    fun saveNote() {
        val current = _noteEditor.value
        if (!current.dirty || current.saveState == NoteSaveState.Saving) return
        _noteEditor.value = current.copy(saveState = NoteSaveState.Saving)
        viewModelScope.launch {
            runCatching {
                metadataMutex.withLock { meta.setNotes(docId, current.draft) }
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
            meta.setCorrespondent(listOf(docId), value?.takeIf { it.isNotBlank() })
        }

    fun setDocType(value: String?) =
        launchMetadata(UiAction.SAVE_DOCUMENT_TYPE) {
            meta.setDocType(listOf(docId), value?.takeIf { it.isNotBlank() })
        }

    fun setDocDate(isoDate: String?) =
        launchMetadata(UiAction.SAVE_DATE) { meta.setDocDate(docId, isoDate?.takeIf { it.isNotBlank() }) }

    fun addTag(tag: String) = launchMetadata(UiAction.ADD_TAG) { meta.addTag(listOf(docId), tag) }

    /** Create a brand-new tag on this doc, optionally with a color chosen inline in the editor. */
    fun addTagWithStyle(
        tag: String,
        color: String?,
    ) = launchMetadata(UiAction.CREATE_TAG) {
        meta.addTag(listOf(docId), tag)
        if (color != null) tagAdmin.setTagStyle(tag, color, null)
    }

    fun removeTag(tag: String) = launchMetadata(UiAction.REMOVE_TAG) { meta.removeTag(listOf(docId), tag) }

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
            runCatching { ai.suggestEdits(docId, AiEditRequest(tags = true)).addTags }.fold(
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
            meta.addTag(listOf(docId), tag)
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

    // ---- AI metadata suggestions ----

    /**
     * Ask the AI endpoint for title/correspondent/date suggestions from this doc's indexed text.
     * No-op while a request is already in flight. Empty results surface an informational message
     * (no OCR text yet, or nothing beyond the current values); failures surface via [messages].
     */
    fun suggestAiMetadata() {
        if (_aiMetadata.value.suggesting) return
        _aiMetadata.value = _aiMetadata.value.copy(suggesting = true)
        viewModelScope.launch {
            val request =
                AiEditRequest(
                    fields = AiField.entries.map { AiFieldConfig(it, AiFieldMode.ALL) },
                )
            runCatching { ai.suggestEdits(docId, request).metadata }.fold(
                onSuccess = { suggestion ->
                    if (suggestion.isEmpty) {
                        message(uiText(UiTextKey.NO_AI_METADATA_SUGGESTIONS))
                    }
                    _aiMetadata.value = AiMetadataState(suggesting = false, suggestion = suggestion)
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_METADATA, reasonOf(error)))
                    _aiMetadata.value = AiMetadataState()
                },
            )
        }
    }

    /** Accept one field's suggestion: write it via the normal journal path and drop it. */
    fun acceptAiMetadata(field: AiField) {
        val suggestion = _aiMetadata.value.suggestion
        when (field) {
            AiField.TITLE -> suggestion.title?.let(::setTitle)
            AiField.CORRESPONDENT -> suggestion.correspondent?.let(::setCorrespondent)
            AiField.DOC_DATE -> suggestion.docDate?.let(::setDocDate)
        }
        dismissAiMetadata(field)
    }

    fun dismissAiMetadata(field: AiField) {
        val current = _aiMetadata.value
        val suggestion =
            when (field) {
                AiField.TITLE -> current.suggestion.copy(title = null)
                AiField.CORRESPONDENT -> current.suggestion.copy(correspondent = null)
                AiField.DOC_DATE -> current.suggestion.copy(docDate = null)
            }
        _aiMetadata.value = current.copy(suggestion = suggestion)
    }

    fun setField(
        key: String,
        value: String?,
    ) = launchMetadata(UiAction.SAVE_FIELD) { meta.setField(docId, key, value) }

    fun removeField(key: String) = launchMetadata(UiAction.REMOVE_FIELD) { meta.setField(docId, key, null) }

    fun setReviewed(reviewed: Boolean) =
        launchMetadata(UiAction.UPDATE_REVIEW_STATE) { meta.setReviewed(listOf(docId), reviewed) }

    private fun launchMetadata(
        action: UiAction,
        block: suspend () -> Unit,
    ) = launchReporting(action) { metadataMutex.withLock { block() } }

    /** On failure [onDone] is not invoked (the screen stays put) and the error is surfaced. */
    fun trash(onDone: () -> Unit) =
        launchReporting(UiAction.MOVE_TO_TRASH) {
            docLifecycle.trashDoc(docId)
            onDone()
        }

    fun rename(newFileName: String) =
        launchReporting(UiAction.RENAME_FILE) { docLifecycle.renameFile(docId, newFileName) }

    fun reOcr() =
        launchReporting(UiAction.RERUN_OCR) {
            _ui.value = _ui.value.copy(reindexing = true)
            try {
                ocr.reOcr(docId)
            } finally {
                _ui.value = _ui.value.copy(reindexing = false)
            }
        }

    /** Pin [engine] as the searchable OCR version (null = automatic best-engine selection). */
    fun setOcrEngine(engine: String?) = launchReporting(UiAction.CHANGE_OCR_ENGINE) { ocr.setOcrEngine(docId, engine) }

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
                runCatching { maintenance.splitPdf(docId, segments) }
                    .getOrElse { SplitOutcome.Failed(it.message.orEmpty()) }
            _ui.value = _ui.value.copy(splitting = false)
            onResult(outcome)
        }
    }

    // Failure is reported through onResult(false); the screen owns the message.
    fun bakeInPlace(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(baking = true)
            val ok = runCatching { content.bakeInPlace(docId) }.getOrDefault(false)
            _ui.value = _ui.value.copy(baking = false)
            onResult(ok)
        }
    }

    suspend fun bakedCopyBytes(): ByteArray? = runCatching { content.bakedCopyBytes(docId) }.getOrNull()

    suspend fun renderPage(
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = runCatching { content.renderPage(docId, pageIndex, maxDim) }.getOrNull()
}
