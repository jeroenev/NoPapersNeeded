package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.queryTerms
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.library.effectiveReviewed
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiOcrOffer
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /**
     * Whether to offer "read text with assistant" for this document, and whether to warn about
     * its length first. See [AiOcrOffer].
     */
    val aiOcr: AiOcrOffer = AiOcrOffer.Unavailable,
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
 *
 * Everything the metadata panel renders is assembled into the single [metadata] state; the flows
 * behind it are private, so a panel that needs a new value gets it from there rather than from a
 * new public flow wired through two call sites. The screen's whole write surface lives here too —
 * every setter the panel's action holders bind — which is why the function count is suppressed.
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
    /**
     * The document row a caller already has in hand (the library list feeds its preview panel one).
     * Seeds [metadata] so the panel renders immediately instead of blanking for the first
     * `observeDoc` query, which a caller that recreates this viewmodel per document would pay on
     * every switch. Null when the caller only knows the id.
     */
    initialItem: DocListItem? = null,
) : MessagesViewModel() {
    constructor(
        docId: String,
        session: LibrarySession,
        searchQuery: String? = null,
        initialItem: DocListItem? = null,
    ) : this(
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
        initialItem = initialItem,
    )

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()
    private val metadataMutex = Mutex()
    private val noteEditor = MutableStateFlow(NoteEditorState())

    /**
     * Search terms from the library query that opened this document, used to highlight matches
     * in the OCR text tab.
     */
    private val highlightTerms: List<String> = searchQuery?.let { queryTerms(it) }.orEmpty()

    private val doc: StateFlow<DocListItem?> =
        documents
            .observeDoc(docId)
            .stateIn(viewModelScope, WhileSubscribedTimeout, initialItem)

    private val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    /** Library-wide visible standard fields (synced setting); title is always shown. */
    private val standardFields: StateFlow<Set<StandardField>> =
        reviewConfig.standardFields
            .stateIn(viewModelScope, WhileSubscribedTimeout, StandardField.DEFAULT)

    private val correspondents: StateFlow<List<String>> =
        documents
            .observeCorrespondents()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val docTypes: StateFlow<List<String>> =
        documents
            .observeDocTypes()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val tagColors: StateFlow<Map<String, String?>> =
        tagRepository
            .observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Distinct custom-field keys in the library, for add-field key autocomplete. */
    private val fieldKeys: StateFlow<List<String>> =
        documents
            .observeFieldKeys()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /**
     * Extracted OCR/embedded text for this doc, reloaded when the doc's OCR state changes (so it
     * appears once background OCR completes). Null until loaded or when there is no text.
     */
    private val ocrText: StateFlow<String?> =
        doc
            .map { it?.doc?.ocrState }
            .distinctUntilChanged()
            .map { runCatching { content.extractedText(docId) }.getOrNull() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Stored OCR versions of this doc (best-ranked first), reloaded whenever the indexed OCR
     * state or engine changes so the picker reflects freshly-adopted sidecars and re-selections.
     */
    private val ocrVersions: StateFlow<List<OcrVersion>> =
        doc
            .map { it?.doc?.ocrState to it?.doc?.ocrEngine }
            .distinctUntilChanged()
            .map { runCatching { ocr.ocrVersions(docId) }.getOrDefault(emptyList()) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val history = MutableStateFlow<HistoryUiState>(HistoryUiState.NotLoaded)

    private val aiTags = MutableStateFlow(AiTagState())

    private val aiMetadata = MutableStateFlow(AiMetadataState())

    private val document: Flow<DocumentSection> =
        combine(doc, reviewMode, standardFields, ocrText, ocrVersions) { item, mode, fields, text, versions ->
            DocumentSection(
                item = item,
                reviewed = item?.effectiveReviewed(mode) == true,
                reviewMode = mode,
                visibleFields = fields,
                ocrText = text,
                ocrVersions = versions,
                highlightTerms = highlightTerms,
            )
        }

    private val vocabulary: Flow<VocabularyState> =
        combine(tagColors, correspondents, docTypes, fieldKeys) { colors, senders, types, keys ->
            VocabularyState(tagColors = colors, correspondents = senders, docTypes = types, fieldKeys = keys)
        }

    private val aiPanel: Flow<AiPanelState> =
        combine(aiTags, aiMetadata) { tags, metadata -> AiPanelState(tags, metadata) }

    /**
     * The single state the metadata panel renders. Assembled here rather than in the composable
     * tree so both call sites (the detail screen and the library's side panel) show the same
     * thing without repeating the wiring — and so the panel's state is assertable in a viewmodel
     * test.
     */
    val metadata: StateFlow<MetadataUiState> =
        combine(document, vocabulary, noteEditor, aiPanel, history) { doc, vocab, note, ai, history ->
            MetadataUiState(document = doc, vocabulary = vocab, note = note, ai = ai, history = history)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, seedState(initialItem))

    /**
     * What the panel shows before the first combined emission. [ReviewMode.DEFAULT] matches the
     * initial value of the review-mode flow, so the seeded badge is exactly what the previous
     * `item.effectiveReviewed(reviewMode)` at the call site produced on its first frame.
     */
    private fun seedState(item: DocListItem?): MetadataUiState =
        MetadataUiState(
            document =
                DocumentSection(
                    item = item,
                    reviewed = item?.effectiveReviewed(ReviewMode.DEFAULT) == true,
                    highlightTerms = highlightTerms,
                ),
        )

    init {
        viewModelScope.launch {
            val count = runCatching { content.pageCount(docId) }.getOrNull() ?: 0
            _ui.value = _ui.value.copy(pageCount = count)
        }
        viewModelScope.launch {
            val configured = ai.isConfiguredOrFalse()
            aiTags.value = aiTags.value.copy(available = configured)
        }
        viewModelScope.launch {
            val offer = runCatching { ocr.aiOcrOffer(docId) }.getOrDefault(AiOcrOffer.Unavailable)
            _ui.value = _ui.value.copy(aiOcr = offer)
        }
        // Sync the note editor's persisted value from the already-shared [doc] StateFlow instead of
        // subscribing to observeDoc a second time (that would double the Room invalidation wiring).
        viewModelScope.launch {
            doc
                .map { it?.doc?.notes.orEmpty() }
                .distinctUntilChanged()
                .collect { persisted ->
                    val current = noteEditor.value
                    noteEditor.value =
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
        if (!refresh && history.value != HistoryUiState.NotLoaded) return
        if (history.value == HistoryUiState.Loading) return
        history.value = HistoryUiState.Loading
        viewModelScope.launch {
            history.value =
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
        noteEditor.value = noteEditor.value.copy(draft = value, saveState = NoteSaveState.Idle)
    }

    fun saveNote() {
        val current = noteEditor.value
        if (!current.dirty || current.saveState == NoteSaveState.Saving) return
        noteEditor.value = current.copy(saveState = NoteSaveState.Saving)
        viewModelScope.launch {
            runCatching {
                metadataMutex.withLock { meta.setNotes(docId, current.draft) }
            }.fold(
                onSuccess = {
                    val latest = noteEditor.value
                    noteEditor.value =
                        latest.copy(
                            persisted = current.draft.takeUnless { it.isBlank() }.orEmpty(),
                            saveState = NoteSaveState.Saved,
                        )
                },
                onFailure = { error ->
                    noteEditor.value =
                        noteEditor.value.copy(
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
        if (aiTags.value.suggesting) return
        aiTags.value = aiTags.value.copy(suggesting = true)
        viewModelScope.launch {
            runCatching { ai.suggestEdits(docId, AiEditRequest(tags = true)).addTags }.fold(
                onSuccess = { suggestions ->
                    if (suggestions.isEmpty()) {
                        message(uiText(UiTextKey.NO_AI_TAG_SUGGESTIONS))
                    }
                    aiTags.value = aiTags.value.copy(suggesting = false, suggestions = suggestions)
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_TAGS, reasonOf(error)))
                    aiTags.value = aiTags.value.copy(suggesting = false, suggestions = emptyList())
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
        aiTags.value =
            aiTags.value.copy(
                suggestions = aiTags.value.suggestions.filterNot { it.tag == tag },
            )
    }

    fun clearAiTagSuggestions() {
        aiTags.value = aiTags.value.copy(suggestions = emptyList())
    }

    // ---- AI metadata suggestions ----

    /**
     * Ask the AI endpoint for title/correspondent/date suggestions from this doc's indexed text.
     * No-op while a request is already in flight. Empty results surface an informational message
     * (no OCR text yet, or nothing beyond the current values); failures surface via [messages].
     */
    fun suggestAiMetadata() {
        if (aiMetadata.value.suggesting) return
        aiMetadata.value = aiMetadata.value.copy(suggesting = true)
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
                    aiMetadata.value = AiMetadataState(suggesting = false, suggestion = suggestion)
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_METADATA, reasonOf(error)))
                    aiMetadata.value = AiMetadataState()
                },
            )
        }
    }

    /** Accept one field's suggestion: write it via the normal journal path and drop it. */
    fun acceptAiMetadata(field: AiField) {
        val suggestion = aiMetadata.value.suggestion
        when (field) {
            AiField.TITLE -> suggestion.title?.let(::setTitle)
            AiField.CORRESPONDENT -> suggestion.correspondent?.let(::setCorrespondent)
            AiField.DOC_DATE -> suggestion.docDate?.let(::setDocDate)
        }
        dismissAiMetadata(field)
    }

    fun dismissAiMetadata(field: AiField) {
        aiMetadata.value = aiMetadata.value.let { it.copy(suggestion = it.suggestion.without(field)) }
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

    /**
     * Re-read this document's text with the AI (vision) engine. Shares [DetailUiState.reindexing]
     * with [reOcr]: to the user this is the same "the text is being redone" state, and both queue
     * the same document, so letting one run while the other is in flight would only mean two
     * requeues of one document.
     *
     * Runs whatever [DetailUiState.aiOcr] reports short of [AiOcrOffer.Unavailable] — warning
     * about a long document is the caller's job, not this one's. A rejected request still throws
     * and surfaces as a snackbar via [launchReporting] rather than failing silently.
     */
    fun ocrWithAi() =
        launchReporting(UiAction.AI_OCR) {
            _ui.value = _ui.value.copy(reindexing = true)
            try {
                ocr.ocrWithAi(docId)
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
