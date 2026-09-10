@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.npn.app.ui.tags

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.onSecondaryClick
import dev.npn.app.ui.common.parseHexColor
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_document_count
import no_papers_needed.composeapp.generated.resources.common_rename
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.common_select_all
import no_papers_needed.composeapp.generated.resources.common_tags_actions
import no_papers_needed.composeapp.generated.resources.common_tags_color_many_title
import no_papers_needed.composeapp.generated.resources.common_tags_color_style
import no_papers_needed.composeapp.generated.resources.common_tags_delete_many_message
import no_papers_needed.composeapp.generated.resources.common_tags_delete_many_title
import no_papers_needed.composeapp.generated.resources.common_tags_delete_message
import no_papers_needed.composeapp.generated.resources.common_tags_delete_title
import no_papers_needed.composeapp.generated.resources.common_tags_empty_subtitle
import no_papers_needed.composeapp.generated.resources.common_tags_empty_title
import no_papers_needed.composeapp.generated.resources.common_tags_merge
import no_papers_needed.composeapp.generated.resources.common_tags_merge_into
import no_papers_needed.composeapp.generated.resources.common_tags_merge_keep_message
import no_papers_needed.composeapp.generated.resources.common_tags_merge_many_title
import no_papers_needed.composeapp.generated.resources.common_tags_merge_title
import no_papers_needed.composeapp.generated.resources.common_tags_namespace_only
import no_papers_needed.composeapp.generated.resources.common_tags_new_name
import no_papers_needed.composeapp.generated.resources.common_tags_rename_title
import no_papers_needed.composeapp.generated.resources.common_tags_style_title
import no_papers_needed.composeapp.generated.resources.common_tags_title
import no_papers_needed.composeapp.generated.resources.library_deselect
import no_papers_needed.composeapp.generated.resources.library_select
import no_papers_needed.composeapp.generated.resources.library_selected_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Multi-select state of the tag grid. Only real tags (nodes with a [TagInfo]) are selectable;
 * namespace-only nodes carry no journal state of their own.
 */
private class TagSelection {
    var selected by mutableStateOf(emptySet<String>())

    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }

    fun clear() {
        selected = emptySet()
    }
}

/**
 * Actions of a card's overflow/context menu, bundled to keep signatures readable. The single
 * variants target one tag; the selected variants apply to the whole current selection.
 */
@Immutable
private data class TagCardActions(
    val onRename: (String) -> Unit,
    val onMerge: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onStyle: (String, String?) -> Unit,
    val onMergeSelected: () -> Unit,
    val onStyleSelected: () -> Unit,
    val onDeleteSelected: () -> Unit,
)

/** Which tags each bulk dialog currently targets; null while the dialog is closed. */
private class TagBulkDialogState {
    var merge by mutableStateOf<List<String>?>(null)
    var style by mutableStateOf<List<String>?>(null)
    var delete by mutableStateOf<List<String>?>(null)
}

