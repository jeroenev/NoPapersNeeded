@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_trash_delete_forever
import no_papers_needed.composeapp.generated.resources.common_trash_delete_forever_message
import no_papers_needed.composeapp.generated.resources.common_trash_duplicates_tab
import no_papers_needed.composeapp.generated.resources.common_trash_empty
import no_papers_needed.composeapp.generated.resources.common_trash_extra_copy
import no_papers_needed.composeapp.generated.resources.common_trash_maintenance_title
import no_papers_needed.composeapp.generated.resources.common_trash_match_reason
import no_papers_needed.composeapp.generated.resources.common_trash_missing_from
import no_papers_needed.composeapp.generated.resources.common_trash_missing_tab
import no_papers_needed.composeapp.generated.resources.common_trash_no_duplicates
import no_papers_needed.composeapp.generated.resources.common_trash_no_duplicates_subtitle
import no_papers_needed.composeapp.generated.resources.common_trash_no_missing_files
import no_papers_needed.composeapp.generated.resources.common_trash_no_relink_candidates
import no_papers_needed.composeapp.generated.resources.common_trash_relink
import no_papers_needed.composeapp.generated.resources.common_trash_remove_extra
import no_papers_needed.composeapp.generated.resources.common_trash_restore
import no_papers_needed.composeapp.generated.resources.common_trash_tab
import no_papers_needed.composeapp.generated.resources.common_trash_tab_count
import no_papers_needed.composeapp.generated.resources.common_trash_was_path
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class TrashTab { TRASH, DUPLICATES, MISSING }

private data class TrashContentState(
    val trashed: List<dev.npn.core.app.DocListItem>,
    val duplicates: List<Pair<String, String>>,
    val missing: List<dev.npn.core.db.DocumentEntity>,
    val relinks: List<dev.npn.core.index.RelinkCandidate>,
)

private data class TrashActions(
    val onRestore: (String) -> Unit,
    val onDeleteForever: (String) -> Unit,
    val onResolveDuplicate: (String, String) -> Unit,
    val onRelink: (String, String) -> Unit,
)

@Composable
private fun trashTabLabel(tab: TrashTab): String = stringResource(tab.labelResource)

private val TrashTab.labelResource: StringResource
    get() =
        when (this) {
            TrashTab.TRASH -> Res.string.common_trash_tab
            TrashTab.DUPLICATES -> Res.string.common_trash_duplicates_tab
            TrashTab.MISSING -> Res.string.common_trash_missing_tab
        }

@Composable
fun TrashScreen(
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: TrashViewModel = viewModel { TrashViewModel(session) }
    val trashed by vm.trashed.collectAsStateWithLifecycle()
    val duplicates by vm.duplicates.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val relinks by vm.relinkCandidates.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(TrashTab.TRASH) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    TrashScaffold(
        tab = tab,
        state = TrashContentState(trashed, duplicates, missing, relinks),
        content = session.content,
        snackbar = snackbar,
        onBack = onBack,
        onSelectTab = { tab = it },
        actions =
            TrashActions(
                onRestore = vm::restore,
                onDeleteForever = { confirmDelete = it },
                onResolveDuplicate = vm::resolveDuplicate,
                onRelink = vm::applyRelink,
            ),
    )

    confirmDelete?.let { docId ->
        DeleteForeverDialog(
            onConfirm = {
                vm.deleteForever(docId)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun TrashScaffold(
    tab: TrashTab,
    state: TrashContentState,
    content: dev.npn.core.app.DocContentFacade,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSelectTab: (TrashTab) -> Unit,
    actions: TrashActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_trash_maintenance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TrashTabs(tab, state, onSelectTab)
            TrashTabContent(tab, state, content, actions)
        }
    }
}

@Composable
private fun TrashTabs(
    selectedTab: TrashTab,
    state: TrashContentState,
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
private fun TrashTabContent(
    tab: TrashTab,
    state: TrashContentState,
    content: dev.npn.core.app.DocContentFacade,
    actions: TrashActions,
) {
    when (tab) {
        TrashTab.TRASH -> {
            TrashList(
                items = state.trashed,
                content = content,
                onRestore = actions.onRestore,
                onDeleteForever = actions.onDeleteForever,
            )
        }

        TrashTab.DUPLICATES -> {
            DuplicatesList(state.duplicates, actions.onResolveDuplicate)
        }

        TrashTab.MISSING -> {
            MissingList(state.missing, state.relinks, actions.onRelink)
        }
    }
}

@Composable
private fun DeleteForeverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.common_trash_delete_forever),
        message = stringResource(Res.string.common_trash_delete_forever_message),
        confirmLabel = stringResource(Res.string.common_trash_delete_forever),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TrashList(
    items: List<dev.npn.core.app.DocListItem>,
    content: dev.npn.core.app.DocContentFacade,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(icon = Icons.Filled.Delete, title = stringResource(Res.string.common_trash_empty))
        }
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
                            documentDisplayTitle(item.doc.title, item.doc.fileName),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.doc.trashedOrigPath?.let {
                            Text(
                                stringResource(Res.string.common_trash_was_path, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                icon = Icons.Filled.Refresh,
                title = stringResource(Res.string.common_trash_no_duplicates),
                subtitle = stringResource(Res.string.common_trash_no_duplicates_subtitle),
            )
        }
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
    missing: List<dev.npn.core.db.DocumentEntity>,
    relinks: List<dev.npn.core.index.RelinkCandidate>,
    onRelink: (oldDocId: String, newDocId: String) -> Unit,
) {
    if (missing.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                icon = Icons.Filled.Refresh,
                title = stringResource(Res.string.common_trash_no_missing_files),
            )
        }
        return
    }
    val relinkByOld = remember(relinks) { relinks.groupBy { it.oldDocId } }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(missing, key = { it.docId }) { doc ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        documentDisplayTitle(doc.title, doc.fileName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(Res.string.common_trash_missing_from, doc.relPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val candidates = relinkByOld[doc.docId].orEmpty()
                    if (candidates.isEmpty()) {
                        Text(
                            stringResource(Res.string.common_trash_no_relink_candidates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        candidates.sortedByDescending { it.score }.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(Res.string.common_trash_match_reason, c.reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
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
