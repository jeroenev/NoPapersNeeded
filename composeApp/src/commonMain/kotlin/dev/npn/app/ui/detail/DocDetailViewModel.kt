package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.queryTerms
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.textFieldValues
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.library.effectiveReviewed
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.BakeFailure
import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.MaintenanceFacade
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SplitFailure
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TagAdmin
import dev.npn.core.app.TagRepository
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_no_endpoint
import no_papers_needed.composeapp.generated.resources.detail_bake_failed
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_not_saved
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_unsupported
import no_papers_needed.composeapp.generated.resources.detail_baked_in_place
import no_papers_needed.composeapp.generated.resources.detail_could_not_bake_copy
import no_papers_needed.composeapp.generated.resources.detail_export_failed
import no_papers_needed.composeapp.generated.resources.detail_exported_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_share_failed
import no_papers_needed.composeapp.generated.resources.detail_shared_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_ocr_details_failed
import no_papers_needed.composeapp.generated.resources.detail_split_failed
import no_papers_needed.composeapp.generated.resources.detail_split_failed_invalid_parts
import no_papers_needed.composeapp.generated.resources.detail_split_failed_not_completed
import no_papers_needed.composeapp.generated.resources.detail_split_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_split_failed_unreadable
import org.jetbrains.compose.resources.StringResource

/**
 * Transient UI state for the detail viewer (page index, page count, busy flags). Zoom/pan are
 * owned by the viewer itself (see [dev.npn.app.ui.detail.ViewerTransform]) since they need not
 * survive process death.
 */
