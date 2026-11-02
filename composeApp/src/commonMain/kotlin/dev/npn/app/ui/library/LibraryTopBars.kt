@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.SelectionTopBar
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.TopBarActions
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.labelFor
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_with_assistant
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.common_select_all
import no_papers_needed.composeapp.generated.resources.library_documents
import no_papers_needed.composeapp.generated.resources.library_filters
import no_papers_needed.composeapp.generated.resources.library_larger_grid_items
import no_papers_needed.composeapp.generated.resources.library_mark_needs_review
import no_papers_needed.composeapp.generated.resources.library_mark_reviewed
import no_papers_needed.composeapp.generated.resources.library_more_actions
import no_papers_needed.composeapp.generated.resources.library_refresh
import no_papers_needed.composeapp.generated.resources.library_remove_tag
import no_papers_needed.composeapp.generated.resources.library_results_summary_ascending
import no_papers_needed.composeapp.generated.resources.library_results_summary_descending
import no_papers_needed.composeapp.generated.resources.library_smaller_grid_items
import no_papers_needed.composeapp.generated.resources.library_sort
import no_papers_needed.composeapp.generated.resources.library_sort_ascending
import no_papers_needed.composeapp.generated.resources.library_sort_created
import no_papers_needed.composeapp.generated.resources.library_sort_descending
import no_papers_needed.composeapp.generated.resources.library_sort_name
import no_papers_needed.composeapp.generated.resources.library_sort_size
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.library_view
import no_papers_needed.composeapp.generated.resources.library_view_grid
import no_papers_needed.composeapp.generated.resources.library_view_list
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The library's top bar: [extras] (the views menu), the filters, the view menu (list or grid, and the
 * grid's item size) and an overflow with "select all" ([onSelectAll], null with nothing to select)
 * and Refresh, which pull-to-refresh and the folder watch cover too. The sort is the line above the
 * results ([SortSummary]).
 */
@Composable
internal fun LibraryTopBar(
    viewType: ViewType,
    gridZoom: GridZoom,
    extras: @Composable () -> Unit,
    onOpenFilter: () -> Unit,
    onSetView: (ViewType) -> Unit,
    onSelectAll: (() -> Unit)?,
    onRefresh: () -> Unit,
) {
    val overflow =
        listOfNotNull(
            onSelectAll?.let { TopBarAction(Icons.Filled.SelectAll, stringResource(Res.string.common_select_all), it) },
            TopBarAction(Icons.Filled.Refresh, stringResource(Res.string.library_refresh), onRefresh),
        )
    TopAppBar(
        title = { Text(stringResource(Res.string.library_documents), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            extras()
            TooltipIconButton(Icons.Filled.FilterList, stringResource(Res.string.library_filters), onOpenFilter)
            ViewMenuButton(viewType, gridZoom, onSetView)
            TopBarActions(
                inline = emptyList(),
                overflow = overflow,
                overflowLabel = stringResource(Res.string.library_more_actions),
            )
        },
    )
}

/**
 * The view menu: list or grid, and in the grid, larger or smaller items, one column at a time as
 * pinch and Ctrl+scroll do. The size items leave the menu open, so the user can step a few at once.
 */
@Composable
private fun ViewMenuButton(
    viewType: ViewType,
    gridZoom: GridZoom,
    onSetView: (ViewType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            // The icon shows the view the list is in now.
            icon = if (viewType == ViewType.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
            label = stringResource(Res.string.library_view),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(ViewType.LIST to Res.string.library_view_list, ViewType.GRID to Res.string.library_view_grid)
                .forEach { (type, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = viewType == type, onClick = null) },
                        onClick = {
                            onSetView(type)
                            expanded = false
                        },
                    )
                }
            if (viewType == ViewType.GRID) {
                HorizontalDivider()
                // Larger items mean fewer columns (zooming in, as a photo gallery does).
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_larger_grid_items)) },
                    leadingIcon = { Icon(Icons.Filled.ZoomIn, contentDescription = null) },
                    enabled = gridZoom.canZoomIn,
                    onClick = gridZoom::zoomIn,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_smaller_grid_items)) },
                    leadingIcon = { Icon(Icons.Filled.ZoomOut, contentDescription = null) },
                    enabled = gridZoom.canZoomOut,
                    onClick = gridZoom::zoomOut,
                )
            }
        }
    }
}

