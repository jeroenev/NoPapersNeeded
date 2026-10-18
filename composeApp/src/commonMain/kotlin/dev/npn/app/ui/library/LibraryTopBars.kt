@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import dev.npn.app.ui.common.HelpText
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
import io.github.vinceglb.filekit.name
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.library_ai_edit
import no_papers_needed.composeapp.generated.resources.library_documents
import no_papers_needed.composeapp.generated.resources.library_filters
import no_papers_needed.composeapp.generated.resources.library_mark_needs_review
import no_papers_needed.composeapp.generated.resources.library_mark_reviewed
import no_papers_needed.composeapp.generated.resources.library_more_actions
import no_papers_needed.composeapp.generated.resources.library_refresh
import no_papers_needed.composeapp.generated.resources.library_remove_tag
import no_papers_needed.composeapp.generated.resources.library_results_summary_ascending
import no_papers_needed.composeapp.generated.resources.library_results_summary_descending
import no_papers_needed.composeapp.generated.resources.library_sort
import no_papers_needed.composeapp.generated.resources.library_sort_added
import no_papers_needed.composeapp.generated.resources.library_sort_ascending
import no_papers_needed.composeapp.generated.resources.library_sort_descending
import no_papers_needed.composeapp.generated.resources.library_sort_name
import no_papers_needed.composeapp.generated.resources.library_sort_size
import no_papers_needed.composeapp.generated.resources.library_switch_to_grid
import no_papers_needed.composeapp.generated.resources.library_switch_to_list
import no_papers_needed.composeapp.generated.resources.library_trash
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryTopBar(
    viewType: ViewType,
    sort: LibrarySort,
    sortableFields: List<FieldDef>,
    wide: Boolean,
    extras: @Composable () -> Unit,
    onOpenFilter: () -> Unit,
    onSetSort: (SortField, Boolean) -> Unit,
    onSetView: (ViewType) -> Unit,
    onRefresh: () -> Unit,
) {
    val next = if (viewType == ViewType.LIST) ViewType.GRID else ViewType.LIST
    val switchView =
        TopBarAction(
            // The icon names the view being switched *to*, matching the label.
            icon = if (next == ViewType.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
            // Whole sentences, not an interpolated view name: Dutch compounds it into one word and
            // German capitalises it, so no single template fits every language.
            label =
                stringResource(
                    if (next == ViewType.GRID) Res.string.library_switch_to_grid else Res.string.library_switch_to_list,
                ),
            onClick = { onSetView(next) },
        )
    val refresh = TopBarAction(Icons.Filled.Refresh, stringResource(Res.string.library_refresh), onRefresh)
    TopAppBar(
        title = { Text(stringResource(Res.string.library_documents), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            extras()
            TooltipIconButton(Icons.Filled.FilterList, stringResource(Res.string.library_filters), onOpenFilter)
            SortMenuButton(sort, sortableFields, onSetSort)
            // A desktop window has room for the view and refresh toggles beside filter and sort;
            // a compact bar keeps both one tap away in the overflow menu.
            TopBarActions(
                actions = listOf(switchView, refresh),
                wide = wide,
                overflowLabel = stringResource(Res.string.library_more_actions),
            )
        },
    )
}

@Composable
private fun SortMenuButton(
    sort: LibrarySort,
    sortableFields: List<FieldDef>,
    onSetSort: (SortField, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Document columns first, then every sortable registry field in registry order.
    val options =
        listOf(SortField.Name, SortField.Added, SortField.Size) + sortableFields.map { SortField.Field(it.key) }
    Box {
        TooltipIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(Res.string.library_sort), { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { field ->
                DropdownMenuItem(
                    text = { Text(sortLabel(field, sortableFields)) },
                    leadingIcon = { RadioButton(selected = sort.field == field, onClick = null) },
                    onClick = {
                        onSetSort(field, sort.ascending)
                        expanded = false
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
                        expanded = false
                    },
                )
            }
        }
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
            TopBarAction(Icons.Filled.AutoFixHigh, stringResource(Res.string.library_ai_edit), it)
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
 * One concise line above the results: how many documents match and how they are ordered. Reads the
 * field label from the registry so a sort on a renamed field still names it correctly; falls back to
 * the sort key for a field the registry no longer lists.
 */
@Composable
internal fun ResultsSummary(
    count: Int,
    sort: LibrarySort,
    sortableFields: List<FieldDef>,
    modifier: Modifier = Modifier,
) {
    HelpText(
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
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A sort's name: a document column's own, or a registry field's label (its key once it is gone). */
@Composable
private fun sortLabel(
    field: SortField,
    sortableFields: List<FieldDef>,
): String =
    when (field) {
        SortField.Name -> stringResource(Res.string.library_sort_name)
        SortField.Added -> stringResource(Res.string.library_sort_added)
        SortField.Size -> stringResource(Res.string.library_sort_size)
        is SortField.Field -> sortableFields.labelFor(field.key)
    }
