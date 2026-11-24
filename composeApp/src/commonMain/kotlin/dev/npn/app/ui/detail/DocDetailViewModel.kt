package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.PdfPrinter
import dev.npn.app.platform.PrintFailure
import dev.npn.app.platform.PrintOutcome
import dev.npn.app.platform.printPdf
import dev.npn.app.ui.common.MessageSink
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.colorsByName
import dev.npn.app.ui.common.newViewModelScope
import dev.npn.app.ui.common.queryTerms
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.BakeFailure
import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.RelocationOutcome
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TrashReceipt
import dev.npn.core.app.reusableFieldValues
import dev.npn.core.index.SplitFailure
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.aiConfigured
import dev.npn.core.util.NpnLog
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rerun_ocr_failed
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import no_papers_needed.composeapp.generated.resources.detail_add_field_failed
import no_papers_needed.composeapp.generated.resources.detail_add_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_failed
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_no_endpoint
import no_papers_needed.composeapp.generated.resources.detail_bake_failed
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_not_saved
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_bake_failed_unsupported
import no_papers_needed.composeapp.generated.resources.detail_baked_in_place
import no_papers_needed.composeapp.generated.resources.detail_could_not_bake_copy
import no_papers_needed.composeapp.generated.resources.detail_create_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_export_failed
import no_papers_needed.composeapp.generated.resources.detail_exported_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_moved_to_trash
import no_papers_needed.composeapp.generated.resources.detail_moved_to_trash_untitled
import no_papers_needed.composeapp.generated.resources.detail_ocr_details_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr_engine_failed
import no_papers_needed.composeapp.generated.resources.detail_print_cancelled
import no_papers_needed.composeapp.generated.resources.detail_print_failed
import no_papers_needed.composeapp.generated.resources.detail_print_failed_job
import no_papers_needed.composeapp.generated.resources.detail_print_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_print_failed_unreadable
import no_papers_needed.composeapp.generated.resources.detail_print_failed_unsupported
import no_papers_needed.composeapp.generated.resources.detail_print_started
import no_papers_needed.composeapp.generated.resources.detail_remove_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_rename_failed
import no_papers_needed.composeapp.generated.resources.detail_rename_invalid
import no_papers_needed.composeapp.generated.resources.detail_rename_taken
import no_papers_needed.composeapp.generated.resources.detail_rename_unsupported
import no_papers_needed.composeapp.generated.resources.detail_share_failed
import no_papers_needed.composeapp.generated.resources.detail_shared_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_split_failed
import no_papers_needed.composeapp.generated.resources.detail_split_failed_invalid_parts
import no_papers_needed.composeapp.generated.resources.detail_split_failed_not_completed
import no_papers_needed.composeapp.generated.resources.detail_split_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_split_failed_unreadable
import no_papers_needed.composeapp.generated.resources.detail_tag_removed
import no_papers_needed.composeapp.generated.resources.detail_trash_failed
import no_papers_needed.composeapp.generated.resources.library_undo
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
    /** A print job is being prepared or handed to the platform's print service. */
    val printing: Boolean = false,
    /** The document left the library from here (trashed, or split into new documents): the host closes. */
    val left: Boolean = false,
)

sealed interface HistoryUiState {
    data object NotLoaded : HistoryUiState

    data object Loading : HistoryUiState

    data object Empty : HistoryUiState

    /** The [items] this build can describe, and how many [newerChanges] a newer version of the app made. */
    data class Loaded(
        val items: List<HistoryItem>,
        val newerChanges: Int = 0,
    ) : HistoryUiState

    data class Error(
        val message: String,
    ) : HistoryUiState
}

