@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.parseHexColor
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_document_count
import no_papers_needed.composeapp.generated.resources.common_rename
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.common_tags_actions
import no_papers_needed.composeapp.generated.resources.common_tags_color_style
import no_papers_needed.composeapp.generated.resources.common_tags_delete_message
import no_papers_needed.composeapp.generated.resources.common_tags_delete_title
import no_papers_needed.composeapp.generated.resources.common_tags_empty_subtitle
import no_papers_needed.composeapp.generated.resources.common_tags_empty_title
import no_papers_needed.composeapp.generated.resources.common_tags_merge
import no_papers_needed.composeapp.generated.resources.common_tags_merge_into
import no_papers_needed.composeapp.generated.resources.common_tags_merge_title
import no_papers_needed.composeapp.generated.resources.common_tags_namespace_only
import no_papers_needed.composeapp.generated.resources.common_tags_new
import no_papers_needed.composeapp.generated.resources.common_tags_new_name
import no_papers_needed.composeapp.generated.resources.common_tags_rename_title
import no_papers_needed.composeapp.generated.resources.common_tags_style_title
import no_papers_needed.composeapp.generated.resources.common_tags_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun TagManagementScreen(
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: TagManagementViewModel = viewModel { TagManagementViewModel(session) }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tree = remember(tags) { buildTree(tags) }
    val flatNames = remember(tags) { tags.map { it.name } }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var styleTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    TagListScaffold(
        tree = tree,
        isEmpty = tags.isEmpty(),
        snackbar = snackbar,
        onBack = onBack,
        onCreate = { showCreate = true },
        onRename = { renameTarget = it },
        onMerge = { mergeTarget = it },
        onDelete = { deleteTarget = it },
        onStyle = { name, color -> styleTarget = name to color },
    )

    TagEditDialogs(
        showCreate = showCreate,
        renameTarget = renameTarget,
        styleTarget = styleTarget,
        onCreate = { name, color ->
            vm.create(name, color, null)
            showCreate = false
        },
        onDismissCreate = { showCreate = false },
        onRename = { from, to ->
            vm.rename(from, to)
            renameTarget = null
        },
        onDismissRename = { renameTarget = null },
        onSetStyle = { name, color ->
            vm.setStyle(name, color, null)
            styleTarget = null
        },
        onDismissStyle = { styleTarget = null },
    )
    TagMergeDeleteDialogs(
        mergeTarget = mergeTarget,
        deleteTarget = deleteTarget,
        tagNames = flatNames,
        onMerge = { from, into ->
            vm.merge(from, into)
            mergeTarget = null
        },
        onDismissMerge = { mergeTarget = null },
        onDelete = { name ->
            vm.delete(name)
            deleteTarget = null
        },
        onDismissDelete = { deleteTarget = null },
    )
}

