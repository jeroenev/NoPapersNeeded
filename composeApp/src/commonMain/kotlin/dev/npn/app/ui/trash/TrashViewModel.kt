package dev.npn.app.ui.trash

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.db.DocumentEntity
import dev.npn.core.index.RelinkCandidate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Trash + maintenance. Trashed docs come from the repository; duplicates (extra copies sharing a
 * hash) and missing files (relink candidates) come from the repository / last scan report.
 */
class TrashViewModel(
    val session: LibrarySession,
) : MessagesViewModel() {
    val trashed: StateFlow<List<DocListItem>> =
        session.documents
            .observeTrashed()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val duplicates: StateFlow<List<Pair<String, String>>> =
        session.documents
            .observeDuplicates()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val missing: StateFlow<List<DocumentEntity>> =
        session.documents
            .observeMissing()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val relinkCandidates: StateFlow<List<RelinkCandidate>> =
        session.lastScanReport
            .map { report -> report?.relinkCandidates.orEmpty() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun restore(docId: String) = launchReporting(UiAction.RESTORE_DOCUMENT) { session.meta.restoreDoc(docId) }

    fun deleteForever(docId: String) =
        launchReporting(UiAction.DELETE_DOCUMENT) { session.meta.deleteDocPermanently(docId) }

    fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    ) = launchReporting(UiAction.REMOVE_DUPLICATE) {
        session.resolveDuplicate(docId, extraRelPath, keepCanonical = true)
    }

    fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = launchReporting(UiAction.RELINK_DOCUMENT) { session.applyRelink(oldDocId, newDocId) }
}
