@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.core.app.DocListItem
import dev.npn.core.app.KanbanGrouping
import dev.npn.core.app.LibrarySession

@Composable
fun KanbanScreen(
    session: LibrarySession,
    onOpenDoc: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: KanbanViewModel = viewModel { KanbanViewModel(session) }
    val columns by vm.columns.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text("Kanban") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Grouping")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Group by status") },
                                onClick = { vm.setGrouping(KanbanGrouping.STATUS); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Group by \"${config.tagNamespace}\" tags") },
                                onClick = { vm.setGrouping(KanbanGrouping.TAGS); menu = false },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyRow(
            Modifier.fillMaxSize().padding(padding).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(columns, key = { it.id }) { column ->
                KanbanLane(
                    column = column,
                    otherColumns = columns.filter { it.id != column.id },
                    content = session.content,
                    onOpen = onOpenDoc,
                    onMove = { docId, targetId, tags -> vm.moveTo(docId, targetId, tags) },
                )
            }
        }
    }
}

@Composable
private fun KanbanLane(
    column: KanbanColumn,
    otherColumns: List<KanbanColumn>,
    content: dev.npn.core.app.DocContentFacade,
    onOpen: (String) -> Unit,
    onMove: (docId: String, targetId: String, tags: List<String>) -> Unit,
) {
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(
            "${column.title} (${column.cards.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(column.cards, key = { it.doc.docId }) { item ->
                KanbanCard(
                    item = item,
                    content = content,
                    otherColumns = otherColumns,
                    onOpen = { onOpen(item.doc.docId) },
                    onMove = { targetId -> onMove(item.doc.docId, targetId, item.tags) },
                )
            }
        }
    }
}

@Composable
private fun KanbanCard(
    item: DocListItem,
    content: dev.npn.core.app.DocContentFacade,
    otherColumns: List<KanbanColumn>,
    onOpen: () -> Unit,
    onMove: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DocThumbnail(docId = item.doc.docId, content = content, size = 40.dp)
            Text(
                item.doc.title?.takeIf { it.isNotBlank() } ?: item.doc.fileName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Move card")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (otherColumns.isEmpty()) {
                        DropdownMenuItem(text = { Text("No other lanes") }, enabled = false, onClick = {})
                    }
                    otherColumns.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("Move to ${target.title}") },
                            onClick = {
                                menu = false
                                onMove(target.id)
                            },
                        )
                    }
                }
            }
        }
    }
}
