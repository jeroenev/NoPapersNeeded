@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.TopBarActions
import dev.npn.app.ui.common.enumNameSaver
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.Doc
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.index.RelinkCandidate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_empty_trash
import no_papers_needed.composeapp.generated.resources.common_empty_trash_question
import no_papers_needed.composeapp.generated.resources.common_more_actions
import no_papers_needed.composeapp.generated.resources.common_restore_all
import no_papers_needed.composeapp.generated.resources.common_restore_all_question
import no_papers_needed.composeapp.generated.resources.common_trash_delete_forever
import no_papers_needed.composeapp.generated.resources.common_trash_delete_forever_message
import no_papers_needed.composeapp.generated.resources.common_trash_duplicates_tab
import no_papers_needed.composeapp.generated.resources.common_trash_empty
import no_papers_needed.composeapp.generated.resources.common_trash_extra_copy
import no_papers_needed.composeapp.generated.resources.common_trash_maintenance_title
import no_papers_needed.composeapp.generated.resources.common_trash_missing_from
import no_papers_needed.composeapp.generated.resources.common_trash_missing_tab
import no_papers_needed.composeapp.generated.resources.common_trash_no_duplicates
import no_papers_needed.composeapp.generated.resources.common_trash_no_duplicates_subtitle
import no_papers_needed.composeapp.generated.resources.common_trash_no_missing_files
import no_papers_needed.composeapp.generated.resources.common_trash_no_relink_candidates
import no_papers_needed.composeapp.generated.resources.common_trash_possible_match
import no_papers_needed.composeapp.generated.resources.common_trash_relink
import no_papers_needed.composeapp.generated.resources.common_trash_remove_extra
import no_papers_needed.composeapp.generated.resources.common_trash_restore
import no_papers_needed.composeapp.generated.resources.common_trash_tab
import no_papers_needed.composeapp.generated.resources.common_trash_tab_count
import no_papers_needed.composeapp.generated.resources.common_trash_was_path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class TrashTab { TRASH, DUPLICATES, MISSING }

/** The confirmation open on the trash screen, if any: each of these writes cannot be undone. */
private sealed interface TrashConfirm {
    data class DeleteForever(
        val docId: String,
    ) : TrashConfirm

    data object RestoreAll : TrashConfirm

    data object EmptyTrash : TrashConfirm
}

/** Whether this confirmation is for a document no longer among [trashed]. */
private fun TrashConfirm.leftTheTrash(trashed: List<DocListItem>): Boolean =
    this is TrashConfirm.DeleteForever && trashed.none { it.doc.docId == docId }

@Composable
private fun trashTabLabel(tab: TrashTab): String = stringResource(tab.labelResource)

private val TrashTab.labelResource: StringResource
    get() =
        when (this) {
            TrashTab.TRASH -> Res.string.common_trash_tab
            TrashTab.DUPLICATES -> Res.string.common_trash_duplicates_tab
            TrashTab.MISSING -> Res.string.common_trash_missing_tab
        }

// The trash gets the viewmodel only as its TrashWrites interface, the hand-down the conventions prescribe.
@Suppress("ViewModelForwarding")
@Composable
fun TrashScreen(
    session: LibrarySession,
    vm: TrashViewModel = viewModel { TrashViewModel(session) },
) {
    val loaded by vm.state.collectAsStateWithLifecycle()
    val state = loaded ?: TrashUiState()

    var tab by rememberSaveable(stateSaver = enumNameSaver()) { mutableStateOf(TrashTab.TRASH) }
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    TrashScaffold(
        tab = tab,
        state = state,
        loading = loaded == null,
        content = session.content,
        snackbar = snackbar,
        writes = vm,
        onSelectTab = { tab = it },
        onConfirm = { confirm = it },
    )
    // A document that left the trash meanwhile (restored here or on another device) is not there to
    // delete any more: its confirmation closes rather than stay open to be confirmed.
    val stale = confirm?.leftTheTrash(state.trashed) == true
    LaunchedEffect(stale) { if (stale) confirm = null }
    confirm?.takeUnless { stale }?.let {
        TrashConfirmDialog(
            confirm = it,
            trashedCount = state.trashed.size,
            writes = vm,
            onClose = { confirm = null },
        )
    }
}

