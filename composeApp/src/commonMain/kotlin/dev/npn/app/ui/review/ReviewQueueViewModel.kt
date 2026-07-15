package dev.npn.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SortField
import dev.npn.core.model.DocStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Review queue: documents still in the INBOX status, oldest first, for quick triage. Correspondent
 * and document type suggestions come from existing values in the library.
 */
class ReviewQueueViewModel(
    val session: LibrarySession,
) : ViewModel() {

    private val filter = LibraryFilter(status = DocStatus.INBOX)
    private val sort = LibrarySort(field = SortField.ADDED, ascending = true)

    val docs: StateFlow<List<DocListItem>> =
        session.documents.observeDocuments(filter, sort)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val correspondents: StateFlow<List<String>> =
        session.documents.observeCorrespondents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val docTypes: StateFlow<List<String>> =
        session.documents.observeDocTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tagColors: StateFlow<Map<String, String?>> =
        session.tags.observeTags()
            .map { tags -> tags.associate { it.name to it.color } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun allTagNames(): List<String> = session.tags.allTagNames()

    fun markReviewed(docId: String) = launch { session.meta.setStatus(listOf(docId), DocStatus.REVIEWED) }
    fun setCorrespondent(docId: String, value: String?) =
        launch { session.meta.setCorrespondent(listOf(docId), value) }
    fun setDocType(docId: String, value: String?) = launch { session.meta.setDocType(listOf(docId), value) }
    fun setDocDate(docId: String, isoDate: String?) = launch { session.meta.setDocDate(docId, isoDate) }
    fun addTag(docId: String, tag: String) = launch { session.meta.addTag(listOf(docId), tag) }
    fun removeTag(docId: String, tag: String) = launch { session.meta.removeTag(listOf(docId), tag) }
    fun trash(docId: String) = launch { session.meta.trashDoc(docId) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}
