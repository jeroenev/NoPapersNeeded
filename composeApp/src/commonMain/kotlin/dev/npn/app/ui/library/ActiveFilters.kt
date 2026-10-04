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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.rememberFieldLocale
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldValues
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import no_papers_needed.composeapp.generated.resources.library_doc_ids_filter
import no_papers_needed.composeapp.generated.resources.library_field_equals
import no_papers_needed.composeapp.generated.resources.library_field_from
import no_papers_needed.composeapp.generated.resources.library_field_to
import no_papers_needed.composeapp.generated.resources.library_remove_filter
import no_papers_needed.composeapp.generated.resources.library_review_queue
import no_papers_needed.composeapp.generated.resources.library_tag_filter_all
import no_papers_needed.composeapp.generated.resources.library_tag_filter_any
import no_papers_needed.composeapp.generated.resources.library_tag_filter_none
import no_papers_needed.composeapp.generated.resources.library_untagged
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Removable chips for each active filter facet under the search bar. The set, order and removal of
 * chips is pure logic in [activeChips]/[removeChip]; this composable only renders each
 * [ActiveFilterChip] with its localized label and wires the tap to clear that one facet. A field
 * chip names the field by its registry label and shows the value the way the filter sheet does.
 */
@Composable
fun ActiveFilters(
    filter: LibraryFilter,
    fields: List<FieldDef>,
    onFilterChange: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = filter.activeChips()
    if (chips.isEmpty()) return
    val locale = rememberFieldLocale()
    val defs = remember(fields) { fields.associateBy { it.key } }
    val fieldText = { key: String, value: String ->
        val def = defs[key]
        (def?.label ?: key) to (def?.let { FieldValues.display(it.type, value, locale) } ?: value)
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = false,
                onClick = { onFilterChange(filter.removeChip(chip)) },
                label = { Text(chipLabel(chip, fieldText)) },
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
private fun chipLabel(
    chip: ActiveFilterChip,
    /** A field key and canonical value as the field's label and display form. */
    fieldText: (key: String, value: String) -> Pair<String, String>,
): String =
    when (chip) {
        is ActiveFilterChip.DocIds -> {
            pluralStringResource(Res.plurals.library_doc_ids_filter, chip.count, chip.count)
        }

        is ActiveFilterChip.TagAll -> {
            stringResource(Res.string.library_tag_filter_all, chip.tag)
        }

        is ActiveFilterChip.TagAny -> {
            stringResource(Res.string.library_tag_filter_any, chip.tag)
        }

        is ActiveFilterChip.TagNone -> {
            stringResource(Res.string.library_tag_filter_none, chip.tag)
        }

        is ActiveFilterChip.Reviewed -> {
            stringResource(if (chip.reviewed) Res.string.common_reviewed else Res.string.common_needs_review)
        }

        is ActiveFilterChip.FieldEquals -> {
            val (label, value) = fieldText(chip.key, chip.value)
            stringResource(Res.string.library_field_equals, label, value)
        }

        is ActiveFilterChip.FieldFrom -> {
            val (label, value) = fieldText(chip.key, chip.value)
            stringResource(Res.string.library_field_from, label, value)
        }

        is ActiveFilterChip.FieldTo -> {
            val (label, value) = fieldText(chip.key, chip.value)
            stringResource(Res.string.library_field_to, label, value)
        }

        ActiveFilterChip.UntaggedOnly -> {
            stringResource(Res.string.library_untagged)
        }

        ActiveFilterChip.ReviewQueue -> {
            stringResource(Res.string.library_review_queue)
        }
    }
