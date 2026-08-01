package dev.npn.app.ui.trash

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.db.DocumentEntity
import dev.npn.core.index.RelinkCandidate
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

    /**
     * Restore every currently trashed document. Runs each doc independently so one failure does
     * not abort the rest; a partial failure is reported with the count that could not be restored.
     */
    fun restoreAll() = runBulk(UiAction.RESTORE_DOCUMENT, session.meta::restoreDoc)

    /**
     * Permanently delete every currently trashed document. Runs each doc independently so one
     * failure does not abort the rest; a partial failure is reported with the count that could
     * not be deleted.
     */
    fun emptyTrash() = runBulk(UiAction.DELETE_DOCUMENT, session.meta::deleteDocPermanently)

    private fun runBulk(
        action: UiAction,
        op: suspend (String) -> Unit,
    ) {
        val ids = trashed.value.map { it.doc.docId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failed = ids.count { runCatching { op(it) }.isFailure }
            if (failed > 0) {
                message(uiText(UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS, action, failed, ids.size))
            }
        }
    }

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
