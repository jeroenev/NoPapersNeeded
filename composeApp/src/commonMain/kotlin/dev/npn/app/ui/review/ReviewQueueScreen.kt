@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.npn.app.ui.review

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.TagEditor
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession

@Composable
fun ReviewQueueScreen(
    session: LibrarySession,
    onOpenDoc: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ReviewQueueViewModel = viewModel { ReviewQueueViewModel(session) }
    val docs by vm.docs.collectAsStateWithLifecycle()
    val correspondents by vm.correspondents.collectAsStateWithLifecycle()
    val docTypes by vm.docTypes.collectAsStateWithLifecycle()
    val tagColors by vm.tagColors.collectAsStateWithLifecycle()

    var allTagNames by remember { mutableStateOf<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        allTagNames = runCatching { vm.allTagNames() }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review queue (${docs.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (docs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "Nothing to review",
                    subtitle = "All documents have been reviewed.",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(docs, key = { it.doc.docId }) { item ->
                    ReviewCard(
                        item = item,
                        content = session.content,
                        allTagNames = allTagNames,
                        tagColors = tagColors,
                        correspondents = correspondents,
                        docTypes = docTypes,
                        onOpen = { onOpenDoc(item.doc.docId) },
                        onReviewed = { vm.markReviewed(item.doc.docId) },
                        onTrash = { vm.trash(item.doc.docId) },
                        onSetCorrespondent = { vm.setCorrespondent(item.doc.docId, it) },
                        onSetDocType = { vm.setDocType(item.doc.docId, it) },
                        onSetDocDate = { vm.setDocDate(item.doc.docId, it) },
                        onAddTag = { vm.addTag(item.doc.docId, it) },
                        onRemoveTag = { vm.removeTag(item.doc.docId, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    item: DocListItem,
    content: dev.npn.core.app.DocContentFacade,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    correspondents: List<String>,
    docTypes: List<String>,
    onOpen: () -> Unit,
    onReviewed: () -> Unit,
    onTrash: () -> Unit,
    onSetCorrespondent: (String?) -> Unit,
    onSetDocType: (String?) -> Unit,
    onSetDocDate: (String?) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DocThumbnail(docId = item.doc.docId, content = content, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        item.doc.title?.takeIf { it.isNotBlank() } ?: item.doc.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(item.doc.correspondent, item.doc.docType, item.doc.docDate)
                        .joinToString(" - ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit metadata")
                }
            }

            if (expanded) {
                QuickSuggest(
                    label = "Correspondent",
                    current = item.doc.correspondent,
                    options = correspondents,
                    onSelect = onSetCorrespondent,
                )
                QuickSuggest(
                    label = "Document type",
                    current = item.doc.docType,
                    options = docTypes,
                    onSelect = onSetDocType,
                )
                TagEditor(
                    tags = item.tags,
                    allTagNames = allTagNames,
                    tagColors = tagColors,
                    onAdd = onAddTag,
                    onRemove = onRemoveTag,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onTrash) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Dismiss", Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = onReviewed) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Text("Mark reviewed", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun QuickSuggest(
    label: String,
    current: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$label${current?.let { ": $it" } ?: ""}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.take(8).forEach { opt ->
                androidx.compose.material3.FilterChip(
                    selected = current == opt,
                    onClick = { onSelect(if (current == opt) null else opt) },
                    label = { Text(opt) },
                )
            }
            if (options.isEmpty()) {
                Text(
                    "No existing values",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
