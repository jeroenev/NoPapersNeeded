@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.app.LibraryFilter
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import no_papers_needed.composeapp.generated.resources.library_remove_filter
import no_papers_needed.composeapp.generated.resources.library_review_queue
import no_papers_needed.composeapp.generated.resources.library_tag_filter_all
import no_papers_needed.composeapp.generated.resources.library_tag_filter_any
import no_papers_needed.composeapp.generated.resources.library_tag_filter_none
import no_papers_needed.composeapp.generated.resources.library_untagged
import org.jetbrains.compose.resources.stringResource

/** Removable chips for each active filter facet under the search bar. */
@Composable
fun ActiveFilters(
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips =
        buildList {
            filter.tagsAll.forEach { t ->
                add(
                    stringResource(Res.string.library_tag_filter_all, t) to {
                        onFilterChange(filter.copy(tagsAll = filter.tagsAll - t))
                    },
                )
            }
            filter.tagsAny.forEach { t ->
                add(
                    stringResource(Res.string.library_tag_filter_any, t) to {
                        onFilterChange(filter.copy(tagsAny = filter.tagsAny - t))
                    },
                )
            }
            filter.tagsNone.forEach { t ->
                add(
                    stringResource(Res.string.library_tag_filter_none, t) to {
                        onFilterChange(filter.copy(tagsNone = filter.tagsNone - t))
                    },
                )
            }
            filter.reviewed?.let { r ->
                add(
                    stringResource(if (r) Res.string.common_reviewed else Res.string.common_needs_review) to {
                        onFilterChange(filter.copy(reviewed = null))
                    },
                )
            }
            filter.correspondent?.let { c -> add(c to { onFilterChange(filter.copy(correspondent = null)) }) }
            filter.docType?.let { d -> add(d to { onFilterChange(filter.copy(docType = null)) }) }
            if (filter.untaggedOnly) {
                add(
                    stringResource(Res.string.library_untagged) to {
                        onFilterChange(filter.copy(untaggedOnly = false))
                    },
                )
            }
            if (filter.unreviewedOrUntagged) {
                add(
                    stringResource(Res.string.library_review_queue) to {
                        onFilterChange(filter.copy(unreviewedOrUntagged = false))
                    },
                )
            }
        }

    if (chips.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (label, remove) ->
            InputChip(
                selected = false,
                onClick = remove,
                label = { Text(label) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(Res.string.library_remove_filter),
                    )
                },
            )
        }
    }
}