@Composable
fun TagManagementScreen(session: LibrarySession) {
    val vm: TagManagementViewModel = viewModel { TagManagementViewModel(session) }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tree = remember(tags) { buildTree(tags) }
    val flatNames = remember(tags) { tags.map { it.name } }

    var renameTarget by remember { mutableStateOf<String?>(null) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var styleTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val selection = remember { TagSelection() }
    val bulk = remember { TagBulkDialogState() }
    // Tags can disappear underneath a selection (another device, auto-pruning after edits).
    val currentSelection = { selection.selected.intersect(flatNames.toSet()).toList() }

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    TagListScaffold(
        tree = tree,
        isEmpty = tags.isEmpty(),
        snackbar = snackbar,
        actions =
            TagCardActions(
                onRename = { renameTarget = it },
                onMerge = { mergeTarget = it },
                onDelete = { deleteTarget = it },
                onStyle = { name, color -> styleTarget = name to color },
                onMergeSelected = { bulk.merge = currentSelection() },
                onStyleSelected = { bulk.style = currentSelection() },
                onDeleteSelected = { bulk.delete = currentSelection() },
            ),
        selection = selection,
    )

    TagEditDialogs(
        renameTarget = renameTarget,
        styleTarget = styleTarget,
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
    TagBulkDialogs(state = bulk, vm = vm, onDone = selection::clear)
}

/** Dialogs of the bulk (multi-select) actions: merge into a chosen primary, color, delete. */
@Composable
private fun TagBulkDialogs(
    state: TagBulkDialogState,
    vm: TagManagementViewModel,
    onDone: () -> Unit,
) {
    state.merge?.let { names ->
        TagPickDialog(
            title = pluralStringResource(Res.plurals.common_tags_merge_many_title, names.size, names.size),
            message = stringResource(Res.string.common_tags_merge_keep_message),
            options = names,
            confirmLabel = stringResource(Res.string.common_tags_merge),
            onConfirm = { primary ->
                vm.mergeAll(names, primary)
                state.merge = null
                onDone()
            },
            onDismiss = { state.merge = null },
        )
    }
    state.style?.let { names ->
        TagFormDialog(
            title = pluralStringResource(Res.plurals.common_tags_color_many_title, names.size, names.size),
            initialName = "",
            initialColor = null,
            confirmLabel = stringResource(Res.string.common_save),
            showNameField = false,
            onConfirm = { _, color ->
                vm.setStyleAll(names, color)
                state.style = null
                onDone()
            },
            onDismiss = { state.style = null },
        )
    }
    state.delete?.let { names ->
        ConfirmDialog(
            title = pluralStringResource(Res.plurals.common_tags_delete_many_title, names.size, names.size),
            message = pluralStringResource(Res.plurals.common_tags_delete_many_message, names.size, names.size),
            confirmLabel = stringResource(Res.string.common_delete),
            destructive = true,
            onConfirm = {
                vm.deleteAll(names)
                state.delete = null
                onDone()
            },
            onDismiss = { state.delete = null },
        )
    }
}

@Composable
private fun TagListScaffold(
    tree: List<TagNode>,
    isEmpty: Boolean,
    snackbar: SnackbarHostState,
    actions: TagCardActions,
    selection: TagSelection,
) {
    val flatTree = remember(tree) { flatten(tree) }
    Scaffold(
        topBar = {
            if (selection.active) {
                TagSelectionTopBar(
                    count = selection.selected.size,
                    onClose = selection::clear,
                    onSelectAll = {
                        selection.selected = flatTree.mapNotNull { it.info?.name }.toSet()
                    },
                    onMergeSelected = actions.onMergeSelected,
                    onStyleSelected = actions.onStyleSelected,
                    onDeleteSelected = actions.onDeleteSelected,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(Res.string.common_tags_title)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (isEmpty) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Sell,
                    title = stringResource(Res.string.common_tags_empty_title),
                    subtitle = stringResource(Res.string.common_tags_empty_subtitle),
                )
            }
        } else {
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
                        actions = actions,
                        selectionMode = selection.active,
                        selected = node.path in selection.selected,
                        selectedCount = selection.selected.size,
                        onToggleSelect = { selection.toggle(node.path) },
                        // Opening a menu on an unselected card replaces the selection with that
                        // card, so the menu never silently acts on off-screen tags.
                        onEnsureSelected = {
                            if (selection.active && node.info != null && node.path !in selection.selected) {
                                selection.selected = setOf(node.path)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Contextual top bar while tags are selected: count, select all, bulk color/merge/delete. */
@Composable
private fun TagSelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMergeSelected: () -> Unit,
    onStyleSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(Res.plurals.library_selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(Res.string.common_cancel))
            }
        },
        actions = {
            TooltipIconButton(
                icon = Icons.Filled.SelectAll,
                label = stringResource(Res.string.common_select_all),
                onClick = onSelectAll,
            )
            TooltipIconButton(
                icon = Icons.Filled.Palette,
                label = stringResource(Res.string.common_tags_color_style),
                onClick = onStyleSelected,
            )
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.CallMerge,
                label = stringResource(Res.string.common_tags_merge),
                onClick = onMergeSelected,
                // Merging needs at least a pair.
                enabled = count >= 2,
            )
            TooltipIconButton(
                icon = Icons.Filled.Delete,
                label = stringResource(Res.string.common_delete),
                onClick = onDeleteSelected,
            )
        },
    )
}

@Composable
private fun TagEditDialogs(
    renameTarget: String?,
    styleTarget: Pair<String, String?>?,
    onRename: (String, String) -> Unit,
    onDismissRename: () -> Unit,
    onSetStyle: (String, String?) -> Unit,
    onDismissStyle: () -> Unit,
) {
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
            destructive = true,
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
    actions: TagCardActions,
    selectionMode: Boolean,
    selected: Boolean,
    selectedCount: Int,
    onToggleSelect: () -> Unit,
    /** Pulls this card into the selection before a menu opens on it (see call site). */
    onEnsureSelected: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selectable = node.info != null
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val selectionLabel =
        stringResource(if (selected) Res.string.library_deselect else Res.string.library_select)
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .hoverable(hoverInteraction)
            .onSecondaryClick {
                onEnsureSelected()
                menu = true
            }.semantics { if (selectable) this.selected = selected }
            .combinedClickable(
                role = Role.Button,
                onClickLabel =
                    if (selectionMode && selectable) selectionLabel else stringResource(Res.string.common_tags_actions),
                onLongClickLabel = selectionLabel.takeIf { selectable },
                // A normal tap opens the actions; once selection mode starts it toggles selection.
                onClick = {
                    if (selectionMode && selectable) {
                        onToggleSelect()
                    } else {
                        onEnsureSelected()
                        menu = true
                    }
                },
                onLongClick = { if (selectable) onToggleSelect() },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectable && (selectionMode || hovered)) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            }
            val dot = parseHexColor(node.info?.color) ?: MaterialTheme.colorScheme.outline
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            TagCardLabel(node, Modifier.weight(1f))
            TagCardMenu(
                expanded = menu,
                onExpand = {
                    onEnsureSelected()
                    menu = true
                },
                onDismiss = { menu = false },
                node = node,
                actions = actions,
                // With several tags selected (and this card among them) the menu acts on the
                // whole selection; otherwise it targets just this tag.
                bulkCount = selectedCount.takeIf { selectionMode && selected && it > 1 },
            )
        }
    }
}

