@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.parseHexColor
import dev.npn.core.app.LibrarySession

@Composable
fun TagManagementScreen(
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: TagManagementViewModel = viewModel { TagManagementViewModel(session) }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tree = remember(tags) { vm.buildTree(tags) }
    val flatNames = remember(tags) { tags.map { it.name } }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var styleTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New tag")
            }
        },
    ) { padding ->
        if (tags.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No tags yet",
                    subtitle = "Create a tag or add tags to documents.",
                )
            }
        } else {
            val flatTree = remember(tree) { flatten(tree) }
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(flatTree, key = { it.path }) { node ->
                    TagRow(
                        node = node,
                        onRename = { renameTarget = node.path },
                        onMerge = { mergeTarget = node.path },
                        onDelete = { deleteTarget = node.path },
                        onStyle = { styleTarget = node.path to node.info?.color },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        TagFormDialog(
            title = "New tag",
            initialName = "",
            initialColor = null,
            confirmLabel = "Create",
            onConfirm = { name, color ->
                vm.create(name, color, null)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { from ->
        SingleValueDialog(
            title = "Rename tag",
            label = "New name",
            initial = from,
            confirmLabel = "Rename",
            onConfirm = { to ->
                vm.rename(from, to)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    mergeTarget?.let { from ->
        TagPickDialog(
            title = "Merge \"$from\" into",
            options = flatNames.filter { it != from },
            confirmLabel = "Merge",
            onConfirm = { into ->
                vm.merge(from, into)
                mergeTarget = null
            },
            onDismiss = { mergeTarget = null },
        )
    }

    deleteTarget?.let { name ->
        ConfirmDialog(
            title = "Delete tag",
            message = "Remove \"$name\" from all documents? This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                vm.delete(name)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    styleTarget?.let { (name, color) ->
        TagFormDialog(
            title = "Tag style",
            initialName = name,
            initialColor = color,
            confirmLabel = "Save",
            nameEditable = false,
            onConfirm = { _, newColor ->
                vm.setStyle(name, newColor, null)
                styleTarget = null
            },
            onDismiss = { styleTarget = null },
        )
    }
}

@Composable
private fun TagRow(
    node: TagNode,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onStyle: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (16 + node.depth * 20).dp, end = 8.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dot = parseHexColor(node.info?.color) ?: MaterialTheme.colorScheme.outline
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(node.segment, style = MaterialTheme.typography.bodyLarge)
            val subtitle = buildString {
                append("${node.descendantCount} doc(s)")
                if (node.info == null) append(" - namespace only")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Tag actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Merge into...") }, onClick = { menu = false; onMerge() })
                DropdownMenuItem(text = { Text("Color / style") }, onClick = { menu = false; onStyle() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun flatten(nodes: List<TagNode>): List<TagNode> {
    val out = ArrayList<TagNode>()
    fun walk(list: List<TagNode>) {
        for (n in list) {
            out.add(n)
            walk(n.children)
        }
    }
    walk(nodes)
    return out
}