data class DetailUiState(
    val pageIndex: Int = 0,
    val pageCount: PageCountState = PageCountState.Loading,
    val baking: Boolean = false,
    val reindexing: Boolean = false,
    val splitting: Boolean = false,
    /**
     * Whether to offer "read text with assistant" for this document, and whether to warn about
     * its length first. See [AiOcrOffer].
     */
    val aiOcr: AiOcrOffer = AiOcrOffer.Unavailable,
)

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
    val suggestion: FieldSuggestion = FieldSuggestion.EMPTY,
)

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 *
 * Everything the metadata panel renders is assembled into the single [metadata] state; the flows
 * behind it are private, so a panel that needs a new value gets it from there rather than from a
 * new public flow wired through two call sites. The panel's immediate writes are the
 * [DocumentWrites] it implements; field values and the review flag go through the document's draft
 * ([DocumentDrafts]), never through here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val fieldRegistry: FieldRegistry,
    searchQuery: String? = null,
    /**
     * The document row a caller already has in hand (the library list feeds its preview panel one).
     * Seeds [metadata] so the panel renders immediately instead of blanking for the first
     * `observeDoc` query, which a caller that recreates this viewmodel per document would pay on
     * every switch. Null when the caller only knows the id.
     */
    initialItem: DocListItem? = null,
) : MessagesViewModel(),
    DocumentWrites {
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
        fieldRegistry = session.fieldRegistry,
        searchQuery = searchQuery,
        initialItem = initialItem,
    )

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()
    private val metadataMutex = Mutex()

    /**
     * Search terms from the library query that opened this document, used to highlight matches
     * in the OCR text tab.
     */
    private val highlightTerms: List<String> = searchQuery?.let { queryTerms(it) }.orEmpty()

    /**
     * The document's load lifecycle. [observeDoc] emits null both before the first query and when
     * the document is gone; [onEach] flips [docObserved] on the first emission so the fold can tell
     * a not-yet-resolved null (→ [DocLoadState.Loading]) from a confirmed absence (→
     * [DocLoadState.Missing]). A caller that handed us an [initialItem] starts resolved.
     */
    private var docObserved: Boolean = initialItem != null

    private val doc: StateFlow<DocListItem?> =
        documents
            .observeDoc(docId)
            .onEach { docObserved = true }
            .stateIn(viewModelScope, WhileSubscribedTimeout, initialItem)

    private val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    /** The registry in display order; the panel renders and edits fields from it. */
    private val fieldDefs: StateFlow<List<FieldDef>> =
        fieldRegistry.fields
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Existing values of every TEXT field, for autocomplete. */
    private val fieldValues: StateFlow<Map<String, List<String>>> =
        fieldRegistry
            .textFieldValues()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    private val tagColors: StateFlow<Map<String, String?>> =
        tagRepository
            .observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Keys documents carry without a definition, offered when the user defines a new field. */
    private val orphanKeys: StateFlow<List<String>> =
        fieldRegistry
            .orphanKeys()
            .map { keys -> keys.map { it.fieldKey } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /**
     * Extracted OCR/embedded text for this doc, reloaded when the doc's OCR state changes (so it
     * appears once background OCR completes). Null until loaded or when there is no text.
     */
    private val ocrText: StateFlow<String?> =
        doc
            .map { it?.doc?.ocrState }
            .distinctUntilChanged()
            .map { content.extractedText(docId) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Stored OCR versions of this doc (best-ranked first), reloaded whenever the indexed OCR
     * state or engine changes so the picker reflects freshly-adopted sidecars and re-selections.
     */
    private val ocrVersions: StateFlow<List<OcrVersion>> =
        doc
            .map { it?.doc?.ocrState to it?.doc?.ocrEngine }
            .distinctUntilChanged()
            .map {
                runCatchingCancellable { ocr.ocrVersions(docId) }
                    .onFailure(::reportOcrDetailsFailure)
                    .getOrDefault(emptyList())
            }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** 0 until the History tab first asks; every explicit refresh then counts up. */
    private val historyRequests = MutableStateFlow(0)

    /**
     * The document's journal timeline, read once the History tab asks for it and read again
     * whenever the document row changes, so an edit made on this screen (or synced in) shows up
     * without a manual refresh. Only an explicit request shows [HistoryUiState.Loading]; a reload
     * behind a change keeps the old timeline on screen until the new one is in.
     */
    private val history: Flow<HistoryUiState> =
        historyRequests.flatMapLatest { request ->
            if (request == 0) {
                flowOf(HistoryUiState.NotLoaded)
            } else {
                doc.mapLatest { readHistory() }.onStart { emit(HistoryUiState.Loading) }
            }
        }

    private val aiTags = MutableStateFlow(AiTagState())

    private val aiMetadata = MutableStateFlow(AiMetadataState())

    private val document: Flow<DocumentSection> =
        combine(doc, reviewMode, fieldDefs, ocrText, ocrVersions) { item, mode, defs, text, versions ->
            DocumentSection(
                item = item,
                loadState = loadStateOf(item),
                reviewed = item?.effectiveReviewed(mode) == true,
                reviewMode = mode,
                fieldDefs = defs,
                ocrText = text,
                ocrVersions = versions,
                highlightTerms = highlightTerms,
            )
        }

    /**
     * The row's load state from the latest [observeDoc] emission: a row present is [Present], a
     * null after the first emission is [Missing], and a null before any emission stays [Loading]
     * so the screen never flashes "unavailable" while the query is still in flight.
     */
    private fun loadStateOf(item: DocListItem?): DocLoadState =
        when {
            item != null -> DocLoadState.Present(item)
            docObserved -> DocLoadState.Missing
            else -> DocLoadState.Loading
        }

    private val vocabulary: Flow<VocabularyState> =
        combine(tagColors, fieldValues, orphanKeys) { colors, values, orphans ->
            VocabularyState(tagColors = colors, fieldValues = values, orphanKeys = orphans)
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
        combine(document, vocabulary, aiPanel, history) { doc, vocab, ai, history ->
            MetadataUiState(document = doc, vocabulary = vocab, ai = ai, history = history)
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
                    loadState = loadStateOf(item),
                    reviewed = item?.effectiveReviewed(ReviewMode.DEFAULT) == true,
                    highlightTerms = highlightTerms,
                ),
        )

    init {
        loadPageCount()
        viewModelScope.launch {
            val configured = ai.isConfiguredOrFalse()
            aiTags.update { it.copy(available = configured) }
        }
        viewModelScope.launch {
            val offer =
                runCatchingCancellable { ocr.aiOcrOffer(docId) }
                    .onFailure(::reportOcrDetailsFailure)
                    .getOrDefault(AiOcrOffer.Unavailable)
            _ui.update { it.copy(aiOcr = offer) }
        }
    }

    /** The OCR versions and the assistant-reading offer fall back to nothing, and say why. */
    private fun reportOcrDetailsFailure(failure: Throwable) =
        message(uiText(Res.string.detail_ocr_details_failed, failure.reasonText()))

    /**
     * Resolve the page count. A null result (unreadable/corrupt file, or a missing row) is a
     * failure the viewer offers to retry, not a silent zero that spins forever; 0 pages is [Empty].
     */
    private fun loadPageCount() {
        _ui.update { it.copy(pageCount = PageCountState.Loading) }
        viewModelScope.launch {
            val count = content.pageCount(docId)
            _ui.update { it.copy(pageCount = pageCountStateOf(count)) }
        }
    }

    /** Re-attempt a failed page-count resolution (the viewer's retry action). */
    fun retryPageCount() = loadPageCount()

    fun goToPage(index: Int) {
        _ui.update { cur ->
            if (index in 0 until cur.pageCount.count.coerceAtLeast(1)) cur.copy(pageIndex = index) else cur
        }
    }

    /**
     * Start showing the history (the tab was opened); a no-op once it is showing, since it then
     * follows the document by itself. Pass [refresh] for an explicit retry or refresh.
     */
    override fun loadHistory(refresh: Boolean) {
        historyRequests.update { if (refresh || it == 0) it + 1 else it }
    }

    private suspend fun readHistory(): HistoryUiState =
        runCatchingCancellable {
            val labels = fieldRegistry.current().associate { it.key to it.label }
            HistoryMapper.toHistoryItems(documents.history(docId)) { key -> labels[key] ?: key }
        }.fold(
            onSuccess = { items -> if (items.isEmpty()) HistoryUiState.Empty else HistoryUiState.Loaded(items) },
            onFailure = { error -> HistoryUiState.Error(error.message.orEmpty()) },
        )

    override fun addTag(tag: String) = launchMetadata(UiAction.ADD_TAG) { meta.addTag(listOf(docId), tag) }

    /** Create a brand-new tag on this doc, optionally with a color chosen inline in the editor. */
    override fun addTagWithStyle(
        tag: String,
        color: String?,
    ) = launchMetadata(UiAction.CREATE_TAG) {
        meta.addTag(listOf(docId), tag)
        if (color != null) tagAdmin.setTagStyle(tag, color, null)
    }

    override fun removeTag(tag: String) = launchMetadata(UiAction.REMOVE_TAG) { meta.removeTag(listOf(docId), tag) }

    /**
     * Ask the AI endpoint for new-tag suggestions from this doc's indexed text. No-op while a
     * request is already in flight. Empty results surface an informational message (the document
     * likely has no OCR text yet); failures surface the error via [messages].
     */
    override fun suggestAiTags() {
        if (aiTags.value.suggesting) return
        aiTags.update { it.copy(suggesting = true) }
        viewModelScope.launch {
            runCatchingCancellable { ai.suggestEdits(docId, AiEditRequest(tags = true)).addTags }.fold(
                onSuccess = { suggestions ->
                    if (suggestions.isEmpty()) {
                        message(uiText(UiTextKey.NO_AI_TAG_SUGGESTIONS))
                    }
                    aiTags.update { it.copy(suggesting = false, suggestions = suggestions) }
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_TAGS, error.reasonText()))
                    aiTags.update { it.copy(suggesting = false, suggestions = emptyList()) }
                },
            )
        }
    }

    /** Accept a suggestion: add it as a real tag (normal journal path) and drop it from the list. */
    override fun acceptAiTag(tag: String) =
        launchMetadata(UiAction.ADD_TAG) {
            meta.addTag(listOf(docId), tag)
            dismissAiTag(tag)
        }

    override fun dismissAiTag(tag: String) {
        aiTags.update { state ->
            state.copy(suggestions = state.suggestions.filterNot { it.tag == tag })
        }
    }

    override fun clearAiTagSuggestions() {
        aiTags.update { it.copy(suggestions = emptyList()) }
    }

    /**
     * Ask the AI endpoint for a value for every aiEnabled field from this doc's indexed text.
     * No-op while a request is already in flight. Empty results surface an informational message
     * (no OCR text yet, or nothing beyond the current values); failures surface via [messages].
     */
    override fun suggestAiMetadata() {
        if (aiMetadata.value.suggesting) return
        aiMetadata.update { it.copy(suggesting = true) }
        viewModelScope.launch {
            // Every field the user marked for AI, in ALL mode: this is an explicit request to
            // second-guess what is there, unlike the automatic pass which only fills gaps.
            val request =
                AiEditRequest(
                    fields =
                        fieldRegistry.current().filter { it.aiEnabled }.map {
                            AiFieldConfig(
                                it.key,
                                AiFieldMode.ALL,
                            )
                        },
                )
            runCatchingCancellable { ai.suggestEdits(docId, request).metadata }.fold(
                onSuccess = { suggestion ->
                    if (suggestion.isEmpty) {
                        message(uiText(UiTextKey.NO_AI_METADATA_SUGGESTIONS))
                    }
                    aiMetadata.value = AiMetadataState(suggesting = false, suggestion = suggestion)
                },
                onFailure = { error ->
                    message(uiText(UiTextKey.COULD_NOT_ACTION, UiAction.SUGGEST_METADATA, error.reasonText()))
                    aiMetadata.value = AiMetadataState()
                },
            )
        }
    }

    /** Accept one field's suggestion: write it via the normal journal path and drop it. */
    override fun acceptAiMetadata(key: String) {
        aiMetadata.value.suggestion[key]?.let { setField(key, it) }
        dismissAiMetadata(key)
    }

    override fun dismissAiMetadata(key: String) {
        aiMetadata.update { it.copy(suggestion = it.suggestion.without(key)) }
    }

    fun setField(
        key: String,
        value: String?,
    ) = launchMetadata(UiAction.SAVE_FIELD) { meta.setField(docId, key, value) }

    /**
     * Register [def] and, when [value] is non-blank, set it on this document. The definition goes
     * first so the value is canonicalized against the new type rather than as plain text.
     */
    override fun defineField(
        def: FieldDef,
        value: String?,
    ) = launchMetadata(UiAction.DEFINE_FIELD) {
        fieldRegistry.upsert(def)
        if (!value.isNullOrBlank()) meta.setField(docId, def.key, value)
    }

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
            _ui.update { it.copy(reindexing = true) }
            try {
                ocr.reOcr(docId)
            } finally {
                _ui.update { it.copy(reindexing = false) }
            }
        }

    /**
     * Re-read this document's text with the AI (vision) engine. Shares [DetailUiState.reindexing]
     * with [reOcr]: to the user this is the same "the text is being redone" state, and both queue
     * the same document, so letting one run while the other is in flight would only mean two
     * requeues of one document.
     *
     * Runs whatever [DetailUiState.aiOcr] reports short of [AiOcrOffer.Unavailable] — warning
     * about a long document is the caller's job, not this one's. A request refused for want of an
     * endpoint (it went away after the action was offered) says so rather than doing nothing.
     */
    fun ocrWithAi() =
        launchReporting(UiAction.AI_OCR) {
            _ui.update { it.copy(reindexing = true) }
            val outcome =
                try {
                    ocr.ocrWithAi(docId)
                } finally {
                    _ui.update { it.copy(reindexing = false) }
                }
            if (outcome == AiOcrOutcome.NO_ENDPOINT) message(uiText(Res.string.detail_ai_ocr_no_endpoint))
        }

    /** Pin [engine] as the searchable OCR version (null = automatic best-engine selection). */
    override fun setOcrEngine(engine: String?) =
        launchReporting(UiAction.CHANGE_OCR_ENGINE) { ocr.setOcrEngine(docId, engine) }

    /**
     * Split into one document per [segments] entry. On success the original is trashed by the
     * session and [onDone] runs, so the screen can leave; a failure is reported as a message.
     */
    fun split(
        segments: List<IntRange>,
        onDone: () -> Unit,
    ) = launchReporting({ reason -> uiText(Res.string.detail_split_failed, reason) }) {
        _ui.update { it.copy(splitting = true) }
        val outcome =
            try {
                maintenance.splitPdf(docId, segments)
            } finally {
                _ui.update { it.copy(splitting = false) }
            }
        when (outcome) {
            is SplitOutcome.Done -> onDone()
            is SplitOutcome.Failed -> message(outcome.reason.text())
        }
    }

    /** Rewrite the library file with this document's metadata, and say how that went. */
    fun bakeInPlace() =
        launchReporting({ reason -> uiText(Res.string.detail_bake_failed, reason) }) {
            _ui.update { it.copy(baking = true) }
            val outcome =
                try {
                    content.bakeInPlace(docId)
                } finally {
                    _ui.update { it.copy(baking = false) }
                }
            message(
                when (outcome) {
                    BakeOutcome.Baked -> uiText(Res.string.detail_baked_in_place)
                    is BakeOutcome.Failed -> outcome.reason.text()
                },
            )
        }

    /** Write a copy carrying this document's metadata through [write] (a file the user picked). */
    fun exportBakedCopy(write: suspend (ByteArray) -> Unit) =
        deliverBakedCopy(
            write,
            delivered = Res.string.detail_exported_baked_copy,
            failed = Res.string.detail_export_failed,
        )

    /** Hand a copy carrying this document's metadata to the platform's share sheet through [share]. */
    fun shareBakedCopy(share: suspend (ByteArray) -> Unit) =
        deliverBakedCopy(
            share,
            delivered = Res.string.detail_shared_baked_copy,
            failed = Res.string.detail_share_failed,
        )

    private fun deliverBakedCopy(
        deliver: suspend (ByteArray) -> Unit,
        delivered: StringResource,
        failed: StringResource,
    ) = launchReporting({ reason -> uiText(failed, reason) }) {
        val bytes = content.bakedCopyBytes(docId)
        if (bytes == null) {
            message(uiText(Res.string.detail_could_not_bake_copy))
        } else {
            deliver(bytes)
            message(uiText(delivered))
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = content.renderPage(docId, pageIndex, maxDim)
}

private fun BakeFailure.text(): UiText =
    when (this) {
        BakeFailure.DOCUMENT_UNAVAILABLE -> uiText(Res.string.detail_bake_failed_unavailable)
        BakeFailure.UNSUPPORTED_PDF -> uiText(Res.string.detail_bake_failed_unsupported)
        BakeFailure.NOT_SAVED -> uiText(Res.string.detail_bake_failed_not_saved)
    }

private fun SplitFailure.text(): UiText =
    when (this) {
        SplitFailure.DOCUMENT_UNAVAILABLE -> uiText(Res.string.detail_split_failed_unavailable)
        SplitFailure.INVALID_PARTS -> uiText(Res.string.detail_split_failed_invalid_parts)
        SplitFailure.UNREADABLE_PDF -> uiText(Res.string.detail_split_failed_unreadable)
        SplitFailure.NOT_COMPLETED -> uiText(Res.string.detail_split_failed_not_completed)
    }