/** Which OCR text a document has indexed: its OCR state, and the engine and writer of that text. */
private data class IndexedOcr(
    val state: OcrState,
    val engine: OcrEngineRef?,
    val source: String?,
)

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 *
 * Everything the metadata panel renders is assembled into the single [metadata] state; the flows
 * behind it are private, so a panel that needs a new value gets it from there rather than from a
 * new public flow wired through two call sites. The panel's immediate writes are the
 * [DocumentWrites] it implements, and the document actions its [DocumentActions]; field values and
 * the review flag go through the document's draft ([DocumentDrafts]), never through here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailViewModel private constructor(
    val docId: String,
    session: LibrarySession,
    private val settings: SettingsRepository,
    searchQuery: String?,
    initialItem: DocListItem?,
    scope: CoroutineScope,
    sink: MessageSink,
    initialPage: Int,
    /** Taken around every metadata write made from here, the AI suggestions' included. */
    private val metadataMutex: Mutex,
    /** Where a result that outlives this document's screen is reported. */
    notices: DocumentNotices?,
    private val aiSuggestions: DocAiSuggestions = DocAiSuggestions(docId, session, scope, sink, metadataMutex),
    /** How a print job reaches the platform; tests inject a recording fake. */
    private val printer: PdfPrinter = PdfPrinter { fileName, bytes, pageCount -> printPdf(fileName, bytes, pageCount) },
) : MessagesViewModel(scope, sink),
    DocumentWrites,
    AiSuggestionActions by aiSuggestions,
    DocumentActions {
    constructor(
        docId: String,
        session: LibrarySession,
        /** The device settings, followed for whether AI is configured. */
        settings: SettingsRepository,
        searchQuery: String? = null,
        /**
         * The document row a caller already has in hand (the library list feeds its preview panel
         * one). Seeds [metadata] so the panel renders immediately instead of blanking for the first
         * `observeDoc` query, which a caller that recreates this viewmodel per document would pay on
         * every switch. Null when the caller only knows the id.
         */
        initialItem: DocListItem? = null,
        /**
         * The page to show first: the one a restored process's view saved ([DocumentView]), as no
         * back-stack entry holds this viewmodel; 0 for a document opened afresh.
         */
        initialPage: Int = 0,
        /**
         * The library's [DocumentNotices], which show what outlives this document's screen (a trash
         * and its Undo) once the screen is gone. Null reports it through this viewmodel's own messages.
         */
        notices: DocumentNotices? = null,
        /** How a print job reaches the platform; tests inject a recording fake. */
        printer: PdfPrinter = PdfPrinter { fileName, bytes, pageCount -> printPdf(fileName, bytes, pageCount) },
    ) : this(
        docId,
        session,
        settings,
        searchQuery,
        initialItem,
        newViewModelScope(),
        MessageSink {
            session.log
        },
        initialPage,
        Mutex(),
        notices,
        printer = printer,
    )

    /** The library's notices when given one, else this viewmodel's own messages. */
    private val notices: DocumentNotices = notices ?: DocumentNotices(scope, sink)

    private val sessionLog: NpnLog = session.log

    /** Refused writes and print failures leave their technical cause here, not in the sentence. */
    override val log: NpnLog get() = sessionLog

    private val documents = session.documents
    private val meta = session.meta
    private val tagAdmin = session.tagAdmin
    private val docLifecycle = session.docLifecycle
    private val content = session.content
    private val ocr = session.ocr
    private val maintenance = session.maintenance
    private val fieldRegistry = session.fieldRegistry

    private val _ui = MutableStateFlow(DetailUiState(pageIndex = initialPage))
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    /**
     * Search terms from the library query that opened this document, used to highlight matches
     * in the OCR text tab.
     */
    private val highlightTerms: List<String> = searchQuery?.let { queryTerms(it) }.orEmpty()

    /**
     * The document's load lifecycle. Every [observeDoc] emission is an answer, a row
     * ([DocLoadState.Present]) or none ([DocLoadState.Missing]), so only the value before the first
     * one is [DocLoadState.Loading], and the screen never flashes "unavailable" while the query is
     * still in flight. A caller that handed us an [initialItem] starts resolved.
     */
    private val initialLoadState: DocLoadState = initialItem?.loadState() ?: DocLoadState.Loading

    private val loadState: StateFlow<DocLoadState> =
        documents
            .observeDoc(docId)
            .map { it.loadState() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, initialLoadState)

    private val doc: Flow<DocListItem?> = loadState.map { it.item }.distinctUntilChanged()

    private val reviewMode: StateFlow<ReviewMode> =
        session.reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    /** The registry in display order; the panel renders and edits fields from it. */
    private val fieldDefs: StateFlow<List<FieldDef>> =
        fieldRegistry.fields
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Other documents' values the editors offer for reuse; a per-document field offers none. */
    private val fieldValues: StateFlow<Map<String, List<String>>> =
        fieldRegistry
            .reusableFieldValues()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    private val tagColors: StateFlow<Map<String, String?>> =
        session.tags
            .observeTags()
            .map { it.colorsByName() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Keys documents carry without a definition, offered when the user defines a new field. */
    private val orphanKeys: StateFlow<List<String>> =
        fieldRegistry.orphanKeys().stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /**
     * What decides the indexed OCR text: the OCR state, and which stored version is indexed. Picking
     * another version keeps the state (a DONE document stays DONE) and changes only the engine, or
     * only the source when both versions come from one engine.
     */
    private val indexedOcr: Flow<IndexedOcr?> =
        doc
            .map { item -> item?.doc?.let { IndexedOcr(it.ocrState, it.ocrEngine, it.ocrSource) } }
            .distinctUntilChanged()

    /**
     * Extracted OCR/embedded text for this doc, reloaded whenever [indexedOcr] changes: once
     * background OCR completes, and when another stored version is picked. Null until loaded or
     * when there is no text.
     */
    private val ocrText: StateFlow<String?> =
        indexedOcr
            .map { content.extractedText(docId) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Stored OCR versions of this doc (best-ranked first), reloaded whenever [indexedOcr] changes
     * so the picker reflects freshly-adopted sidecars and re-selections.
     */
    private val ocrVersions: StateFlow<List<OcrVersion>> =
        indexedOcr
            .map {
                runCatchingCancellable { ocr.ocrVersions(docId) }
                    .onFailure(::reportOcrDetailsFailure)
                    .getOrDefault(emptyList())
            }.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

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

    /** Whether AI is configured in Settings, as it changes. */
    private val aiAvailable = MutableStateFlow(settings.settings.value.aiConfigured)

    private val document: Flow<DocumentSection> =
        combine(loadState, reviewMode, fieldDefs, ocrText, ocrVersions) { state, mode, defs, text, versions ->
            DocumentSection(
                loadState = state,
                reviewMode = mode,
                fieldDefs = defs,
                ocrText = text,
                ocrVersions = versions,
                highlightTerms = highlightTerms,
            )
        }

    private val vocabulary: Flow<VocabularyState> =
        combine(tagColors, fieldValues, orphanKeys) { colors, values, orphans ->
            VocabularyState(tagColors = colors, fieldValues = values, orphanKeys = orphans)
        }

    private val aiPanel: Flow<AiPanelState> =
        combine(aiAvailable, aiSuggestions.tags, aiSuggestions.metadata, ::AiPanelState)

    /**
     * The single state the metadata panel renders. Assembled here rather than in the composable
     * tree so both call sites (the detail screen and the library's side panel) show the same
     * thing without repeating the wiring — and so the panel's state is assertable in a viewmodel
     * test.
     */
    val metadata: StateFlow<MetadataUiState> =
        combine(document, vocabulary, aiPanel, history) { doc, vocab, ai, history ->
            MetadataUiState(document = doc, vocabulary = vocab, ai = ai, history = history)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, seedState())

    /**
     * What the panel shows before the first combined emission. Its [ReviewMode.DEFAULT] matches the
     * initial value of the review-mode flow, so the seeded reviewed badge does not flicker when
     * that emission arrives.
     */
    private fun seedState(): MetadataUiState =
        MetadataUiState(
            document =
                DocumentSection(
                    loadState = initialLoadState,
                    highlightTerms = highlightTerms,
                ),
            ai = AiPanelState(available = aiAvailable.value),
        )

    init {
        loadPageCount()
        // Follows Settings, so turning AI on or off there shows or hides the AI actions here at
        // once, without reopening the document. The assistant-reading offer depends on it too.
        viewModelScope.launch {
            settings.aiConfigured().collectLatest { configured ->
                aiAvailable.value = configured
                val offer =
                    runCatchingCancellable { ocr.aiOcrOffer(docId) }
                        .onFailure(::reportOcrDetailsFailure)
                        .getOrDefault(AiOcrOffer.Unavailable)
                _ui.update { it.copy(aiOcr = offer) }
            }
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

    fun goToPage(index: Int) =
        _ui.update { cur ->
            if (index in 0 until cur.pageCount.count.coerceAtLeast(1)) cur.copy(pageIndex = index) else cur
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
            HistoryMapper.toTimeline(documents.history(docId)) { key -> labels[key] ?: key }
        }.fold(
            onSuccess = { timeline ->
                if (timeline.isEmpty) {
                    HistoryUiState.Empty
                } else {
                    HistoryUiState.Loaded(
                        timeline.items,
                        timeline.newerChanges,
                    )
                }
            },
            onFailure = { error -> HistoryUiState.Error(error.reason()) },
        )

    override fun addTag(tag: String) =
        launchMetadata(Res.string.detail_add_tag_failed) { meta.addTag(listOf(docId), tag) }

    /** Create a brand-new tag on this doc, optionally with a color chosen inline in the editor. */
    override fun addTagWithStyle(
        tag: String,
        color: String?,
    ) = launchMetadata(Res.string.detail_create_tag_failed) {
        meta.addTag(listOf(docId), tag)
        if (color != null) tagAdmin.setTagStyle(tag, color, null)
    }

    override fun removeTag(tag: String) {
        viewModelScope.launch {
            val removed =
                reporting(Res.string.detail_remove_tag_failed) {
                    metadataMutex.withLock { meta.removeTag(listOf(docId), tag) }
                }
            if (removed) {
                message(
                    uiText(Res.string.detail_tag_removed, tag),
                    UiMessage.Action(uiText(Res.string.library_undo)) { addTag(tag) },
                )
            }
        }
    }

    /**
     * Register [def] and, when [value] is non-blank, set it on this document. The definition goes
     * first so the value is canonicalized against the new type rather than as plain text.
     */
    override fun defineField(
        def: FieldDef,
        value: String?,
    ) = launchMetadata(Res.string.detail_add_field_failed) {
        fieldRegistry.upsert(def)
        if (!value.isNullOrBlank()) meta.setField(docId, def.key, value)
    }

    private fun launchMetadata(
        failed: StringResource,
        block: suspend () -> Unit,
    ) = launchReporting(failed) { metadataMutex.withLock { block() } }

    /**
     * On success the document has [DetailUiState.left], and [notices] say so with an Undo that takes
     * back exactly this trash, from wherever the user is by then; a failure keeps it and is reported.
     */
    override fun trash() =
        launchReporting(Res.string.detail_trash_failed) {
            val title =
                loadState.value.item
                    ?.doc
                    ?.displayTitle
            val receipt = docLifecycle.trashDoc(docId)
            _ui.update { it.copy(left = true) }
            // No receipt: the document was already gone, and restoring it would undo that instead.
            if (receipt != null) {
                notices.message(
                    title?.let { uiText(Res.string.detail_moved_to_trash, it) }
                        ?: uiText(Res.string.detail_moved_to_trash_untitled),
                    UiMessage.Action(uiText(Res.string.library_undo)) { undoTrash(receipt) },
                )
            }
        }

    /** The trash message's Undo: restore the document while [receipt]'s trash still holds it. */
    private fun undoTrash(receipt: TrashReceipt) {
        notices.launchReporting(Res.string.common_trash_restore_failed) {
            // A failure counted rather than thrown has its cause in the session log.
            if (docLifecycle.undoTrash(listOf(receipt)) > 0) {
                notices.message(uiText(Res.plurals.common_restore_documents_failed, 1, 1, 1))
            }
        }
    }

    /**
     * The host closed after [DetailUiState.left]. This viewmodel can outlive the host's composition
     * (a recreated window, a change of language), so a later showing of it must not close at once.
     */
    fun closed() = _ui.update { it.copy(left = false) }

    /** Rename this document's file; a rename that cannot be carried out says why. */
    override fun rename(newFileName: String) =
        launchReporting(Res.string.detail_rename_failed) {
            val refusal =
                when (docLifecycle.renameFile(docId, newFileName)) {
                    RelocationOutcome.MOVED, RelocationOutcome.UNCHANGED -> null
                    RelocationOutcome.UNSUPPORTED -> Res.string.detail_rename_unsupported
                    RelocationOutcome.TAKEN -> Res.string.detail_rename_taken
                    RelocationOutcome.INVALID_NAME -> Res.string.detail_rename_invalid
                }
            refusal?.let { message(uiText(it)) }
        }

    override fun reOcr() =
        launchReporting(Res.string.common_rerun_ocr_failed) { busy({ copy(reindexing = it) }) { ocr.reOcr(docId) } }

    /** Run [block] with the busy flag [flag] sets on [DetailUiState] raised, lowering it however it ends. */
    private suspend fun <T> busy(
        flag: DetailUiState.(Boolean) -> DetailUiState,
        block: suspend () -> T,
    ): T {
        _ui.update { it.flag(true) }
        try {
            return block()
        } finally {
            _ui.update { it.flag(false) }
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
    override fun ocrWithAi() =
        launchReporting(Res.string.detail_ai_ocr_failed) {
            val outcome = busy({ copy(reindexing = it) }) { ocr.ocrWithAi(docId) }
            if (outcome == AiOcrOutcome.NO_ENDPOINT) message(uiText(Res.string.detail_ai_ocr_no_endpoint))
        }

    /** Pin [engine] as the searchable OCR version (null = automatic best-engine selection). */
    override fun setOcrEngine(engine: OcrEngineRef?) =
        launchReporting(Res.string.detail_ocr_engine_failed) { ocr.setOcrEngine(docId, engine) }

    /**
     * Split into one document per [segments] entry. On success the original is trashed by the
     * session and the document has [DetailUiState.left]; a failure is reported as a message.
     */
    override fun split(segments: List<IntRange>) =
        launchReporting(Res.string.detail_split_failed) {
            when (val outcome = busy({ copy(splitting = it) }) { maintenance.splitPdf(docId, segments) }) {
                is SplitOutcome.Done -> _ui.update { it.copy(left = true) }
                is SplitOutcome.Failed -> message(outcome.reason.text())
            }
        }

    /** Rewrite the library file with this document's metadata, and say how that went. */
    override fun bakeInPlace() =
        launchReporting(Res.string.detail_bake_failed) {
            val outcome = busy({ copy(baking = it) }) { content.bakeInPlace(docId) }
            message(
                when (outcome) {
                    BakeOutcome.Baked -> uiText(Res.string.detail_baked_in_place)
                    is BakeOutcome.Failed -> outcome.reason.text()
                },
            )
        }

    /** Write a copy carrying this document's metadata through [write] (a file the user picked). */
    override fun exportBakedCopy(write: suspend (ByteArray) -> Unit) =
        deliverBakedCopy(
            write,
            delivered = Res.string.detail_exported_baked_copy,
            failed = Res.string.detail_export_failed,
        )

    /** Hand a copy carrying this document's metadata to the platform's share sheet through [share]. */
    override fun shareBakedCopy(share: suspend (ByteArray) -> Unit) =
        deliverBakedCopy(
            share,
            delivered = Res.string.detail_shared_baked_copy,
            failed = Res.string.detail_share_failed,
        )

    private fun deliverBakedCopy(
        deliver: suspend (ByteArray) -> Unit,
        delivered: StringResource,
        failed: StringResource,
    ) = launchReporting(failed) {
        val bytes = content.bakedCopyBytes(docId)
        if (bytes == null) {
            message(uiText(Res.string.detail_could_not_bake_copy))
        } else {
            deliver(bytes)
            message(uiText(delivered))
        }
    }

    private val printMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Send this document's library bytes to the platform's print service: no copy is baked and the
     * file is untouched. A document whose bytes cannot be read, or that is not a PDF, says so rather
     * than opening an empty print dialog; the menu disables the action in that case.
     */
    override fun printDocument() =
        launchReporting(Res.string.detail_print_failed) {
            if (!printMutex.tryLock()) return@launchReporting
            try {
                busy({ copy(printing = it) }) {
                    val item = loadState.value.item
                    if (item == null || !item.doc.isPrintable) {
                        message(uiText(Res.string.detail_print_failed_unavailable))
                        return@busy
                    }
                    val bytes = content.documentBytes(docId)
                    if (bytes == null) {
                        message(uiText(Res.string.detail_print_failed_unavailable))
                        return@busy
                    }
                    val pageCount =
                        _ui.value.pageCount.count
                            .takeIf { it > 0 } ?: item.doc.pageCount
                    val outcome = printer.print(item.doc.fileName, bytes, pageCount)
                    when (outcome) {
                        PrintOutcome.Started -> {
                            message(uiText(Res.string.detail_print_started))
                        }

                        PrintOutcome.Cancelled -> {
                            message(uiText(Res.string.detail_print_cancelled))
                        }

                        is PrintOutcome.Failed -> {
                            outcome.cause?.let { log.warn("Printing $docId failed", it) }
                            message(outcome.reason.text())
                        }
                    }
                }
            } finally {
                printMutex.unlock()
            }
        }

    suspend fun renderPage(
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = content.renderPage(docId, pageIndex, maxDim)
}

/** Missing files and bake predecessors still carry editable metadata; lifecycle tombstones do not. */
private fun DocListItem?.loadState(): DocLoadState =
    this?.takeUnless { it.doc.trashed || it.doc.deleted }?.let(DocLoadState::Present) ?: DocLoadState.Missing

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

private fun PrintFailure.text(): UiText =
    when (this) {
        PrintFailure.UNSUPPORTED -> uiText(Res.string.detail_print_failed_unsupported)
        PrintFailure.UNREADABLE_PDF -> uiText(Res.string.detail_print_failed_unreadable)
        PrintFailure.PRINT_FAILED -> uiText(Res.string.detail_print_failed_job)
    }