@Composable
private fun TrashConfirmDialog(
    confirm: TrashConfirm,
    trashedCount: Int,
    writes: TrashWrites,
    onClose: () -> Unit,
) {
    when (confirm) {
        is TrashConfirm.DeleteForever -> {
            ConfirmDialog(
                title = stringResource(Res.string.common_trash_delete_forever),
                message = stringResource(Res.string.common_trash_delete_forever_message),
                confirmLabel = stringResource(Res.string.common_trash_delete_forever),
                destructive = true,
                onConfirm = {
                    writes.deleteForever(confirm.docId)
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        TrashConfirm.RestoreAll -> {
            ConfirmDialog(
                title = stringResource(Res.string.common_restore_all),
                message = pluralStringResource(Res.plurals.common_restore_all_question, trashedCount, trashedCount),
                confirmLabel = stringResource(Res.string.common_restore_all),
                onConfirm = {
                    writes.restoreAll()
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        TrashConfirm.EmptyTrash -> {
            ConfirmDialog(
                title = stringResource(Res.string.common_empty_trash),
                message = pluralStringResource(Res.plurals.common_empty_trash_question, trashedCount, trashedCount),
                confirmLabel = stringResource(Res.string.common_empty_trash),
                destructive = true,
                onConfirm = {
                    writes.emptyTrash()
                    onClose()
                },
                onDismiss = onClose,
            )
        }
    }
}

@Composable
private fun TrashScaffold(
    tab: TrashTab,
    state: TrashUiState,
    /** True until the first load; the tabs then show a spinner rather than their empty states. */
    loading: Boolean,
    content: DocContentFacade,
    snackbar: SnackbarHostState,
    writes: TrashWrites,
    onSelectTab: (TrashTab) -> Unit,
    onConfirm: (TrashConfirm) -> Unit,
) {
    Scaffold(
        topBar = {
            // A wide window shows the two bulk actions directly; a compact bar keeps them in the
            // overflow menu so the bar stays uncrowded.
            val wide = LocalWindowWidthClass.current.isWide
            TopAppBar(
                title = { Text(stringResource(Res.string.common_trash_maintenance_title)) },
                // No back arrow: Trash is a top-level rail destination reached from navigation, not a
                // pushed screen, so a back arrow would misrepresent it and crowd the bar.
                actions = {
                    if (tab == TrashTab.TRASH && state.trashed.isNotEmpty()) {
                        TrashBulkActions(
                            wide = wide,
                            onRestoreAll = { onConfirm(TrashConfirm.RestoreAll) },
                            onEmptyTrash = { onConfirm(TrashConfirm.EmptyTrash) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TrashTabs(tab, state, onSelectTab)
            if (loading) {
                LoadingIndicator()
            } else {
                TrashTabContent(tab, state, content, writes, onConfirm)
            }
        }
    }
}

/** The list [tab] shows. */
@Composable
private fun TrashTabContent(
    tab: TrashTab,
    state: TrashUiState,
    content: DocContentFacade,
    writes: TrashWrites,
    onConfirm: (TrashConfirm) -> Unit,
) {
    when (tab) {
        TrashTab.TRASH -> {
            TrashList(
                items = state.trashed,
                content = content,
                onRestore = writes::restore,
                onDeleteForever = { onConfirm(TrashConfirm.DeleteForever(it)) },
            )
        }

        TrashTab.DUPLICATES -> {
            DuplicatesList(state.duplicates, writes::resolveDuplicate)
        }

        TrashTab.MISSING -> {
            MissingList(state.missing, state.relinks, writes::applyRelink)
        }
    }
}

/**
 * The trash tab's bulk actions. A desktop window has room for both as direct icons; a compact bar
 * keeps them in the overflow menu instead. Only rendered when there is something to act on (the
 * caller gates on a non-empty trash), so the menu never opens onto nothing.
 */
@Composable
private fun TrashBulkActions(
    wide: Boolean,
    onRestoreAll: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    val restoreAll =
        TopBarAction(Icons.Filled.Restore, stringResource(Res.string.common_restore_all), onRestoreAll)
    val emptyTrash =
        TopBarAction(Icons.Filled.DeleteSweep, stringResource(Res.string.common_empty_trash), onEmptyTrash)
    TopBarActions(
        actions = listOf(restoreAll, emptyTrash),
        wide = wide,
        overflowLabel = stringResource(Res.string.common_more_actions),
    )
}

@Composable
private fun TrashTabs(
    selectedTab: TrashTab,
    state: TrashUiState,
    onSelectTab: (TrashTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
        TrashTab.entries.forEach { tab ->
            val badge =
                when (tab) {
                    TrashTab.TRASH -> state.trashed.size
                    TrashTab.DUPLICATES -> state.duplicates.size
                    TrashTab.MISSING -> state.missing.size
                }
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = {
                    val label = trashTabLabel(tab)
                    Text(
                        if (badge > 0) {
                            stringResource(Res.string.common_trash_tab_count, label, badge)
                        } else {
                            label
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun TrashList(
    items: List<DocListItem>,
    content: DocContentFacade,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(icon = Icons.Filled.Delete, title = stringResource(Res.string.common_trash_empty))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.doc.docId }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DocThumbnail(docId = item.doc.docId, content = content, size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.doc.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.doc.trashedOrigPath?.let {
                            HelpText(
                                stringResource(Res.string.common_trash_was_path, it),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column {
                        TextButton(onClick = { onRestore(item.doc.docId) }) {
                            Text(stringResource(Res.string.common_trash_restore))
                        }
                        TextButton(onClick = { onDeleteForever(item.doc.docId) }) {
                            Text(stringResource(Res.string.common_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicatesList(
    duplicates: List<Pair<String, String>>,
    onResolve: (docId: String, extraRelPath: String) -> Unit,
) {
    if (duplicates.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.FileCopy,
            title = stringResource(Res.string.common_trash_no_duplicates),
            subtitle = stringResource(Res.string.common_trash_no_duplicates_subtitle),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(duplicates, key = { it.first + it.second }) { (docId, extra) ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.common_trash_extra_copy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(extra, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onResolve(docId, extra) }) {
                        Text(stringResource(Res.string.common_trash_remove_extra))
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingList(
    missing: List<Doc>,
    relinks: Map<String, List<RelinkCandidate>>,
    onRelink: (oldDocId: String, newDocId: String) -> Unit,
) {
    if (missing.isEmpty()) {
        EmptyState(icon = Icons.Filled.FindInPage, title = stringResource(Res.string.common_trash_no_missing_files))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(missing, key = { it.docId }) { doc ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        doc.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    HelpText(stringResource(Res.string.common_trash_missing_from, doc.relPath))
                    val candidates = relinks[doc.docId].orEmpty()
                    if (candidates.isEmpty()) {
                        HelpText(stringResource(Res.string.common_trash_no_relink_candidates))
                    } else {
                        candidates.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                RelinkEvidence(c, Modifier.weight(1f))
                                TextButton(onClick = { onRelink(c.oldDocId, c.newDocId) }) {
                                    Text(stringResource(Res.string.common_trash_relink))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Why [candidate] may be the missing document's file: a badge per reason. */
@Composable
private fun RelinkEvidence(
    candidate: RelinkCandidate,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.common_trash_possible_match), style = MaterialTheme.typography.bodySmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            candidate.signalLabels().forEach { label ->
                StatusBadge(
                    label = label.resolve(),
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
