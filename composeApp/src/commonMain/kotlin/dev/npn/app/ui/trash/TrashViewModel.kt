package dev.npn.app.ui.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiState
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.PermanentDeleteOutcome
import dev.npn.core.index.DuplicateReport
import dev.npn.core.index.RelinkCandidate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
    val duplicates: List<DuplicateReport> = emptyList(),
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

/** The trash screen's tabs. */
@Serializable
internal enum class TrashTab { TRASH, DUPLICATES, MISSING }

/**
 * The confirmation open on the trash screen, if any: each of these writes cannot be undone.
 * [TrashViewModel.confirm] keeps it, so it is open again after the screen is recreated.
 */
@Serializable
internal sealed interface TrashConfirm {
    @Serializable
    data class DeleteForever(
        val docId: String,
    ) : TrashConfirm

    @Serializable
    data object RestoreAll : TrashConfirm

    @Serializable
    data object EmptyTrash : TrashConfirm
}

/** Whether this confirmation is for a document no longer among [trashed]. */
internal fun TrashConfirm.leftTheTrash(trashed: List<DocListItem>): Boolean =
    this is TrashConfirm.DeleteForever && trashed.none { it.doc.docId == docId }

/**
 * Trash + maintenance. Trashed docs come from the repository; duplicates (extra copies sharing a
 * hash) and missing files (relink candidates) come from the repository / last scan report.
 */
class TrashViewModel(
    session: LibrarySession,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    saved: SavedStateHandle = SavedStateHandle(),
) : MessagesViewModel(),
    TrashWrites {
    private val docLifecycle = session.docLifecycle
    private val maintenance = session.maintenance
    override val log = session.log

    /** Null until the first load, so the screen does not flash its empty states before it. */
    val state: StateFlow<TrashUiState?> =
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
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    private val tabState = saved.uiState(TAB_KEY, TrashTab.TRASH)

    /** The tab the screen shows. */
    internal val tab: StateFlow<TrashTab> = tabState.flow

    internal fun selectTab(tab: TrashTab) = tabState.set(tab)

    private val confirmState = saved.uiState<TrashConfirm?>(CONFIRM_KEY, null)

    /**
     * The confirmation the screen has open, if any. One restored with a restored process is shown
     * only once the trash has loaded, and dropped if its document has left it meanwhile.
     */
    internal val confirm: StateFlow<TrashConfirm?> = confirmState.flow

    internal fun confirm(confirm: TrashConfirm?) = confirmState.set(confirm)

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
     * Run [op] over every trashed document: one bulk call that lists the trash once and returns how
     * many documents failed, reported as [countReporting] does.
     */
    private fun runBulk(
        partlyFailed: PluralStringResource,
        op: suspend (List<String>) -> Int,
    ) {
        val ids =
            state.value
                ?.trashed
                .orEmpty()
                .map { it.doc.docId }
        if (ids.isEmpty()) return
        viewModelScope.launch { countReporting(ids.size, partlyFailed) { op(ids) } }
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

    private companion object {
        const val TAB_KEY = "trash.tab"
        const val CONFIRM_KEY = "trash.confirm"
    }
}
