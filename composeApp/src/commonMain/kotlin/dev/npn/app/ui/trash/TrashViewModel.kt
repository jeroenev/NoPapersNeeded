package dev.npn.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.db.DocumentEntity
import dev.npn.core.index.RelinkCandidate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Trash + maintenance. Trashed docs come from the repository; duplicates (extra copies sharing a
 * hash) and missing files (relink candidates) come from the repository / last scan report.
 */
class TrashViewModel(
    val session: LibrarySession,
) : ViewModel() {

    val trashed: StateFlow<List<DocListItem>> =
        session.documents.observeTrashed()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val duplicates: StateFlow<List<Pair<String, String>>> =
        session.documents.observeDuplicates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val missing: StateFlow<List<DocumentEntity>> =
        session.documents.observeMissing()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val relinkCandidates: StateFlow<List<RelinkCandidate>> =
        session.lastScanReport
            .map { report -> report?.relinkCandidates.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(docId: String) = launch { session.meta.restoreDoc(docId) }
    fun deleteForever(docId: String) = launch { session.meta.deleteDocPermanently(docId) }
    fun resolveDuplicate(docId: String, extraRelPath: String) =
        launch { session.resolveDuplicate(docId, extraRelPath, keepCanonical = true) }
    fun applyRelink(oldDocId: String, newDocId: String) = launch { session.applyRelink(oldDocId, newDocId) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}
