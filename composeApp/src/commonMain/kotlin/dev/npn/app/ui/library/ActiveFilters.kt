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
import no_papers_needed.composeapp.generated.resources.library_date_from
import no_papers_needed.composeapp.generated.resources.library_date_to
import no_papers_needed.composeapp.generated.resources.library_remove_filter
import no_papers_needed.composeapp.generated.resources.library_review_queue
import no_papers_needed.composeapp.generated.resources.library_tag_filter_all
import no_papers_needed.composeapp.generated.resources.library_tag_filter_any
import no_papers_needed.composeapp.generated.resources.library_tag_filter_none
import no_papers_needed.composeapp.generated.resources.library_untagged
import org.jetbrains.compose.resources.stringResource

/**
 * Removable chips for each active filter facet under the search bar. The set, order and removal of
 * chips is pure logic in [activeChips]/[removeChip]; this composable only renders each
 * [FilterChip] with its localized label and wires the tap to clear that one facet.
 */
@Composable
fun ActiveFilters(
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = filter.activeChips()
    if (chips.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = false,
                onClick = { onFilterChange(filter.removeChip(chip)) },
                label = { Text(chipLabel(chip)) },
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

/** Localized label for one active-filter chip. Presentation only; the chip's meaning is the type. */
@Composable
private fun chipLabel(chip: FilterChip): String =
    when (chip) {
        is FilterChip.TagAll -> {
            stringResource(Res.string.library_tag_filter_all, chip.tag)
        }

        is FilterChip.TagAny -> {
            stringResource(Res.string.library_tag_filter_any, chip.tag)
        }

        is FilterChip.TagNone -> {
            stringResource(Res.string.library_tag_filter_none, chip.tag)
        }

        is FilterChip.Reviewed -> {
            stringResource(if (chip.reviewed) Res.string.common_reviewed else Res.string.common_needs_review)
        }

        is FilterChip.Correspondent -> {
            chip.value
        }

        is FilterChip.DocType -> {
            chip.value
        }

        is FilterChip.DocDateFrom -> {
            stringResource(Res.string.library_date_from, chip.value)
        }

        is FilterChip.DocDateTo -> {
            stringResource(Res.string.library_date_to, chip.value)
        }

        FilterChip.UntaggedOnly -> {
            stringResource(Res.string.library_untagged)
        }

        FilterChip.ReviewQueue -> {
            stringResource(Res.string.library_review_queue)
        }
    }