@Composable
private fun TagListScaffold(
    tree: List<TagNode>,
    isEmpty: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
    onDelete: (String) -> Unit,
    onStyle: (String, String?) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_tags_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.common_tags_new))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (isEmpty) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = stringResource(Res.string.common_tags_empty_title),
                    subtitle = stringResource(Res.string.common_tags_empty_subtitle),
                )
            }
        } else {
            val flatTree = remember(tree) { flatten(tree) }
            // Compact cards in an adaptive grid: several tags per row on wide layouts instead of
            // one full-width row each. The namespace hierarchy shows as a muted parent-path line
            // on each card (grid cells cannot carry the old indentation).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TagCardMinWidth),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(flatTree, key = { it.path }) { node ->
                    TagCard(
                        node = node,
                        onRename = { onRename(node.path) },
                        onMerge = { onMerge(node.path) },
                        onDelete = { onDelete(node.path) },
                        onStyle = { onStyle(node.path, node.info?.color) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagEditDialogs(
    showCreate: Boolean,
    renameTarget: String?,
    styleTarget: Pair<String, String?>?,
    onCreate: (String, String?) -> Unit,
    onDismissCreate: () -> Unit,
    onRename: (String, String) -> Unit,
    onDismissRename: () -> Unit,
    onSetStyle: (String, String?) -> Unit,
    onDismissStyle: () -> Unit,
) {
    if (showCreate) {
        TagFormDialog(
            title = stringResource(Res.string.common_tags_new),
            initialName = "",
            initialColor = null,
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = onCreate,
            onDismiss = onDismissCreate,
        )
    }

    renameTarget?.let { from ->
        TextInputDialog(
            title = stringResource(Res.string.common_tags_rename_title),
            label = stringResource(Res.string.common_tags_new_name),
            initial = from,
            confirmLabel = stringResource(Res.string.common_rename),
            // Block confirming until the name is non-blank and actually changed.
            confirmEnabled = { it.isNotBlank() && it.trim() != from },
            onConfirm = { to ->
                onRename(from, to)
            },
            onDismiss = onDismissRename,
        )
    }

    styleTarget?.let { (name, color) ->
        TagFormDialog(
            title = stringResource(Res.string.common_tags_style_title),
            initialName = name,
            initialColor = color,
            confirmLabel = stringResource(Res.string.common_save),
            nameEditable = false,
            onConfirm = { _, newColor -> onSetStyle(name, newColor) },
            onDismiss = onDismissStyle,
        )
    }
}

@Composable
private fun TagMergeDeleteDialogs(
    mergeTarget: String?,
    deleteTarget: String?,
    tagNames: List<String>,
    onMerge: (String, String) -> Unit,
    onDismissMerge: () -> Unit,
    onDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
) {
    mergeTarget?.let { from ->
        TagPickDialog(
            title = stringResource(Res.string.common_tags_merge_title, from),
            options = tagNames.filter { it != from },
            confirmLabel = stringResource(Res.string.common_tags_merge),
            onConfirm = { into -> onMerge(from, into) },
            onDismiss = onDismissMerge,
        )
    }

    deleteTarget?.let { name ->
        ConfirmDialog(
            title = stringResource(Res.string.common_tags_delete_title),
            message = stringResource(Res.string.common_tags_delete_message, name),
            confirmLabel = stringResource(Res.string.common_delete),
            onConfirm = {
                onDelete(name)
            },
            onDismiss = onDismissDelete,
        )
    }
}

/** Minimum card width driving the adaptive column count of the tag grid. */
private val TagCardMinWidth = 240.dp

@Composable
private fun TagCard(
    node: TagNode,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onStyle: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dot = parseHexColor(node.info?.color) ?: MaterialTheme.colorScheme.outline
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                val parentPath = node.path.substringBeforeLast('.', "")
                if (parentPath.isNotEmpty()) {
                    Text(
                        parentPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    node.segment,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val documentCount =
                    pluralStringResource(
                        Res.plurals.common_document_count,
                        node.descendantCount,
                        node.descendantCount,
                    )
                val subtitle =
                    if (node.info == null) {
                        stringResource(Res.string.common_tags_namespace_only, documentCount)
                    } else {
                        documentCount
                    }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TagCardMenu(
                expanded = menu,
                onExpand = { menu = true },
                onDismiss = { menu = false },
                onRename = onRename,
                onMerge = onMerge,
                onDelete = onDelete,
                onStyle = onStyle,
            )
        }
    }
}

@Composable
private fun TagCardMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onStyle: () -> Unit,
) {
    Box {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.common_tags_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_rename)) },
                onClick = {
                    onDismiss()
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_tags_merge_into)) },
                onClick = {
                    onDismiss()
                    onMerge()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_tags_color_style)) },
                onClick = {
                    onDismiss()
                    onStyle()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete)) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}

/** Build the namespace tree from the flat tag list. */
internal fun buildTree(flat: List<TagInfo>): List<TagNode> {
    val byPath = flat.associateBy { it.name }
    // Collect every distinct path (real tags plus the intermediate namespace prefixes they imply),
    // already sorted. Grouping by immediate parent prefix below makes the tree build linear instead
    // of repeatedly filtering the whole path set per node (the old implementation was O(N^2)).
    val allPaths = sortedSetOf<String>()
    for (t in flat) {
        val parts = t.name.split('.')
        val acc = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) acc.append('.')
            acc.append(p)
            allPaths.add(acc.toString())
        }
    }
    val childrenByParent = HashMap<String, MutableList<String>>()
    for (path in allPaths) {
        val parent = path.substringBeforeLast('.', "")
        childrenByParent.getOrPut(parent) { mutableListOf() }.add(path)
    }

    fun childrenOf(
        prefix: String,
        depth: Int,
    ): List<TagNode> {
        val direct = childrenByParent[prefix].orEmpty()
        return direct.map { path ->
            val kids = childrenOf(path, depth + 1)
            val info = byPath[path]
            val own = info?.count ?: 0
            TagNode(
                path = path,
                segment = path.substringAfterLast('.'),
                depth = depth,
                info = info,
                count = own,
                descendantCount = own + kids.sumOf { it.descendantCount },
                children = kids,
            )
        }
    }

    return childrenOf("", 0)
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
