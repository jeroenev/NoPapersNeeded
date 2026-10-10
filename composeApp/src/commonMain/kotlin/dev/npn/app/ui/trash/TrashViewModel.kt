package dev.npn.app.ui.trash

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.PermanentDeleteOutcome
import dev.npn.core.index.RelinkCandidate
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_all_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_all_files_left
import no_papers_needed.composeapp.generated.resources.common_trash_delete_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_file_left
import no_papers_needed.composeapp.generated.resources.common_trash_relink_failed
import no_papers_needed.composeapp.generated.resources.common_trash_remove_duplicate_failed
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import org.jetbrains.compose.resources.PluralStringResource

/** Everything the trash screen shows. */
data class TrashUiState(
    val trashed: List<DocListItem> = emptyList(),
    /** Extra copies of a document's bytes: its id, and the path of the copy. */
    val duplicates: List<Pair<String, String>> = emptyList(),
    val missing: List<Doc> = emptyList(),
    /** Where each missing document may have gone, by its id, best match first. */
    val relinks: Map<String, List<RelinkCandidate>> = emptyMap(),
)

/** What the trash screen writes: restore and purge, alone or all at once, and the repairs. */
interface TrashWrites {
    fun restore(docId: String)

    fun deleteForever(docId: String)

    /**
     * Restore every currently trashed document. Runs each doc independently so one failure does
     * not abort the rest; a partial failure is reported with the count that could not be restored.
     */
    fun restoreAll()

    /**
     * Permanently delete every currently trashed document. Runs each doc independently so one
     * failure does not abort the rest; a partial failure is reported with the count that could
     * not be deleted, and deleted documents whose files could not be removed with theirs.
     */
    fun emptyTrash()

    fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    )

    fun applyRelink(
        oldDocId: String,
        newDocId: String,
    )
}

/**
 * Trash + maintenance. Trashed docs come from the repository; duplicates (extra copies sharing a
 * hash) and missing files (relink candidates) come from the repository / last scan report.
 */
class TrashViewModel(
    session: LibrarySession,
) : MessagesViewModel(),
    TrashWrites {
    private val docLifecycle = session.docLifecycle
    private val maintenance = session.maintenance

    val state: StateFlow<TrashUiState> =
        combine(
            session.documents.observeTrashed(),
            session.documents.observeDuplicates(),
            session.documents.observeMissing(),
            session.ingest.lastScanReport,
        ) { trashed, duplicates, missing, report ->
            TrashUiState(
                trashed = trashed,
                duplicates = duplicates,
                missing = missing,
                relinks =
                    report
                        ?.relinkCandidates
                        .orEmpty()
                        .sortedByDescending { it.score }
                        .groupBy { it.oldDocId },
            )
        }.stateIn(viewModelScope, WhileSubscribedTimeout, TrashUiState())

    override fun restore(docId: String) =
        launchReporting(Res.string.common_trash_restore_failed) { docLifecycle.restoreDoc(docId) }

    // A file that could not be removed leaves the document deleted, so it is said apart from a
    // delete that failed.
    override fun deleteForever(docId: String) =
        launchReporting(Res.string.common_trash_delete_failed) {
            if (docLifecycle.deleteDocPermanently(docId) == PermanentDeleteOutcome.FILE_LEFT) {
                message(uiText(Res.string.common_trash_delete_file_left))
            }
        }

    override fun restoreAll() = runBulk(Res.plurals.common_restore_documents_failed, docLifecycle::restoreDocs)

    override fun emptyTrash() =
        runBulk(Res.plurals.common_trash_delete_all_failed) { ids ->
            val tally = docLifecycle.deleteDocsPermanently(ids)
            if (tally.filesLeft > 0) {
                message(uiText(Res.plurals.common_trash_delete_all_files_left, tally.filesLeft, tally.filesLeft))
            }
            tally.failed
        }

    /**
     * Run [op] over every trashed document, one bulk call that lists the trash once and returns how
     * many documents failed; a throw from it counts every one as failed.
     */
    private fun runBulk(
        partlyFailed: PluralStringResource,
        op: suspend (List<String>) -> Int,
    ) {
        val ids = state.value.trashed.map { it.doc.docId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failed = runCatchingCancellable { op(ids) }.getOrElse { ids.size }
            if (failed > 0) {
                message(uiText(partlyFailed, ids.size, failed, ids.size))
            }
        }
    }

    override fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    ) = launchReporting(Res.string.common_trash_remove_duplicate_failed) {
        maintenance.resolveDuplicate(docId, extraRelPath)
    }

    override fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = launchReporting(Res.string.common_trash_relink_failed) { maintenance.applyRelink(oldDocId, newDocId) }
}