/** The sort menu's items: every field the results can be ordered by, then the direction. */
@Composable
private fun SortMenuItems(
    sort: LibrarySort,
    sortableFields: List<FieldDef>,
    onSetSort: (SortField, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Document columns first, then every sortable registry field in registry order.
    val options =
        listOf(SortField.Name, SortField.Created, SortField.Size) + sortableFields.map { SortField.Field(it.key) }
    options.forEach { field ->
        DropdownMenuItem(
            text = { Text(sortLabel(field, sortableFields)) },
            leadingIcon = { RadioButton(selected = sort.field == field, onClick = null) },
            onClick = {
                onSetSort(field, sort.ascending)
                onDismiss()
            },
        )
    }
    HorizontalDivider()
    listOf(true, false).forEach { ascending ->
        DropdownMenuItem(
            text = {
                val label =
                    if (ascending) {
                        Res.string.library_sort_ascending
                    } else {
                        Res.string.library_sort_descending
                    }
                Text(stringResource(label))
            },
            leadingIcon = { RadioButton(selected = sort.ascending == ascending, onClick = null) },
            onClick = {
                onSetSort(sort.field, ascending)
                onDismiss()
            },
        )
    }
}

/** The library's [SelectionTopBar]: tag, trash, AI-edit and review actions on the selected documents. */
@Composable
internal fun LibrarySelectionTopBar(
    count: Int,
    /** Every document in the results, for "select all". */
    allDocIds: List<String>,
    reviewMode: ReviewMode,
    wide: Boolean,
    actions: LibraryActions,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onAiEdit: (() -> Unit)?,
    onRequestTrash: () -> Unit,
) {
    val addTag = TopBarAction(Icons.Filled.NewLabel, stringResource(Res.string.common_add_tag), onAddTag)
    val removeTag =
        TopBarAction(Icons.AutoMirrored.Filled.LabelOff, stringResource(Res.string.library_remove_tag), onRemoveTag)
    val aiEdit =
        onAiEdit?.let {
            TopBarAction(Icons.Filled.AutoFixHigh, stringResource(Res.string.ai_edit_with_assistant), it)
        }
    val trash = TopBarAction(Icons.Filled.Delete, stringResource(Res.string.library_trash), onRequestTrash)
    // Adding and removing tags and AI edit are all about the selection itself, so a wide bar shows
    // them beside add-tag and trash. A compact bar keeps just the two frequent actions and leaves
    // remove-tag, AI edit and the reviewed toggles in the overflow menu.
    val inline =
        buildList {
            add(addTag)
            if (wide) add(removeTag)
            if (wide) aiEdit?.let { add(it) }
            add(trash)
        }
    val overflow =
        buildList {
            if (!wide) add(removeTag)
            if (!wide) aiEdit?.let { add(it) }
            if (reviewMode.reviewMarkerIsAuthoritative) {
                add(
                    TopBarAction(
                        icon = Icons.Filled.CheckCircle,
                        label = stringResource(Res.string.library_mark_reviewed),
                        onClick = { actions.bulkSetReviewed(true) },
                    ),
                )
                add(
                    TopBarAction(
                        icon = Icons.Filled.RadioButtonUnchecked,
                        label = stringResource(Res.string.library_mark_needs_review),
                        onClick = { actions.bulkSetReviewed(false) },
                    ),
                )
            }
        }
    SelectionTopBar(
        count = count,
        onClose = actions::clearSelection,
        onSelectAll = { actions.selectAll(allDocIds) }.takeIf { allDocIds.isNotEmpty() },
        inline = inline,
        // Select all leads it, so it is never empty.
        overflow = overflow,
    )
}

/**
 * One concise line above the results, which is also the sort control: how many documents match and
 * how they are ordered, opening the sort menu when clicked. Reads the field label from the registry,
 * so a sort on a renamed field still names it correctly; falls back to the sort key for a field the
 * registry no longer lists.
 */
@Composable
internal fun SortSummary(
    count: Int,
    sort: LibrarySort,
    sortableFields: List<FieldDef>,
    onSetSort: (SortField, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                pluralStringResource(
                    if (sort.ascending) {
                        Res.plurals.library_results_summary_ascending
                    } else {
                        Res.plurals.library_results_summary_descending
                    },
                    count,
                    count,
                    sortLabel(sort.field, sortableFields),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Names the action: the summary text says what the order is now.
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(Res.string.library_sort),
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMenuItems(sort, sortableFields, onSetSort, onDismiss = { expanded = false })
        }
    }
}

/** A sort's name: a document column's own, or a registry field's label (its key once it is gone). */
@Composable
private fun sortLabel(
    field: SortField,
    sortableFields: List<FieldDef>,
): String =
    when (field) {
        SortField.Name -> stringResource(Res.string.library_sort_name)
        SortField.Created -> stringResource(Res.string.library_sort_created)
        SortField.Size -> stringResource(Res.string.library_sort_size)
        is SortField.Field -> sortableFields.labelFor(field.key)
    }
