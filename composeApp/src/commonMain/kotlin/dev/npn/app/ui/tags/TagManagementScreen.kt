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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.npn.app.ui.common.ActionMenuItems
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TagFormDialog
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.onSecondaryClick
import dev.npn.app.ui.common.parseHexColor
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.TagInfo
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable
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
 * namespace-only nodes carry no journal state of their own. Saveable, so a selection survives a
 * trip to another tab and back.
 */
private class TagSelection(
    initial: Set<String> = emptySet(),
) {
    var selected by mutableStateOf(initial)

    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }

    fun clear() {
        selected = emptySet()
    }

    companion object {
        val Saver: Saver<TagSelection, List<String>> =
            Saver(save = { it.selected.toList() }, restore = { TagSelection(it.toSet()) })
    }
}

/**
 * The one dialog the tags screen has open, or none. Every action but rename names a list of tags: a
 * card's own menu one, the selection all of it. Saved as JSON, so it is open again after the screen
 * is recreated.
 */
@Serializable
private sealed interface TagDialog {
    @Serializable
    data class Rename(
        val name: String,
    ) : TagDialog

    @Serializable
    data class Merge(
        val names: List<String>,
    ) : TagDialog

    @Serializable
    data class Style(
        val names: List<String>,
    ) : TagDialog

    @Serializable
    data class Delete(
        val names: List<String>,
    ) : TagDialog

    companion object {
        val Saver: Saver<TagDialog?, String> =
            Saver(
                save = { dialog -> dialog?.let { NpnJson.Persisted.encodeToString(serializer(), it) } },
                restore = { NpnJson.Persisted.decodeFromString(serializer(), it) },
            )
    }
}

@Composable
fun TagManagementScreen(session: LibrarySession) {
    val vm: TagManagementViewModel = viewModel { TagManagementViewModel(session) }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tree = remember(tags) { buildTree(tags) }

    var dialog by rememberSaveable(stateSaver = TagDialog.Saver) { mutableStateOf(null) }
    val selection = rememberSaveable(saver = TagSelection.Saver) { TagSelection() }

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    TagListScaffold(
        tree = tree,
        isEmpty = tags.isEmpty(),
        snackbar = snackbar,
        selection = selection,
        onOpen = { dialog = it },
    )
    dialog?.let { open ->
        TagDialogHost(
            dialog = open,
            tags = tags,
            writes = vm,
            onClose = { dialog = null },
            // A confirmed action ends the selection it was taken on.
            onDone = {
                dialog = null
                selection.clear()
            },
        )
    }
}

