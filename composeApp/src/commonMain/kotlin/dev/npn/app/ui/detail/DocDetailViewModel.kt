package dev.npn.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.MutableStateFlow
import dev.npn.app.ui.common.WhileSubscribedTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Transient UI state for the detail viewer (page index, zoom, busy flags). */
data class DetailUiState(
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val zoom: Float = 1f,
    val baking: Boolean = false,
    val reindexing: Boolean = false,
)

/**
 * View model for a single document. The document row and its tags come from the session's
 * reactive [dev.npn.core.app.DocumentRepository.observeDoc] flow so external edits (journal
 * folds, OCR completion) refresh the screen. Page count is resolved once via the content facade.
 */
class DocDetailViewModel(
    val docId: String,
    val session: LibrarySession,
) : ViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

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

    init {
        viewModelScope.launch {
            val count = runCatching { session.content.pageCount(docId) }.getOrNull() ?: 0
            _ui.value = _ui.value.copy(pageCount = count)
        }
    }

    // ---- viewer controls ----

    fun nextPage() {
        val cur = _ui.value
        if (cur.pageIndex < cur.pageCount - 1) _ui.value = cur.copy(pageIndex = cur.pageIndex + 1)
    }

    fun prevPage() {
        val cur = _ui.value
        if (cur.pageIndex > 0) _ui.value = cur.copy(pageIndex = cur.pageIndex - 1)
    }

    fun goToPage(index: Int) {
        val cur = _ui.value
        if (index in 0 until cur.pageCount.coerceAtLeast(1)) _ui.value = cur.copy(pageIndex = index)
    }

    fun setZoom(zoom: Float) {
        _ui.value = _ui.value.copy(zoom = zoom.coerceIn(1f, 5f))
    }

    fun zoomIn() = setZoom(_ui.value.zoom + 0.5f)
    fun zoomOut() = setZoom(_ui.value.zoom - 0.5f)
    fun resetZoom() = setZoom(1f)

    // ---- metadata edits ----

    fun setTitle(title: String?) = launch { session.meta.setTitle(docId, title?.takeIf { it.isNotBlank() }) }
    fun setCorrespondent(value: String?) =
        launch { session.meta.setCorrespondent(listOf(docId), value?.takeIf { it.isNotBlank() }) }
    fun setDocType(value: String?) =
        launch { session.meta.setDocType(listOf(docId), value?.takeIf { it.isNotBlank() }) }
    fun setDocDate(isoDate: String?) = launch { session.meta.setDocDate(docId, isoDate?.takeIf { it.isNotBlank() }) }

    fun addTag(tag: String) = launch { session.meta.addTag(listOf(docId), tag) }
    fun removeTag(tag: String) = launch { session.meta.removeTag(listOf(docId), tag) }

    fun setReviewed(reviewed: Boolean) = launch { session.meta.setReviewed(listOf(docId), reviewed) }
    fun markReviewed() = setReviewed(true)
    fun markUnreviewed() = setReviewed(false)

    fun trash(onDone: () -> Unit) = launch {
        session.meta.trashDoc(docId)
        onDone()
    }

    fun rename(newFileName: String) = launch { session.meta.renameFile(docId, newFileName) }

    fun reOcr() = launch {
        _ui.value = _ui.value.copy(reindexing = true)
        runCatching { session.reOcr(docId) }
        _ui.value = _ui.value.copy(reindexing = false)
    }

    fun bakeInPlace(onResult: (Boolean) -> Unit) = launch {
        _ui.value = _ui.value.copy(baking = true)
        val ok = runCatching { session.content.bakeInPlace(docId) }.getOrDefault(false)
        _ui.value = _ui.value.copy(baking = false)
        onResult(ok)
    }

    suspend fun bakedCopyBytes(): ByteArray? = runCatching { session.content.bakedCopyBytes(docId) }.getOrNull()

    suspend fun allTagNames(): List<String> = session.tags.allTagNames()

    suspend fun renderPage(pageIndex: Int, maxDim: Int): ByteArray? =
        runCatching { session.content.renderPage(docId, pageIndex, maxDim) }.getOrNull()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}
