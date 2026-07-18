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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
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
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.core.app.LibrarySession

private enum class TrashTab(val label: String) {
    TRASH("Trash"),
    DUPLICATES("Duplicates"),
    MISSING("Missing"),
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
    // Surface one-shot VM messages (failed restore/delete/relink) as snackbars.
    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash & maintenance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                TrashTab.entries.forEach { t ->
                    val badge = when (t) {
                        TrashTab.TRASH -> trashed.size
                        TrashTab.DUPLICATES -> duplicates.size
                        TrashTab.MISSING -> missing.size
                    }
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(if (badge > 0) "${t.label} ($badge)" else t.label) },
                    )
                }
            }

            when (tab) {
                TrashTab.TRASH -> TrashList(
                    items = trashed,
                    content = session.content,
                    onRestore = vm::restore,
                    onDeleteForever = { confirmDelete = it },
                )
                TrashTab.DUPLICATES -> DuplicatesList(
                    duplicates = duplicates,
                    onResolve = vm::resolveDuplicate,
                )
                TrashTab.MISSING -> MissingList(
                    missing = missing,
                    relinks = relinks,
                    onRelink = vm::applyRelink,
                )
            }
        }
    }

    confirmDelete?.let { docId ->
        ConfirmDialog(
            title = "Delete forever",
            message = "Permanently delete this document? The file is removed and cannot be recovered.",
            confirmLabel = "Delete forever",
            onConfirm = {
                vm.deleteForever(docId)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
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
            EmptyState(icon = Icons.Filled.Delete, title = "Trash is empty")
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
                                "was: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column {
                        TextButton(onClick = { onRestore(item.doc.docId) }) { Text("Restore") }
                        TextButton(onClick = { onDeleteForever(item.doc.docId) }) { Text("Delete") }
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
            EmptyState(icon = Icons.Filled.Refresh, title = "No duplicates", subtitle = "Every hash has one canonical path.")
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
                        Text("Extra copy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(extra, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onResolve(docId, extra) }) { Text("Remove extra") }
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
            EmptyState(icon = Icons.Filled.Refresh, title = "No missing files")
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
                        "Missing from: ${doc.relPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val candidates = relinkByOld[doc.docId].orEmpty()
                    if (candidates.isEmpty()) {
                        Text(
                            "No relink candidates found. Rescan after the file returns.",
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
                                    "Match (${c.reason})",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRelink(c.oldDocId, c.newDocId) }) { Text("Relink") }
                            }
                        }
                    }
                }
            }
        }
    }
}
