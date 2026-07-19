package dev.npn.app.ui.detail

import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SplitOutcome
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.MutableStateFlow
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
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

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 */
class DocDetailViewModel(
    val docId: String,
    val session: LibrarySession,
) : MessagesViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()
    private val metadataMutex = Mutex()

    val doc: StateFlow<DocListItem?> =
        session.documents.observeDoc(docId)
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val reviewMode: StateFlow<ReviewMode> =
        session.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    val correspondents: StateFlow<List<String>> =
        session.documents.observeCorrespondents()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val docTypes: StateFlow<List<String>> =
        session.documents.observeDocTypes()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val tagColors: StateFlow<Map<String, String?>> =
        session.tags.observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** Distinct custom-field keys in the library, for add-field key autocomplete. */
    val fieldKeys: StateFlow<List<String>> =
        session.documents.observeFieldKeys()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /**
     * Extracted OCR/embedded text for this doc, reloaded when the doc's OCR state changes (so it
     * appears once background OCR completes). Null until loaded or when there is no text.
     */
    val ocrText: StateFlow<String?> =
        doc.map { it?.doc?.ocrState }
            .distinctUntilChanged()
            .map { runCatching { session.content.extractedText(docId) }.getOrNull() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    init {
        viewModelScope.launch {
            val count = runCatching { session.content.pageCount(docId) }.getOrNull() ?: 0
            _ui.value = _ui.value.copy(pageCount = count)
        }
    }

    // ---- viewer controls ----

    fun goToPage(index: Int) {
        val cur = _ui.value
        if (index in 0 until cur.pageCount.coerceAtLeast(1)) _ui.value = cur.copy(pageIndex = index)
    }

    // ---- metadata edits ----

    fun setTitle(title: String?) =
        launchMetadata("save title") { session.meta.setTitle(docId, title?.takeIf { it.isNotBlank() }) }
    fun setCorrespondent(value: String?) =
        launchMetadata("save correspondent") {
            session.meta.setCorrespondent(listOf(docId), value?.takeIf { it.isNotBlank() })
        }
    fun setDocType(value: String?) =
        launchMetadata("save document type") {
            session.meta.setDocType(listOf(docId), value?.takeIf { it.isNotBlank() })
        }
    fun setDocDate(isoDate: String?) =
        launchMetadata("save date") { session.meta.setDocDate(docId, isoDate?.takeIf { it.isNotBlank() }) }

    fun addTag(tag: String) = launchMetadata("add tag") { session.meta.addTag(listOf(docId), tag) }
    fun removeTag(tag: String) = launchMetadata("remove tag") { session.meta.removeTag(listOf(docId), tag) }

    fun setField(key: String, value: String?) =
        launchMetadata("save field") { session.meta.setField(docId, key, value) }
    fun removeField(key: String) = launchMetadata("remove field") { session.meta.setField(docId, key, null) }

    fun setReviewed(reviewed: Boolean) =
        launchMetadata("update review state") { session.meta.setReviewed(listOf(docId), reviewed) }

    private fun launchMetadata(action: String, block: suspend () -> Unit) =
        launchReporting(action) { metadataMutex.withLock { block() } }

    /** On failure [onDone] is not invoked (the screen stays put) and the error is surfaced. */
    fun trash(onDone: () -> Unit) = launchReporting("move to trash") {
        session.meta.trashDoc(docId)
        onDone()
    }

    fun rename(newFileName: String) =
        launchReporting("rename file") { session.meta.renameFile(docId, newFileName) }

    fun reOcr() = launchReporting("re-run OCR") {
        _ui.value = _ui.value.copy(reindexing = true)
        try {
            session.reOcr(docId)
        } finally {
            _ui.value = _ui.value.copy(reindexing = false)
        }
    }

    /**
     * Split into one document per [segments] entry. On success the original is trashed by the
     * session, so the screen should navigate back; failures surface via [onResult]'s message.
     */
    fun split(segments: List<IntRange>, onResult: (SplitOutcome) -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(splitting = true)
            val outcome = runCatching { session.splitPdf(docId, segments) }
                .getOrElse { SplitOutcome.Failed(it.message ?: "split failed") }
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

    suspend fun renderPage(pageIndex: Int, maxDim: Int): ByteArray? =
        runCatching { session.content.renderPage(docId, pageIndex, maxDim) }.getOrNull()
}
