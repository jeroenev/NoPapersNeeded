package dev.npn.app.ui.trash

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.index.RelinkCandidate
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_all_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_failed
import no_papers_needed.composeapp.generated.resources.common_trash_relink_failed
import no_papers_needed.composeapp.generated.resources.common_trash_remove_duplicate_failed
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Trash + maintenance. Trashed docs come from the repository; duplicates (extra copies sharing a
 * hash) and missing files (relink candidates) come from the repository / last scan report.
 */
class TrashViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val docLifecycle = session.docLifecycle
    private val maintenance = session.maintenance

    val trashed: StateFlow<List<DocListItem>> =
        session.documents
            .observeTrashed()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val duplicates: StateFlow<List<Pair<String, String>>> =
        session.documents
            .observeDuplicates()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val missing: StateFlow<List<Doc>> =
        session.documents
            .observeMissing()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val relinkCandidates: StateFlow<List<RelinkCandidate>> =
        session.ingest.lastScanReport
            .map { report -> report?.relinkCandidates.orEmpty() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun restore(docId: String) =
        launchReporting(Res.string.common_trash_restore_failed) { docLifecycle.restoreDoc(docId) }

    fun deleteForever(docId: String) =
        launchReporting(Res.string.common_trash_delete_failed) { docLifecycle.deleteDocPermanently(docId) }

    /**
     * Restore every currently trashed document. Runs each doc independently so one failure does
     * not abort the rest; a partial failure is reported with the count that could not be restored.
     */
    fun restoreAll() = runBulk(Res.plurals.common_restore_documents_failed, docLifecycle::restoreDoc)

    /**
     * Permanently delete every currently trashed document. Runs each doc independently so one
     * failure does not abort the rest; a partial failure is reported with the count that could
     * not be deleted.
     */
    fun emptyTrash() = runBulk(Res.plurals.common_trash_delete_all_failed, docLifecycle::deleteDocPermanently)

    private fun runBulk(
        partlyFailed: PluralStringResource,
        op: suspend (String) -> Unit,
    ) {
        val ids = trashed.value.map { it.doc.docId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failed = ids.count { runCatchingCancellable { op(it) }.isFailure }
            if (failed > 0) {
                message(uiText(partlyFailed, ids.size, failed, ids.size))
            }
        }
    }

    fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    ) = launchReporting(Res.string.common_trash_remove_duplicate_failed) {
        maintenance.resolveDuplicate(docId, extraRelPath)
    }

    fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = launchReporting(Res.string.common_trash_relink_failed) { maintenance.applyRelink(oldDocId, newDocId) }
}