@Composable
private fun TagListScaffold(
    tree: List<TagNode>,
    isEmpty: Boolean,
    snackbar: SnackbarHostState,
    selection: TagSelection,
    onOpen: (TagDialog) -> Unit,
) {
    val flatTree = remember(tree) { flatten(tree) }
    // Tags can disappear underneath a selection (another device, auto-pruning after edits).
    val selected = flatTree.mapNotNull { node -> node.info?.name?.takeIf { it in selection.selected } }
    Scaffold(
        topBar = {
            if (selection.active) {
                TagSelectionTopBar(
                    selected = selected,
                    onClose = selection::clear,
                    onSelectAll = {
                        selection.selected = flatTree.mapNotNull { it.info?.name }.toSet()
                    },
                    onOpen = onOpen,
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
            EmptyState(
                icon = Icons.Filled.Sell,
                title = stringResource(Res.string.common_tags_empty_title),
                modifier = Modifier.padding(padding),
                subtitle = stringResource(Res.string.common_tags_empty_subtitle),
            )
        } else {
            // Compact cards in an adaptive grid: several tags per row on wide layouts instead of
            // one full-width row each. The namespace hierarchy shows as a muted parent-path line
            // on each card (grid cells cannot carry indentation).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TagCardMinWidth),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(flatTree, key = { it.path }) { node ->
                    val isSelected = node.path in selection.selected
                    TagCard(
                        node = node,
                        selectionMode = selection.active,
                        selected = isSelected,
                        // With several tags selected, this card among them, its menu acts on the
                        // whole selection; otherwise on this tag alone.
                        targets = if (isSelected && selected.size > 1) selected else listOf(node.path),
                        onToggleSelect = { selection.toggle(node.path) },
                        // Opening a menu on an unselected card replaces the selection with that
                        // card, so the menu never silently acts on off-screen tags.
                        onPrepareMenu = {
                            if (selection.active && node.info != null && !isSelected) {
                                selection.selected = setOf(node.path)
                            }
                        },
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/** Contextual top bar while tags are selected: count, select all, and color, merge or delete them. */
@Composable
private fun TagSelectionTopBar(
    selected: List<String>,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onOpen: (TagDialog) -> Unit,
) {
    TopAppBar(
        title = { Text(pluralStringResource(Res.plurals.library_selected_count, selected.size, selected.size)) },
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
                onClick = { onOpen(TagDialog.Style(selected)) },
            )
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.CallMerge,
                label = stringResource(Res.string.common_tags_merge),
                onClick = { onOpen(TagDialog.Merge(selected)) },
                // Merging needs at least a pair.
                enabled = selected.size >= 2,
            )
            TooltipIconButton(
                icon = Icons.Filled.Delete,
                label = stringResource(Res.string.common_delete),
                onClick = { onOpen(TagDialog.Delete(selected)) },
            )
        },
    )
}

/**
 * The open [dialog], writing through [writes] once confirmed. A dialog about one tag names it; one
 * about several counts them.
 */
@Composable
private fun TagDialogHost(
    dialog: TagDialog,
    tags: List<TagInfo>,
    writes: TagWrites,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    when (dialog) {
        is TagDialog.Rename -> {
            TextInputDialog(
                title = stringResource(Res.string.common_tags_rename_title),
                label = stringResource(Res.string.common_tags_new_name),
                initial = dialog.name,
                confirmLabel = stringResource(Res.string.common_rename),
                // Block confirming until the name is non-blank and actually changed.
                confirmEnabled = { it.isNotBlank() && it.trim() != dialog.name },
                onConfirm = { to ->
                    writes.rename(dialog.name, to)
                    onDone()
                },
                onDismiss = onClose,
            )
        }

        is TagDialog.Merge -> {
            MergeDialog(dialog.names, tags, onClose) { into ->
                writes.merge(dialog.names, into)
                onDone()
            }
        }

        is TagDialog.Style -> {
            val single = dialog.names.singleOrNull()
            val count = dialog.names.size
            TagFormDialog(
                title =
                    if (single != null) {
                        stringResource(Res.string.common_tags_style_title)
                    } else {
                        pluralStringResource(Res.plurals.common_tags_color_many_title, count, count)
                    },
                initialName = single.orEmpty(),
                initialColor = single?.let { name -> tags.firstOrNull { it.name == name }?.color },
                confirmLabel = stringResource(Res.string.common_save),
                onConfirm = { _, color ->
                    writes.setStyle(dialog.names, color)
                    onDone()
                },
                onDismiss = onClose,
                nameEditable = false,
                // One color for many tags has no single name to show.
                showNameField = single != null,
            )
        }

        is TagDialog.Delete -> {
            val single = dialog.names.singleOrNull()
            val count = dialog.names.size
            ConfirmDialog(
                title =
                    if (single != null) {
                        stringResource(Res.string.common_tags_delete_title)
                    } else {
                        pluralStringResource(Res.plurals.common_tags_delete_many_title, count, count)
                    },
                message =
                    if (single != null) {
                        stringResource(Res.string.common_tags_delete_message, single)
                    } else {
                        pluralStringResource(Res.plurals.common_tags_delete_many_message, count, count)
                    },
                confirmLabel = stringResource(Res.string.common_delete),
                destructive = true,
                onConfirm = {
                    writes.delete(dialog.names)
                    onDone()
                },
                onDismiss = onClose,
            )
        }
    }
}

/**
 * Merging one tag picks which other tag takes its documents; merging several picks which of them
 * is kept.
 */
@Composable
private fun MergeDialog(
    names: List<String>,
    tags: List<TagInfo>,
    onDismiss: () -> Unit,
    onMerge: (into: String) -> Unit,
) {
    val single = names.singleOrNull()
    if (single != null) {
        TagPickDialog(
            title = stringResource(Res.string.common_tags_merge_title, single),
            options = tags.map { it.name }.filter { it != single },
            confirmLabel = stringResource(Res.string.common_tags_merge),
            onConfirm = onMerge,
            onDismiss = onDismiss,
        )
    } else {
        TagPickDialog(
            title = pluralStringResource(Res.plurals.common_tags_merge_many_title, names.size, names.size),
            options = names,
            confirmLabel = stringResource(Res.string.common_tags_merge),
            onConfirm = onMerge,
            onDismiss = onDismiss,
            message = stringResource(Res.string.common_tags_merge_keep_message),
        )
    }
}

/** Minimum card width driving the adaptive column count of the tag grid. */
private val TagCardMinWidth = 240.dp

@Composable
private fun TagCard(
    node: TagNode,
    selectionMode: Boolean,
    selected: Boolean,
    /** The tags this card's menu acts on: this one, or the selection it is part of. */
    targets: List<String>,
    onToggleSelect: () -> Unit,
    /** Pulls this card into the selection before a menu opens on it (see call site). */
    onPrepareMenu: () -> Unit,
    onOpen: (TagDialog) -> Unit,
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
                onPrepareMenu()
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
                        onPrepareMenu()
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
                    onPrepareMenu()
                    menu = true
                },
                onDismiss = { menu = false },
                targets = targets,
                onOpen = onOpen,
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
        HelpText(
            if (node.info == null) {
                stringResource(Res.string.common_tags_namespace_only, documentCount)
            } else {
                documentCount
            },
        )
    }
}

/**
 * A card's actions. On one tag: rename, merge into another, color and delete. On several: merge,
 * color and delete them all, the labels carrying the count so the scope is clear.
 */
@Composable
private fun TagCardMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    targets: List<String>,
    onOpen: (TagDialog) -> Unit,
) {
    val single = targets.singleOrNull()
    val count = targets.size
    val actions =
        listOfNotNull(
            single?.let {
                TopBarAction(
                    Icons.Filled.Edit,
                    stringResource(Res.string.common_rename),
                    { onOpen(TagDialog.Rename(it)) },
                )
            },
            TopBarAction(
                Icons.AutoMirrored.Filled.CallMerge,
                if (single != null) {
                    stringResource(Res.string.common_tags_merge_into)
                } else {
                    pluralStringResource(Res.plurals.common_tags_merge_many_title, count, count)
                },
                { onOpen(TagDialog.Merge(targets)) },
            ),
            TopBarAction(
                Icons.Filled.Palette,
                if (single != null) {
                    stringResource(Res.string.common_tags_color_style)
                } else {
                    pluralStringResource(Res.plurals.common_tags_color_many_title, count, count)
                },
                { onOpen(TagDialog.Style(targets)) },
            ),
            TopBarAction(
                Icons.Filled.Delete,
                if (single != null) {
                    stringResource(Res.string.common_delete)
                } else {
                    pluralStringResource(Res.plurals.common_tags_delete_many_title, count, count)
                },
                { onOpen(TagDialog.Delete(targets)) },
            ),
        )
    Box {
        IconButton(onClick = onExpand) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.common_tags_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            ActionMenuItems(actions, onDismiss)
        }
    }
}

/** Build the namespace tree from the flat tag list. */
internal fun buildTree(flat: List<TagInfo>): List<TagNode> {
    val byPath = flat.associateBy { it.name }
    // Collect every distinct path (real tags plus the intermediate namespace prefixes they imply),
    // already sorted. Grouping by immediate parent prefix below keeps the tree build linear, rather
    // than filtering the whole path set once per node.
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

    fun childrenOf(prefix: String): List<TagNode> =
        childrenByParent[prefix].orEmpty().map { path ->
            val kids = childrenOf(path)
            val info = byPath[path]
            TagNode(
                path = path,
                segment = path.substringAfterLast('.'),
                info = info,
                descendantCount = (info?.count ?: 0) + kids.sumOf { it.descendantCount },
                children = kids,
            )
        }

    return childrenOf("")
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