@Composable
private fun TagCardLabel(
    node: TagNode,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
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
        Text(
            if (node.info == null) {
                stringResource(Res.string.common_tags_namespace_only, documentCount)
            } else {
                documentCount
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagCardMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    node: TagNode,
    actions: TagCardActions,
    /** Number of selected tags the menu applies to, or null for single-tag mode. */
    bulkCount: Int?,
) {
    Box {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.common_tags_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (bulkCount != null) {
                BulkMenuItems(count = bulkCount, actions = actions, onDismiss = onDismiss)
            } else {
                SingleMenuItems(node = node, actions = actions, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun SingleMenuItems(
    node: TagNode,
    actions: TagCardActions,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_rename)) },
        onClick = {
            onDismiss()
            actions.onRename(node.path)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_tags_merge_into)) },
        onClick = {
            onDismiss()
            actions.onMerge(node.path)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_tags_color_style)) },
        onClick = {
            onDismiss()
            actions.onStyle(node.path, node.info?.color)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_delete)) },
        onClick = {
            onDismiss()
            actions.onDelete(node.path)
        },
    )
}

/** Menu items while several tags are selected; labels carry the count to make the scope clear. */
@Composable
private fun BulkMenuItems(
    count: Int,
    actions: TagCardActions,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(pluralStringResource(Res.plurals.common_tags_merge_many_title, count, count)) },
        onClick = {
            onDismiss()
            actions.onMergeSelected()
        },
    )
    DropdownMenuItem(
        text = { Text(pluralStringResource(Res.plurals.common_tags_color_many_title, count, count)) },
        onClick = {
            onDismiss()
            actions.onStyleSelected()
        },
    )
    DropdownMenuItem(
        text = { Text(pluralStringResource(Res.plurals.common_tags_delete_many_title, count, count)) },
        onClick = {
            onDismiss()
            actions.onDeleteSelected()
        },
    )
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
